package dev.rabauer.ai.diary.views.today;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Time-of-day greeting and the locale-aware date line for the Today header (pure, no Vaadin types). */
public final class TodayGreeting {

    private TodayGreeting() {
    }

    /** i18n key of the greeting for the user's local time: morning until 11:59, afternoon until 17:59, else evening. */
    public static String key(LocalDateTime now) {
        int hour = now.getHour();
        if (hour < 12) {
            return "today.greeting.morning";
        }
        return hour < 18 ? "today.greeting.afternoon" : "today.greeting.evening";
    }

    /** Weekday, day and month in the given locale, e.g. "Tuesday, 24 November" or "Dienstag, 24. November". */
    public static String dateLine(LocalDateTime now, Locale locale) {
        String pattern = "de".equals(locale.getLanguage()) ? "EEEE, d. MMMM" : "EEEE, d MMMM";
        return DateTimeFormatter.ofPattern(pattern, locale).format(now);
    }
}
