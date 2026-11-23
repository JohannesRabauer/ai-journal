package dev.rabauer.ai.diary.views.today;

import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.entry.EntryStatus;
import dev.rabauer.ai.diary.api.transcription.TranscriptionJobDto;
import dev.rabauer.ai.diary.api.transcription.TranscriptionStatus;
import dev.rabauer.ai.diary.views.today.TranscriptionPolling.Decision;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TranscriptionPollingTest {

    private static Optional<TranscriptionJobDto> job(TranscriptionStatus s) {
        return Optional.of(new TranscriptionJobDto(1, 1, UUID.randomUUID(), s, Language.AUTO, null, 1, null, null, null));
    }

    @Test
    void keepsPollingWhileQueuedRunningOrNoJobYet() {
        assertThat(TranscriptionPolling.decide(EntryStatus.RECORDED, Optional.empty(), 1)).isEqualTo(Decision.CONTINUE);
        assertThat(TranscriptionPolling.decide(EntryStatus.RECORDED, job(TranscriptionStatus.QUEUED), 2)).isEqualTo(Decision.CONTINUE);
        assertThat(TranscriptionPolling.decide(EntryStatus.TRANSCRIBING, job(TranscriptionStatus.RUNNING), 3)).isEqualTo(Decision.CONTINUE);
    }

    @Test
    void stopsWhenEntryIsTranscribed() {
        assertThat(TranscriptionPolling.decide(EntryStatus.TRANSCRIBED, job(TranscriptionStatus.SUCCEEDED), 4)).isEqualTo(Decision.SUCCEEDED);
    }

    @Test
    void stopsWhenEntryWasFinishedMeanwhile() {
        assertThat(TranscriptionPolling.decide(EntryStatus.FINISHED, Optional.empty(), 1)).isEqualTo(Decision.SUCCEEDED);
    }

    @Test
    void stopsOnFailureByEntryStatusOrJob() {
        assertThat(TranscriptionPolling.decide(EntryStatus.TRANSCRIPTION_FAILED, Optional.empty(), 1)).isEqualTo(Decision.FAILED);
        assertThat(TranscriptionPolling.decide(EntryStatus.TRANSCRIBING, job(TranscriptionStatus.FAILED), 1)).isEqualTo(Decision.FAILED);
    }

    @Test
    void succeededJobBeforeEntrySwitchesPollsOnceMore() {
        assertThat(TranscriptionPolling.decide(EntryStatus.TRANSCRIBING, job(TranscriptionStatus.SUCCEEDED), 5)).isEqualTo(Decision.CONTINUE);
    }

    @Test
    void givesUpQuietlyAfterMaxPolls() {
        assertThat(TranscriptionPolling.decide(EntryStatus.TRANSCRIBING, job(TranscriptionStatus.RUNNING), TranscriptionPolling.MAX_POLLS - 1))
                .isEqualTo(Decision.CONTINUE);
        assertThat(TranscriptionPolling.decide(EntryStatus.TRANSCRIBING, job(TranscriptionStatus.RUNNING), TranscriptionPolling.MAX_POLLS))
                .isEqualTo(Decision.GIVE_UP);
    }

    @Test
    void inProgressStatuses() {
        assertThat(TranscriptionPolling.isInProgress(EntryStatus.RECORDED)).isTrue();
        assertThat(TranscriptionPolling.isInProgress(EntryStatus.TRANSCRIBING)).isTrue();
        assertThat(TranscriptionPolling.isInProgress(EntryStatus.TRANSCRIBED)).isFalse();
        assertThat(TranscriptionPolling.isInProgress(EntryStatus.DRAFT)).isFalse();
    }
}
