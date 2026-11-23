package dev.rabauer.ai.diary.audio;

import dev.rabauer.ai.diary.entry.EntryRecordingLookup;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

/** Fills {@code EntryDto.recordingId}. */
@ApplicationScoped
@Unremovable
public class RecordingLookup implements EntryRecordingLookup {

    @Inject
    RecordingRepository repository;

    @Override
    public UUID recordingIdOf(long entryId) {
        RecordingEntity r = repository.latestForEntry(entryId);
        return r == null ? null : r.id;
    }
}
