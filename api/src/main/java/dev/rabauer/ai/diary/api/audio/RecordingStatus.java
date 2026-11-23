package dev.rabauer.ai.diary.api.audio;

public enum RecordingStatus {
    /** chunks are being received */
    RECORDING,
    /** client declared the final chunk count and all chunks are present */
    COMPLETE,
    /** audio was deleted (retention setting or user action); transcript remains */
    AUDIO_DELETED
}
