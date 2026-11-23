package dev.rabauer.ai.diary.spi.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.rabauer.ai.diary.spi.EmbeddingGateway;
import dev.rabauer.ai.diary.spi.ExternalProvider;
import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.spi.ProviderName;
import dev.rabauer.ai.diary.spi.SelfValidating;
import dev.rabauer.ai.diary.spi.llm.JsonHttp;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** OpenAI embeddings (lite-cloud profile only; sends entry text off-device). Key and content are never logged. */
@ApplicationScoped
@ProviderName("openai")
@Unremovable
public class OpenAiEmbeddingGateway implements EmbeddingGateway, SelfValidating, ExternalProvider {

    private static final int BATCH = 64;

    @ConfigProperty(name = "journal.openai.base-url", defaultValue = "https://api.openai.com/v1")
    String baseUrl;
    @ConfigProperty(name = "journal.openai.api-key")
    Optional<String> apiKey = Optional.empty();
    @ConfigProperty(name = "journal.embedding.openai.model", defaultValue = "text-embedding-3-small")
    String model;
    @ConfigProperty(name = "journal.embedding.openai.timeout", defaultValue = "2m")
    Duration timeout;

    private volatile JsonHttp http;
    private volatile int dimension;

    public OpenAiEmbeddingGateway() {
    }

    public OpenAiEmbeddingGateway(String baseUrl, String apiKey, String model, Duration timeout) {
        this.baseUrl = baseUrl;
        this.apiKey = Optional.ofNullable(apiKey);
        this.model = model;
        this.timeout = timeout;
    }

    private JsonHttp http() {
        JsonHttp h = http;
        if (h == null) {
            h = http = new JsonHttp("openai", baseUrl, timeout);
        }
        return h;
    }

    @Override
    public float[] embed(String text) {
        return embedAll(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += BATCH) {
            out.addAll(embedBatch(texts.subList(from, Math.min(texts.size(), from + BATCH))));
        }
        return out;
    }

    private List<float[]> embedBatch(List<String> texts) {
        ObjectNode body = JsonHttp.mapper().createObjectNode();
        body.put("model", model);
        body.putArray("input").addAll(texts.stream().map(JsonHttp.mapper().getNodeFactory()::textNode).toList());
        JsonNode resp = http().post("/embeddings", body, Map.of("Authorization", "Bearer " + apiKey.orElse("")));
        JsonNode data = resp.get("data");
        if (data == null || !data.isArray() || data.size() != texts.size()) {
            throw new ProviderException("openai", ProviderException.Kind.BAD_RESPONSE, "unexpected number of embeddings");
        }
        float[][] ordered = new float[texts.size()][];
        for (int i = 0; i < data.size(); i++) {
            int idx = data.get(i).path("index").asInt(i);
            if (idx < 0 || idx >= ordered.length || ordered[idx] != null) {
                throw new ProviderException("openai", ProviderException.Kind.BAD_RESPONSE, "invalid embedding index");
            }
            ordered[idx] = http().vector(data.get(i).get("embedding"));
        }
        dimension = ordered[0].length;
        return List.of(ordered);
    }

    @Override
    public String modelId() {
        int d = dimension;
        return "openai:" + model + (d > 0 ? ":" + d : "");
    }

    @Override
    public void validateConfiguration(List<String> errors) {
        String problem = JsonHttp.checkUrl(baseUrl);
        if (problem != null) {
            errors.add("journal.openai.base-url " + problem + ".");
        }
        if (apiKey.filter(k -> !k.isBlank()).isEmpty()) {
            errors.add("OpenAI API key missing: set OPENAI_API_KEY (property journal.openai.api-key).");
        }
        if (model == null || model.isBlank()) {
            errors.add("journal.embedding.openai.model is empty.");
        }
    }
}
