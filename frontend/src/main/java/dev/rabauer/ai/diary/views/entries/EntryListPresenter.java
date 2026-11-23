package dev.rabauer.ai.diary.views.entries;

import dev.rabauer.ai.diary.api.common.PageDto;
import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.EntrySource;
import dev.rabauer.ai.diary.views.EntryFormat;

import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

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
                      boolean hasLegacyRemark, String legacyRemark, boolean hasCoachConversation) {
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
                e.hasCoachConversation());
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
