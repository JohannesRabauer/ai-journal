package dev.rabauer.ai.diary.privacy;

import java.io.IOException;

/**
 * Every feature that stores user data implements this (as an {@code @ApplicationScoped} bean) so that export and
 * "delete everything" (SPEC section 10) are complete by construction. A feature that adds a table without
 * registering it here is a privacy bug.
 */
public interface UserDataOwner {

    /** Short stable name used in export paths and delete reports, e.g. "recordings". */
    String name();

    /**
     * Owners are deleted in ascending order. Dependents (rows referencing entries) use 100..900, entries use 1000,
     * stores outside the DB (audio files, vectors) use 2000.
     */
    int deleteOrder();

    /** @return number of rows/files removed */
    long deleteAll();

    /** Write this owner's data as JSON (and binaries) into the export. Paths are relative, e.g. "coach/conversations.json". */
    void export(ExportSink sink) throws IOException;
}
