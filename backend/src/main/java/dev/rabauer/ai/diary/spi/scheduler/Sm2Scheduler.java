package dev.rabauer.ai.diary.spi.scheduler;

import dev.rabauer.ai.diary.spi.ProviderName;
import dev.rabauer.ai.diary.spi.ReviewScheduler;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;
import java.util.List;

/**
 * SM-2 with a configurable initial interval ladder (SPEC section 6).
 *
 * <ul>
 *   <li>Initial state: repetitions 0, EF 2.5, first review after {@code ladder[0]} days.</li>
 *   <li>Ease factor after every grade (also lapses): {@code EF' = EF + (0.1 - (5-q)(0.08 + (5-q)0.02))}, floor 1.3.</li>
 *   <li>{@code q < 3} (lapse): repetitions 0, interval {@code ladder[0]}.</li>
 *   <li>Otherwise repetitions + 1 and the interval is {@code ladder[repetitions]} while that index is inside the ladder.
 *       Beyond the ladder: {@code round(previous interval * EF')}, but never less than the last ladder step + 1 and
 *       never shorter than the previous interval.</li>
 *   <li>{@code nextReviewOn = today + interval}; {@code today} is the day the review was done.</li>
 * </ul>
 */
@ApplicationScoped
@ProviderName("sm2")
@Unremovable
public class Sm2Scheduler implements ReviewScheduler {

    public static final double INITIAL_EASE = 2.5;
    public static final double MIN_EASE = 1.3;

    @Override
    public State initial(LocalDate today, List<Integer> ladder) {
        int first = checked(ladder).get(0);
        return new State(0, first, INITIAL_EASE, today.plusDays(first));
    }

    @Override
    public State next(State current, int quality, LocalDate today, List<Integer> ladder) {
        if (quality < 0 || quality > 5) {
            throw new IllegalArgumentException("quality must be 0..5");
        }
        checked(ladder);
        double ease = easeAfter(current.easeFactor(), quality);
        if (quality < 3) {
            int first = ladder.get(0);
            return new State(0, first, ease, today.plusDays(first));
        }
        int repetitions = current.repetitions() + 1;
        int interval;
        if (repetitions < ladder.size()) {
            interval = ladder.get(repetitions);
        } else {
            int last = ladder.get(ladder.size() - 1);
            interval = (int) Math.round(current.intervalDays() * ease);
            interval = Math.max(interval, Math.max(last + 1, current.intervalDays()));
        }
        return new State(repetitions, interval, ease, today.plusDays(interval));
    }

    static double easeAfter(double ease, int quality) {
        double d = 5 - quality;
        return Math.max(MIN_EASE, ease + (0.1 - d * (0.08 + d * 0.02)));
    }

    private static List<Integer> checked(List<Integer> ladder) {
        if (ladder == null || ladder.isEmpty()) {
            throw new IllegalArgumentException("interval ladder must not be empty");
        }
        return ladder;
    }
}
