package dev.rabauer.ai.diary.entry;

/**
 * CDI event fired inside the transaction that deletes an entry. Features with per-entry data observe it with
 * {@code @Observes(during = TransactionPhase.AFTER_SUCCESS)} and remove their rows/files, so a single entry delete
 * leaves no orphans (delete-all is covered separately by UserDataOwner).
 */
public record EntryDeleted(long entryId) {
}
