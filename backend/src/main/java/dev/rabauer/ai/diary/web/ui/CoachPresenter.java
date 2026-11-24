package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.api.coach.CoachMessageDto;
import dev.rabauer.ai.diary.api.coach.CoachMessageKind;
import dev.rabauer.ai.diary.api.coach.CoachRole;
import dev.rabauer.ai.diary.api.coach.ConversationDto;
import dev.rabauer.ai.diary.api.coach.ConversationStatus;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.web.ApiException;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Pure presentation logic of the coach conversation (port of the Vaadin CoachPresenter, SPEC sections 5, 9, 12): which
 * controls a conversation offers, calm errors, which conversation to show, and the view model of the panel. No
 * framework types. The browser side ({@code js/coach/coach-state.js}) repeats the small decisions it needs after an
 * in-place update (new questions, errors, speech language).
 */
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
     * @param retryable  offer a retry (the same action is repeated)
     * @param reload     the conversation changed elsewhere (409): the page shows the current state instead
     */
    public record ErrorInfo(String messageKey, boolean retryable, boolean reload) {
    }

    public static ErrorInfo errorFor(RuntimeException failure) {
        if (!(failure instanceof ApiException e)) {
            return new ErrorInfo("coach.error.unreachable", true, false);
        }
        String code = e.code() == null ? "" : e.code();
        if ("LLM_OUTPUT_INVALID".equals(code)) {
            return new ErrorInfo("coach.error.invalid", true, false);
        }
        if (code.startsWith("PROVIDER_")) {
            return new ErrorInfo("coach.error.provider", true, false);
        }
        return switch (e.status().getStatusCode()) {
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

    // ---- view model of the panel ----

    /** What the panel shows: no reflection possible yet, the start state, or a conversation in one of its statuses. */
    public enum State { NOT_FINISHED, NOT_STARTED, ACTIVE, PAUSED, ENDED }

    /**
     * One bubble of the transcript.
     *
     * @param speaker "Coach" or "You": the text label that tells the speakers apart (never colour alone)
     * @param kind    label of the message kind ("Question", "Explanation", ...)
     * @param number  "Question 3" for coach questions, otherwise empty
     * @param current the question the user is answering now
     * @param iso     machine-readable time for {@code <time datetime>}
     */
    public record Message(long id, boolean coach, boolean user, String speaker, String kind, String number,
                          boolean explanation, boolean current, boolean voice, String iso, String time,
                          String content) {
    }

    /**
     * @param title          heading of the panel (h2)
     * @param conversationId the conversation shown, 0 without one
     * @param speechLanguage "DE" or "EN": what questions are read aloud in
     * @param texts          JSON of the texts the browser module needs (data-texts)
     * @param error          calm message of a failed no-JavaScript action, or null
     * @param draft          the answer that failed to send (kept so nothing is lost)
     */
    public record View(long entryId, State state, String title, boolean fullPage, long conversationId,
                       String language, String speechLanguage, boolean autoPlay, String voiceDe, String voiceEn,
                       String statusText, List<Message> messages, boolean canAnswer, boolean canPause,
                       boolean canResume, boolean canEnd, boolean canStartNew, boolean canExtract,
                       String extractHref, String reflectHref, String newHref, String bodyHref, String error,
                       String draft, String texts, String voiceLabels, String speechLabels) {
        public boolean notFinished() {
            return state == State.NOT_FINISHED;
        }

        public boolean notStarted() {
            return state == State.NOT_STARTED;
        }

        public boolean hasConversation() {
            return state == State.ACTIVE || state == State.PAUSED || state == State.ENDED;
        }

        public boolean hasError() {
            return error != null;
        }
    }

    /** The transcript as bubbles; questions are numbered in order and the open question is marked. */
    public static List<Message> messages(ConversationDto c, Messages t, ZoneId zone) {
        List<Message> out = new ArrayList<>();
        long current = currentQuestion(c).map(CoachMessageDto::id).orElse(-1L);
        int n = 0;
        for (CoachMessageDto m : c.messages() == null ? List.<CoachMessageDto>of() : c.messages()) {
            boolean user = m.role() == CoachRole.USER;
            String number = "";
            if (!user && isQuestion(m.kind())) {
                n++;
                number = t.get("coach.question.number", n);
            }
            out.add(new Message(m.id(), !user, user, t.get("coach.speaker." + m.role().name()),
                    t.get(kindKey(m.kind())), number, m.kind() == CoachMessageKind.EXPLANATION, m.id() == current,
                    user && m.voice(), m.createdAt() == null ? "" : m.createdAt().toString(),
                    time(m.createdAt(), zone, t.locale()), m.content()));
        }
        return out;
    }
}
