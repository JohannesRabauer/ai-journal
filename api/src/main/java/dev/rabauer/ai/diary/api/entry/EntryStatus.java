package dev.rabauer.ai.diary.api.entry;

/** Lifecycle, see SPEC section 4.3. Only the user moves an entry to {@code FINISHED}. */
public enum EntryStatus {
    DRAFT, RECORDING, RECORDED, TRANSCRIBING, TRANSCRIBED, TRANSCRIPTION_FAILED, FINISHED
}
