package dev.rabauer.ai.diary.spi.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.rabauer.ai.diary.spi.ExternalProvider;
import dev.rabauer.ai.diary.spi.LlmGateway;
import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.spi.ProviderName;
import dev.rabauer.ai.diary.spi.SelfValidating;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * OpenAI chat completions (lite-cloud profile only; sends entry text off-device).
 *
 * <p>{@code chatJson} uses {@code response_format: json_schema} with {@code strict:true} when the schema satisfies
 * OpenAI's strict-mode rules (root object; every object has {@code additionalProperties:false} and lists all its
 * properties as required; no allOf/oneOf/not/if/patternProperties). Otherwise it falls back to
 * {@code response_format: json_object} and prepends the schema as a system instruction; the caller's validation in
 * {@code StructuredOutputService} then enforces the schema. The API key and message content are never logged.
 */
@ApplicationScoped
@ProviderName("openai")
@Unremovable
public class OpenAiLlmGateway implements LlmGateway, SelfValidating, ExternalProvider {

    private static final Set<String> UNSUPPORTED_STRICT = Set.of(
            "allOf", "oneOf", "not", "if", "then", "else", "patternProperties", "dependentRequired", "dependentSchemas");

    @ConfigProperty(name = "journal.openai.base-url", defaultValue = "https://api.openai.com/v1")
    String baseUrl;
    @ConfigProperty(name = "journal.openai.api-key")
    Optional<String> apiKey = Optional.empty();
    @ConfigProperty(name = "journal.llm.openai.model", defaultValue = "gpt-4.1-mini")
    String model;
    @ConfigProperty(name = "journal.llm.openai.timeout", defaultValue = "2m")
    Duration timeout;

    private volatile JsonHttp http;

    public OpenAiLlmGateway() {
    }

    public OpenAiLlmGateway(String baseUrl, String apiKey, String model, Duration timeout) {
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
    public String chat(Request request) {
        return call(request, null);
    }

    @Override
    public String chatJson(Request request, JsonSchema schema) {
        JsonNode schemaNode;
        try {
            schemaNode = JsonHttp.mapper().readTree(schema.schemaJson());
        } catch (IOException e) {
            throw new ProviderException("openai", ProviderException.Kind.REJECTED, "JSON schema '" + schema.name() + "' is not valid JSON");
        }
        return call(request, new Format(schema.name(), schemaNode, strictCompatible(schemaNode)));
    }

    private record Format(String name, JsonNode schema, boolean strict) {
    }

    private String call(Request request, Format format) {
        ObjectNode body = JsonHttp.mapper().createObjectNode();
        body.put("model", model);
        ArrayNode messages = body.putArray("messages");
        if (format != null && !format.strict()) {
            // json_object mode needs the word JSON in the prompt; give the model the schema as instruction.
            messages.addObject().put("role", "system").put("content",
                    "Answer with ONLY a JSON object that matches this JSON schema: " + format.schema());
        }
        for (Message m : request.messages()) {
            messages.addObject().put("role", m.role().name().toLowerCase(Locale.ROOT)).put("content", m.content());
        }
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        if (request.maxTokens() != null) {
            body.put("max_tokens", request.maxTokens());
        }
        if (format != null) {
            ObjectNode rf = body.putObject("response_format");
            if (format.strict()) {
                rf.put("type", "json_schema");
                ObjectNode js = rf.putObject("json_schema");
                js.put("name", format.name().replaceAll("[^A-Za-z0-9_-]", "_"));
                js.set("schema", format.schema());
                js.put("strict", true);
            } else {
                rf.put("type", "json_object");
            }
        }
        JsonNode resp = http().post("/chat/completions", body,
                Map.of("Authorization", "Bearer " + apiKey.orElse("")));
        JsonNode message = resp.path("choices").path(0).path("message");
        JsonNode content = message.path("content");
        if (content.isTextual()) {
            return content.asText();
        }
        if (message.hasNonNull("refusal")) {
            throw new ProviderException("openai", ProviderException.Kind.REJECTED, "model refused to answer");
        }
        throw new ProviderException("openai", ProviderException.Kind.BAD_RESPONSE, "response has no message content");
    }

    /** OpenAI strict structured outputs: root object, all properties required, additionalProperties=false everywhere. */
    static boolean strictCompatible(JsonNode root) {
        return root.isObject() && "object".equals(root.path("type").asText()) && strictNode(root);
    }

    private static boolean strictNode(JsonNode node) {
        if (node.isArray()) {
            for (JsonNode n : node) {
                if (!strictNode(n)) {
                    return false;
                }
            }
            return true;
        }
        if (!node.isObject()) {
            return true;
        }
        JsonNode props = node.get("properties");
        if (props != null && props.isObject()) {
            if (!node.path("additionalProperties").isBoolean() || node.path("additionalProperties").asBoolean()) {
                return false;
            }
            Set<String> required = new HashSet<>();
            node.path("required").forEach(r -> required.add(r.asText()));
            for (Iterator<String> it = props.fieldNames(); it.hasNext(); ) {
                if (!required.contains(it.next())) {
                    return false;
                }
            }
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            var e = it.next();
            if (UNSUPPORTED_STRICT.contains(e.getKey()) || !strictNode(e.getValue())) {
                return false;
            }
        }
        return true;
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
            errors.add("journal.llm.openai.model is empty.");
        }
    }
}
