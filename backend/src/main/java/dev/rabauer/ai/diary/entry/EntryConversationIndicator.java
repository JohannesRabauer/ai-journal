package dev.rabauer.ai.diary.entry;

/** Implemented by the coach feature so the entries list can show a "has conversation" badge. */
public interface EntryConversationIndicator {
    boolean hasConversation(long entryId);
}
