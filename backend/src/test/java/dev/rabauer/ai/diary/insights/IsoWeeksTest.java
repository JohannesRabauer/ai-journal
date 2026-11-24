package dev.rabauer.ai.diary.insights;

import dev.rabauer.ai.diary.web.ApiException;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IsoWeeksTest {

    @Test
    void weekRunsMondayToSunday() {
        LocalDate monday = IsoWeeks.start(2031, 10);
        assertThat(monday).isEqualTo(LocalDate.of(2031, 3, 3));
        assertThat(monday.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(IsoWeeks.endExclusive(2031, 10)).isEqualTo(LocalDate.of(2031, 3, 10));
    }

    @Test
    void weekOneCanStartInThePreviousCalendarYear() {
        // 2025-01-01 is a Wednesday, so ISO week 1 of 2025 starts on Monday 2024-12-30.
        assertThat(IsoWeeks.start(2025, 1)).isEqualTo(LocalDate.of(2024, 12, 30));
        assertThat(IsoWeeks.start(2026, 1)).isEqualTo(LocalDate.of(2025, 12, 29));
    }

    @Test
    void week53ExistsOnlyInLongYears() {
        assertThat(IsoWeeks.weeksIn(2020)).isEqualTo(53);
        assertThat(IsoWeeks.weeksIn(2026)).isEqualTo(53);
        assertThat(IsoWeeks.weeksIn(2021)).isEqualTo(52);
        assertThat(IsoWeeks.start(2020, 53)).isEqualTo(LocalDate.of(2020, 12, 28));
        assertThat(IsoWeeks.endExclusive(2020, 53)).isEqualTo(LocalDate.of(2021, 1, 4));
        assertThatThrownBy(() -> IsoWeeks.start(2021, 53)).isInstanceOf(ApiException.class);
    }

    @Test
    void invalidWeeksAreBadRequests() {
        for (int[] bad : new int[][]{{2031, 0}, {2031, 54}, {2031, -1}, {1999, 10}, {2201, 1}}) {
            assertThatThrownBy(() -> IsoWeeks.validate(bad[0], bad[1]))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().getStatusCode()).isEqualTo(400));
        }
    }
}
