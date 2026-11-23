package dev.rabauer.ai.diary.spi.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.rabauer.ai.diary.spi.EmbeddingGateway;
import dev.rabauer.ai.diary.spi.ProviderName;
import dev.rabauer.ai.diary.spi.SelfValidating;
import dev.rabauer.ai.diary.spi.llm.JsonHttp;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Local embeddings via Ollama {@code POST /api/embed} (batch input). */
@ApplicationScoped
@ProviderName("ollama")
@Unremovable
public class OllamaEmbeddingGateway implements EmbeddingGateway, SelfValidating {

    @ConfigProperty(name = "journal.embedding.ollama.base-url", defaultValue = "http://localhost:11434")
    String baseUrl;
    @ConfigProperty(name = "journal.embedding.ollama.model", defaultValue = "qwen3-embedding:0.6b")
    String model;
    @ConfigProperty(name = "journal.embedding.ollama.timeout", defaultValue = "2m")
    Duration timeout;

    private volatile JsonHttp http;
    private volatile int dimension;

    public OllamaEmbeddingGateway() {
    }

    public OllamaEmbeddingGateway(String baseUrl, String model, Duration timeout) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.timeout = timeout;
    }

    private JsonHttp http() {
        JsonHttp h = http;
        if (h == null) {
            h = http = new JsonHttp("ollama", baseUrl, timeout);
        }
        return h;
    }

    @Override
    public float[] embed(String text) {
        return embedAll(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        ObjectNode body = JsonHttp.mapper().createObjectNode();
        body.put("model", model);
        body.putArray("input").addAll(texts.stream().map(JsonHttp.mapper().getNodeFactory()::textNode).toList());
        JsonNode resp = http().post("/api/embed", body, Map.of());
        List<float[]> out = http().vectors(resp.get("embeddings"), texts.size());
        dimension = out.get(0).length;
        return out;
    }

    @Override
    public String modelId() {
        int d = dimension;
        return "ollama:" + model + (d > 0 ? ":" + d : "");
    }

    @Override
    public void validateConfiguration(List<String> errors) {
        String problem = JsonHttp.checkUrl(baseUrl);
        if (problem != null) {
            errors.add("journal.embedding.ollama.base-url " + problem + " (env JOURNAL_EMBEDDING_OLLAMA_BASE_URL).");
        }
        if (model == null || model.isBlank()) {
            errors.add("journal.embedding.ollama.model is empty.");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            errors.add("journal.embedding.ollama.timeout must be positive, e.g. 2m.");
        }
    }
}
