package dev.rabauer.ai.diary.llm;

import dev.rabauer.ai.diary.spi.LlmGateway;
import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.support.Fakes;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class StructuredOutputServiceTest {

    public record Questions(@Size(min = 2, max = 3) List<@NotBlank String> questions) {
    }

    static final LlmGateway.JsonSchema SCHEMA = new LlmGateway.JsonSchema("questions",
            "{\"type\":\"object\",\"properties\":{\"questions\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},\"required\":[\"questions\"]}");

    @Inject
    StructuredOutputService service;

    @BeforeEach
    void reset() {
        Fakes.reset();
    }

    private StructuredRequest<Questions> request() {
        return StructuredRequest.of(List.of(LlmGateway.Message.system("sys"), LlmGateway.Message.user("entry")), SCHEMA, Questions.class)
                .withSemanticCheck(q -> q.questions().stream().allMatch(s -> s.endsWith("?")) ? List.of() : List.of("every question must end with '?'"));
    }

    @Test
    void validOutputIsReturnedOnFirstAttempt() {
        Fakes.llm().enqueue("{\"questions\":[\"What mattered?\",\"Why?\"]}");
        assertThat(service.generate(request()).questions()).containsExactly("What mattered?", "Why?");
        assertThat(Fakes.llm().calls()).hasSize(1).allMatch(FakeCall -> FakeCall.structured());
    }

    @Test
    void invalidJsonIsRetriedWithCorrectiveFeedback() {
        Fakes.llm().enqueue("not json").enqueue("```json\n{\"questions\":[\"A?\",\"B?\"]}\n```");
        assertThat(service.generate(request()).questions()).containsExactly("A?", "B?");
        var calls = Fakes.llm().calls();
        assertThat(calls).hasSize(2);
        var retryMessages = calls.get(1).request().messages();
        assertThat(retryMessages.get(retryMessages.size() - 1).content()).contains("not valid JSON").contains("ONLY a JSON object");
    }

    @Test
    void constraintAndSemanticViolationsAreRetried() {
        Fakes.llm().enqueue("{\"questions\":[\"only one?\"]}")
                .enqueue("{\"questions\":[\"no mark\",\"none\"]}")
                .enqueue("{\"questions\":[\"One?\",\"Two?\",\"Three?\"]}");
        assertThat(service.generate(request()).questions()).hasSize(3);
        assertThat(Fakes.llm().calls()).hasSize(3);
    }

    @Test
    void persistentlyInvalidOutputIsRejectedWithoutLeakingContent() {
        String secret = "{\"questions\":[\"SECRET-CONTENT\"],\"extra\":1}";
        Fakes.llm().enqueue(secret).enqueue(secret).enqueue(secret);
        assertThatThrownBy(() -> service.generate(request()))
                .isInstanceOf(InvalidLlmOutputException.class)
                .satisfies(e -> {
                    var ex = (InvalidLlmOutputException) e;
                    assertThat(ex.attempts()).isEqualTo(3);
                    assertThat(ex.problems()).containsExactly("unknown field 'extra'");
                    assertThat(ex.getMessage()).doesNotContain("SECRET-CONTENT");
                });
    }

    @Test
    void wrongTypesAreRejected() {
        Fakes.llm().enqueue("{\"questions\":\"A?\"}").enqueue("[]").enqueue("null");
        assertThatThrownBy(() -> service.generate(request().withMaxAttempts(3))).isInstanceOf(InvalidLlmOutputException.class);
    }

    @Test
    void providerFailuresPropagateUnchanged() {
        Fakes.llm().enqueueFailure(new ProviderException("fake", ProviderException.Kind.UNREACHABLE, "down"));
        assertThatThrownBy(() -> service.generate(request())).isInstanceOf(ProviderException.class);
    }
}
