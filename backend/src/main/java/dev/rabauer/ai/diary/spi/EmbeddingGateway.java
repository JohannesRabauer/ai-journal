package dev.rabauer.ai.diary.spi;

import java.util.ArrayList;
import java.util.List;

/** Text embeddings. Implementations: {@code ollama}, {@code openai} (lite-cloud only), {@code fake}. */
public interface EmbeddingGateway {

    /** @throws ProviderException */
    float[] embed(String text);

    default List<float[]> embedAll(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (String t : texts) {
            out.add(embed(t));
        }
        return out;
    }

    /** Stable id of model + dimension, stored with every vector so a model change never mixes spaces. */
    String modelId();
}
