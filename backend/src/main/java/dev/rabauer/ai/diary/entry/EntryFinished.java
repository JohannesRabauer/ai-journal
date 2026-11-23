package dev.rabauer.ai.diary.entry;

/** CDI event fired (synchronously, inside the transaction) when the user finishes an entry. */
public record EntryFinished(long entryId) {
}
