package dev.rabauer.ai.diary.entry;

import dev.rabauer.ai.diary.privacy.ExportSink;
import dev.rabauer.ai.diary.privacy.UserDataOwner;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Bookkeeping table {@code entry_index_state} (V30): which embedding model id has indexed which entry. The
 * VectorStore only counts chunks per owner across models, so this is what lets the backfill find entries that are
 * missing from the CURRENT embedding model (never indexed, legacy, or indexed with a previous model).
 * Derived data: not exported, deleted with "delete everything".
 */
@ApplicationScoped
public class EntryIndexState implements UserDataOwner {

    @Inject
    EntityManager em;

    /** Records that the entry is indexed for {@code modelId}. */
    public void record(long entryId, String modelId) {
        QuarkusTransaction.requiringNew().run(() -> {
            Timestamp now = Timestamp.from(Instant.now());
            int updated = em.createNativeQuery("UPDATE entry_index_state SET model_id = ?1, indexed_at = ?2 WHERE entry_id = ?3")
                    .setParameter(1, modelId).setParameter(2, now).setParameter(3, entryId).executeUpdate();
            if (updated == 0) {
                em.createNativeQuery("INSERT INTO entry_index_state (entry_id, model_id, indexed_at) VALUES (?1, ?2, ?3)")
                        .setParameter(1, entryId).setParameter(2, modelId).setParameter(3, now).executeUpdate();
            }
        });
    }

    /** Ids (ascending, greater than {@code afterId}) of FINISHED entries not indexed for {@code modelId}. */
    @SuppressWarnings("unchecked")
    public List<Long> unindexed(String modelId, long afterId, int limit) {
        return QuarkusTransaction.requiringNew().call(() -> ((List<Number>) em.createNativeQuery(
                        "SELECT d.id FROM documents d LEFT JOIN entry_index_state s ON s.entry_id = d.id "
                                + "WHERE d.status = 'FINISHED' AND d.id > ?1 AND (s.entry_id IS NULL OR s.model_id <> ?2) "
                                + "ORDER BY d.id LIMIT ?3")
                .setParameter(1, afterId).setParameter(2, modelId).setParameter(3, limit).getResultList())
                .stream().map(Number::longValue).toList());
    }

    @Override
    public String name() {
        return "entry-index-state";
    }

    @Override
    public int deleteOrder() {
        return 900;
    }

    @Override
    public long deleteAll() {
        return QuarkusTransaction.requiringNew().call(() -> (long) em.createNativeQuery("DELETE FROM entry_index_state").executeUpdate());
    }

    @Override
    public void export(ExportSink sink) throws IOException {
        // derived data, rebuilt by the backfill
    }
}
