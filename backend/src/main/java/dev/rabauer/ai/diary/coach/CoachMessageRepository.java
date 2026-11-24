package dev.rabauer.ai.diary.coach;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class CoachMessageRepository implements PanacheRepository<CoachMessageEntity> {

    /** All messages (revealed and planned) in insertion order. */
    public List<CoachMessageEntity> forConversation(long conversationId) {
        return list("conversationId", Sort.by("id"), conversationId);
    }

    public long deleteForEntry(long entryId) {
        return delete("conversationId in (select c.id from CoachConversationEntity c where c.entryId = ?1)", entryId);
    }
}
