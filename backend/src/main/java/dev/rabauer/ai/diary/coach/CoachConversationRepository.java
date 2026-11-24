package dev.rabauer.ai.diary.coach;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class CoachConversationRepository implements PanacheRepository<CoachConversationEntity> {

    /** Newest first. */
    public List<CoachConversationEntity> forEntry(long entryId) {
        return list("entryId", Sort.by("createdAt", Sort.Direction.Descending).and("id", Sort.Direction.Descending), entryId);
    }
}
