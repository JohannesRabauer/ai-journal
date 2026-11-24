package dev.rabauer.ai.diary.memory;

import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.EntryStatus;
import dev.rabauer.ai.diary.api.memory.CandidateDecisionRequest;
import dev.rabauer.ai.diary.api.memory.CandidateStatus;
import dev.rabauer.ai.diary.api.memory.ExtractionRequest;
import dev.rabauer.ai.diary.api.memory.LessonDto;
import dev.rabauer.ai.diary.api.memory.MemoryCandidateDto;
import dev.rabauer.ai.diary.api.memory.MemoryItemDto;
import dev.rabauer.ai.diary.api.memory.RevealDto;
import dev.rabauer.ai.diary.api.memory.ReviewAttemptRequest;
import dev.rabauer.ai.diary.api.memory.ReviewCardDto;
import dev.rabauer.ai.diary.api.memory.SourceType;
import dev.rabauer.ai.diary.coach.CoachConversationEntity;
import dev.rabauer.ai.diary.coach.CoachMessageEntity;
import dev.rabauer.ai.diary.coach.CoachMessageRepository;
import dev.rabauer.ai.diary.coach.CoachRules;
import dev.rabauer.ai.diary.entry.EntryDeleted;
import dev.rabauer.ai.diary.entry.EntryService;
import dev.rabauer.ai.diary.llm.StructuredOutputService;
import dev.rabauer.ai.diary.llm.StructuredRequest;
import dev.rabauer.ai.diary.settings.SettingsService;
import dev.rabauer.ai.diary.spi.LlmGateway;
import dev.rabauer.ai.diary.spi.ReviewScheduler;
import dev.rabauer.ai.diary.web.ApiException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Memory feature (SPEC section 6): extraction into candidates, user approval, retrieval practice, SM-2 scheduling.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>Extraction goes through {@link StructuredOutputService}; the LLM call runs outside any transaction. Only a
 *       fully validated answer is persisted, as PENDING candidates (candidates equal to an existing non-discarded
 *       candidate of the same source are skipped). Invalid output is retried, then rejected and nothing is stored.</li>
 *   <li>Only {@code APPROVE} creates a memory item. Deciding a candidate twice is a 409.</li>
 *   <li>A review cycle is one {@link MemoryReviewEntity}: the attempt opens it (a second attempt in the same cycle
 *       replaces the text), the grade closes it and is rejected with 409 without an open attempt. Attempts are not
 *       restricted to due items; the card list only offers due ones.</li>
 *   <li>Nothing here logs prompts, answers, quotes or attempts: only ids and counts.</li>
 * </ul>
 *
 * <h2>Deletion policy</h2>
 * <ul>
 *   <li><b>Entry deleted</b> ({@link EntryDeleted}, also for its conversations): candidates of that entry are deleted;
 *       approved items are KEPT (the user approved them) but lose {@code sourceQuote}, {@code sourceType},
 *       {@code sourceId} (the quote would keep a piece of the deleted entry alive). Review rows are untouched.</li>
 *   <li><b>Item deleted</b> by the user: the item and the typed attempt texts of its reviews are removed; the
 *       review rows stay as an anonymous log (time, grade) so review counts and unlocked lessons do not shrink.</li>
 *   <li><b>Delete everything</b>: all three tables ({@link MemoryDataOwner}).</li>
 * </ul>
 */
@ApplicationScoped
public class MemoryService {

    private static final Logger LOG = Logger.getLogger(MemoryService.class);

    static final int MAX_CANDIDATES = 7;
    static final int MAX_PROMPT_CHARS = 500;
    static final int MAX_ANSWER_CHARS = 2000;
    static final int MAX_QUOTE_CHARS = 1000;
    static final int MAX_ATTEMPT_CHARS = 20_000;
    static final int MAX_SOURCE_CHARS = 60_000;

    @Inject
    EntryService entries;
    @Inject
    CoachMessageRepository coachMessages;
    @Inject
    StructuredOutputService structured;
    @Inject
    SettingsService settings;
    @Inject
    ReviewScheduler scheduler;
    @Inject
    Clock clock;

    // ------------------------------------------------------------------ extraction

    /** The text the candidates are extracted from, with the entry it belongs to. */
    private record Source(SourceType type, long id, long entryId, Language language, String text) {
    }

