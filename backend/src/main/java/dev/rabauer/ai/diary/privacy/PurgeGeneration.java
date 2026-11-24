package dev.rabauer.ai.diary.privacy;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Global "purge generation". {@link PrivacyService#deleteAll()} advances it before deleting (and once more when it
 * is done). A background writer that derives data from journal content (indexer, weekly generation, ...) captures
 * {@link #current()} when it reads its input and must not persist its result when {@link #changedSince(long)}
 * answers true: the input it worked on may be exactly what the user just deleted.
 *
 * <p>Covered writers: entry indexer (and therefore the backfill), weekly reflection generation. Not covered, see
 * docs/PRIVACY.md: transcription jobs (they write to the recording's own entry, which delete-all removes first, so
 * a late result finds no entry to update) and coach / gym / memory LLM calls (synchronous, user-initiated; a
 * delete-all issued while one is in flight can leave that one result behind, delete-all again clears it).
 */
@ApplicationScoped
public class PurgeGeneration {

    private final AtomicLong generation = new AtomicLong();

    public long current() {
        return generation.get();
    }

    public long advance() {
        return generation.incrementAndGet();
    }

    public boolean changedSince(long captured) {
        return generation.get() != captured;
    }
}
