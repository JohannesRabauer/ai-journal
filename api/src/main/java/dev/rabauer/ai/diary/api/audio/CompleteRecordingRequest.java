package dev.rabauer.ai.diary.api.audio;

/** Marks the recording complete; the server answers 409 listing missing indices if chunks are missing. */
public record CompleteRecordingRequest(int totalChunks, long durationMs) {
}