    public List<MemoryCandidateDto> extract(ExtractionRequest request) {
        if (request == null || request.sourceType() == null || request.sourceId() <= 0) {
            throw ApiException.badRequest("INVALID_REQUEST", "sourceType and sourceId are required.");
        }
        Source source = loadSource(request);
        String normalizedSource = SourceQuotes.normalize(source.text());

        List<LlmGateway.Message> prompt = List.of(
                LlmGateway.Message.system(MemoryPrompts.system(source.language())),
                LlmGateway.Message.user(MemoryPrompts.user(source.language(), source.text())));
        ExtractedCandidates extracted = structured.generate(StructuredRequest.of(prompt,
                        new LlmGateway.JsonSchema("memory_candidates", MemoryPrompts.SCHEMA), ExtractedCandidates.class)
                .withSemanticCheck(c -> check(c, normalizedSource)));

        List<MemoryCandidateDto> created = QuarkusTransaction.requiringNew().call(() -> {
            Set<String> known = MemoryCandidateEntity.<MemoryCandidateEntity>list("sourceType = ?1 and sourceId = ?2 and status <> ?3",
                            source.type(), source.id(), CandidateStatus.DISCARDED).stream()
                    .map(c -> SourceQuotes.normalize(c.prompt)).collect(Collectors.toCollection(HashSet::new));
            List<MemoryCandidateDto> out = new ArrayList<>();
            Instant now = now();
            for (ExtractedCandidates.Candidate c : extracted.candidates()) {
                if (!known.add(SourceQuotes.normalize(c.prompt()))) {
                    continue;
                }
                MemoryCandidateEntity e = new MemoryCandidateEntity();
                e.sourceType = source.type();
                e.sourceId = source.id();
                e.entryId = source.entryId();
                e.type = c.type();
                e.prompt = c.prompt().strip();
                e.answer = c.answer().strip();
                e.sourceQuote = c.sourceQuote().strip();
                e.status = CandidateStatus.PENDING;
                e.createdAt = now;
                e.persist();
                out.add(toDto(e));
            }
            return out;
        });
        LOG.infof("Memory extraction for %s %d: %d proposed, %d stored", source.type(), source.id(), extracted.candidates().size(), created.size());
        return created;
    }

    private Source loadSource(ExtractionRequest request) {
        if (request.sourceType() == SourceType.ENTRY) {
            EntryDto entry = entries.get(request.sourceId());
            if (entry.status() != EntryStatus.FINISHED) {
                throw ApiException.conflict("ENTRY_NOT_FINISHED", "Finish the entry first, then extract learnings.");
            }
            String text = entry.text() == null ? "" : entry.text().strip();
            if (text.isEmpty()) {
                throw ApiException.conflict("ENTRY_EMPTY", "This entry has no text to extract learnings from.");
            }
            return new Source(SourceType.ENTRY, entry.id(), entry.id(), languageOf(entry.language(), text), truncate(text));
        }
        record Loaded(CoachConversationEntity conversation, List<CoachMessageEntity> messages) {
        }
        Loaded loaded = QuarkusTransaction.requiringNew().call(() -> {
            CoachConversationEntity c = CoachConversationEntity.findById(request.sourceId());
            if (c == null) {
                throw ApiException.notFound("Conversation");
            }
            return new Loaded(c, coachMessages.forConversation(c.id));
        });
        EntryDto entry = entries.get(loaded.conversation().entryId);
        if (entry.status() != EntryStatus.FINISHED) {
            throw ApiException.conflict("ENTRY_NOT_FINISHED", "Finish the entry first, then extract learnings.");
        }
        StringBuilder text = new StringBuilder();
        if (entry.text() != null && !entry.text().isBlank()) {
            text.append(entry.text().strip()).append("\n\n");
        }
        int turns = 0;
        for (CoachMessageEntity m : loaded.messages()) {
            if (!m.revealed()) {
                continue;
            }
            turns++;
            text.append(m.role == dev.rabauer.ai.diary.api.coach.CoachRole.COACH ? "Coach: " : "Me: ").append(m.content.strip()).append('\n');
        }
        if (turns == 0) {
            throw ApiException.conflict("CONVERSATION_EMPTY", "This conversation has no messages to extract learnings from.");
        }
        Language lang = languageOf(Language.fromCode(loaded.conversation().language), text.toString());
        return new Source(SourceType.CONVERSATION, loaded.conversation().id, entry.id(), lang, truncate(text.toString().strip()));
    }

    private static Language languageOf(Language declared, String text) {
        return declared == Language.DE || declared == Language.EN ? declared : CoachRules.detectLanguage(text);
    }

    private static String truncate(String s) {
        return s.length() <= MAX_SOURCE_CHARS ? s : s.substring(0, MAX_SOURCE_CHARS);
    }

