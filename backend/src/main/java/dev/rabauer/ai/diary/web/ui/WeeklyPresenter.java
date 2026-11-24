package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.api.insights.WeeklyReportDto;
import dev.rabauer.ai.diary.api.insights.WeeklyReportSummaryDto;
import dev.rabauer.ai.diary.web.ApiException;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;

/** Pure presentation logic of the weekly screen (no Vaadin types): weeks, errors, section helpers, edit draft. */
public final class WeeklyPresenter {

    /** Completed weeks offered in the picker besides "this week so far". */
    public static final int PICKER_WEEKS = 12;

    private WeeklyPresenter() {
    }

    /** An ISO week (Monday to Sunday). Natural order is oldest first. */
    public record Week(int isoYear, int isoWeek) implements Comparable<Week> {

        public static Week of(LocalDate date) {
            return new Week(date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
        }

        /** "2026-W12": the form used in page addresses. */
        public String code() {
            return String.format(Locale.ROOT, "%d-W%02d", isoYear, isoWeek);
        }

        /** Parses {@link #code()}; empty for anything else, including weeks that do not exist (W53 in a 52-week year). */
        public static Optional<Week> parse(String code) {
            if (code == null || !code.matches("\\d{4}-W\\d{2}")) {
                return Optional.empty();
            }
            int year = Integer.parseInt(code.substring(0, 4));
            int week = Integer.parseInt(code.substring(6));
            if (year < 1970 || year > 2200 || week < 1 || week > 53) {
                return Optional.empty();
            }
            Week parsed = new Week(year, week);
            return Week.of(parsed.monday()).equals(parsed) ? Optional.of(parsed) : Optional.empty();
        }

        public LocalDate monday() {
            return LocalDate.of(isoYear, 1, 4).with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, isoWeek).with(DayOfWeek.MONDAY);
        }

        public LocalDate sunday() {
            return monday().plusDays(6);
        }

        @Override
        public int compareTo(Week other) {
            int byYear = Integer.compare(isoYear, other.isoYear);
            return byYear != 0 ? byYear : Integer.compare(isoWeek, other.isoWeek);
        }
    }

    public static Week thisWeek(LocalDate today) {
        return Week.of(today);
    }

    /** The most recent week that is over: the week before the one containing {@code today}. */
    public static Week lastCompleted(LocalDate today) {
        return Week.of(today.minusDays(7));
    }

    /**
     * Weeks for the picker, newest first: this week (so far), the last {@link #PICKER_WEEKS} completed weeks, and any
     * older week that already has a report.
     */
    public static List<Week> pickerWeeks(LocalDate today, Collection<Week> withReports) {
        TreeSet<Week> weeks = new TreeSet<>(Comparator.reverseOrder());
        weeks.add(thisWeek(today));
        for (int i = 1; i <= PICKER_WEEKS; i++) {
            weeks.add(Week.of(today.minusDays(7L * i)));
        }
        weeks.addAll(withReports);
        return List.copyOf(weeks);
    }

    public static Week weekOf(WeeklyReportSummaryDto summary) {
        return new Week(summary.isoYear(), summary.isoWeek());
    }

    public static Week weekOf(WeeklyReportDto report) {
        return new Week(report.isoYear(), report.isoWeek());
    }

