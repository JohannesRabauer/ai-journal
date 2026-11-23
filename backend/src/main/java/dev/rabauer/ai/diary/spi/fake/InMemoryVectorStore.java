package dev.rabauer.ai.diary.spi.fake;

import dev.rabauer.ai.diary.spi.ProviderName;
import dev.rabauer.ai.diary.spi.VectorStore;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Reference implementation (cosine similarity) used by tests and as the semantic baseline for contract tests. */
@ApplicationScoped
@ProviderName("memory")
@Unremovable
public class InMemoryVectorStore implements VectorStore {

    private final Map<String, List<Chunk>> data = new ConcurrentHashMap<>();

    private static String key(String namespace, String ownerId) {
        return namespace + "\u0000" + ownerId;
    }

    @Override
    public void upsert(String namespace, String ownerId, List<Chunk> chunks) {
        data.put(key(namespace, ownerId), List.copyOf(chunks));
    }

    @Override
    public List<Match> search(String namespace, float[] query, String modelId, int limit, double minScore) {
        List<Match> out = new ArrayList<>();
        data.forEach((k, chunks) -> {
            int sep = k.indexOf('\u0000');
            if (!k.substring(0, sep).equals(namespace)) {
                return;
            }
            for (Chunk c : chunks) {
                if (!c.modelId().equals(modelId) || c.vector().length != query.length) {
                    continue;
                }
                double s = cosine(query, c.vector());
                if (s >= minScore) {
                    out.add(new Match(namespace, k.substring(sep + 1), c.index(), c.text(), s));
                }
            }
        });
        out.sort(Comparator.comparingDouble(Match::score).reversed());
        return out.size() > limit ? List.copyOf(out.subList(0, limit)) : out;
    }

    public static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    @Override
    public void delete(String namespace, String ownerId) {
        data.remove(key(namespace, ownerId));
    }

    @Override
    public int count(String namespace, String ownerId) {
        return data.getOrDefault(key(namespace, ownerId), List.of()).size();
    }

    @Override
    public long deleteAll() {
        long n = data.values().stream().mapToLong(List::size).sum();
        data.clear();
        return n;
    }
}
