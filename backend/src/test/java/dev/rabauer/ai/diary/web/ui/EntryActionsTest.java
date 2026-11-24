package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.api.entry.EntryStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EntryActionsTest {

    @Test
    void draftWithTextCanBeEditedAndFinished() {
        var a = EntryActions.forEntry(EntryStatus.DRAFT, false, true, false);
        assertThat(a.editText()).isTrue();
        assertThat(a.finish()).isTrue();
        assertThat(a.transcribing()).isFalse();
        assertThat(a.deleteAudio()).isFalse();
        assertThat(a.deleteEntry()).isTrue();
    }

    @Test
    void emptyDraftCannotBeFinished() {
        assertThat(EntryActions.forEntry(EntryStatus.DRAFT, false, false, false).finish()).isFalse();
    }

    @Test
    void recordedAndTranscribingShowOnlyProgress() {
        for (EntryStatus s : new EntryStatus[]{EntryStatus.RECORDED, EntryStatus.TRANSCRIBING}) {
            var a = EntryActions.forEntry(s, true, false, false);
            assertThat(a.transcribing()).as(s.name()).isTrue();
            assertThat(a.editText()).isFalse();
            assertThat(a.finish()).isFalse();
            assertThat(a.retryTranscription()).isFalse();
            assertThat(a.deleteAudio()).isFalse();
            assertThat(a.deleteEntry()).isFalse();
        }
    }

    @Test
    void transcribedIsReviewedEditedAndFinishedWithAudioOptionallyDeleted() {
        var a = EntryActions.forEntry(EntryStatus.TRANSCRIBED, true, true, false);
        assertThat(a.editText()).isTrue();
        assertThat(a.finish()).isTrue();
        assertThat(a.deleteAudio()).isTrue();
        assertThat(a.retryTranscription()).isFalse();
    }

    @Test
    void failedTranscriptionOffersRetryAndTypingKeepingAudio() {
        var a = EntryActions.forEntry(EntryStatus.TRANSCRIPTION_FAILED, true, false, false);
        assertThat(a.retryTranscription()).isTrue();
        assertThat(a.typeManually()).isTrue();
        assertThat(a.editText()).isFalse();
        assertThat(a.finish()).isFalse();
        assertThat(a.deleteAudio()).isTrue();
    }

    @Test
    void afterChoosingToTypeYourselfTheEntryCanBeFinishedOnceTextExists() {
        var empty = EntryActions.forEntry(EntryStatus.TRANSCRIPTION_FAILED, true, false, true);
        assertThat(empty.editText()).isTrue();
        assertThat(empty.typeManually()).isFalse();
        assertThat(empty.finish()).isFalse();
        assertThat(EntryActions.forEntry(EntryStatus.TRANSCRIPTION_FAILED, true, true, true).finish()).isTrue();
    }

    @Test
    void failedWithoutAudioCannotRetry() {
        assertThat(EntryActions.forEntry(EntryStatus.TRANSCRIPTION_FAILED, false, false, false).retryTranscription()).isFalse();
    }

    @Test
    void finishedEntriesAreEditableButNotFinishableAgain() {
        var a = EntryActions.forEntry(EntryStatus.FINISHED, true, true, false);
        assertThat(a.editText()).isTrue();
        assertThat(a.finish()).isFalse();
        assertThat(a.deleteAudio()).isTrue();
        assertThat(a.deleteEntry()).isTrue();
    }

    @Test
    void entryStillBeingRecordedHasNoEditOrFinish() {
        var a = EntryActions.forEntry(EntryStatus.RECORDING, true, false, false);
        assertThat(a.editText()).isFalse();
        assertThat(a.finish()).isFalse();
        assertThat(a.transcribing()).isFalse();
        assertThat(a.deleteAudio()).isFalse();
    }
}
