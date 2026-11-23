package dev.rabauer.ai.diary.spi;

import java.util.List;

/**
 * Chat LLM. Implementations: {@code ollama}, {@code openai} (lite-cloud only), {@code fake}.
 *
 * <p>Output that will be persisted MUST go through {@code StructuredOutputService}, which calls
 * {@link #chatJson} and validates/retries. {@link #chat} is only for transient text.
 */
public interface LlmGateway {

    /** Free text completion. @throws ProviderException */
    String chat(Request request);

    /**
     * Completion constrained to JSON. Implementations should use the provider's native structured output
     * (Ollama {@code format}, OpenAI {@code response_format: json_schema}) and return the raw JSON text unvalidated.
     *
     * @throws ProviderException
     */
    String chatJson(Request request, JsonSchema schema);

    enum Role { SYSTEM, USER, ASSISTANT }

    record Message(Role role, String content) {
        public static Message system(String c) { return new Message(Role.SYSTEM, c); }
        public static Message user(String c) { return new Message(Role.USER, c); }
        public static Message assistant(String c) { return new Message(Role.ASSISTANT, c); }
    }

    /** @param temperature null = provider default */
    record Request(List<Message> messages, Double temperature, Integer maxTokens) {
        public static Request of(List<Message> messages) {
            return new Request(List.copyOf(messages), null, null);
        }
    }

    /** @param schemaJson a JSON Schema document (draft 2020-12 subset supported by Ollama/OpenAI) */
    record JsonSchema(String name, String schemaJson) {
    }
}
