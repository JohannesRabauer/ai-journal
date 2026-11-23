package dev.rabauer.ai.diary.llm;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.core.JsonProcessingException;
import dev.rabauer.ai.diary.spi.LlmGateway;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The ONLY way to obtain LLM output that will be persisted (non-negotiable 3): JSON is requested with a schema,
 * parsed strictly into a record, validated (Bean Validation + semantic check), and retried with corrective
 * feedback. After {@code maxAttempts} an {@link InvalidLlmOutputException} is thrown and nothing must be persisted.
 */
@ApplicationScoped
public class StructuredOutputService {

    private static final Logger LOG = Logger.getLogger(StructuredOutputService.class);

    private final LlmGateway llm;
    private final Validator validator;
    private final ObjectMapper mapper;

    @Inject
    public StructuredOutputService(LlmGateway llm, Validator validator, ObjectMapper mapper) {
        this.llm = llm;
        this.validator = validator;
        this.mapper = mapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }

    public <T> T generate(StructuredRequest<T> request) {
        List<LlmGateway.Message> messages = new ArrayList<>(request.messages());
        List<String> problems = List.of();
        int attempts = Math.max(1, request.maxAttempts());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            String raw = llm.chatJson(new LlmGateway.Request(List.copyOf(messages), request.temperature(), null), request.schema());
            problems = new ArrayList<>();
            T value = parse(raw, request.type(), problems);
            if (value != null) {
                validator.validate(value).stream()
                        .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                        .map(StructuredOutputService::describe)
                        .forEach(problems::add);
                if (problems.isEmpty()) {
                    problems.addAll(request.semanticCheck().apply(value));
                }
                if (problems.isEmpty()) {
                    return value;
                }
            }
            LOG.warnf("Structured output '%s' attempt %d/%d rejected: %s", request.schema().name(), attempt, attempts, problems);
            messages.add(LlmGateway.Message.assistant(raw == null ? "" : raw));
            messages.add(LlmGateway.Message.user("Your previous answer was invalid: " + String.join("; ", problems)
                    + ". Answer again with ONLY a JSON object that matches the schema."));
        }
        throw new InvalidLlmOutputException(attempts, problems);
    }

    private <T> T parse(String raw, Class<T> type, List<String> problems) {
        if (raw == null || raw.isBlank()) {
            problems.add("empty answer");
            return null;
        }
        String json = stripFences(raw.strip());
        try {
            T value = mapper.readValue(json, type);
            if (value == null) {
                problems.add("answer is JSON null");
            }
            return value;
        } catch (UnrecognizedPropertyException e) {
            problems.add("unknown field '" + e.getPropertyName() + "'");
        } catch (MismatchedInputException e) {
            problems.add("wrong type or structure at '" + path(e) + "'");
        } catch (JsonProcessingException e) {
            problems.add("not valid JSON");
        }
        return null;
    }

    private static String path(MismatchedInputException e) {
        StringBuilder sb = new StringBuilder();
        for (var ref : e.getPath()) {
            if (ref.getFieldName() != null) {
                sb.append(sb.isEmpty() ? "" : ".").append(ref.getFieldName());
            } else {
                sb.append('[').append(ref.getIndex()).append(']');
            }
        }
        return sb.isEmpty() ? "$" : sb.toString();
    }

    private static String describe(ConstraintViolation<?> v) {
        return "field '" + v.getPropertyPath() + "' " + v.getMessage();
    }

    /** Some models wrap JSON in markdown fences despite the format constraint. */
    static String stripFences(String s) {
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            int last = s.lastIndexOf("```");
            if (firstNl > 0 && last > firstNl) {
                return s.substring(firstNl + 1, last).strip();
            }
        }
        return s;
    }
}
