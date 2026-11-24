package dev.rabauer.ai.diary.coach;

import dev.rabauer.ai.diary.api.coach.AnswerRequest;
import dev.rabauer.ai.diary.api.coach.CoachMessageDto;
import dev.rabauer.ai.diary.api.coach.CoachMessageKind;
import dev.rabauer.ai.diary.api.coach.CoachRole;
import dev.rabauer.ai.diary.api.coach.ConversationDto;
import dev.rabauer.ai.diary.api.coach.ConversationStatus;
import dev.rabauer.ai.diary.api.coach.StartConversationRequest;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.EntryStatus;
import dev.rabauer.ai.diary.entry.EntryDeleted;
import dev.rabauer.ai.diary.entry.EntryIndexer;
import dev.rabauer.ai.diary.entry.EntryService;
import dev.rabauer.ai.diary.llm.StructuredOutputService;
import dev.rabauer.ai.diary.llm.StructuredRequest;
import dev.rabauer.ai.diary.spi.EmbeddingGateway;
import dev.rabauer.ai.diary.spi.LlmGateway;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The coach (SPEC section 5), reachable ONLY through the coach endpoints: nothing in entry saving, recording or
 * transcription calls this class (SPEC 4.4).
 *
 * <h2>Conversation model</h2>
 * <ul>
 *   <li><b>Opening questions.</b> On start the LLM produces 2-3 single questions (structured, validated, retried).
 *       All are persisted as {@code OPENING_QUESTION} messages, but only the first is <i>revealed</i>
 *       ({@code seq_no} set). The others are planned (hidden) and never appear in a {@link ConversationDto} until
 *       revealed, so the UI asks one question at a time.</li>
 *   <li><b>Each user answer</b> is stored and then the next coach turn is chosen deterministically in Java:
 *       <ol>
 *         <li>Crisis keywords in the answer: fixed non-AI {@code CLOSING} message, conversation {@code ENDED},
 *             no LLM call.</li>
 *         <li>While planned questions remain and the user neither asked for an explanation nor said they are done,
 *             and the current question already received {@code journal.coach.follow-ups-per-question} follow-up(s)
 *             (default 1): the next planned question is revealed, no LLM call.</li>
 *         <li>Otherwise one LLM call (structured {@code {kind, text}}). Allowed kinds: {@code FOLLOW_UP_QUESTION};
 *             plus {@code EXPLANATION} only if the answer explicitly asks for one (phrase patterns in
 *             {@link CoachRules}); plus {@code CLOSING} if all questions were revealed or the user said they are
 *             done (then CLOSING is the only kind besides an explicitly requested EXPLANATION).</li>
 *       </ol></li>
 *   <li><b>CLOSING is a message, not a state change.</b> The conversation stays {@code ACTIVE} until the user calls
 *       {@code /end} (the user may still add something). Only the crisis path ends a conversation by itself.</li>
 *   <li><b>Crisis</b> (entry text at start, or an answer): conversation is created/kept {@code ENDED} with the fixed,
 *       localized message. ENDED rather than PAUSED because the coach has stopped questioning for good; the user can
 *       start a new conversation. The text is never logged.</li>
 *   <li><b>Failure of the coach turn</b> (provider down, invalid output after retries): the user's answer stays
 *       stored, nothing from the LLM is persisted, the client receives 503/502.</li>
 *   <li>Similar past entries (max 3, FINISHED, via embeddings) are used only for the opening questions; follow-ups
 *       see the entry and the transcript. Retrieval failure never fails a start.</li>
 * </ul>
 */
@ApplicationScoped
public class CoachService {

    private static final Logger LOG = Logger.getLogger(CoachService.class);
    static final String SIMILAR_NAMESPACE = EntryIndexer.NAMESPACE;
    static final int MAX_ANSWER_CHARS = 20_000;
    static final int HISTORY_LIMIT = 30;