    static List<String> check(ExtractedCandidates extracted, String normalizedSource) {
        Set<String> problems = new java.util.LinkedHashSet<>();
        Set<String> prompts = new HashSet<>();
        int i = 0;
        for (ExtractedCandidates.Candidate c : extracted.candidates()) {
            i++;
            String label = "candidate " + i + ": ";
            if (!isQuestionOrCue(c.prompt())) {
                problems.add(label + "prompt must be a question ending with '?' or a cue ending with ':'");
            }
            if (c.answer().isBlank()) {
                problems.add(label + "answer must not be empty");
            }
            if (SourceQuotes.normalize(c.prompt()).equals(SourceQuotes.normalize(c.answer()))) {
                problems.add(label + "answer must differ from the prompt");
            }
            if (!SourceQuotes.isVerbatim(c.sourceQuote(), normalizedSource)) {
                problems.add(label + "sourceQuote must be copied word for word from the source text");
            }
            if (!prompts.add(SourceQuotes.normalize(c.prompt()))) {
                problems.add(label + "prompts must differ from each other");
            }
        }
        return List.copyOf(problems);
    }

    static boolean isQuestionOrCue(String prompt) {
        String p = prompt.strip();
        return p.endsWith("?") || p.endsWith(":") || p.endsWith("…") || p.endsWith("...") || p.contains("___");
    }

    // ------------------------------------------------------------------ candidates

    public List<MemoryCandidateDto> candidates(CandidateStatus status) {
        CandidateStatus s = status == null ? CandidateStatus.PENDING : status;
        return QuarkusTransaction.requiringNew().call(() -> MemoryCandidateEntity.<MemoryCandidateEntity>list(
                        "status = ?1 order by createdAt desc, id desc", s).stream().map(MemoryService::toDto).toList());
    }

    public MemoryCandidateDto decide(long id, CandidateDecisionRequest request) {
        if (request == null || request.decision() == null) {
            throw ApiException.badRequest("INVALID_REQUEST", "decision is required.");
        }
        List<Integer> ladder = intervals();
        return QuarkusTransaction.requiringNew().call(() -> {
            MemoryCandidateEntity c = MemoryCandidateEntity.findById(id, LockModeType.PESSIMISTIC_WRITE);
            if (c == null) {
                throw ApiException.notFound("Candidate");
            }
            if (c.status != CandidateStatus.PENDING) {
                throw ApiException.conflict("CANDIDATE_DECIDED", "This candidate was already decided.");
            }
            if (request.decision() == CandidateDecisionRequest.Decision.DISCARD) {
                c.status = CandidateStatus.DISCARDED;
                return toDto(c);
            }
            String prompt = request.prompt() == null ? c.prompt : request.prompt().strip();
            String answer = request.answer() == null ? c.answer : request.answer().strip();
            if (prompt.isEmpty() || prompt.length() > MAX_PROMPT_CHARS) {
                throw ApiException.badRequest("INVALID_PROMPT", "The prompt must not be empty or too long.");
            }
            if (answer.isEmpty() || answer.length() > MAX_ANSWER_CHARS) {
                throw ApiException.badRequest("INVALID_ANSWER", "The answer must not be empty or too long.");
            }
            c.prompt = prompt;
            c.answer = answer;
            if (request.type() != null) {
                c.type = request.type();
            }
            c.status = CandidateStatus.APPROVED;

            ReviewScheduler.State state = scheduler.initial(today(), ladder);
            MemoryItemEntity item = new MemoryItemEntity();
            item.type = c.type;
            item.prompt = c.prompt;
            item.answer = c.answer;
            item.sourceQuote = c.sourceQuote;
            item.sourceType = c.sourceType;
            item.sourceId = c.sourceId;
            item.entryId = c.entryId;
            apply(item, state);
            item.createdAt = now();
            item.persist();
            return toDto(c);
        });
    }

    // ------------------------------------------------------------------ items

    public List<MemoryItemDto> items() {
        return QuarkusTransaction.requiringNew().call(() -> MemoryItemEntity.<MemoryItemEntity>list("order by nextReviewOn, id").stream()
                .map(MemoryService::toDto).toList());
    }

    public void deleteItem(long id) {
        QuarkusTransaction.requiringNew().run(() -> {
            if (!MemoryItemEntity.deleteById(id)) {
                throw ApiException.notFound("Memory item");
            }
            MemoryReviewEntity.update("attemptText = null where itemId = ?1", id);
        });
    }

    // ------------------------------------------------------------------ reviews

    public List<ReviewCardDto> due() {
        LocalDate today = today();
        return QuarkusTransaction.requiringNew().call(() -> MemoryItemEntity.<MemoryItemEntity>list(
                        "nextReviewOn <= ?1 order by nextReviewOn, id", today).stream()
                .map(i -> new ReviewCardDto(i.id, i.type, i.prompt)).toList());
    }

    public long dueCount() {
        LocalDate today = today();
        return QuarkusTransaction.requiringNew().call(() -> MemoryItemEntity.count("nextReviewOn <= ?1", today));
    }

