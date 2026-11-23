package dev.rabauer.ai.diary.views.entries;

import dev.rabauer.ai.diary.api.common.PageDto;
import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.EntrySource;
import dev.rabauer.ai.diary.api.entry.EntryStatus;
import dev.rabauer.ai.diary.views.entries.EntryListPresenter.Paging;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class EntryListPresenterTest {

    private static EntryDto entry(EntryStatus status, EntrySource source, String text, String remark) {
        return new EntryDto(1, LocalDateTime.of(2026, 3, 4, 18, 30), status, source, null, text, remark, null, false,
                null, null, null);
    }

    @Test
    void rowShowsPreviewVoiceBadgeAndStatusKey() {
        var row = EntryListPresenter.row(entry(EntryStatus.TRANSCRIBED, EntrySource.VOICE, "First line\nsecond", null), Locale.GERMANY);
        assertThat(row.preview()).isEqualTo("First line");
        assertThat(row.voice()).isTrue();
        assertThat(row.finished()).isFalse();
        assertThat(row.statusKey()).isEqualTo("entries.status.TRANSCRIBED");
        assertThat(row.date()).contains("04.03.2026").contains("18:30");
        assertThat(row.hasLegacyRemark()).isFalse();
    }

    @Test
    void legacyRemarkIsCarriedForCollapsedDisplay() {
        var row = EntryListPresenter.row(entry(EntryStatus.FINISHED, EntrySource.TEXT, "old", "Stoic remark"), Locale.ENGLISH);
        assertThat(row.finished()).isTrue();
        assertThat(row.voice()).isFalse();
        assertThat(row.hasLegacyRemark()).isTrue();
        assertThat(row.legacyRemark()).isEqualTo("Stoic remark");
    }

    @Test
    void blankRemarkIsNotShown() {
        assertThat(EntryListPresenter.row(entry(EntryStatus.FINISHED, EntrySource.TEXT, "x", "  "), Locale.ENGLISH).hasLegacyRemark()).isFalse();
    }

    @Test
    void longTextIsTruncated() {
        var row = EntryListPresenter.row(entry(EntryStatus.DRAFT, EntrySource.TEXT, "x".repeat(500), null), Locale.ENGLISH);
        assertThat(row.preview()).hasSize(EntryListPresenter.PREVIEW_CHARS);
    }

    @Test
    void pagingMiddlePage() {
        var p = Paging.of(new PageDto<>(List.of(), 1, 20, 45));
        assertThat(p.pageCount()).isEqualTo(3);
        assertThat(p.hasPrevious()).isTrue();
        assertThat(p.hasNext()).isTrue();
        assertThat(p.displayPage()).isEqualTo(2);
    }

    @Test
    void pagingLastAndFirstAndEmpty() {
        assertThat(new Paging(2, 20, 45).hasNext()).isFalse();
        assertThat(new Paging(0, 20, 20).hasNext()).isFalse();
        assertThat(new Paging(0, 20, 21).hasNext()).isTrue();
        var empty = new Paging(0, 20, 0);
        assertThat(empty.hasNext()).isFalse();
        assertThat(empty.hasPrevious()).isFalse();
        assertThat(empty.displayPageCount()).isEqualTo(1);
    }
}
