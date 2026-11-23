package dev.rabauer.ai.diary.spi;

import java.time.LocalDate;
import java.util.List;

/**
 * Spaced-repetition scheduler. Implementations: {@code sm2}, {@code fake}.
 */
public interface ReviewScheduler {

    /** State of a newly approved memory item: first review after {@code initialIntervalsDays[0]} days. */
    State initial(LocalDate today, List<Integer> initialIntervalsDays);

    /**
     * @param quality SM-2 recall quality 0..5 (below 3 = lapse)
     * @param initialIntervalsDays configured ladder for the first repetitions, default [1,3,7,14,30]
     */
    State next(State current, int quality, LocalDate today, List<Integer> initialIntervalsDays);

    record State(int repetitions, int intervalDays, double easeFactor, LocalDate nextReviewOn) {
    }
}
