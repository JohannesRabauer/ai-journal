package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.EntrySource;
import dev.rabauer.ai.diary.api.entry.EntryStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Pure logic of the Today page: greeting, date line and the "Earlier today" timeline (port of the Vaadin
 * TodayGreeting, TodayEntries and TodayView.timelineItem). Hard rule (SPEC 4.4): nothing here is AI output.
 */
public final class TodayPresenter {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private TodayPresenter() {
    }

    /** i18n key of the greeting for the user's local time: morning until 11:59, afternoon until 17:59, else evening. */
    public static String greetingKey(LocalDateTime now) {
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

    /** Entries written or spoken on {@code today}, newest first. */
    public static List<EntryDto> todayEntries(List<EntryDto> items, LocalDate today) {
        return items.stream()
                .filter(e -> e.entryTimestamp() != null && e.entryTimestamp().toLocalDate().equals(today))
                .sorted(Comparator.comparing(EntryDto::entryTimestamp).reversed())
                .toList();
    }

    /**
     * One timeline row. {@code statusLabel} is null for finished entries; {@code spinner} is true while transcribing;
     * {@code blank} entries show the muted "No text yet." as their excerpt.
     */
    public record Item(long id, String href, String time, boolean voice, boolean coach, String statusLabel,
                       boolean spinner, boolean blank, String excerpt) {
        public boolean hasMeta() {
            return voice || coach || statusLabel != null;
        }
    }

    public static List<Item> timeline(List<EntryDto> today, Messages t) {
        return today.stream().map(e -> item(e, t)).toList();
    }

    static Item item(EntryDto e, Messages t) {
        String preview = JournalPresenter.preview(e.text(), JournalPresenter.PREVIEW_CHARS);
        boolean blank = preview.isBlank();
        return new Item(e.id(), "/journal/" + e.id(),
                e.entryTimestamp() == null ? "" : TIME.format(e.entryTimestamp()),
                e.source() == EntrySource.VOICE, e.hasCoachConversation(),
                e.status() == EntryStatus.FINISHED ? null : t.get("entries.status." + e.status().name()),
                e.status() == EntryStatus.TRANSCRIBING, blank, blank ? t.get("today.entry.noText") : preview);
    }
}
