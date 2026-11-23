package dev.rabauer.ai.diary.transcription;

import dev.rabauer.ai.diary.api.transcription.TranscriptionStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class TranscriptionJobRepository implements PanacheRepository<TranscriptionJobEntity> {

    public TranscriptionJobEntity latestForEntry(long entryId) {
        return find("entryId", Sort.by("id", Sort.Direction.Descending), entryId).firstResult();
    }

    public boolean hasActive(long entryId) {
        return count("entryId = ?1 and status in ?2", entryId, List.of(TranscriptionStatus.QUEUED, TranscriptionStatus.RUNNING)) > 0;
    }

    public List<TranscriptionJobEntity> unfinished() {
        return list("status in ?1", Sort.by("id"), List.of(TranscriptionStatus.QUEUED, TranscriptionStatus.RUNNING));
    }
}
