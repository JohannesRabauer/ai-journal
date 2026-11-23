package dev.rabauer.ai.diary.transcription;

import java.time.Duration;

/** Exponential retry delay: base, 2*base, 4*base ... capped. */
final class Backoff {

    static final Duration MAX = Duration.ofMinutes(1);

    private Backoff() {
    }

    /** @param failedAttempts number of attempts that already failed (1 = first failure) */
    static Duration delay(int failedAttempts, Duration base) {
        if (failedAttempts < 1 || base.isZero() || base.isNegative()) {
            return Duration.ZERO;
        }
        long factor = 1L << Math.min(failedAttempts - 1, 20);
        long millis;
        try {
            millis = Math.multiplyExact(base.toMillis(), factor);
        } catch (ArithmeticException e) {
            return MAX;
        }
        return Duration.ofMillis(Math.min(millis, MAX.toMillis()));
    }
}
