package dev.rabauer.ai.diary.gym;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.entry.EntryStatus;
import dev.rabauer.ai.diary.api.gym.AttemptDto;
import dev.rabauer.ai.diary.api.gym.AttemptRequest;
import dev.rabauer.ai.diary.api.gym.ExerciseDto;
import dev.rabauer.ai.diary.api.gym.GymCategory;
import dev.rabauer.ai.diary.coach.CoachRules;
import dev.rabauer.ai.diary.entry.DiaryEntryEntity;
import dev.rabauer.ai.diary.entry.EntryDeleted;
import dev.rabauer.ai.diary.entry.EntryIndexer;
import dev.rabauer.ai.diary.entry.EntryRepository;
import dev.rabauer.ai.diary.llm.InvalidLlmOutputException;
import dev.rabauer.ai.diary.llm.StructuredOutputService;
import dev.rabauer.ai.diary.llm.StructuredRequest;
import dev.rabauer.ai.diary.settings.SettingsService;
import dev.rabauer.ai.diary.spi.EmbeddingGateway;
import dev.rabauer.ai.diary.spi.LlmGateway;
import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.spi.VectorStore;
import dev.rabauer.ai.diary.web.ApiException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * The Thinking Gym (SPEC section 7). Works without any AI; the LLM only personalizes and only on request gives
 * follow-up questions.
 *
 * <h2>Which exercise comes next</h2>
 * <ul>
 *   <li><b>Category</b> (when the client names none): the least recently <i>practiced</i> one. Practiced means an
 *       attempt was saved. Order: categories without any attempt first; then by oldest last attempt. Ties (for
 *       example several never practiced) are broken by the oldest last <i>issued</i> exercise (never issued first),
 *       then by the order of {@link GymCategory}. So asking for an exercise and skipping it still moves on to a
 *       different category next time, and the rotation is fair over time.</li>
 *   <li><b>Personalized</b> (always attempted first; nothing is checked up front, so "no LLM" is just a failure):
 *       a FINISHED entry is chosen (not one that looks like a crisis text), preferring entries that match the
 *       category theme by embedding search, then the most recent ones, skipping entries used as the source of one of
 *       the last 5 exercises when others exist. The LLM returns {title, prompt, steps, sourceExcerpt} through
 *       {@link StructuredOutputService} (2 attempts: the first plus one corrective retry). The excerpt must be a
 *       verbatim passage of the entry (case, whitespace and quote style are ignored; the stored excerpt is the entry's
 *       own wording), steps 2-5, no praise, no diagnosis or score.</li>
 *   <li><b>Template fallback</b> on ANY problem (no finished entry, retrieval failure ignored, provider error,
 *       invalid output incl. an invented excerpt after the retry): the template of the category and language that was
 *       used least recently. Template language is the UI language (settings, then Accept-Language, then the language
 *       of the latest entry, then English); personalized exercises use the entry's language.</li>
 * </ul>
 *
 * <h2>Entry deletion</h2>
 * Exercises that draw on an entry (personalized, with sourceEntryId) are derived from it: their prompt may paraphrase
 * the entry. When the entry is deleted those exercises are deleted together with their attempts (the user's answers
 * and follow-up questions). Template exercises and their attempts stay.
 *
 * <p>Nothing here logs entry text, prompts, answers or LLM output; only ids, counts and error kinds.
 */
@ApplicationScoped
public class GymService {

    private static final Logger LOG = Logger.getLogger(GymService.class);
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    static final int MAX_ANSWER_CHARS = 20_000;
    static final int MAX_DURATION_SECONDS = 86_400;
    static final int RECENT_ENTRIES = 30;
    static final int MIN_ENTRY_CHARS = 40;
    static final int RECENT_SOURCE_WINDOW = 5;
    static final int DEFAULT_MINUTES = 7;
    static final int MAX_QUESTION_WORDS = 40;