    @Inject
    CoachConversationRepository conversations;
    @Inject
    CoachMessageRepository messages;
    @Inject
    EntryService entries;
    @Inject
    StructuredOutputService structured;
    @Inject
    EmbeddingGateway embeddings;
    @Inject
    VectorStore vectors;
    @Inject
    Clock clock;

    @ConfigProperty(name = "journal.coach.follow-ups-per-question", defaultValue = "1")
    int followUpsPerQuestion;
    @ConfigProperty(name = "journal.coach.similar-entries", defaultValue = "3")
    int similarEntries;
    @ConfigProperty(name = "journal.coach.similar-min-score", defaultValue = "0.3")
    double similarMinScore;

    // ------------------------------------------------------------------ start

    public ConversationDto start(StartConversationRequest request) {
        EntryDto entry = entries.get(request.entryId());
        if (entry.status() != EntryStatus.FINISHED) {
            throw ApiException.conflict("ENTRY_NOT_FINISHED", "Finish the entry first, then reflect with the coach.");
        }
        String text = entry.text() == null ? "" : entry.text();
        if (text.isBlank()) {
            throw ApiException.conflict("ENTRY_EMPTY", "This entry has no text to reflect on.");
        }
        Language lang = entry.language() != null && entry.language() != Language.AUTO ? entry.language() : CoachRules.detectLanguage(text);

        if (CoachRules.indicatesCrisis(text)) {
            LOG.infof("Coach start for entry %d: crisis rule applied", entry.id());
            return persistNew(entry.id(), lang, ConversationStatus.ENDED,
                    List.of(new Planned(CoachMessageKind.CLOSING, CoachPrompts.crisisMessage(lang), true)));
        }

        List<CoachPrompts.PastEntry> past = similarEntries(entry, text);
        List<LlmGateway.Message> prompt = List.of(
                LlmGateway.Message.system(CoachPrompts.openingSystemPrompt(lang)),
                LlmGateway.Message.user(CoachPrompts.contextBlock(lang, text, past)));
        OpeningQuestions opening = structured.generate(StructuredRequest.of(prompt,
                        new LlmGateway.JsonSchema("coach_opening_questions", CoachPrompts.OPENING_SCHEMA), OpeningQuestions.class)
                .withSemanticCheck(CoachService::checkOpening));

        List<Planned> planned = new ArrayList<>();
        for (int i = 0; i < opening.questions().size(); i++) {
            planned.add(new Planned(CoachMessageKind.OPENING_QUESTION, opening.questions().get(i).strip(), i == 0));
        }
        ConversationDto dto = persistNew(entry.id(), lang, ConversationStatus.ACTIVE, planned);
        LOG.infof("Coach conversation %d started for entry %d (%d opening questions, %d past entries as context)",
                dto.id(), entry.id(), opening.questions().size(), past.size());
        return dto;
    }

