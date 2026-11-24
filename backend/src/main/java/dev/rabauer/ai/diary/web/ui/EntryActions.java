package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.api.entry.EntryStatus;

/**
 * Which actions an entry offers in its current state (SPEC section 4.3). Pure presentation logic, a port of the Vaadin
 * {@code EntryActions}; {@code js/entry-state.js} has the same rules for the in-place updates of the page.
 *
 * @param editText           text area is editable (drafts, transcripts under review, finished entries)
 * @param finish             "Finish entry" is available (only the user finishes an entry)
 * @param transcribing       transcription is queued or running: show progress, nothing else
 * @param retryTranscription offered after a failed transcription while the audio is still there
 * @param typeManually       offered after a failed transcription: write the entry by hand instead
 * @param deleteAudio        delete the audio only; entry and transcript remain
 * @param deleteEntry        delete the whole entry (after confirmation)
 */
public record EntryActions(boolean editText, boolean finish, boolean transcribing, boolean retryTranscription,
                           boolean typeManually, boolean deleteAudio, boolean deleteEntry) {

    /**
     * @param hasAudio   a complete, not deleted recording exists
     * @param hasText    the entry text is not blank
     * @param manualMode user chose "type it yourself" after a failed transcription
     */
    public static EntryActions forEntry(EntryStatus status, boolean hasAudio, boolean hasText, boolean manualMode) {
        boolean transcribing = status == EntryStatus.RECORDED || status == EntryStatus.TRANSCRIBING;
        boolean busy = transcribing || status == EntryStatus.RECORDING;
        boolean failed = status == EntryStatus.TRANSCRIPTION_FAILED;

        boolean edit = switch (status) {
            case DRAFT, TRANSCRIBED, FINISHED -> true;
            case TRANSCRIPTION_FAILED -> manualMode;
            case RECORDING, RECORDED, TRANSCRIBING -> false;
        };
        boolean finish = edit && status != EntryStatus.FINISHED && hasText;
        return new EntryActions(edit, finish, transcribing, failed && hasAudio, failed && !manualMode,
                hasAudio && !busy, !transcribing);
    }
}
