package dev.rabauer.ai.diary.spi.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.rabauer.ai.diary.spi.LlmGateway;
import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.spi.contract.LlmGatewayContract;
import dev.rabauer.ai.diary.support.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OllamaLlmGatewayTest extends LlmGatewayContract {

    private static final ObjectMapper JSON = new ObjectMapper();
    private WireMockServer wm;
    private int port;

    @BeforeEach
    void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        port = wm.port();
    }

    @AfterEach
    void stop() {
        if (wm.isRunning()) {
            wm.stop();
        }
    }

    @Override
    protected LlmGateway create() {
        return new OllamaLlmGateway("http://localhost:" + port, "qwen3:1.7b", Duration.ofSeconds(5), null);
    }

    private static String reply(String content) throws Exception {
        return JSON.writeValueAsString(JSON.createObjectNode().set("message",
                JSON.createObjectNode().put("role", "assistant").put("content", content)));
    }

    private void stubChat(int status, String body) {
        wm.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse().withStatus(status).withBody(body)));
    }

    @Override
    protected void arrangeChat(String r) {
        try {
            stubChat(200, reply(r));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    protected void arrangeJson(String json) {
        arrangeChat(json);
    }

    @Override
    protected void arrangeUnreachable() {
        wm.stop();
    }

    private JsonNode lastBody() throws Exception {
        var events = wm.findAll(postRequestedFor(urlEqualTo("/api/chat")));
        return JSON.readTree(events.get(events.size() - 1).getBodyAsString());
    }

    private static LlmGateway.Request userRequest(String text) {
        return LlmGateway.Request.of(List.of(LlmGateway.Message.user(text)));
    }

    @Test
    void chatJsonSendsSchemaAsFormatAndDisablesThinking() throws Exception {
        arrangeJson("{\"q\":\"x\"}");
        create().chatJson(LlmGateway.Request.of(List.of(LlmGateway.Message.system("s"), LlmGateway.Message.user("u"))), SCHEMA);
        JsonNode body = lastBody();
        assertThat(body.path("model").asText()).isEqualTo("qwen3:1.7b");
        assertThat(body.path("stream").asBoolean(true)).isFalse();
        assertThat(body.path("think").asBoolean(true)).isFalse();
        assertThat(body.path("format")).isEqualTo(JSON.readTree(SCHEMA.schemaJson()));
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").get(1).path("content").asText()).isEqualTo("u");
    }

    @Test
    void plainChatSendsNoFormatAndAppliesOptions() throws Exception {
        arrangeChat("hi");
        new OllamaLlmGateway("http://localhost:" + port, "m", Duration.ofSeconds(5), 0.3)
                .chat(new LlmGateway.Request(List.of(LlmGateway.Message.user("u")), null, 50));
        JsonNode body = lastBody();
        assertThat(body.has("format")).isFalse();
        assertThat(body.path("think").asBoolean(true)).isFalse();
        assertThat(body.path("options").path("temperature").asDouble()).isEqualTo(0.3);
        assertThat(body.path("options").path("num_predict").asInt()).isEqualTo(50);
    }

    @Test
    void leadingThinkBlockIsStripped() throws Exception {
        stubChat(200, reply("<think>pondering</think>\n{\"q\":\"x\"}"));
        assertThat(create().chatJson(userRequest("u"), SCHEMA)).isEqualTo("{\"q\":\"x\"}");
    }

    @Test
    void statusCodesMapToProviderExceptionKinds() {
        assertKind(500, ProviderException.Kind.ERROR_RESPONSE);
        assertKind(429, ProviderException.Kind.ERROR_RESPONSE);
        assertKind(404, ProviderException.Kind.REJECTED);
        assertKind(400, ProviderException.Kind.REJECTED);
    }

    private void assertKind(int status, ProviderException.Kind kind) {
        stubChat(status, "{\"error\":\"nope\"}");
        assertThatThrownBy(() -> create().chat(userRequest("u")))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(kind));
    }

    @Test
    void unparseableOrIncompleteResponseIsBadResponse() {
        stubChat(200, "not json at all");
        assertThatThrownBy(() -> create().chat(userRequest("u")))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(ProviderException.Kind.BAD_RESPONSE));
        stubChat(200, "{\"done\":true}");
        assertThatThrownBy(() -> create().chat(userRequest("u")))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(ProviderException.Kind.BAD_RESPONSE));
    }

    @Test
    void timeoutIsUnreachable() {
        wm.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse().withFixedDelay(1500).withStatus(200).withBody("{}")));
        var slow = new OllamaLlmGateway("http://localhost:" + port, "m", Duration.ofMillis(300), null);
        assertThatThrownBy(() -> slow.chat(userRequest("u")))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.kind()).isEqualTo(ProviderException.Kind.UNREACHABLE);
                    assertThat(e.retryable()).isTrue();
                });
    }

    @Test
    void failuresNeverLogOrReportPromptText() {
        String secret = "ZEBRA-PRIVATE-PROMPT-4711";
        stubChat(500, "{\"error\":\"" + secret + "\"}");
        try (LogCapture logs = new LogCapture()) {
            assertThatThrownBy(() -> create().chat(userRequest(secret)))
                    .satisfies(e -> {
                        List<String> texts = new ArrayList<>();
                        for (Throwable t = e; t != null; t = t.getCause()) {
                            texts.add(String.valueOf(t.getMessage()));
                        }
                        assertThat(String.join(" ", texts)).doesNotContain(secret);
                    });
            assertThat(logs.all()).doesNotContain(secret);
        }
    }

    @Test
    void selfValidationChecksUrlModelAndTimeoutOnly() {
        List<String> errors = new ArrayList<>();
        new OllamaLlmGateway("http://localhost:11434", "m", Duration.ofMinutes(1), null).validateConfiguration(errors);
        assertThat(errors).isEmpty();
        new OllamaLlmGateway("not a url", " ", Duration.ZERO, null).validateConfiguration(errors);
        assertThat(errors).hasSize(3);
    }
}
