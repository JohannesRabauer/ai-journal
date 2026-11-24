package dev.rabauer.ai.diary.views.memory;

import dev.rabauer.ai.diary.api.memory.MemoryItemType;
import dev.rabauer.ai.diary.api.memory.RevealDto;
import dev.rabauer.ai.diary.api.memory.ReviewCardDto;
import dev.rabauer.ai.diary.api.memory.SourceType;
import dev.rabauer.ai.diary.client.BackendException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pure presentation logic of the "Remember" screen (no Vaadin types). Deliberately without any score, percentage or
 * streak: only "due today: n" and the cards themselves.
 */
public final class MemoryPresenter {

    /** Backend limits, mirrored so the form can explain instead of failing. */
    public static final int MAX_PROMPT = 500;
    public static final int MAX_ANSWER = 2000;

    private MemoryPresenter() {
    }

    /** The four grade buttons; quality as defined in SPEC section 6. */
    public enum Grade {
        FORGOT(1), HARD(3), GOOD(4), EASY(5);

        private final int quality;

        Grade(int quality) {
            this.quality = quality;
        }

        public int quality() {
            return quality;
        }

        public String key() {
            return "memory.grade." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** @param retryable the same action may be repeated; @param refresh the data changed elsewhere (409): reload it */
    public record ErrorInfo(String messageKey, boolean retryable, boolean refresh) {
    }

    public static ErrorInfo errorFor(RuntimeException failure) {
        if (!(failure instanceof BackendException e)) {
            return new ErrorInfo("memory.error.unreachable", true, false);
        }
        String code = e.error() == null || e.error().code() == null ? "" : e.error().code();
        if ("LLM_OUTPUT_INVALID".equals(code)) {
            return new ErrorInfo("memory.error.invalid", true, false);
        }
        if (code.startsWith("PROVIDER_")) {
            return new ErrorInfo("memory.error.provider", true, false);
        }
        return switch (e.status()) {
            case 409 -> switch (code) {
                case "ENTRY_NOT_FINISHED" -> new ErrorInfo("memory.error.notFinished", false, false);
                case "ENTRY_EMPTY", "CONVERSATION_EMPTY" -> new ErrorInfo("memory.error.empty", false, false);
                default -> new ErrorInfo("memory.error.state", false, true);
            };
            case 404 -> new ErrorInfo("memory.error.notFound", false, true);
            case 400 -> new ErrorInfo("memory.error.badInput", false, false);
            case 501 -> new ErrorInfo("memory.error.unavailable", false, false);
            default -> new ErrorInfo("memory.error.unreachable", true, false);
        };
    }

    public static String typeKey(MemoryItemType type) {
        return "memory.type." + type.name().toLowerCase(java.util.Locale.ROOT);
    }

    // ---- extraction target (from the entry / coach "Extract learnings" action)

    /** What to extract from; carried as query parameters {@code source=entry|conversation} and {@code id}. */
    public record Target(SourceType type, long id) {
        public static final String PARAM_SOURCE = "source";
        public static final String PARAM_ID = "id";

        public Map<String, String> toParameters() {
            return Map.of(PARAM_SOURCE, type.name().toLowerCase(java.util.Locale.ROOT), PARAM_ID, Long.toString(id));
        }
    }

    public static Optional<Target> targetFrom(Map<String, List<String>> query) {
        String source = first(query, Target.PARAM_SOURCE);
        String id = first(query, Target.PARAM_ID);
        if (source == null || id == null) {
            return Optional.empty();
        }
        SourceType type;
        switch (source.toLowerCase(java.util.Locale.ROOT)) {
            case "entry" -> type = SourceType.ENTRY;
            case "conversation" -> type = SourceType.CONVERSATION;
            default -> {
                return Optional.empty();
            }
        }
        try {
            long value = Long.parseLong(id.strip());
            return value > 0 ? Optional.of(new Target(type, value)) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String first(Map<String, List<String>> query, String key) {
        List<String> values = query == null ? null : query.get(key);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    // ---- candidate editing

    /** Validation of an edited candidate before it is sent: calm message keys instead of a backend 400. */
    public static Optional<String> editProblem(String prompt, String answer) {
        if (prompt == null || prompt.isBlank()) {
            return Optional.of("memory.edit.promptEmpty");
        }
        if (prompt.strip().length() > MAX_PROMPT) {
            return Optional.of("memory.edit.promptTooLong");
        }
        if (answer == null || answer.isBlank()) {
            return Optional.of("memory.edit.answerEmpty");
        }
        if (answer.strip().length() > MAX_ANSWER) {
            return Optional.of("memory.edit.answerTooLong");
        }
        return Optional.empty();
    }

    // ---- review session

    /** Where the current card is: showing the prompt, or showing the revealed answer waiting for a grade. */
    public enum Phase { PROMPT, REVEALED, DONE }

    /**
     * The due cards in order. The answer is only known to the session after {@link #revealed(RevealDto)}; before that
     * there is nothing to show but the prompt (retrieval practice). No counters beyond "remaining".
     */
    public static final class ReviewSession {

        private final List<ReviewCardDto> queue = new ArrayList<>();
        private final int total;
        private RevealDto reveal;

        public ReviewSession(List<ReviewCardDto> cards) {
            if (cards != null) {
                queue.addAll(cards);
            }
            total = queue.size();
        }

        /** Cards this session started with (for "Card 2 of 3"); plain information, not a score. */
        public int total() {
            return total;
        }

        /** 1-based position of the current card within the session. */
        public int position() {
            return Math.min(total, total - queue.size() + 1);
        }

        public Phase phase() {
            if (queue.isEmpty()) {
                return Phase.DONE;
            }
            return reveal == null ? Phase.PROMPT : Phase.REVEALED;
        }

        public Optional<ReviewCardDto> current() {
            return queue.isEmpty() ? Optional.empty() : Optional.of(queue.get(0));
        }

        /** The answer of the current card; empty until an attempt was recorded. */
        public Optional<RevealDto> reveal() {
            return Optional.ofNullable(reveal);
        }

        /** Cards still due in this session including the current one. */
        public int remaining() {
            return queue.size();
        }

        public void revealed(RevealDto dto) {
            if (!queue.isEmpty() && dto != null && dto.itemId() == queue.get(0).itemId()) {
                reveal = dto;
            }
        }

        /** The current card was graded (or no longer exists): move on. */
        public void graded() {
            if (!queue.isEmpty()) {
                queue.remove(0);
            }
            reveal = null;
        }
    }

    // ---- attempt text

    /** Typed answer to send; blank means "I answered in my head" (null). */
    public static String attemptText(String typed) {
        return typed == null || typed.isBlank() ? null : typed.strip();
    }
}
