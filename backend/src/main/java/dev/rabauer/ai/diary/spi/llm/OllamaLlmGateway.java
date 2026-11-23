package dev.rabauer.ai.diary.spi.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.rabauer.ai.diary.spi.LlmGateway;
import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.spi.ProviderName;
import dev.rabauer.ai.diary.spi.SelfValidating;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Local chat LLM via Ollama {@code POST /api/chat} (non-streaming). Structured output uses Ollama's {@code format}
 * parameter with the JSON schema. {@code think:false} is always sent so thinking models (qwen3) put pure JSON/text
 * into {@code message.content}; a leading {@code <think>...</think>} block from older servers is stripped anyway.
 */
@ApplicationScoped
@ProviderName("ollama")
@Unremovable
public class OllamaLlmGateway implements LlmGateway, SelfValidating {

    private static final Pattern THINK_BLOCK = Pattern.compile("^\\s*<think>.*?</think>\\s*", Pattern.DOTALL);

    @ConfigProperty(name = "journal.llm.ollama.base-url", defaultValue = "http://localhost:11434")
    String baseUrl;
    @ConfigProperty(name = "journal.llm.ollama.model", defaultValue = "qwen3:1.7b")
    String model;
    @ConfigProperty(name = "journal.llm.ollama.timeout", defaultValue = "5m")
    Duration timeout;
    @ConfigProperty(name = "journal.llm.ollama.temperature")
    Optional<Double> temperature = Optional.empty();

    private volatile JsonHttp http;

    public OllamaLlmGateway() {
    }

    public OllamaLlmGateway(String baseUrl, String model, Duration timeout, Double temperature) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.timeout = timeout;
        this.temperature = Optional.ofNullable(temperature);
    }

    private JsonHttp http() {
        JsonHttp h = http;
        if (h == null) {
            h = http = new JsonHttp("ollama", baseUrl, timeout);
        }
        return h;
    }

    @Override
    public String chat(Request request) {
        return call(request, null);
    }

    @Override
    public String chatJson(Request request, JsonSchema schema) {
        JsonNode schemaNode;
        try {
            schemaNode = JsonHttp.mapper().readTree(schema.schemaJson());
        } catch (IOException e) {
            throw new ProviderException("ollama", ProviderException.Kind.REJECTED, "JSON schema '" + schema.name() + "' is not valid JSON");
        }
        return call(request, schemaNode);
    }

    private String call(Request request, JsonNode format) {
        ObjectNode body = JsonHttp.mapper().createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        body.put("think", false);
        ArrayNode messages = body.putArray("messages");
        for (Message m : request.messages()) {
            messages.addObject().put("role", m.role().name().toLowerCase(Locale.ROOT)).put("content", m.content());
        }
        if (format != null) {
            body.set("format", format);
        }
        Double temp = request.temperature() != null ? request.temperature() : temperature.orElse(null);
        if (temp != null || request.maxTokens() != null) {
            ObjectNode options = body.putObject("options");
            if (temp != null) {
                options.put("temperature", temp);
            }
            if (request.maxTokens() != null) {
                options.put("num_predict", request.maxTokens());
            }
        }
        JsonNode resp = http().post("/api/chat", body, Map.of());
        JsonNode content = resp.path("message").path("content");
        if (!content.isTextual()) {
            throw new ProviderException("ollama", ProviderException.Kind.BAD_RESPONSE, "response has no message.content");
        }
        return THINK_BLOCK.matcher(content.asText()).replaceFirst("");
    }

    @Override
    public void validateConfiguration(List<String> errors) {
        String problem = JsonHttp.checkUrl(baseUrl);
        if (problem != null) {
            errors.add("journal.llm.ollama.base-url " + problem + " (env JOURNAL_LLM_OLLAMA_BASE_URL).");
        }
        if (model == null || model.isBlank()) {
            errors.add("journal.llm.ollama.model is empty.");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            errors.add("journal.llm.ollama.timeout must be positive, e.g. 5m.");
        }
    }
}
