package dev.rabauer.ai.diary.spi.fake;

import dev.rabauer.ai.diary.spi.ProviderName;
import dev.rabauer.ai.diary.spi.ReviewScheduler;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;
import java.util.List;

/** Simplistic stand-in until the SM-2 implementation exists: walks the interval ladder, lapses reset. */
@ApplicationScoped
@ProviderName("fake")
@Unremovable
public class FakeReviewScheduler implements ReviewScheduler {

    @Override
    public State initial(LocalDate today, List<Integer> ladder) {
        return new State(0, ladder.get(0), 2.5, today.plusDays(ladder.get(0)));
    }

    @Override
    public State next(State current, int quality, LocalDate today, List<Integer> ladder) {
        if (quality < 3) {
            return new State(0, ladder.get(0), current.easeFactor(), today.plusDays(ladder.get(0)));
        }
        int reps = current.repetitions() + 1;
        int interval = ladder.get(Math.min(reps, ladder.size() - 1));
        return new State(reps, interval, current.easeFactor(), today.plusDays(interval));
    }
}