    /** "16.03.2026 - 22.03.2026" in the locale's medium date style. */
    public static String range(Week week, Locale locale) {
        DateTimeFormatter f = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale);
        return f.format(week.monday()) + " – " + f.format(week.sunday());
    }

    /** Short date of an entry for a link, e.g. "Mon, Mar 3". */
    public static String entryDate(LocalDateTime timestamp, Locale locale) {
        if (timestamp == null) {
            return null;
        }
        return DateTimeFormatter.ofPattern("EEE, d MMM", locale).format(timestamp);
    }

    /** Date and time of a stored instant in the user's zone, medium style. */
    public static String dateTime(Instant instant, ZoneId zone, Locale locale) {
        if (instant == null) {
            return "";
        }
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone).format(instant);
    }

    /**
     * A calm, user-presentable explanation of a failed call.
     *
     * @param messageKey i18n key of the message
     * @param retryable  offer a retry (the same action is repeated)
     * @param reload     the stored data changed elsewhere (404): reload the list instead of repeating
     */
    public record ErrorInfo(String messageKey, boolean retryable, boolean reload) {
    }

    public static ErrorInfo errorFor(RuntimeException failure) {
        if (!(failure instanceof ApiException e)) {
            return new ErrorInfo("weekly.error.unreachable", true, false);
        }
        String code = e.code() == null ? "" : e.code();
        if ("LLM_OUTPUT_INVALID".equals(code)) {
            return new ErrorInfo("weekly.error.invalid", true, false);
        }
        if (code.startsWith("PROVIDER_")) {
            return new ErrorInfo("weekly.error.provider", true, false);
        }
        if ("NO_ENTRIES_IN_WEEK".equals(code)) {
            return new ErrorInfo("weekly.error.noEntries", false, false);
        }
        return switch (e.status().getStatusCode()) {
            case 400 -> "INVALID_REPORT".equals(code)
                    ? new ErrorInfo("weekly.error.invalidEdit", false, false)
                    : new ErrorInfo("weekly.error.badWeek", false, false);
            case 404 -> new ErrorInfo("weekly.error.notFound", false, true);
            case 501 -> new ErrorInfo("weekly.error.unavailable", false, false);
            default -> new ErrorInfo("weekly.error.unreachable", true, false);
        };
    }

    /** The report summary of a week, if one exists. */
    public static Optional<WeeklyReportSummaryDto> summaryOf(Week week, List<WeeklyReportSummaryDto> summaries) {
        if (summaries == null) {
            return Optional.empty();
        }
        return summaries.stream().filter(s -> weekOf(s).equals(week)).findFirst();
    }

    /** All entry ids a report links to, in order of first appearance (to look up their dates). */
    public static List<Long> referencedEntries(WeeklyReportDto report) {
        List<Long> ids = new ArrayList<>();
        if (report.recurringThemes() != null) {
            report.recurringThemes().forEach(t -> {
                if (t.entryIds() != null) {
                    t.entryIds().stream().filter(i -> !ids.contains(i)).forEach(ids::add);
                }
            });
        }
        if (report.inYourOwnWords() != null) {
            report.inYourOwnWords().stream().map(WeeklyReportDto.Quote::entryId).filter(i -> !ids.contains(i)).forEach(ids::add);
        }
        return ids;
    }

    /** Plain counts, no grading: nothing to show when both are zero and there are no prompts. */
    public static boolean hasLearned(WeeklyReportDto.Learned learned) {
        return learned != null && (learned.itemsCreated() > 0 || learned.reviewsDone() > 0
                || (learned.itemPrompts() != null && !learned.itemPrompts().isEmpty()));
    }

    // ------------------------------------------------------------------ edit draft

    /** Editable copy of the six sections. Text is stripped, blank items are dropped; entry references are kept. */
    public static final class EditDraft {

        public static final class ThemeRow {
            public String title;
            public String description;
            public final List<Long> entryIds;

            ThemeRow(String title, String description, List<Long> entryIds) {
                this.title = title;
                this.description = description;
                this.entryIds = entryIds;
            }
        }

        public static final class QuoteRow {
            public String text;
            public final long entryId;

            QuoteRow(String text, long entryId) {
                this.text = text;
                this.entryId = entryId;
            }
        }

        public final List<ThemeRow> themes = new ArrayList<>();
        public final List<QuoteRow> quotes = new ArrayList<>();
        public final List<String> openThreads = new ArrayList<>();
        public final List<String> patterns = new ArrayList<>();
        public int itemsCreated;
        public int reviewsDone;
        public String question = "";
        private final WeeklyReportDto base;

        public EditDraft(WeeklyReportDto base) {
            this.base = base;
            if (base.recurringThemes() != null) {
                base.recurringThemes().forEach(t -> themes.add(new ThemeRow(t.title(), t.description(),
                        t.entryIds() == null ? new ArrayList<>() : new ArrayList<>(t.entryIds()))));
            }
            if (base.inYourOwnWords() != null) {
                base.inYourOwnWords().forEach(q -> quotes.add(new QuoteRow(q.text(), q.entryId())));
            }
            if (base.openThreads() != null) {
                openThreads.addAll(base.openThreads());
            }
            if (base.noticedPatterns() != null) {
                patterns.addAll(base.noticedPatterns());
            }
            if (base.whatYouLearned() != null) {
                itemsCreated = base.whatYouLearned().itemsCreated();
                reviewsDone = base.whatYouLearned().reviewsDone();
            }
            question = base.questionForNextWeek() == null ? "" : base.questionForNextWeek();
        }

        public ThemeRow addTheme() {
            ThemeRow row = new ThemeRow("", "", new ArrayList<>());
            themes.add(row);
            return row;
        }

        /**
         * Replaces the sections with what a no-JavaScript edit form posted. The lists are parallel (one entry per
         * form row); entry references of themes come as comma separated ids and of quotes one id per row. Rows whose
         * text is blank are dropped by {@link #toReport()}.
         */
        public void applyForm(List<String> themeTitles, List<String> themeDescriptions, List<String> themeEntries,
                              List<String> quoteTexts, List<String> quoteEntries, List<String> threads,
                              List<String> patternTexts, Integer items, Integer reviews, String questionText) {
            themes.clear();
            for (int i = 0; i < size(themeTitles); i++) {
                themes.add(new ThemeRow(themeTitles.get(i), at(themeDescriptions, i), ids(at(themeEntries, i))));
            }
            quotes.clear();
            for (int i = 0; i < size(quoteTexts); i++) {
                List<Long> id = ids(at(quoteEntries, i));
                if (!id.isEmpty()) { // a quote always belongs to an entry; rows without one cannot be stored
                    quotes.add(new QuoteRow(quoteTexts.get(i), id.get(0)));
                }
            }
            openThreads.clear();
            if (threads != null) {
                openThreads.addAll(threads);
            }
            patterns.clear();
            if (patternTexts != null) {
                patterns.addAll(patternTexts);
            }
            itemsCreated = items == null ? itemsCreated : items;
            reviewsDone = reviews == null ? reviewsDone : reviews;
            question = questionText == null ? "" : questionText;
        }

        private static int size(List<String> list) {
            return list == null ? 0 : list.size();
        }

        private static String at(List<String> list, int i) {
            return list == null || i >= list.size() || list.get(i) == null ? "" : list.get(i);
        }

        private static List<Long> ids(String commaSeparated) {
            List<Long> ids = new ArrayList<>();
            for (String part : commaSeparated.split(",")) {
                try {
                    long id = Long.parseLong(part.strip());
                    if (id > 0 && !ids.contains(id)) {
                        ids.add(id);
                    }
                } catch (NumberFormatException e) {
                    // ignore garbage
                }
            }
            return ids;
        }

        /** The report to send: edited sections; week, language and dates are the stored ones. */
        public WeeklyReportDto toReport() {
            List<String> prompts = base.whatYouLearned() == null || base.whatYouLearned().itemPrompts() == null
                    ? List.of() : base.whatYouLearned().itemPrompts();
            return new WeeklyReportDto(base.id(), base.isoYear(), base.isoWeek(), base.language(),
                    themes.stream().filter(t -> !blank(t.title))
                            .map(t -> new WeeklyReportDto.Theme(t.title.strip(), t.description == null ? "" : t.description.strip(),
                                    List.copyOf(t.entryIds))).toList(),
                    quotes.stream().filter(q -> !blank(q.text)).map(q -> new WeeklyReportDto.Quote(q.text.strip(), q.entryId)).toList(),
                    clean(openThreads),
                    new WeeklyReportDto.Learned(Math.max(0, itemsCreated), Math.max(0, reviewsDone), prompts),
                    clean(patterns),
                    question == null ? "" : question.strip(),
                    base.generatedAt(), base.editedAt());
        }

        private static List<String> clean(List<String> items) {
            return items.stream().filter(s -> !blank(s)).map(String::strip).toList();
        }

        private static boolean blank(String s) {
            return s == null || s.isBlank();
        }
    }
}
