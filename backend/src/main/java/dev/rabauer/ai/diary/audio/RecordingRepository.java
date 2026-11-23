package dev.rabauer.ai.diary.audio;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class RecordingRepository implements PanacheRepositoryBase<RecordingEntity, UUID> {

    public List<RecordingEntity> forEntry(long entryId) {
        return list("entryId", Sort.by("createdAt", Sort.Direction.Descending), entryId);
    }

    /** Newest recording of the entry, or null. */
    public RecordingEntity latestForEntry(long entryId) {
        return find("entryId", Sort.by("createdAt", Sort.Direction.Descending), entryId).firstResult();
    }
}
