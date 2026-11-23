package dev.rabauer.ai.diary.spi.fake;

import dev.rabauer.ai.diary.spi.AudioStorage;
import dev.rabauer.ai.diary.spi.ProviderName;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

@ApplicationScoped
@ProviderName("memory")
@Unremovable
public class InMemoryAudioStorage implements AudioStorage {

    private final Map<UUID, ConcurrentSkipListMap<Integer, byte[]>> chunks = new ConcurrentHashMap<>();
    private final Map<UUID, byte[]> audio = new ConcurrentHashMap<>();

    @Override
    public void writeChunk(UUID recordingId, int chunkIndex, InputStream data) throws IOException {
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must be >= 0");
        }
        chunks.computeIfAbsent(recordingId, k -> new ConcurrentSkipListMap<>()).put(chunkIndex, data.readAllBytes());
    }

    @Override
    public List<Integer> chunkIndices(UUID recordingId) {
        var m = chunks.get(recordingId);
        return m == null ? List.of() : List.copyOf(m.keySet());
    }

    @Override
    public void assemble(UUID recordingId, int totalChunks) throws IOException {
        var m = chunks.getOrDefault(recordingId, new ConcurrentSkipListMap<>());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < totalChunks; i++) {
            byte[] c = m.get(i);
            if (c == null) {
                throw new IllegalStateException("missing chunk " + i);
            }
            out.write(c);
        }
        audio.put(recordingId, out.toByteArray());
        chunks.remove(recordingId);
    }

    @Override
    public boolean hasAudio(UUID recordingId) {
        return audio.containsKey(recordingId);
    }

    @Override
    public InputStream openAudio(UUID recordingId) throws IOException {
        byte[] a = audio.get(recordingId);
        if (a == null) {
            throw new FileNotFoundException(recordingId.toString());
        }
        return new ByteArrayInputStream(a);
    }

    @Override
    public long audioSize(UUID recordingId) {
        byte[] a = audio.get(recordingId);
        return a == null ? 0 : a.length;
    }

    @Override
    public boolean delete(UUID recordingId) {
        boolean a = audio.remove(recordingId) != null;
        boolean c = chunks.remove(recordingId) != null;
        return a || c;
    }

    @Override
    public long deleteAll() {
        var ids = new java.util.HashSet<UUID>(audio.keySet());
        ids.addAll(chunks.keySet());
        audio.clear();
        chunks.clear();
        return ids.size();
    }
}
