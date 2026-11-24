package dev.rabauer.ai.diary.views.entries;

import dev.rabauer.ai.diary.api.common.PageDto;
import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.EntrySource;
import dev.rabauer.ai.diary.views.EntryFormat;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure list-row and paging logic for the journal (no Vaadin types). */
public final class EntryListPresenter {

    public static final int PREVIEW_CHARS = 140;

    private EntryListPresenter() {
    }

    /**
     * @param statusKey i18n key for the status label, e.g. {@code entries.status.TRANSCRIBED}
     * @param finished  finished entries get no status label in the list (it would be noise)
     */
    public record Row(long id, String date, String preview, String statusKey, boolean finished, boolean voice,
                      boolean hasLegacyRemark, String legacyRemark, boolean hasCoachConversation,
                      LocalDate day, String time) {
    }

    public static Row row(EntryDto e, Locale locale) {
        String date = e.entryTimestamp() == null ? ""
                : DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
                .format(e.entryTimestamp());
        boolean remark = e.legacyAiRemark() != null && !e.legacyAiRemark().isBlank();
        return new Row(
                e.id(),
                date,
                EntryFormat.preview(e.text(), PREVIEW_CHARS),
                statusKey(e),
                e.status() == dev.rabauer.ai.diary.api.entry.EntryStatus.FINISHED,
                e.source() == EntrySource.VOICE,
                remark,
                remark ? e.legacyAiRemark() : "",
                e.hasCoachConversation(),
                e.entryTimestamp() == null ? null : e.entryTimestamp().toLocalDate(),
                e.entryTimestamp() == null ? ""
                        : DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(e.entryTimestamp()));
    }

    /** Entries of one local day, in the order they were given (newest first). {@code day} is null for undated rows. */
    public record DayGroup(LocalDate day, List<Row> rows) {
    }

    /** Groups consecutive rows that share a day; the input order is kept. */
    public static List<DayGroup> groupByDay(List<Row> rows) {
        List<DayGroup> groups = new ArrayList<>();
        for (Row row : rows) {
            DayGroup last = groups.isEmpty() ? null : groups.get(groups.size() - 1);
            if (last != null && java.util.Objects.equals(last.day(), row.day())) {
                last.rows().add(row);
            } else {
                List<Row> list = new ArrayList<>();
                list.add(row);
                groups.add(new DayGroup(row.day(), list));
            }
        }
        return groups;
    }

    /** @param label relative name or date; @param detail muted secondary date text */
    public record DayHeader(String label, String detail) {
    }

    /**
     * "Today" / "Yesterday" / weekday (within the last week) / full date, plus a muted second text.
     *
     * @param todayText     already translated word for today
     * @param yesterdayText already translated word for yesterday
     */
    public static DayHeader dayHeader(LocalDate day, LocalDate today, Locale locale, String todayText,
                                      String yesterdayText) {
        long ago = java.time.temporal.ChronoUnit.DAYS.between(day, today);
        if (ago == 0) {
            return new DayHeader(todayText, format(day, "MMMEd", locale));
        }
        if (ago == 1) {
            return new DayHeader(yesterdayText, format(day, "MMMEd", locale));
        }
        if (ago > 1 && ago < 7) {
            return new DayHeader(format(day, "EEEE", locale), format(day, "MMMd", locale));
        }
        return new DayHeader(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(day),
                format(day, "EEEE", locale));
    }

    private static String format(LocalDate day, String skeleton, Locale locale) {
        if (skeleton.equals("EEEE")) {
            return DateTimeFormatter.ofPattern("EEEE", locale).format(day);
        }
        return DateTimeFormatter.ofLocalizedPattern(skeleton).withLocale(locale).format(day);
    }

    /** Eyebrow of the detail page: full date and time, e.g. "Tuesday, November 24, 2026 · 7:15 AM". */
    public static String detailEyebrow(EntryDto e, Locale locale) {
        if (e.entryTimestamp() == null) {
            return "";
        }
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(e.entryTimestamp())
                + " · "
                + DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(e.entryTimestamp());
    }

    /** Appends the next page to what is already loaded; entries that moved between pages are not shown twice. */
    public static List<EntryDto> merge(List<EntryDto> loaded, List<EntryDto> next) {
        List<EntryDto> merged = new ArrayList<>(loaded);
        Set<Long> ids = new HashSet<>();
        loaded.forEach(e -> ids.add(e.id()));
        for (EntryDto e : next) {
            if (ids.add(e.id())) {
                merged.add(e);
            }
        }
        return merged;
    }

    /** Id of the first entry that came with the latest page (focus target), or -1 if there is none. */
    public static long firstNewId(List<EntryDto> before, List<EntryDto> after) {
        return after.size() > before.size() ? after.get(before.size()).id() : -1;
    }

    public static String statusKey(EntryDto e) {
        return "entries.status." + e.status().name();
    }

    /** Paging state derived from one backend page (zero-based page index). */
    public record Paging(int page, int size, long total) {

        public static Paging of(PageDto<?> p) {
            return new Paging(p.page(), p.size(), p.total());
        }

        public int pageCount() {
            return size <= 0 ? 0 : (int) ((total + size - 1) / size);
        }

        public boolean hasPrevious() {
            return page > 0;
        }

        public boolean hasNext() {
            return (long) (page + 1) * size < total;
        }

        /** Human page number, 1-based; at least 1 even for an empty journal. */
        public int displayPage() {
            return page + 1;
        }

        public int displayPageCount() {
            return Math.max(1, pageCount());
        }
    }
}
