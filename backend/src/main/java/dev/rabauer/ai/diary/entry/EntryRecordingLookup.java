package dev.rabauer.ai.diary.entry;

import java.util.UUID;

/** Implemented by the recording feature (W1a) so entries expose their recording id. */
public interface EntryRecordingLookup {
    /** @return the recording id of the entry, or null */
    UUID recordingIdOf(long entryId);
}
