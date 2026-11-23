package dev.rabauer.ai.diary.spi.contract;

import dev.rabauer.ai.diary.spi.ReviewScheduler;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Behavior every scheduler must have. The SM-2 implementation adds its own exhaustive tests on top. */
public abstract class ReviewSchedulerContract {

    protected static final List<Integer> LADDER = List.of(1, 3, 7, 14, 30);
    protected static final LocalDate TODAY = LocalDate.of(2026, 11, 23);

    protected abstract ReviewScheduler create();

    @Test
    void initialStateUsesFirstInterval() {
        var s = create().initial(TODAY, LADDER);
        assertThat(s.repetitions()).isZero();
        assertThat(s.intervalDays()).isEqualTo(1);
        assertThat(s.nextReviewOn()).isEqualTo(TODAY.plusDays(1));
        assertThat(s.easeFactor()).isGreaterThanOrEqualTo(1.3);
    }

    @Test
    void successfulRecallsNeverShortenTheInterval() {
        var scheduler = create();
        var s = scheduler.initial(TODAY, LADDER);
        LocalDate day = TODAY;
        int previous = 0;
        for (int i = 0; i < 8; i++) {
            day = s.nextReviewOn();
            s = scheduler.next(s, 4, day, LADDER);
            assertThat(s.intervalDays()).isGreaterThanOrEqualTo(previous);
            assertThat(s.nextReviewOn()).isEqualTo(day.plusDays(s.intervalDays()));
            previous = s.intervalDays();
        }
        assertThat(s.repetitions()).isGreaterThan(0);
    }

    @Test
    void lapseRestartsAtFirstInterval() {
        var scheduler = create();
        var s = scheduler.next(scheduler.next(scheduler.initial(TODAY, LADDER), 5, TODAY, LADDER), 5, TODAY, LADDER);
        var lapsed = scheduler.next(s, 1, TODAY, LADDER);
        assertThat(lapsed.repetitions()).isZero();
        assertThat(lapsed.intervalDays()).isEqualTo(1);
        assertThat(lapsed.nextReviewOn()).isEqualTo(TODAY.plusDays(1));
        assertThat(lapsed.easeFactor()).isGreaterThanOrEqualTo(1.3);
    }

    @Test
    void configuredLadderIsRespected() {
        var custom = List.of(2, 5);
        var s = create().initial(TODAY, custom);
        assertThat(s.intervalDays()).isEqualTo(2);
    }
}
