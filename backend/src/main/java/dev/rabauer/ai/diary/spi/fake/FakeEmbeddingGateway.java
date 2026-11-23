package dev.rabauer.ai.diary.spi.fake;

import dev.rabauer.ai.diary.spi.EmbeddingGateway;
import dev.rabauer.ai.diary.spi.ProviderName;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Locale;

/**
 * Deterministic bag-of-words embedding (dimension 64): texts sharing words are similar. Good enough for tests.
 */
@ApplicationScoped
@ProviderName("fake")
@Unremovable
public class FakeEmbeddingGateway implements EmbeddingGateway {

    public static final int DIMENSION = 64;

    @Override
    public float[] embed(String text) {
        float[] v = new float[DIMENSION];
        for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!token.isEmpty()) {
                v[Math.floorMod(token.hashCode(), DIMENSION)] += 1f;
            }
        }
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        if (norm > 0) {
            float n = (float) Math.sqrt(norm);
            for (int i = 0; i < v.length; i++) {
                v[i] /= n;
            }
        }
        return v;
    }

    @Override
    public String modelId() {
        return "fake-bow-" + DIMENSION;
    }
}
