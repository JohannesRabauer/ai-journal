package dev.rabauer.ai.diary.tts;

import dev.rabauer.ai.diary.spi.TextToSpeech;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.assertj.core.api.Assertions.assertThat;

class TtsCacheTest {

    @TempDir
    Path dir;

    private static TextToSpeech.Audio audio(int b, String type) {
        return new TextToSpeech.Audio(new byte[]{(byte) b, 2, 3}, type);
    }

    @Test
    void roundTripKeepsContentType() {
        TtsCache cache = new TtsCache(dir.resolve("c"), 5);
        String k = TtsCache.key("piper", "v", "de", "Hallo");
        assertThat(cache.get(k)).isEmpty();
        cache.put(k, audio(1, "audio/mpeg"));
        var hit = cache.get(k).orElseThrow();
        assertThat(hit.contentType()).isEqualTo("audio/mpeg");
        assertThat(hit.data()).containsExactly(1, 2, 3);
    }

    @Test
    void keyDependsOnAllParts() {
        String base = TtsCache.key("piper", "v", "de", "Hallo");
        assertThat(base).hasSize(64)
                .isNotEqualTo(TtsCache.key("openai", "v", "de", "Hallo"))
                .isNotEqualTo(TtsCache.key("piper", "w", "de", "Hallo"))
                .isNotEqualTo(TtsCache.key("piper", "v", "en", "Hallo"))
                .isNotEqualTo(TtsCache.key("piper", "v", "de", "Hallo!"));
    }

    @Test
    void evictsLeastRecentlyUsedBeyondMaxEntries() throws IOException {
        TtsCache cache = new TtsCache(dir, 3);
        for (int i = 1; i <= 3; i++) {
            cache.put("k" + i, audio(i, "audio/wav"));
            age("k" + i, 10 - i); // k1 oldest
        }
        assertThat(cache.get("k1")).isPresent(); // touch k1: now most recently used
        cache.put("k4", audio(4, "audio/wav"));  // evicts k2 (oldest access)
        assertThat(cache.size()).isEqualTo(3);
        assertThat(cache.get("k2")).isEmpty();
        assertThat(cache.get("k1")).isPresent();
        assertThat(cache.get("k3")).isPresent();
        assertThat(cache.get("k4")).isPresent();
    }

    private void age(String key, int secondsAgo) throws IOException {
        try (var s = Files.list(dir)) {
            Path p = s.filter(f -> f.getFileName().toString().startsWith(key + ".")).findFirst().orElseThrow();
            Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis() - secondsAgo * 1000L));
        }
    }

    @Test
    void nonAudioIsNotCachedAndNoTempFilesRemain() throws IOException {
        TtsCache cache = new TtsCache(dir, 3);
        cache.put("a", audio(1, "application/json"));
        cache.put("b", audio(1, "audio/wav; charset=x"));
        assertThat(cache.size()).isEqualTo(1);
        try (var s = Files.list(dir)) {
            assertThat(s.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".tmp"));
        }
    }

    @Test
    void clearRemovesEverything() {
        TtsCache cache = new TtsCache(dir, 5);
        cache.put("a", audio(1, "audio/wav"));
        cache.put("b", audio(2, "audio/wav"));
        assertThat(cache.clear()).isEqualTo(2);
        assertThat(cache.size()).isZero();
        assertThat(new TtsCache(dir.resolve("missing"), 5).clear()).isZero();
    }
}
