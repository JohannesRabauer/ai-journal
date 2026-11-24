package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.api.gym.AttemptDto;
import dev.rabauer.ai.diary.api.gym.AttemptRequest;
import dev.rabauer.ai.diary.api.gym.GymCategory;
import dev.rabauer.ai.diary.api.system.SystemInfoDto;
import dev.rabauer.ai.diary.web.ApiException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure presentation logic of the Thinking Gym screen (no Vaadin types). No scores, counts or streaks anywhere. */
public final class GymPresenter {

    /** The longest practice time that is reported; a tab left open for hours is not "practice time". */
    public static final int MAX_DURATION_SECONDS = 2 * 60 * 60;
    public static final int HISTORY_PAGE = 20;
    private static final Set<String> NO_LLM = Set.of("fake", "none", "");

    private GymPresenter() {
    }

    public static String categoryKey(GymCategory category) {
        return "gym.category." + category.name();
    }

    public static String descriptionKey(GymCategory category) {
        return "gym.category." + category.name() + ".desc";
    }

    /** A calm, user-presentable explanation of a failed call. */
    public record ErrorInfo(String messageKey, boolean retryable) {
    }

    public static ErrorInfo errorFor(RuntimeException failure) {
        if (!(failure instanceof ApiException e)) {
            return new ErrorInfo("gym.error.unreachable", true);
        }
        String code = e.code() == null ? "" : e.code();
        if ("LLM_OUTPUT_INVALID".equals(code)) {
            return new ErrorInfo("gym.error.aiInvalid", true);
        }
        if (code.startsWith("PROVIDER_")) {
            return new ErrorInfo("gym.error.aiUnavailable", true);
        }
        if ("ANSWER_EMPTY".equals(code)) {
            return new ErrorInfo("gym.error.answerEmpty", false);
        }
        if ("ANSWER_TOO_LONG".equals(code)) {
            return new ErrorInfo("gym.error.answerTooLong", false);
        }
        return switch (e.status().getStatusCode()) {
            case 404 -> new ErrorInfo("gym.error.notFound", false);
            case 501 -> new ErrorInfo("gym.error.unavailable", false);
            default -> new ErrorInfo("gym.error.unreachable", true);
        };
    }

    /** Whether AI follow-up questions can be offered: a real LLM provider is configured (not fake, not none). */
    public static boolean llmAvailable(SystemInfoDto info) {
        if (info == null || info.providers() == null) {
            return false;
        }
        String llm = info.providers().get("llm");
        return llm != null && !NO_LLM.contains(llm.strip().toLowerCase(Locale.ROOT));
    }

    /** Seconds spent on an exercise, from the moment it was shown, never negative and capped. */
    public static int durationSeconds(Instant shownAt, Instant now) {
        if (shownAt == null || now == null) {
            return 0;
        }
        long seconds = Duration.between(shownAt, now).getSeconds();
        return (int) Math.max(0, Math.min(MAX_DURATION_SECONDS, seconds));
    }

    /** The request to save, or null when there is nothing written yet. */
    public static AttemptRequest request(String answer, int durationSeconds) {
        return answer == null || answer.isBlank() ? null : new AttemptRequest(answer.strip(), Math.max(0, durationSeconds));
    }

    /** "m:ss" for the optional timer and the history. */
    public static String clock(int seconds) {
        int s = Math.max(0, seconds);
        return (s / 60) + ":" + String.format(Locale.ROOT, "%02d", s % 60);
    }

    /** Whole minutes for history entries ("under a minute" is 0). */
    public static int minutes(int seconds) {
        return Math.max(0, seconds) / 60;
    }

    /** First characters of an answer for the collapsed history item. */
    public static String snippet(String text, int max) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() <= max ? flat : flat.substring(0, Math.max(0, max - 1)).stripTrailing() + "…";
    }

    public static String time(Instant instant, ZoneId zone, Locale locale) {
        if (instant == null) {
            return "";
        }
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(locale).withZone(zone).format(instant);
    }

    /** "de" or "en" for the UI locale, used as the template language hint for the backend. */
    public static String languageTag(Locale locale) {
        return locale != null && "de".equals(locale.getLanguage()) ? "de" : "en";
    }

    /** The first {@code shown} attempts; the list is already newest first. */
    public static List<AttemptDto> visible(List<AttemptDto> history, int shown) {
        if (history == null) {
            return List.of();
        }
        return history.subList(0, Math.min(history.size(), Math.max(0, shown)));
    }

    public static boolean hasMore(List<AttemptDto> history, int shown) {
        return history != null && history.size() > shown;
    }

    public static boolean hasFeedback(AttemptDto attempt) {
        return attempt != null && attempt.feedbackQuestions() != null && !attempt.feedbackQuestions().isEmpty();
    }
}
