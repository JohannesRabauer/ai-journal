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

    private JournalPresenter() {
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
