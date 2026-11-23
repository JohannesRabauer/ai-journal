package dev.rabauer.ai.diary.views.today;

import dev.rabauer.ai.diary.api.entry.EntryDto;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/** Selection of "today's entries" for the Today view (pure). */
public final class TodayEntries {

    private TodayEntries() {
    }

    /** Entries written or spoken on {@code today}, newest first. */
    public static List<EntryDto> of(List<EntryDto> items, LocalDate today) {
        return items.stream()
                .filter(e -> e.entryTimestamp() != null && e.entryTimestamp().toLocalDate().equals(today))
                .sorted(Comparator.comparing(EntryDto::entryTimestamp).reversed())
                .toList();
    }
}
