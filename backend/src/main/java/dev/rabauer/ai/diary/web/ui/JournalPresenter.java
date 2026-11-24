package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.EntrySource;
import dev.rabauer.ai.diary.api.entry.EntryStatus;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Pure list logic of the journal page (port of the Vaadin EntryListPresenter, no framework types). */
public final class JournalPresenter {

    public static final int PREVIEW_CHARS = 140;

    /** Longest search text the page uses (same limit as the API). */
    public static final int MAX_QUERY_CHARS = 200;

    private JournalPresenter() {
    }

    /** The segmented filter of the toolbar; {@code param} is the value of the {@code filter} query parameter. */
    public enum Filter {
        ALL("all"), VOICE("voice"), REFLECTED("reflected");

        private final String param;

        Filter(String param) {
            this.param = param;
        }

        public String param() {
            return param;
        }

        /** Unknown or missing values mean "all". */
        public static Filter parse(String value) {
            if (value != null) {
                for (Filter f : values()) {
                    if (f.param.equalsIgnoreCase(value.strip())) {
                        return f;
                    }
                }
            }
            return ALL;
        }

        /** API {@code source} filter. */
        public EntrySource source() {
            return this == VOICE ? EntrySource.VOICE : null;
        }

        /** API {@code reflected} filter. */
        public Boolean reflected() {
            return this == REFLECTED ? Boolean.TRUE : null;
        }
    }

    /** Trimmed, at most {@link #MAX_QUERY_CHARS} characters, never null. */
    public static String normalizeQuery(String q) {
        if (q == null) {
            return "";
        }
        String t = q.strip();
        return t.length() <= MAX_QUERY_CHARS ? t : t.substring(0, MAX_QUERY_CHARS).stripTrailing();
    }

    /** Journal URL for a state; defaults (empty q, filter all, page 0) are left out. Mirrors buildUrl() in journal-search.js. */
    public static String href(String q, Filter filter, int page) {
        StringBuilder sb = new StringBuilder("/journal");
        char sep = '?';
        String text = normalizeQuery(q);
        if (!text.isEmpty()) {
            sb.append(sep).append("q=").append(java.net.URLEncoder.encode(text, java.nio.charset.StandardCharsets.UTF_8));
            sep = '&';
        }
        if (filter != Filter.ALL) {
            sb.append(sep).append("filter=").append(filter.param());
            sep = '&';
        }
        if (page > 0) {
            sb.append(sep).append("page=").append(page);
        }
        return sb.toString();
    }

    public record FilterOption(String param, String labelKey, String href, boolean current) {
    }

    /** The three filter links; they keep the (normalized) search text. */
    public static List<FilterOption> filterOptions(String q, Filter active) {
        List<FilterOption> out = new ArrayList<>();
        for (Filter f : Filter.values()) {
            out.add(new FilterOption(f.param, "entries.filter." + f.param, href(q, f, 0), f == active));
        }
        return out;
    }

    public record Card(long id, String href, String time, boolean voice, boolean coach, String statusLabel,
                       String preview, String legacyRemark) {
    }

    public record DaySection(String isoDay, String label, String detail, List<Card> cards) {
    }

    public static List<DaySection> sections(List<EntryDto> entries, LocalDate today, Messages t) {
        List<DaySection> out = new ArrayList<>();
        LocalDate lastDay = null;
        List<Card> current = null;
        for (EntryDto e : entries) {
            // entryTimestamp is a LocalDateTime in the user's local time: its date is the day to group by.
            LocalDate day = e.entryTimestamp() == null ? null : e.entryTimestamp().toLocalDate();
            if (current == null || !Objects.equals(day, lastDay)) {
                current = new ArrayList<>();
                out.add(section(day, today, t, current));
                lastDay = day;
            }
            current.add(card(e, t));
        }
        return out;
    }

    private static DaySection section(LocalDate day, LocalDate today, Messages t, List<Card> cards) {
        if (day == null) {
            return new DaySection("", t.get("entries.untitled"), "", cards);
        }
        Locale l = t.locale();
        long ago = ChronoUnit.DAYS.between(day, today);
        String label;
        String detail;
        if (ago == 0) {
            label = t.get("entries.journal.today");
            detail = pattern(day, "MMMEd", l);
        } else if (ago == 1) {
            label = t.get("entries.journal.yesterday");
            detail = pattern(day, "MMMEd", l);
        } else if (ago > 1 && ago < 7) {
            label = DateTimeFormatter.ofPattern("EEEE", l).format(day);
            detail = pattern(day, "MMMd", l);
        } else {
            label = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(l).format(day);
            detail = DateTimeFormatter.ofPattern("EEEE", l).format(day);
        }
        return new DaySection(day.toString(), label, detail, cards);
    }

    private static String pattern(LocalDate day, String skeleton, Locale l) {
        return DateTimeFormatter.ofLocalizedPattern(skeleton).withLocale(l).format(day);
    }

    static Card card(EntryDto e, Messages t) {
        String time = e.entryTimestamp() == null ? ""
                : DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(t.locale()).format(e.entryTimestamp());
        String remark = e.legacyAiRemark() == null || e.legacyAiRemark().isBlank() ? null : e.legacyAiRemark();
        return new Card(e.id(), "/journal/" + e.id(), time, e.source() == EntrySource.VOICE, e.hasCoachConversation(),
                e.status() == EntryStatus.FINISHED ? null : t.get("entries.status." + e.status().name()),
                preview(e.text(), PREVIEW_CHARS), remark);
    }

    /** First line, trimmed to {@code max} characters (same rule as the Vaadin EntryFormat.preview). */
    static String preview(String text, int max) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String first = text.strip().lines().findFirst().orElse("");
        return first.length() <= max ? first : first.substring(0, Math.max(0, max - 1)).stripTrailing() + "…";
    }
}
