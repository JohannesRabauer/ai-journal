package dev.rabauer.ai.diary.insights;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rabauer.ai.diary.api.coach.CoachRole;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.entry.EntryStatus;
import dev.rabauer.ai.diary.api.insights.GenerateWeeklyReportRequest;
import dev.rabauer.ai.diary.api.insights.WeeklyReportDto;
import dev.rabauer.ai.diary.api.insights.WeeklyReportSummaryDto;
import dev.rabauer.ai.diary.coach.CoachConversationEntity;
import dev.rabauer.ai.diary.coach.CoachConversationRepository;
import dev.rabauer.ai.diary.coach.CoachMessageEntity;
import dev.rabauer.ai.diary.coach.CoachMessageRepository;
import dev.rabauer.ai.diary.entry.DiaryEntryEntity;
import dev.rabauer.ai.diary.entry.EntryDeleted;
import dev.rabauer.ai.diary.entry.EntryRepository;
import dev.rabauer.ai.diary.llm.StructuredOutputService;
import dev.rabauer.ai.diary.llm.StructuredRequest;
import dev.rabauer.ai.diary.memory.MemoryStats;
import dev.rabauer.ai.diary.settings.SettingsService;
import dev.rabauer.ai.diary.spi.LlmGateway;
import dev.rabauer.ai.diary.web.ApiException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Weekly reflection (SPEC section 8).
 *
 * <ul>
 *   <li>Input: FINISHED entries whose local entry date lies in the ISO week (Monday to Sunday), plus the visible
 *       coach messages of conversations about them. Nothing is sent to the LLM for an empty week (409).</li>
 *   <li>One report per (isoYear, isoWeek): regenerating replaces the stored report (same id, edits are discarded,
 *       {@code editedAt} reset). A failed generation leaves the previous report untouched.</li>
 *   <li>"What you learned" is never generated: it is {@link MemoryStats#learnedBetween} for the week's range from
 *       Monday 00:00 to the next Monday 00:00 in the SERVER time zone ({@link Clock#getZone()}), the same zone in
 *       which entry timestamps (local date-times) are written. Without a MemoryStats bean it is zero.</li>
 *   <li>User edits are the user's words: only structurally validated, never content-checked.</li>
 *   <li>Deleting an entry redacts stored reports (see {@link #onEntryDeleted}); the report itself stays.</li>
 * </ul>
 */
@ApplicationScoped
public class WeeklyReportService {

    private static final Logger LOG = Logger.getLogger(WeeklyReportService.class);

    static final int MAX_THEMES = 10;
    static final int MAX_QUOTES = 10;
    static final int MAX_LIST_ITEMS = 20;
    static final int MAX_ENTRY_IDS = 50;
    static final int MAX_TEXT = 2000;
    static final int MAX_LEARNED_PROMPTS = 10;

    @Inject
    WeeklyReportRepository reports;
    @Inject
    EntryRepository entries;
    @Inject
    CoachConversationRepository conversations;
    @Inject
    CoachMessageRepository coachMessages;
    @Inject
    StructuredOutputService structured;
    @Inject
    SettingsService settings;
    @Inject
    Instance<MemoryStats> memoryStats;
    @Inject
    ObjectMapper mapper;
    @Inject
    Clock clock;

    @ConfigProperty(name = "journal.weekly.max-entry-chars", defaultValue = "4000")
    int maxEntryChars;
    @ConfigProperty(name = "journal.weekly.max-coach-chars", defaultValue = "1500")
    int maxCoachChars;
    @ConfigProperty(name = "journal.weekly.temperature", defaultValue = "0.3")
    double temperature;

    /** Single user: serialising generation keeps "one report per week" without a retry loop on the unique index. */
    private final ReentrantLock generation = new ReentrantLock();

    // ------------------------------------------------------------------ generate

    public WeeklyReportDto generate(GenerateWeeklyReportRequest request) {
        if (request == null) {
            throw ApiException.badRequest("INVALID_WEEK", "Choose an ISO year and week.");
        }
        int year = request.isoYear();
        int week = request.isoWeek();
        LocalDate from = IsoWeeks.start(year, week);
        LocalDate toExclusive = IsoWeeks.endExclusive(year, week);
        if (from.isAfter(LocalDate.now(clock))) {
            // the current (partial) week is allowed; a week that has not started yet has nothing to reflect on
            throw ApiException.badRequest("INVALID_WEEK", "This week has not started yet. Choose the current or an earlier week.");
        }

        generation.lock();
        try {
            Input input = QuarkusTransaction.requiringNew().call(() -> loadInput(from, toExclusive));
            if (input.entries().isEmpty()) {
                throw ApiException.conflict("NO_ENTRIES_IN_WEEK",
                        "There are no finished entries in this week yet. Finish an entry first, then create the reflection.");
            }
            Language lang = chooseLanguage(input.languages());
            LOG.infof("Weekly reflection %d-W%02d: %d entries, %d coach conversations, language %s",
                    year, week, input.entries().size(), input.coach().size(), lang.code());

            Map<Long, String> texts = new LinkedHashMap<>();
            input.entries().forEach(e -> texts.put(e.id(), e.text()));
            String coachText = input.coach().stream().flatMap(c -> c.lines().stream()).map(WeeklyPrompts.CoachLine::text)
                    .collect(Collectors.joining("\n"));
            List<LlmGateway.Message> prompt = List.of(
                    LlmGateway.Message.system(WeeklyPrompts.systemPrompt(lang)),
                    LlmGateway.Message.user(WeeklyPrompts.dataBlock(lang, from, toExclusive.minusDays(1), input.entries(),
                            input.coach(), maxEntryChars, maxCoachChars)));
            WeeklyLlmOutput out = structured.generate(StructuredRequest.of(prompt,
                            new LlmGateway.JsonSchema("weekly_reflection", WeeklyPrompts.SCHEMA), WeeklyLlmOutput.class)
                    .withTemperature(temperature)
                    .withSemanticCheck(o -> WeeklyRules.check(o, texts, coachText)));

            WeeklyContent content = new WeeklyContent(
                    out.recurringThemes().stream().map(t -> new WeeklyReportDto.Theme(t.title().strip(), t.description().strip(),
                            List.copyOf(new LinkedHashSet<>(t.entryIds())))).toList(),
                    out.inYourOwnWords().stream().map(q -> new WeeklyReportDto.Quote(WeeklyRules.cleanQuote(q.text()), q.entryId())).toList(),
                    out.openThreads().stream().map(String::strip).toList(),
                    learned(from, toExclusive),
                    out.noticedPatterns().stream().map(String::strip).toList(),
                    out.questionForNextWeek().strip());
            return QuarkusTransaction.requiringNew().call(() -> store(year, week, lang, content));
        } finally {
            generation.unlock();
        }
    }

    private record Input(List<WeeklyPrompts.EntryInput> entries, List<WeeklyPrompts.CoachInput> coach, List<String> languages) {
    }

    private Input loadInput(LocalDate from, LocalDate toExclusive) {
        List<DiaryEntryEntity> found = entries.list("status = ?1 and timestamp >= ?2 and timestamp < ?3",
                EntryStatus.FINISHED, from.atStartOfDay(), toExclusive.atStartOfDay()).stream()
                .filter(e -> e.entryText != null && !e.entryText.isBlank())
                .sorted(Comparator.comparing((DiaryEntryEntity e) -> e.timestamp).thenComparingLong(e -> e.id))
                .toList();
        if (found.isEmpty()) {
            return new Input(List.of(), List.of(), List.of());
        }
        List<Long> ids = found.stream().map(e -> e.id).toList();
        Map<Long, List<Long>> conversationsByEntry = conversations.list("entryId in ?1", ids).stream()
                .sorted(Comparator.comparing((CoachConversationEntity c) -> c.createdAt).thenComparingLong(c -> c.id))
                .collect(Collectors.groupingBy(c -> c.entryId, LinkedHashMap::new, Collectors.mapping(c -> c.id, Collectors.toList())));
        List<WeeklyPrompts.CoachInput> coach = new ArrayList<>();
        for (DiaryEntryEntity e : found) {
            List<WeeklyPrompts.CoachLine> lines = new ArrayList<>();
            for (Long conversationId : conversationsByEntry.getOrDefault(e.id, List.of())) {
                coachMessages.forConversation(conversationId).stream()
                        .filter(CoachMessageEntity::revealed)
                        .sorted(Comparator.comparingInt(m -> m.seqNo))
                        .forEach(m -> lines.add(new WeeklyPrompts.CoachLine(m.role == CoachRole.USER ? "user" : "coach", m.content)));
            }
            if (!lines.isEmpty()) {
                coach.add(new WeeklyPrompts.CoachInput(e.id, lines));
            }
        }
        return new Input(
                found.stream().map(e -> new WeeklyPrompts.EntryInput(e.id, e.timestamp.toLocalDate(), e.entryText)).toList(),
                coach,
                found.stream().map(e -> e.language).toList());
    }

    /** Majority of the entries' explicit languages; on a tie or without any: settings uiLanguage, else English. */
    Language chooseLanguage(List<String> entryLanguages) {
        long de = entryLanguages.stream().filter("de"::equalsIgnoreCase).count();
        long en = entryLanguages.stream().filter("en"::equalsIgnoreCase).count();
        if (de > en) {
            return Language.DE;
        }
        if (en > de) {
            return Language.EN;
        }
        Language ui = settings.get().uiLanguage();
        return ui == Language.DE || ui == Language.EN ? ui : Language.EN;
    }

    WeeklyReportDto.Learned learned(LocalDate from, LocalDate toExclusive) {
        if (!memoryStats.isResolvable()) {
            return new WeeklyReportDto.Learned(0, 0, List.of());
        }
        try {
            ZoneId zone = clock.getZone();
            Instant start = from.atStartOfDay(zone).toInstant();
            Instant end = toExclusive.atStartOfDay(zone).toInstant();
            MemoryStats.Learned l = memoryStats.get().learnedBetween(start, end);
            if (l == null) {
                l = MemoryStats.Learned.none();
            }
            List<String> prompts = l.itemPrompts() == null ? List.of()
                    : l.itemPrompts().stream().limit(MAX_LEARNED_PROMPTS).toList();
            return new WeeklyReportDto.Learned(Math.max(0, l.itemsCreated()), Math.max(0, l.reviewsDone()), prompts);
        } catch (RuntimeException e) {
            LOG.warnf("Memory statistics unavailable for the weekly reflection (%s)", e.getClass().getSimpleName());
            return new WeeklyReportDto.Learned(0, 0, List.of());
        }
    }

    private WeeklyReportDto store(int year, int week, Language lang, WeeklyContent content) {
        WeeklyReportEntity e = reports.forWeek(year, week);
        if (e == null) {
            e = new WeeklyReportEntity();
            e.isoYear = year;
            e.isoWeek = week;
        }
        e.language = lang;
        e.content = write(content);
        e.generatedAt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        e.editedAt = null;
        reports.persist(e);
        return toDto(e);
    }

    // ------------------------------------------------------------------ read

    public List<WeeklyReportSummaryDto> list() {
        return QuarkusTransaction.requiringNew().call(() -> reports.newestWeekFirst().stream()
                .map(e -> new WeeklyReportSummaryDto(e.id, e.isoYear, e.isoWeek, e.generatedAt)).toList());
    }

    public WeeklyReportDto get(long id) {
        return QuarkusTransaction.requiringNew().call(() -> toDto(find(id)));
    }

    public void delete(long id) {
        QuarkusTransaction.requiringNew().run(() -> {
            if (!reports.deleteById(id)) {
                throw ApiException.notFound("Weekly report");
            }
        });
    }

    // ------------------------------------------------------------------ edit

    /** Replaces the six sections with the user's version. Week, language and generation date cannot be changed. */
    public WeeklyReportDto update(long id, WeeklyReportDto in) {
        if (in == null) {
            throw invalid("The report is missing.");
        }
        WeeklyContent content = validateEdit(in);
        return QuarkusTransaction.requiringNew().call(() -> {
            WeeklyReportEntity e = find(id);
            e.content = write(content);
            e.editedAt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            return toDto(e);
        });
    }

    private WeeklyContent validateEdit(WeeklyReportDto in) {
        List<WeeklyReportDto.Theme> themes = required(in.recurringThemes(), "recurringThemes", MAX_THEMES);
        List<WeeklyReportDto.Quote> quotes = required(in.inYourOwnWords(), "inYourOwnWords", MAX_QUOTES);
        List<String> threads = required(in.openThreads(), "openThreads", MAX_LIST_ITEMS);
        List<String> patterns = required(in.noticedPatterns(), "noticedPatterns", MAX_LIST_ITEMS);
        WeeklyReportDto.Learned learned = in.whatYouLearned();
        if (learned == null || learned.itemsCreated() < 0 || learned.reviewsDone() < 0
                || learned.itemsCreated() > 1_000_000 || learned.reviewsDone() > 1_000_000) {
            throw invalid("whatYouLearned needs non-negative counts.");
        }
        List<String> learnedPrompts = required(learned.itemPrompts(), "whatYouLearned.itemPrompts", MAX_LEARNED_PROMPTS);
        String question = in.questionForNextWeek();
        if (question == null || question.length() > MAX_TEXT) {
            throw invalid("questionForNextWeek is missing or too long.");
        }

        Set<Long> referenced = new LinkedHashSet<>();
        for (WeeklyReportDto.Theme t : themes) {
            if (t == null || blank(t.title()) || t.title().length() > 200 || t.description() == null || t.description().length() > MAX_TEXT) {
                throw invalid("Each theme needs a title and a description of reasonable length.");
            }
            if (t.entryIds() == null || t.entryIds().size() > MAX_ENTRY_IDS || t.entryIds().stream().anyMatch(i -> i == null)) {
                throw invalid("A theme has an invalid list of entries.");
            }
            referenced.addAll(t.entryIds());
        }
        for (WeeklyReportDto.Quote q : quotes) {
            if (q == null || blank(q.text()) || q.text().length() > MAX_TEXT) {
                throw invalid("Each quote needs text of reasonable length.");
            }
            referenced.add(q.entryId());
        }
        for (String s : threads) {
            textItem(s, "open thread");
        }
        for (String s : patterns) {
            textItem(s, "pattern");
        }
        for (String s : learnedPrompts) {
            textItem(s, "memory item");
        }
        if (!referenced.isEmpty()) {
            long existing = QuarkusTransaction.requiringNew().call(() -> entries.count("id in ?1", List.copyOf(referenced)));
            if (existing != referenced.size()) {
                throw invalid("A theme or quote refers to an entry that does not exist.");
            }
        }
        return new WeeklyContent(List.copyOf(themes), List.copyOf(quotes), List.copyOf(threads),
                new WeeklyReportDto.Learned(learned.itemsCreated(), learned.reviewsDone(), List.copyOf(learnedPrompts)),
                List.copyOf(patterns), question);
    }

    private static <T> List<T> required(List<T> list, String name, int max) {
        if (list == null || list.size() > max) {
            throw invalid(name + " is missing or has too many items.");
        }
        return list;
    }

    private static void textItem(String s, String what) {
        if (blank(s) || s.length() > MAX_TEXT) {
            throw invalid("Each " + what + " needs text of reasonable length.");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static ApiException invalid(String message) {
        return ApiException.badRequest("INVALID_REPORT", message);
    }

    // ------------------------------------------------------------------ entry deletion

    /**
     * Policy (D-019): the entry's id is removed from every theme and its quotes are dropped from every stored report.
     * The report itself, its other sections and the user's edits stay. Descriptive text that was written about the
     * entry (theme descriptions, patterns) is not rewritten: regenerate or delete the report to remove it.
     */
    void onEntryDeleted(@Observes(during = TransactionPhase.AFTER_SUCCESS) EntryDeleted event) {
        QuarkusTransaction.requiringNew().run(() -> {
            long changed = 0;
            for (WeeklyReportEntity e : reports.listAll()) {
                WeeklyContent before = read(e.content);
                WeeklyContent after = redact(before, event.entryId());
                if (!after.equals(before)) {
                    e.content = write(after);
                    changed++;
                }
            }
            if (changed > 0) {
                LOG.infof("Entry %d removed from %d weekly report(s)", event.entryId(), changed);
            }
        });
    }

    static WeeklyContent redact(WeeklyContent c, long entryId) {
        return new WeeklyContent(
                c.recurringThemes().stream().map(t -> new WeeklyReportDto.Theme(t.title(), t.description(),
                        t.entryIds().stream().filter(i -> i != entryId).toList())).toList(),
                c.inYourOwnWords().stream().filter(q -> q.entryId() != entryId).toList(),
                c.openThreads(), c.whatYouLearned(), c.noticedPatterns(), c.questionForNextWeek());
    }

    // ------------------------------------------------------------------ mapping

    WeeklyReportEntity find(long id) {
        WeeklyReportEntity e = reports.findById(id);
        if (e == null) {
            throw ApiException.notFound("Weekly report");
        }
        return e;
    }

    WeeklyReportDto toDto(WeeklyReportEntity e) {
        WeeklyContent c = read(e.content);
        return new WeeklyReportDto(e.id, e.isoYear, e.isoWeek, e.language, c.recurringThemes(), c.inYourOwnWords(),
                c.openThreads(), c.whatYouLearned(), c.noticedPatterns(), c.questionForNextWeek(), e.generatedAt, e.editedAt);
    }

    private String write(WeeklyContent c) {
        try {
            return mapper.writeValueAsString(c);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize weekly report", ex);
        }
    }

    private WeeklyContent read(String json) {
        try {
            return mapper.readValue(json, WeeklyContent.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored weekly report is unreadable");
        }
    }
}
