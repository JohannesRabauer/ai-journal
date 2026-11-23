package dev.rabauer.ai.diary.transcription;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffTest {

    @Test
    void doublesWithEveryFailure() {
        Duration base = Duration.ofSeconds(5);
        assertThat(Backoff.delay(1, base)).isEqualTo(Duration.ofSeconds(5));
        assertThat(Backoff.delay(2, base)).isEqualTo(Duration.ofSeconds(10));
        assertThat(Backoff.delay(3, base)).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void isCappedAndSafeForHugeAttemptCounts() {
        assertThat(Backoff.delay(10, Duration.ofSeconds(5))).isEqualTo(Backoff.MAX);
        assertThat(Backoff.delay(Integer.MAX_VALUE, Duration.ofDays(3000))).isEqualTo(Backoff.MAX);
    }

    @Test
    void zeroForNoFailureOrNoBase() {
        assertThat(Backoff.delay(0, Duration.ofSeconds(5))).isZero();
        assertThat(Backoff.delay(2, Duration.ZERO)).isZero();
    }

    @Test
    void mergeAppendsAfterBlankLine() {
        assertThat(TranscriptionService.merge("", "Hallo")).isEqualTo("Hallo");
        assertThat(TranscriptionService.merge(null, " Hallo ")).isEqualTo("Hallo");
        assertThat(TranscriptionService.merge("Vorher\n", "Hallo")).isEqualTo("Vorher\n\nHallo");
        assertThat(TranscriptionService.merge("Vorher", "")).isEqualTo("Vorher");
    }
}
