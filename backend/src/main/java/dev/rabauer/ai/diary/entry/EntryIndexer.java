package dev.rabauer.ai.diary.entry;

import dev.rabauer.ai.diary.spi.EmbeddingGateway;
import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.spi.VectorStore;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Embeds finished entries in the background for retrieval (coach context, gym personalization).
 * Failure is non-fatal: the entry is saved regardless. Never logs content.
 */
@ApplicationScoped
public class EntryIndexer {

    public static final String NAMESPACE = "entry";
    private static final Logger LOG = Logger.getLogger(EntryIndexer.class);
    static final int CHUNK_CHARS = 800;

    @Inject
    EmbeddingGateway embeddings;
    @Inject
    VectorStore vectors;
    @Inject
    EntryRepository repository;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "entry-indexer");
        t.setDaemon(true);
        return t;
    });

    void onFinished(@Observes(during = TransactionPhase.AFTER_SUCCESS) EntryFinished event) {
        executor.submit(() -> index(event.entryId()));
    }

    /** Synchronous indexing; returns false on provider failure. */
    public boolean index(long entryId) {
        try {
            String text = QuarkusTransaction.requiringNew().call(() -> {
                DiaryEntryEntity e = repository.findById(entryId);
                return e == null ? null : e.entryText;
            });
            if (text == null || text.isBlank()) {
                vectors.delete(NAMESPACE, Long.toString(entryId));
                return true;
            }
            List<String> parts = chunk(text, CHUNK_CHARS);
            List<float[]> vecs = embeddings.embedAll(parts);
            List<VectorStore.Chunk> chunks = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                chunks.add(new VectorStore.Chunk(i, parts.get(i), vecs.get(i), embeddings.modelId()));
            }
            vectors.upsert(NAMESPACE, Long.toString(entryId), chunks);
            return true;
        } catch (ProviderException e) {
            LOG.warnf("Indexing entry %d failed (%s/%s); will retry on next backfill.", entryId, e.provider(), e.kind());
            return false;
        } catch (RuntimeException e) {
            LOG.warnf("Indexing entry %d failed: %s", entryId, e.getClass().getSimpleName());
            return false;
        }
    }

    /** Splits on paragraph, then sentence boundaries, into pieces of at most {@code max} characters. */
    static List<String> chunk(String text, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : text.strip().split("(?<=[.!?])\\s+|\\n{2,}")) {
            String s = sentence.strip();
            if (s.isEmpty()) {
                continue;
            }
            while (s.length() > max) {
                flush(out, current);
                out.add(s.substring(0, max));
                s = s.substring(max);
            }
            if (current.length() + s.length() + 1 > max) {
                flush(out, current);
            }
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(s);
        }
        flush(out, current);
        return out;
    }

    private static void flush(List<String> out, StringBuilder sb) {
        if (!sb.isEmpty()) {
            out.add(sb.toString());
            sb.setLength(0);
        }
    }

    /** For tests: wait until queued indexing work is done. */
    public void awaitIdle() {
        var f = executor.submit(() -> { });
        try {
            f.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
