package dev.rabauer.ai.diary.llm;

import dev.rabauer.ai.diary.spi.LlmGateway;

import java.util.List;
import java.util.function.Function;

/**
 * A request for validated, typed LLM output.
 *
 * @param type          Java record the JSON is bound to; Jakarta Bean Validation annotations on it are enforced
 * @param semanticCheck extra checks returning problems (empty list = ok), e.g. "questions must end with '?'"
 * @param maxAttempts   total attempts including the first (default 3)
 */
public record StructuredRequest<T>(
        List<LlmGateway.Message> messages,
        LlmGateway.JsonSchema schema,
        Class<T> type,
        Function<T, List<String>> semanticCheck,
        int maxAttempts,
        Double temperature
) {
    public static <T> StructuredRequest<T> of(List<LlmGateway.Message> messages, LlmGateway.JsonSchema schema, Class<T> type) {
        return new StructuredRequest<>(messages, schema, type, t -> List.of(), 3, null);
    }

    public StructuredRequest<T> withSemanticCheck(Function<T, List<String>> check) {
        return new StructuredRequest<>(messages, schema, type, check, maxAttempts, temperature);
    }

    public StructuredRequest<T> withMaxAttempts(int attempts) {
        return new StructuredRequest<>(messages, schema, type, semanticCheck, attempts, temperature);
    }

    public StructuredRequest<T> withTemperature(Double t) {
        return new StructuredRequest<>(messages, schema, type, semanticCheck, maxAttempts, t);
    }
}
