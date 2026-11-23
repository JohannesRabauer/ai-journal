package dev.rabauer.ai.diary.api.coach;

/** Only allowed for FINISHED entries (409 otherwise). */
public record StartConversationRequest(long entryId) {
}
