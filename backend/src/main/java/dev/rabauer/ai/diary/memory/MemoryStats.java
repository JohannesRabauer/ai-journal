package dev.rabauer.ai.diary.memory;

import java.time.Instant;
import java.util.List;

/**
 * Contract between the memory feature (W3a, implements it as an {@code @ApplicationScoped} bean) and the weekly
 * reflection (W3c, consumes it via {@code Instance<MemoryStats>}; if unresolvable it reports zero learned items).
 * Counts only, plus item prompts; never answers or source quotes.
 */
public interface MemoryStats {

    /**
     * @param from inclusive
     * @param to   exclusive
     */
    Learned learnedBetween(Instant from, Instant to);

    /**
     * @param itemsCreated memory items approved in the period
     * @param reviewsDone  graded reviews in the period
     * @param itemPrompts  prompts of the items created in the period (max 10, newest first)
     */
    record Learned(int itemsCreated, int reviewsDone, List<String> itemPrompts) {
        public static Learned none() {
            return new Learned(0, 0, List.of());
        }
    }
}