    public RevealDto attempt(long itemId, ReviewAttemptRequest request) {
        String text = request == null || request.attemptText() == null || request.attemptText().isBlank() ? null : request.attemptText().strip();
        if (text != null && text.length() > MAX_ATTEMPT_CHARS) {
            throw ApiException.badRequest("ATTEMPT_TOO_LONG", "The answer is too long.");
        }
        return QuarkusTransaction.requiringNew().call(() -> {
            MemoryItemEntity item = MemoryItemEntity.findById(itemId, LockModeType.PESSIMISTIC_WRITE);
            if (item == null) {
                throw ApiException.notFound("Memory item");
            }
            MemoryReviewEntity open = openReview(itemId);
            if (open == null) {
                open = new MemoryReviewEntity();
                open.itemId = itemId;
            }
            open.attemptText = text;
            open.attemptedAt = now();
            open.persist();
            return new RevealDto(item.id, item.answer, item.sourceQuote, item.sourceType, item.sourceId, text);
        });
    }

    public MemoryItemDto grade(long itemId, int quality) {
        if (quality < 0 || quality > 5) {
            throw ApiException.badRequest("INVALID_QUALITY", "Quality must be between 0 and 5.");
        }
        List<Integer> ladder = intervals();
        return QuarkusTransaction.requiringNew().call(() -> {
            MemoryItemEntity item = MemoryItemEntity.findById(itemId, LockModeType.PESSIMISTIC_WRITE);
            if (item == null) {
                throw ApiException.notFound("Memory item");
            }
            MemoryReviewEntity open = openReview(itemId);
            if (open == null) {
                throw ApiException.conflict("ATTEMPT_REQUIRED", "Try an answer first, then rate how it went.");
            }
            ReviewScheduler.State next = scheduler.next(
                    new ReviewScheduler.State(item.repetitions, item.intervalDays, item.easeFactor, item.nextReviewOn),
                    quality, today(), ladder);
            apply(item, next);
            open.quality = quality;
            open.gradedAt = now();
            open.intervalDays = next.intervalDays();
            return toDto(item);
        });
    }

    private static MemoryReviewEntity openReview(long itemId) {
        return MemoryReviewEntity.<MemoryReviewEntity>find("itemId = ?1 and gradedAt is null order by id desc", itemId).firstResult();
    }

    // ------------------------------------------------------------------ lessons

    public List<LessonDto> lessons(boolean german) {
        long approved = QuarkusTransaction.requiringNew().call(() -> MemoryCandidateEntity.count("status", CandidateStatus.APPROVED));
        long items = QuarkusTransaction.requiringNew().call(() -> MemoryItemEntity.count());
        long reviews = QuarkusTransaction.requiringNew().call(() -> MemoryReviewEntity.count("gradedAt is not null"));
        // An approved candidate disappears with its entry and an item with its deletion; a graded review implies that an
        // item was approved once. Taking the maximum keeps unlocked lessons unlocked.
        long keptItems = Math.max(Math.max(approved, items), reviews > 0 ? 1 : 0);
        return Lessons.forLanguage(german, keptItems, reviews);
    }

    // ------------------------------------------------------------------ entry deletion

    /** See the class Javadoc: candidates go, items stay without their source reference. */
    void onEntryDeleted(@Observes(during = TransactionPhase.AFTER_SUCCESS) EntryDeleted event) {
        QuarkusTransaction.requiringNew().run(() -> {
            MemoryCandidateEntity.delete("entryId", event.entryId());
            MemoryItemEntity.update("sourceQuote = null, sourceType = null, sourceId = null, entryId = null where entryId = ?1", event.entryId());
        });
    }

    // ------------------------------------------------------------------ helpers

    private List<Integer> intervals() {
        List<Integer> l = settings.get().reviewIntervalsDays();
        return l == null || l.isEmpty() ? SettingsService.DEFAULT_INTERVALS : l;
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static void apply(MemoryItemEntity item, ReviewScheduler.State s) {
        item.repetitions = s.repetitions();
        item.intervalDays = s.intervalDays();
        item.easeFactor = s.easeFactor();
        item.nextReviewOn = s.nextReviewOn();
    }

    static MemoryCandidateDto toDto(MemoryCandidateEntity e) {
        return new MemoryCandidateDto(e.id, e.sourceType, e.sourceId, e.type, e.prompt, e.answer, e.sourceQuote, e.status, e.createdAt);
    }

    static MemoryItemDto toDto(MemoryItemEntity e) {
        return new MemoryItemDto(e.id, e.type, e.prompt, e.answer, e.sourceQuote, e.sourceType, e.sourceId,
                e.repetitions, e.intervalDays, e.easeFactor, e.nextReviewOn, e.createdAt);
    }
}