    static List<String> checkOpening(OpeningQuestions q) {
        Set<String> problems = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String question : q.questions()) {
            CoachRules.commonProblems(question).forEach(p -> problems.add(p));
            for (String p : CoachRules.singleQuestionProblems(question)) {
                problems.add("each opening question: " + p);
            }
            if (!seen.add(question.strip().toLowerCase(Locale.ROOT))) {
                problems.add("opening questions must differ from each other");
            }
        }
        return List.copyOf(problems);
    }

    /** Up to N similar past FINISHED entries; any failure yields an empty list (never fails the start). */
    List<CoachPrompts.PastEntry> similarEntries(EntryDto entry, String text) {
        if (similarEntries <= 0) {
            return List.of();
        }
        try {
            float[] query = embeddings.embed(CoachPrompts.truncate(text, 2000));
            List<VectorStore.Match> matches = vectors.search(SIMILAR_NAMESPACE, query, embeddings.modelId(), similarEntries * 6, similarMinScore);
            Map<Long, CoachPrompts.PastEntry> out = new LinkedHashMap<>();
            for (VectorStore.Match m : matches) {
                if (out.size() >= similarEntries) {
                    break;
                }
                long id;
                try {
                    id = Long.parseLong(m.ownerId());
                } catch (NumberFormatException e) {
                    continue;
                }
                if (id == entry.id() || out.containsKey(id)) {
                    continue;
                }
                try {
                    EntryDto other = entries.get(id);
                    if (other.status() == EntryStatus.FINISHED && other.entryTimestamp() != null) {
                        out.put(id, new CoachPrompts.PastEntry(other.entryTimestamp().toLocalDate(), m.text()));
                    }
                } catch (ApiException e) {
                    // entry vanished since indexing: skip
                }
            }
            return List.copyOf(out.values());
        } catch (RuntimeException e) {
            LOG.warnf("Similar-entry retrieval failed for entry %d (%s); continuing without context", entry.id(), e.getClass().getSimpleName());
            return List.of();
        }
    }

    // ------------------------------------------------------------------ answer

    public ConversationDto answer(long id, AnswerRequest request) {
        String text = request == null || request.text() == null ? "" : request.text().strip();
        if (text.isEmpty()) {
            throw ApiException.badRequest("EMPTY_ANSWER", "The answer is empty.");
        }
        if (text.length() > MAX_ANSWER_CHARS) {
            throw ApiException.badRequest("ANSWER_TOO_LONG", "The answer is too long.");
        }
        boolean voice = request.voice();

        Loaded loaded = tx(() -> {
            CoachConversationEntity c = require(id);
            requireActive(c);
            append(c, CoachRole.USER, CoachMessageKind.USER_ANSWER, text, voice, true);
            touch(c);
            return load(c);
        });
        Language lang = Language.fromCode(loaded.conversation().language);

        if (CoachRules.indicatesCrisis(text)) {
            LOG.infof("Coach conversation %d: crisis rule applied", id);
            return endWithCrisis(id, lang);
        }

        boolean explicit = CoachRules.asksForExplanation(text);
        boolean done = CoachRules.signalsDone(text);
        int hidden = (int) loaded.all().stream().filter(m -> !m.revealed()).count();
        List<CoachMessageEntity> visible = loaded.visible();

        if (!explicit && !done && hidden > 0 && followUpsSinceLastOpening(visible) >= followUpsPerQuestion) {
            return tx(() -> {
                CoachConversationEntity c = require(id);
                List<CoachMessageEntity> all = messages.forConversation(id);
                CoachMessageEntity next = all.stream().filter(m -> !m.revealed()).min(Comparator.comparingLong(m -> m.id)).orElse(null);
                if (next != null && c.status == ConversationStatus.ACTIVE) {
                    next.seqNo = all.stream().filter(CoachMessageEntity::revealed).mapToInt(m -> m.seqNo).max().orElse(0) + 1;
                    next.createdAt = now();
                    touch(c);
                }
                return toDto(c, messages.forConversation(id));
            });
        }

        List<CoachTurnKind> allowed = allowedKinds(explicit, done, hidden == 0);
        EntryDto entry = entries.get(loaded.conversation().entryId);

        List<LlmGateway.Message> prompt = new ArrayList<>();
        prompt.add(LlmGateway.Message.system(CoachPrompts.turnSystemPrompt(lang, allowed, explicit, done)));
        prompt.add(LlmGateway.Message.user(CoachPrompts.contextBlock(lang, entry.text() == null ? "" : entry.text(), List.of())));
        int from = Math.max(0, visible.size() - HISTORY_LIMIT);
        for (CoachMessageEntity m : visible.subList(from, visible.size())) {
            prompt.add(m.role == CoachRole.COACH ? LlmGateway.Message.assistant(m.content) : LlmGateway.Message.user(m.content));
        }
        CoachTurn turn = structured.generate(StructuredRequest.of(prompt,
                        new LlmGateway.JsonSchema("coach_turn", CoachPrompts.turnSchema(allowed)), CoachTurn.class)
                .withSemanticCheck(t -> checkTurn(t, allowed)));

        return tx(() -> {
            CoachConversationEntity c = require(id);
            if (c.status == ConversationStatus.ACTIVE) { // paused/ended meanwhile: the reply is dropped, never interrupt
                append(c, CoachRole.COACH, turn.kind().messageKind(), turn.text().strip(), false, true);
                touch(c);
            }
            return toDto(c, messages.forConversation(id));
        });
    }

    static List<CoachTurnKind> allowedKinds(boolean explicit, boolean done, boolean questionsExhausted) {
        List<CoachTurnKind> allowed = new ArrayList<>();
        if (!done) {
            allowed.add(CoachTurnKind.FOLLOW_UP_QUESTION);
        }
        if (explicit) {
            allowed.add(CoachTurnKind.EXPLANATION);
        }
        if (done || questionsExhausted) {
            allowed.add(CoachTurnKind.CLOSING);
        }
        return List.copyOf(allowed);
    }

    static List<String> checkTurn(CoachTurn t, List<CoachTurnKind> allowed) {
        List<String> problems = new ArrayList<>();
        if (!allowed.contains(t.kind())) {
            problems.add(t.kind() == CoachTurnKind.EXPLANATION
                    ? "EXPLANATION is not allowed: the user did not explicitly ask for an explanation, opinion or advice"
                    : "kind " + t.kind() + " is not allowed now; allowed: " + allowed);
            return problems;
        }
        problems.addAll(CoachRules.commonProblems(t.text()));
        switch (t.kind()) {
            case FOLLOW_UP_QUESTION -> problems.addAll(CoachRules.singleQuestionProblems(t.text()));
            case EXPLANATION -> {
                if (!CoachRules.endsWithQuestion(t.text())) {
                    problems.add("an explanation must end with a question");
                }
                if (CoachRules.questionMarks(t.text()) > 1) {
                    problems.add("ask exactly one question at a time");
                }
            }
            case CLOSING -> {
            }
        }
        return problems;
    }

    static int followUpsSinceLastOpening(List<CoachMessageEntity> visible) {
        int count = 0;
        for (CoachMessageEntity m : visible) {
            if (m.kind == CoachMessageKind.OPENING_QUESTION) {
                count = 0;
            } else if (m.kind == CoachMessageKind.FOLLOW_UP_QUESTION || m.kind == CoachMessageKind.EXPLANATION) {
                count++;
            }
        }
        return count;
    }

    private ConversationDto endWithCrisis(long id, Language lang) {
        return tx(() -> {
            CoachConversationEntity c = require(id);
            append(c, CoachRole.COACH, CoachMessageKind.CLOSING, CoachPrompts.crisisMessage(lang), false, true);
            c.status = ConversationStatus.ENDED;
            c.endedAt = now();
            touch(c);
            return toDto(c, messages.forConversation(id));
        });
    }

    // ------------------------------------------------------------------ read / lifecycle

    public ConversationDto get(long id) {
        return tx(() -> {
            CoachConversationEntity c = require(id);
            return toDto(c, messages.forConversation(id));
        });
    }

    public List<ConversationDto> forEntry(long entryId) {
        return tx(() -> conversations.forEntry(entryId).stream().map(c -> toDto(c, messages.forConversation(c.id))).toList());
    }

    public ConversationDto pause(long id) {
        return transition(id, ConversationStatus.PAUSED);
    }

    public ConversationDto resume(long id) {
        return transition(id, ConversationStatus.ACTIVE);
    }

    public ConversationDto end(long id) {
        return transition(id, ConversationStatus.ENDED);
    }

    /** ACTIVE and PAUSED toggle (idempotent); ENDED is final but ending again is idempotent. */
    private ConversationDto transition(long id, ConversationStatus target) {
        return tx(() -> {
            CoachConversationEntity c = require(id);
            if (c.status == target) {
                return toDto(c, messages.forConversation(id));
            }
            if (c.status == ConversationStatus.ENDED) {
                throw ApiException.conflict("CONVERSATION_ENDED", "This conversation has ended. Start a new one to continue.");
            }
            c.status = target;
            if (target == ConversationStatus.ENDED) {
                c.endedAt = now();
            }
            touch(c);
            return toDto(c, messages.forConversation(id));
        });
    }

    void onEntryDeleted(@Observes(during = TransactionPhase.AFTER_SUCCESS) EntryDeleted event) {
        QuarkusTransaction.requiringNew().run(() -> {
            messages.deleteForEntry(event.entryId());
            conversations.delete("entryId", event.entryId());
        });
    }

    // ------------------------------------------------------------------ persistence helpers

    private record Planned(CoachMessageKind kind, String text, boolean revealed) {
    }

    private record Loaded(CoachConversationEntity conversation, List<CoachMessageEntity> all) {
        List<CoachMessageEntity> visible() {
            return all.stream().filter(CoachMessageEntity::revealed).sorted(Comparator.comparingInt(m -> m.seqNo)).toList();
        }
    }

    private ConversationDto persistNew(long entryId, Language lang, ConversationStatus status, List<Planned> planned) {
        return tx(() -> {
            Instant now = now();
            CoachConversationEntity c = new CoachConversationEntity();
            c.entryId = entryId;
            c.status = status;
            c.language = lang.code();
            c.createdAt = now;
            c.updatedAt = now;
            c.endedAt = status == ConversationStatus.ENDED ? now : null;
            conversations.persist(c);
            for (Planned p : planned) {
                append(c, CoachRole.COACH, p.kind(), p.text(), false, p.revealed());
            }
            return toDto(c, messages.forConversation(c.id));
        });
    }

    /** Adds a message; a revealed one gets the next position in the visible transcript. */
    private void append(CoachConversationEntity c, CoachRole role, CoachMessageKind kind, String content, boolean voice, boolean reveal) {
        CoachMessageEntity m = new CoachMessageEntity();
        m.conversationId = c.id;
        m.role = role;
        m.kind = kind;
        m.content = content;
        m.voice = voice;
        m.createdAt = now();
        if (reveal) {
            m.seqNo = messages.forConversation(c.id).stream().filter(CoachMessageEntity::revealed).mapToInt(x -> x.seqNo).max().orElse(0) + 1;
        }
        messages.persist(m);
    }

    private Loaded load(CoachConversationEntity c) {
        return new Loaded(c, messages.forConversation(c.id));
    }

    private CoachConversationEntity require(long id) {
        CoachConversationEntity c = conversations.findById(id);
        if (c == null) {
            throw ApiException.notFound("Conversation");
        }
        return c;
    }

    private static void requireActive(CoachConversationEntity c) {
        if (c.status == ConversationStatus.PAUSED) {
            throw ApiException.conflict("CONVERSATION_PAUSED", "The conversation is paused. Resume it to continue.");
        }
        if (c.status == ConversationStatus.ENDED) {
            throw ApiException.conflict("CONVERSATION_ENDED", "This conversation has ended. Start a new one to continue.");
        }
    }

    private void touch(CoachConversationEntity c) {
        c.updatedAt = now();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    static ConversationDto toDto(CoachConversationEntity c, List<CoachMessageEntity> all) {
        List<CoachMessageDto> visible = all.stream().filter(CoachMessageEntity::revealed)
                .sorted(Comparator.comparingInt(m -> m.seqNo))
                .map(m -> new CoachMessageDto(m.id, m.role, m.kind, m.content, m.voice, m.createdAt))
                .toList();
        return new ConversationDto(c.id, c.entryId, c.status, Language.fromCode(c.language), visible, c.createdAt, c.updatedAt, c.endedAt);
    }

    /** Runs the block in its own transaction so LLM calls never hold a database transaction. */
    private static <T> T tx(java.util.concurrent.Callable<T> block) {
        return QuarkusTransaction.requiringNew().call(block);
    }
}