    @Inject
    GymExerciseRepository exercises;
    @Inject
    GymAttemptRepository attempts;
    @Inject
    GymTemplates templates;
    @Inject
    EntryRepository entries;
    @Inject
    StructuredOutputService structured;
    @Inject
    EmbeddingGateway embeddings;
    @Inject
    VectorStore vectors;
    @Inject
    SettingsService settings;
    @Inject
    Clock clock;
    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "journal.gym.similar-entries", defaultValue = "10")
    int similarEntries;
    @ConfigProperty(name = "journal.gym.similar-min-score", defaultValue = "0.2")
    double similarMinScore;
    @ConfigProperty(name = "journal.gym.generation-attempts", defaultValue = "2")
    int generationAttempts;

    // ------------------------------------------------------------------ exercises

    public ExerciseDto next(GymCategory requested, String acceptLanguage) {
        GymCategory category = requested != null ? requested : tx(this::leastRecentlyPracticed);
        Language ui = tx(() -> uiLanguage(acceptLanguage));
        ExerciseDto personalized = tryPersonalize(category, ui);
        if (personalized != null) {
            return personalized;
        }
        ExerciseDto template = tx(() -> persistTemplate(category, ui));
        LOG.infof("Gym exercise %d (%s): template %s", template.id(), category, template.language());
        return template;
    }

    public ExerciseDto get(long id) {
        return tx(() -> toDto(findExercise(id)));
    }

    GymCategory leastRecentlyPracticed() {
        Map<GymCategory, Instant> practiced = attempts.lastAttemptPerCategory();
        Map<GymCategory, Instant> issued = exercises.lastIssuedPerCategory();
        Comparator<GymCategory> order = Comparator
                .comparing((GymCategory c) -> practiced.get(c), Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(c -> issued.get(c), Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingInt(Enum::ordinal);
        return java.util.Arrays.stream(GymCategory.values()).min(order).orElseThrow();
    }

    // ---- personalization

    private record Candidate(long id, String text, Language language) {
    }

    private ExerciseDto tryPersonalize(GymCategory category, Language ui) {
        Candidate candidate;
        try {
            List<Long> relevant = relevantEntryIds(category, ui);
            candidate = tx(() -> pickEntry(relevant));
        } catch (RuntimeException e) {
            LOG.infof("Gym: entry selection failed (%s), using a template", e.getClass().getSimpleName());
            return null;
        }
        if (candidate == null) {
            LOG.info("Gym: no suitable finished entry, using a template");
            return null;
        }
        Language lang = candidate.language();
        GeneratedExercise generated;
        try {
            generated = structured.generate(StructuredRequest.of(
                            List.of(LlmGateway.Message.system(GymPrompts.exerciseSystemPrompt(category, lang)),
                                    LlmGateway.Message.user(GymPrompts.exerciseUserPrompt(lang, candidate.text()))),
                            new LlmGateway.JsonSchema("gym_exercise", GymPrompts.EXERCISE_SCHEMA), GeneratedExercise.class)
                    .withMaxAttempts(Math.max(1, generationAttempts))
                    .withSemanticCheck(g -> checkGenerated(g, candidate.text())));
        } catch (ProviderException e) {
            LOG.infof("Gym: personalization skipped, provider %s/%s; using a template", e.provider(), e.kind());
            return null;
        } catch (InvalidLlmOutputException e) {
            LOG.infof("Gym: personalization rejected after %d attempt(s); using a template", e.attempts());
            return null;
        } catch (RuntimeException e) {
            LOG.infof("Gym: personalization failed (%s); using a template", e.getClass().getSimpleName());
            return null;
        }
        String excerpt = GymExcerpts.findVerbatim(candidate.text(), generated.sourceExcerpt()).orElse(null);
        if (excerpt == null) {
            return null; // cannot happen after the semantic check; never persist an unverified excerpt
        }
        ExerciseDto saved = tx(() -> {
            GymExerciseEntity e = newExercise(category, lang);
            e.title = generated.title().strip();
            e.prompt = generated.prompt().strip();
            e.steps = json(generated.steps().stream().map(String::strip).toList());
            e.estimatedMinutes = generated.estimatedMinutes() == null ? DEFAULT_MINUTES : generated.estimatedMinutes();
            e.sourceExcerpt = excerpt;
            e.sourceEntryId = candidate.id();
            e.personalized = true;
            exercises.persist(e);
            return toDto(e);
        });
        LOG.infof("Gym exercise %d (%s): personalized from entry %d", saved.id(), category, candidate.id());
        return saved;
    }

    static List<String> checkGenerated(GeneratedExercise g, String entryText) {
        List<String> problems = new ArrayList<>();
        if (GymExcerpts.findVerbatim(entryText, g.sourceExcerpt()).isEmpty()) {
            problems.add("sourceExcerpt must be ONE passage copied exactly, character for character, from the journal entry ("
                    + GymExcerpts.MIN_CHARS + " to 300 characters, no '...')");
        }
        StringBuilder all = new StringBuilder(g.title()).append(' ').append(g.prompt());
        g.steps().forEach(s -> all.append(' ').append(s));
        if (CoachRules.containsPraise(all.toString())) {
            problems.add("no praise or flattery");
        }
        if (CoachRules.containsDiagnosisOrScore(all.toString())) {
            problems.add("no diagnoses, therapy claims, scores or ratings");
        }
        return problems;
    }

    /** Embedding search for entries that suit the category. Any failure just means "no hints". */
    private List<Long> relevantEntryIds(GymCategory category, Language ui) {
        if (similarEntries <= 0) {
            return List.of();
        }
        try {
            float[] query = embeddings.embed(GymPrompts.theme(category, ui));
            Set<Long> ids = new LinkedHashSet<>();
            for (VectorStore.Match m : vectors.search(EntryIndexer.NAMESPACE, query, embeddings.modelId(), similarEntries * 3, similarMinScore)) {
                try {
                    ids.add(Long.parseLong(m.ownerId()));
                } catch (NumberFormatException ignored) {
                    // not an entry id
                }
                if (ids.size() >= similarEntries) {
                    break;
                }
            }
            return List.copyOf(ids);
        } catch (RuntimeException e) {
            LOG.infof("Gym: retrieval unavailable (%s), using recent entries", e.getClass().getSimpleName());
            return List.of();
        }
    }

    private Candidate pickEntry(List<Long> relevant) {
        List<Candidate> list = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (long id : relevant) {
            usable(entries.findById(id)).filter(c -> seen.add(c.id())).ifPresent(list::add);
        }
        for (DiaryEntryEntity e : entries.find("status", EntryRepository.NEWEST_FIRST, EntryStatus.FINISHED)
                .page(0, RECENT_ENTRIES).list()) {
            usable(e).filter(c -> seen.add(c.id())).ifPresent(list::add);
        }
        Set<Long> recentSources = exercises.recentSourceEntries(RECENT_SOURCE_WINDOW);
        return list.stream().filter(c -> !recentSources.contains(c.id())).findFirst()
                .or(() -> list.stream().findFirst()).orElse(null);
    }

    private static java.util.Optional<Candidate> usable(DiaryEntryEntity e) {
        if (e == null || e.status != EntryStatus.FINISHED || e.entryText == null || e.entryText.strip().length() < MIN_ENTRY_CHARS
                || CoachRules.indicatesCrisis(e.entryText)) {
            return java.util.Optional.empty();
        }
        Language lang = "de".equals(e.language) ? Language.DE : "en".equals(e.language) ? Language.EN
                : CoachRules.detectLanguage(e.entryText);
        return java.util.Optional.of(new Candidate(e.id, e.entryText, lang));
    }

    // ---- templates

    private ExerciseDto persistTemplate(GymCategory category, Language lang) {
        List<GymTemplates.Template> candidates = templates.forCategory(lang, category);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No templates for " + category + "/" + lang);
        }
        Map<String, Instant> use = exercises.lastUsePerTemplate(category, lang.code());
        GymTemplates.Template chosen = candidates.stream()
                .min(Comparator.comparing((GymTemplates.Template t) -> use.get(t.id()), Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElseThrow();
        GymExerciseEntity e = newExercise(category, lang);
        e.title = chosen.title();
        e.prompt = chosen.prompt();
        e.steps = json(chosen.steps());
        e.estimatedMinutes = chosen.estimatedMinutes();
        e.personalized = false;
        e.templateId = chosen.id();
        exercises.persist(e);
        return toDto(e);
    }

    private GymExerciseEntity newExercise(GymCategory category, Language lang) {
        GymExerciseEntity e = new GymExerciseEntity();
        e.category = category;
        e.language = lang.code();
        e.createdAt = now();
        return e;
    }

    private Language uiLanguage(String acceptLanguage) {
        Language configured = settings.get().uiLanguage();
        if (configured == Language.DE || configured == Language.EN) {
            return configured;
        }
        Language header = fromAcceptLanguage(acceptLanguage);
        if (header != null) {
            return header;
        }
        DiaryEntryEntity latest = entries.find("status", EntryRepository.NEWEST_FIRST, EntryStatus.FINISHED).firstResult();
        if (latest != null && latest.entryText != null && !latest.entryText.isBlank()) {
            return "de".equals(latest.language) ? Language.DE : "en".equals(latest.language) ? Language.EN
                    : CoachRules.detectLanguage(latest.entryText);
        }
        return Language.EN;
    }

    /** First listed language of an Accept-Language header if it is German or English, else null. */
    static Language fromAcceptLanguage(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String first = header.split(",")[0].split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (first.startsWith("de")) {
            return Language.DE;
        }
        if (first.startsWith("en")) {
            return Language.EN;
        }
        return null;
    }

    // ------------------------------------------------------------------ attempts

    public AttemptDto attempt(long exerciseId, AttemptRequest request) {
        String answer = request == null || request.answer() == null ? "" : request.answer().strip();
        if (answer.isEmpty()) {
            throw ApiException.badRequest("ANSWER_EMPTY", "Write something before saving your answer.");
        }
        if (answer.length() > MAX_ANSWER_CHARS) {
            throw ApiException.badRequest("ANSWER_TOO_LONG", "The answer is too long to save.");
        }
        int seconds = Math.max(0, Math.min(MAX_DURATION_SECONDS, request.durationSeconds()));
        AttemptDto saved = tx(() -> {
            GymExerciseEntity exercise = findExercise(exerciseId);
            GymAttemptEntity a = new GymAttemptEntity();
            a.exerciseId = exercise.id;
            a.category = exercise.category;
            a.answer = answer;
            a.durationSeconds = seconds;
            a.createdAt = now();
            attempts.persist(a);
            return toDto(a);
        });
        LOG.infof("Gym attempt %d saved for exercise %d (%d chars, %d s)", saved.id(), exerciseId, answer.length(), seconds);
        return saved;
    }

    public List<AttemptDto> history(GymCategory category) {
        return tx(() -> attempts.history(category).stream().map(this::toDto).toList());
    }

    /**
     * Optional AI follow-up questions, only when the user asks. A second request returns the stored questions without
     * another LLM call. A failure leaves the attempt unchanged and reaches the client as 503/502.
     */
    public AttemptDto feedback(long attemptId) {
        record Loaded(AttemptDto existing, GymExerciseEntity exercise, String answer) {
        }
        Loaded loaded = tx(() -> {
            GymAttemptEntity a = findAttempt(attemptId);
            GymExerciseEntity e = findExercise(a.exerciseId);
            AttemptDto dto = toDto(a);
            return new Loaded(a.feedbackQuestions == null ? null : dto, e, a.answer);
        });
        if (loaded.existing() != null) {
            return loaded.existing();
        }
        Language lang = "de".equals(loaded.exercise().language) ? Language.DE : Language.EN;
        GymFeedback feedback = structured.generate(StructuredRequest.of(
                        List.of(LlmGateway.Message.system(GymPrompts.feedbackSystemPrompt(lang)),
                                LlmGateway.Message.user(GymPrompts.feedbackUserPrompt(lang, loaded.exercise(),
                                        parse(loaded.exercise().steps), loaded.answer()))),
                        new LlmGateway.JsonSchema("gym_feedback", GymPrompts.FEEDBACK_SCHEMA), GymFeedback.class)
                .withSemanticCheck(GymService::checkFeedback));
        AttemptDto saved = tx(() -> {
            GymAttemptEntity a = findAttempt(attemptId);
            if (a.feedbackQuestions == null) {
                a.feedbackQuestions = json(feedback.questions().stream().map(String::strip).toList());
            }
            return toDto(a);
        });
        LOG.infof("Gym attempt %d: %d follow-up question(s) stored", attemptId, saved.feedbackQuestions().size());
        return saved;
    }

    /** Questions only: exactly one question mark each, ends with it, short, no praise/diagnosis/score. */
    static List<String> checkFeedback(GymFeedback f) {
        List<String> problems = new ArrayList<>();
        for (String q : f.questions()) {
            problems.addAll(CoachRules.singleQuestionProblems(q));
            if (!CoachRules.endsWithQuestion(q)) {
                problems.add("every item must be a question ending with a question mark");
            }
            if (CoachRules.wordCount(q) > MAX_QUESTION_WORDS) {
                problems.add("each question must be at most " + MAX_QUESTION_WORDS + " words");
            }
            if (CoachRules.containsPraise(q)) {
                problems.add("no praise or flattery");
            }
            if (CoachRules.containsDiagnosisOrScore(q)) {
                problems.add("no diagnoses, therapy claims, scores or ratings");
            }
        }
        return problems.stream().distinct().toList();
    }

    // ------------------------------------------------------------------ privacy

    /** D-019: exercises drawn from the deleted entry are removed with their attempts (see class comment). */
    void onEntryDeleted(@Observes(during = TransactionPhase.AFTER_SUCCESS) EntryDeleted event) {
        QuarkusTransaction.requiringNew().run(() -> {
            attempts.delete("exerciseId in (select e.id from GymExerciseEntity e where e.sourceEntryId = ?1)", event.entryId());
            long removed = exercises.delete("sourceEntryId", event.entryId());
            if (removed > 0) {
                LOG.infof("Gym: removed %d exercise(s) drawn from deleted entry %d", removed, event.entryId());
            }
        });
    }

    // ------------------------------------------------------------------ mapping and helpers

    private GymExerciseEntity findExercise(long id) {
        GymExerciseEntity e = exercises.findById(id);
        if (e == null) {
            throw ApiException.notFound("Exercise");
        }
        return e;
    }

    private GymAttemptEntity findAttempt(long id) {
        GymAttemptEntity a = attempts.findById(id);
        if (a == null) {
            throw ApiException.notFound("Attempt");
        }
        return a;
    }

    ExerciseDto toDto(GymExerciseEntity e) {
        return new ExerciseDto(e.id, e.category, Language.fromCode(e.language), e.title, e.prompt, e.sourceExcerpt,
                e.sourceEntryId, parse(e.steps), e.estimatedMinutes, e.personalized);
    }

    AttemptDto toDto(GymAttemptEntity a) {
        return new AttemptDto(a.id, a.exerciseId, a.category, a.answer, a.durationSeconds,
                a.feedbackQuestions == null ? List.of() : parse(a.feedbackQuestions), a.createdAt);
    }

    private String json(List<String> values) {
        try {
            return mapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> parse(String json) {
        try {
            return mapper.readValue(json, STRINGS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored gym list is not valid JSON", e);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static <T> T tx(Callable<T> block) {
        return QuarkusTransaction.requiringNew().call(block);
    }
}
