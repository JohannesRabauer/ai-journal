package dev.rabauer.ai.diary.spi.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import dev.rabauer.ai.diary.llm.StructuredOutputService;
import dev.rabauer.ai.diary.llm.StructuredRequest;
import dev.rabauer.ai.diary.spi.LlmGateway;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/** StructuredOutputService + the real Ollama gateway against WireMock: invalid JSON first, valid second. */
@QuarkusTest
class OllamaStructuredOutputTest {

    public record Questions(@Size(min = 2, max = 3) List<@NotBlank String> questions) {
    }

    private static final LlmGateway.JsonSchema SCHEMA = new LlmGateway.JsonSchema("questions",
            "{\"type\":\"object\",\"properties\":{\"questions\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},\"required\":[\"questions\"]}");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    Validator validator;
    @Inject
    ObjectMapper mapper;

    private WireMockServer wm;

    @BeforeEach
    void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    private static String reply(String content) throws Exception {
        return JSON.writeValueAsString(JSON.createObjectNode().set("message",
                JSON.createObjectNode().put("role", "assistant").put("content", content)));
    }

    @Test
    void invalidJsonIsRetriedAndSecondAnswerIsAccepted() throws Exception {
        wm.stubFor(post(urlEqualTo("/api/chat")).inScenario("retry").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withBody(reply("this is not json"))).willSetStateTo("second"));
        wm.stubFor(post(urlEqualTo("/api/chat")).inScenario("retry").whenScenarioStateIs("second")
                .willReturn(aResponse().withBody(reply("{\"questions\":[\"What mattered?\",\"Why?\"]}"))));

        var gateway = new OllamaLlmGateway("http://localhost:" + wm.port(), "qwen3:1.7b", Duration.ofSeconds(5), null);
        var service = new StructuredOutputService(gateway, validator, mapper);
        var result = service.generate(StructuredRequest.of(
                List.of(LlmGateway.Message.system("sys"), LlmGateway.Message.user("entry")), SCHEMA, Questions.class));

        assertThat(result.questions()).containsExactly("What mattered?", "Why?");
        var requests = wm.findAll(postRequestedFor(urlEqualTo("/api/chat")));
        assertThat(requests).hasSize(2);
        var second = JSON.readTree(requests.get(1).getBodyAsString());
        assertThat(second.path("format").path("type").asText()).isEqualTo("object");
        assertThat(second.path("messages")).hasSize(4); // sys, entry, invalid answer, corrective feedback
        assertThat(second.path("messages").get(3).path("content").asText()).contains("not valid JSON");
    }
}
