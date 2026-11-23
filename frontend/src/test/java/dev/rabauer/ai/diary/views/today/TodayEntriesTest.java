package dev.rabauer.ai.diary.views.today;

import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.EntrySource;
import dev.rabauer.ai.diary.api.entry.EntryStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TodayEntriesTest {

    private static EntryDto at(long id, LocalDateTime t) {
        return new EntryDto(id, t, EntryStatus.DRAFT, EntrySource.TEXT, null, "x", null, null, false, null, null, null);
    }

    @Test
    void keepsOnlyTodayNewestFirst() {
        LocalDate today = LocalDate.of(2026, 5, 6);
        var items = List.of(
                at(1, LocalDateTime.of(2026, 5, 5, 23, 59)),
                at(2, LocalDateTime.of(2026, 5, 6, 8, 0)),
                at(3, LocalDateTime.of(2026, 5, 6, 20, 0)),
                at(4, null));
        assertThat(TodayEntries.of(items, today)).extracting(EntryDto::id).containsExactly(3L, 2L);
    }
}
