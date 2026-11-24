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

    private static EntryDto at(long id, LocalDateTime when) {
        return new EntryDto(id, when, EntryStatus.FINISHED, EntrySource.TEXT, null, "t" + id, null, null, false,
                null, null, null);
    }

    @Test
    void rowCarriesDayAndLocalTime() {
        var row = EntryListPresenter.row(at(1, LocalDateTime.of(2026, 11, 24, 7, 15)), Locale.GERMANY);
        assertThat(row.day()).isEqualTo(java.time.LocalDate.of(2026, 11, 24));
        assertThat(row.time()).isEqualTo("07:15");
    }

    @Test
    void groupsConsecutiveRowsByDay() {
        var rows = java.util.stream.Stream.of(
                at(3, LocalDateTime.of(2026, 11, 24, 9, 0)), at(2, LocalDateTime.of(2026, 11, 24, 7, 0)),
                at(1, LocalDateTime.of(2026, 11, 23, 21, 0)))
                .map(e -> EntryListPresenter.row(e, Locale.ENGLISH)).toList();
        var groups = EntryListPresenter.groupByDay(rows);
        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).rows()).extracting(EntryListPresenter.Row::id).containsExactly(3L, 2L);
        assertThat(groups.get(1).rows()).extracting(EntryListPresenter.Row::id).containsExactly(1L);
    }

    @Test
    void dayHeaderIsRelativeThenWeekdayThenDate() {
        var today = java.time.LocalDate.of(2026, 11, 24);
        var h0 = EntryListPresenter.dayHeader(today, today, Locale.ENGLISH, "Today", "Yesterday");
        assertThat(h0.label()).isEqualTo("Today");
        assertThat(h0.detail()).contains("24");
        assertThat(EntryListPresenter.dayHeader(today.minusDays(1), today, Locale.GERMAN, "Heute", "Gestern").label())
                .isEqualTo("Gestern");
        assertThat(EntryListPresenter.dayHeader(today.minusDays(3), today, Locale.ENGLISH, "Today", "Yesterday").label())
                .isEqualTo("Saturday");
        assertThat(EntryListPresenter.dayHeader(today.minusDays(3), today, Locale.GERMAN, "Heute", "Gestern").label())
                .isEqualTo("Samstag");
        var old = EntryListPresenter.dayHeader(today.minusDays(30), today, Locale.ENGLISH, "Today", "Yesterday");
        assertThat(old.label()).contains("October").contains("25");
    }

    @Test
    void mergeAppendsWithoutDuplicates() {
        var a = at(3, LocalDateTime.of(2026, 11, 24, 9, 0));
        var b = at(2, LocalDateTime.of(2026, 11, 23, 9, 0));
        var c = at(1, LocalDateTime.of(2026, 11, 22, 9, 0));
        var merged = EntryListPresenter.merge(List.of(a, b), List.of(b, c));
        assertThat(merged).containsExactly(a, b, c);
        assertThat(EntryListPresenter.firstNewId(List.of(a, b), merged)).isEqualTo(1L);
        assertThat(EntryListPresenter.firstNewId(merged, merged)).isEqualTo(-1L);
    }

    @Test
    void detailEyebrowHasFullDateAndTime() {
        var text = EntryListPresenter.detailEyebrow(at(1, LocalDateTime.of(2026, 11, 24, 7, 15)), Locale.GERMANY);
        assertThat(text).contains("Dienstag").contains("2026").contains("07:15").contains("·");
    }
}
