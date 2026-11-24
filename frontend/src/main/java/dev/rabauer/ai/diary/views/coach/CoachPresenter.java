package dev.rabauer.ai.diary.views.coach;

import dev.rabauer.ai.diary.api.coach.AnswerRequest;
import dev.rabauer.ai.diary.api.coach.CoachMessageDto;
import dev.rabauer.ai.diary.api.coach.CoachMessageKind;
import dev.rabauer.ai.diary.api.coach.CoachRole;
import dev.rabauer.ai.diary.api.coach.ConversationDto;
import dev.rabauer.ai.diary.api.coach.ConversationStatus;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.client.BackendException;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Pure presentation logic of the coach screen (no Vaadin types): which actions exist, errors, transcript helpers. */
public final class CoachPresenter {

    private CoachPresenter() {
    }

    /** Which controls a conversation offers in its status (SPEC section 5.9). */
    public record Actions(boolean answer, boolean pause, boolean resume, boolean end, boolean startNew) {
    }

    public static Actions actions(ConversationStatus status) {
        return switch (status) {
            case ACTIVE -> new Actions(true, true, false, true, false);
            case PAUSED -> new Actions(false, false, true, true, false);
            case ENDED -> new Actions(false, false, false, false, true);
        };
    }

    /**
     * A calm, user-presentable explanation of a failed call.
     *
     * @param messageKey i18n key of the message
     * @param retryable  offer a retry button (the same action is repeated)
     * @param reload     the conversation changed elsewhere (409): reload it instead of repeating the action
     */
    public record ErrorInfo(String messageKey, boolean retryable, boolean reload) {
    }

    public static ErrorInfo errorFor(RuntimeException failure) {
        if (!(failure instanceof BackendException e)) {
            return new ErrorInfo("coach.error.unreachable", true, false);
        }
        String code = e.error() == null || e.error().code() == null ? "" : e.error().code();
        if ("LLM_OUTPUT_INVALID".equals(code)) {
            return new ErrorInfo("coach.error.invalid", true, false);
        }
        if (code.startsWith("PROVIDER_")) {
            return new ErrorInfo("coach.error.provider", true, false);
        }
        return switch (e.status()) {
            case 409 -> new ErrorInfo("coach.error.state", false, true);
            case 404 -> new ErrorInfo("coach.error.notFound", false, false);
            case 501 -> new ErrorInfo("coach.error.unavailable", false, false);
            default -> new ErrorInfo("coach.error.unreachable", true, false);
        };
    }

    /** The conversation to show for an entry: the most recently started one. */
    public static Optional<ConversationDto> latest(List<ConversationDto> conversations) {
        if (conversations == null) {
            return Optional.empty();
        }
        return conversations.stream().max(Comparator
                .comparing((ConversationDto c) -> c.startedAt() == null ? Instant.MIN : c.startedAt())
                .thenComparingLong(ConversationDto::id));
    }

    public static boolean isQuestion(CoachMessageKind kind) {
        return kind == CoachMessageKind.OPENING_QUESTION || kind == CoachMessageKind.FOLLOW_UP_QUESTION;
    }

    /** The question the user is answering now: the last message, when it is a coach question and the chat is active. */
    public static Optional<CoachMessageDto> currentQuestion(ConversationDto c) {
        if (c.status() != ConversationStatus.ACTIVE || c.messages() == null || c.messages().isEmpty()) {
            return Optional.empty();
        }
        CoachMessageDto last = c.messages().get(c.messages().size() - 1);
        return last.role() == CoachRole.COACH && isQuestion(last.kind()) ? Optional.of(last) : Optional.empty();
    }

    /** Coach questions that were not shown before; these are the only ones read aloud automatically. */
    public static List<CoachMessageDto> newQuestions(Collection<Long> alreadyShown, ConversationDto c) {
        if (c.messages() == null) {
            return List.of();
        }
        return c.messages().stream()
                .filter(m -> m.role() == CoachRole.COACH && isQuestion(m.kind()) && !alreadyShown.contains(m.id()))
                .toList();
    }

    public static String kindKey(CoachMessageKind kind) {
        return "coach.kind." + kind.name();
    }

    /** The language to speak in: the conversation language, else the UI language, else English. */
    public static Language speechLanguage(Language conversation, Locale ui) {
        if (conversation == Language.DE || conversation == Language.EN) {
            return conversation;
        }
        return ui != null && "de".equals(ui.getLanguage()) ? Language.DE : Language.EN;
    }

    public static String voiceFor(Language language, String germanVoice, String englishVoice) {
        String voice = language == Language.DE ? germanVoice : englishVoice;
        return voice == null || voice.isBlank() ? null : voice;
    }

    public static String time(Instant instant, ZoneId zone, Locale locale) {
        if (instant == null) {
            return "";
        }
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(locale).withZone(zone).format(instant);
    }

    /**
     * The answer being written. Text is kept until the backend accepted it, so a failed send never loses it. The voice
     * flag is true once any part came from a transcription, even if the user edited it afterwards; it resets when the
     * field is emptied (a fresh answer).
     */
    public static final class AnswerDraft {

        private String text = "";
        private boolean voice;

        public String text() {
            return text;
        }

        public boolean voice() {
            return voice;
        }

        /** The user typed or edited the field. */
        public void edited(String value) {
            text = value == null ? "" : value;
            if (text.isBlank()) {
                voice = false;
            }
        }

        /** A transcription arrives: it is added to what is already written, for review. Returns the new field value. */
        public String transcribed(String transcript) {
            String add = transcript == null ? "" : transcript.strip();
            if (add.isEmpty()) {
                return text;
            }
            text = text.isBlank() ? add : text.stripTrailing() + " " + add;
            voice = true;
            return text;
        }

        /** Null when there is nothing to send. */
        public AnswerRequest request() {
            return text.isBlank() ? null : new AnswerRequest(text.strip(), voice);
        }

        public void sent() {
            text = "";
            voice = false;
        }

        /** A failed send keeps everything as it is. */
        public void sendFailed() {
            // intentionally nothing: the answer stays in the field
        }
    }
}
