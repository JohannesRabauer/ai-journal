package dev.rabauer.ai.diary.spi.scheduler;

import dev.rabauer.ai.diary.spi.ReviewScheduler;
import dev.rabauer.ai.diary.spi.ReviewScheduler.State;
import dev.rabauer.ai.diary.spi.contract.ReviewSchedulerContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** SM-2 details on top of the shared contract. */
class Sm2SchedulerTest extends ReviewSchedulerContract {

    private final Sm2Scheduler sm2 = new Sm2Scheduler();

    @Override
    protected ReviewScheduler create() {
        return sm2;
    }

    @Test
    void initialState() {
        State s = sm2.initial(TODAY, LADDER);
        assertThat(s).isEqualTo(new State(0, 1, 2.5, TODAY.plusDays(1)));
    }

    /** quality, expected ease factor after the first grade, expected repetitions, expected interval. */
    @ParameterizedTest
    @CsvSource({
            "0, 1.70, 0, 1",
            "1, 1.96, 0, 1",
            "2, 2.18, 0, 1",
            "3, 2.36, 1, 3",
            "4, 2.50, 1, 3",
            "5, 2.60, 1, 3"})
    void everyQualityFromTheInitialState(int quality, double ease, int repetitions, int interval) {
        LocalDate day = TODAY.plusDays(1);
        State s = sm2.next(sm2.initial(TODAY, LADDER), quality, day, LADDER);
        assertThat(s.easeFactor()).isCloseTo(ease, within(1e-9));
        assertThat(s.repetitions()).isEqualTo(repetitions);
        assertThat(s.intervalDays()).isEqualTo(interval);
        assertThat(s.nextReviewOn()).isEqualTo(day.plusDays(interval));
    }

    @Test
    void qualityOutsideTheRangeIsRejected() {
        State s = sm2.initial(TODAY, LADDER);
        assertThatThrownBy(() -> sm2.next(s, -1, TODAY, LADDER)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sm2.next(s, 6, TODAY, LADDER)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyLadderIsRejected() {
        assertThatThrownBy(() -> sm2.initial(TODAY, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void easeFactorNeverDropsBelowTheFloor() {
        State s = sm2.initial(TODAY, LADDER);
        for (int i = 0; i < 10; i++) {
            s = sm2.next(s, 0, TODAY, LADDER);
            assertThat(s.easeFactor()).isGreaterThanOrEqualTo(1.3);
        }
        assertThat(s.easeFactor()).isEqualTo(1.3);
        // quality 3 keeps pulling down, still floored
        s = sm2.next(new State(1, 3, 1.3, TODAY), 3, TODAY, LADDER);
        assertThat(s.easeFactor()).isEqualTo(1.3);
    }

    @Test
    void easeFactorGrowsWithEasyAnswers() {
        State s = sm2.initial(TODAY, LADDER);
        for (int i = 0; i < 5; i++) {
            s = sm2.next(s, 5, TODAY, LADDER);
        }
        assertThat(s.easeFactor()).isCloseTo(3.0, within(1e-9));
    }

    @Test
    void longChainOfGoodAnswersWalksTheLadderThenMultiplies() {
        State s = sm2.initial(TODAY, LADDER);
        LocalDate day = TODAY;
        int[] expected = {3, 7, 14, 30, 75, 188, 470};
        for (int i = 0; i < expected.length; i++) {
            day = s.nextReviewOn();
            s = sm2.next(s, 4, day, LADDER);
            assertThat(s.repetitions()).isEqualTo(i + 1);
            assertThat(s.intervalDays()).as("step %d", i + 1).isEqualTo(expected[i]);
            assertThat(s.nextReviewOn()).isEqualTo(day.plusDays(expected[i]));
            assertThat(s.easeFactor()).isCloseTo(2.5, within(1e-9));
        }
    }

    @Test
    void firstIntervalBeyondTheLadderIsAtLeastLastStepPlusOne() {
        List<Integer> ladder = List.of(1);
        State s = new State(0, 1, 1.3, TODAY);
        State n = sm2.next(s, 3, TODAY, ladder);
        assertThat(n.repetitions()).isEqualTo(1);
        assertThat(n.intervalDays()).isEqualTo(2); // round(1 * 1.3) = 1, raised to last step + 1
    }

    @Test
    void lapseAfterALongChainRestartsTheLadderButKeepsTheLoweredEase() {
        State s = sm2.initial(TODAY, LADDER);
        for (int i = 0; i < 7; i++) {
            s = sm2.next(s, 4, TODAY, LADDER);
        }
        assertThat(s.intervalDays()).isEqualTo(470);
        State lapsed = sm2.next(s, 1, TODAY, LADDER);
        assertThat(lapsed.repetitions()).isZero();
        assertThat(lapsed.intervalDays()).isEqualTo(1);
        assertThat(lapsed.nextReviewOn()).isEqualTo(TODAY.plusDays(1));
        assertThat(lapsed.easeFactor()).isCloseTo(1.96, within(1e-9));
        // and the next success continues at the second ladder step
        State again = sm2.next(lapsed, 4, TODAY, LADDER);
        assertThat(again.repetitions()).isEqualTo(1);
        assertThat(again.intervalDays()).isEqualTo(3);
    }

    @Test
    void customLadderTwoSteps() {
        List<Integer> ladder = List.of(2, 5);
        State s = sm2.initial(TODAY, ladder);
        assertThat(s.intervalDays()).isEqualTo(2);
        assertThat(s.nextReviewOn()).isEqualTo(TODAY.plusDays(2));
        s = sm2.next(s, 4, TODAY, ladder);
        assertThat(s.intervalDays()).isEqualTo(5);
        s = sm2.next(s, 4, TODAY, ladder);
        assertThat(s.intervalDays()).isEqualTo(13); // round(5 * 2.5) = 12.5 -> 13
        s = sm2.next(s, 4, TODAY, ladder);
        assertThat(s.intervalDays()).isEqualTo(33); // 32.5 -> 33
        State lapsed = sm2.next(s, 2, TODAY, ladder);
        assertThat(lapsed.intervalDays()).isEqualTo(2);
    }

    @Test
    void singleStepLadder() {
        List<Integer> ladder = List.of(4);
        State s = sm2.initial(TODAY, ladder);
        assertThat(s.intervalDays()).isEqualTo(4);
        s = sm2.next(s, 4, TODAY, ladder);
        assertThat(s.repetitions()).isEqualTo(1);
        assertThat(s.intervalDays()).isEqualTo(10);
        s = sm2.next(s, 4, TODAY, ladder);
        assertThat(s.intervalDays()).isEqualTo(25);
        assertThat(sm2.next(s, 0, TODAY, ladder).intervalDays()).isEqualTo(4);
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4, 5})
    void intervalsNeverShrinkOnSuccess(int quality) {
        State s = sm2.initial(TODAY, LADDER);
        int previous = 0;
        for (int i = 0; i < 12; i++) {
            s = sm2.next(s, quality, TODAY, LADDER);
            assertThat(s.intervalDays()).isGreaterThanOrEqualTo(previous);
            previous = s.intervalDays();
        }
    }

    @Test
    void nextReviewIsCountedFromTheDayOfTheReviewNotTheDueDate() {
        State s = sm2.initial(TODAY, LADDER); // due TODAY+1
        LocalDate late = TODAY.plusDays(10);
        State n = sm2.next(s, 4, late, LADDER);
        assertThat(n.nextReviewOn()).isEqualTo(late.plusDays(3));
    }
}
