package dev.rabauer.ai.diary.spi.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.rabauer.ai.diary.spi.ExternalProvider;
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
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiLlmGatewayTest extends LlmGatewayContract {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "sk-test-KEY-123";
    private static final String STRICT_SCHEMA = "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},"
            + "\"required\":[\"q\"],\"additionalProperties\":false}";
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
        return new OpenAiLlmGateway("http://localhost:" + port + "/v1", KEY, "gpt-4.1-mini", Duration.ofSeconds(5));
    }

    private void stubCompletion(int status, String body) {
        wm.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(status).withBody(body)));
    }

    private static String choice(String content) throws Exception {
        return JSON.writeValueAsString(JSON.createObjectNode().set("choices", JSON.createArrayNode().add(
                JSON.createObjectNode().set("message", JSON.createObjectNode().put("role", "assistant").put("content", content)))));
    }

    @Override
    protected void arrangeChat(String reply) {
        try {
            stubCompletion(200, choice(reply));
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
        var events = wm.findAll(postRequestedFor(urlEqualTo("/v1/chat/completions")));
        return JSON.readTree(events.get(events.size() - 1).getBodyAsString());
    }

    private static LlmGateway.Request userRequest(String text) {
        return LlmGateway.Request.of(List.of(LlmGateway.Message.user(text)));
    }

    @Test
    void sendsBearerKeyAndModel() throws Exception {
        arrangeChat("ok");
        create().chat(userRequest("u"));
        wm.verify(postRequestedFor(urlEqualTo("/v1/chat/completions")).withHeader("Authorization", equalTo("Bearer " + KEY)));
        assertThat(lastBody().path("model").asText()).isEqualTo("gpt-4.1-mini");
        assertThat(lastBody().has("response_format")).isFalse();
    }

    @Test
    void strictCompatibleSchemaUsesJsonSchemaFormat() throws Exception {
        arrangeJson("{\"q\":\"x\"}");
        create().chatJson(userRequest("u"), new LlmGateway.JsonSchema("my question!", STRICT_SCHEMA));
        JsonNode rf = lastBody().path("response_format");
        assertThat(rf.path("type").asText()).isEqualTo("json_schema");
        assertThat(rf.path("json_schema").path("name").asText()).isEqualTo("my_question_");
        assertThat(rf.path("json_schema").path("strict").asBoolean()).isTrue();
        assertThat(rf.path("json_schema").path("schema")).isEqualTo(JSON.readTree(STRICT_SCHEMA));
        assertThat(lastBody().path("messages")).hasSize(1);
    }

    @Test
    void nonStrictSchemaFallsBackToJsonObjectWithSchemaInstruction() throws Exception {
        arrangeJson("{\"q\":\"x\"}");
        create().chatJson(userRequest("u"), SCHEMA); // no additionalProperties:false
        JsonNode body = lastBody();
        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").get(0).path("content").asText()).contains("JSON").contains("\"q\"");
    }

    @Test
    void strictCompatibilityRules() throws Exception {
        assertThat(OpenAiLlmGateway.strictCompatible(JSON.readTree(STRICT_SCHEMA))).isTrue();
        assertThat(OpenAiLlmGateway.strictCompatible(JSON.readTree(SCHEMA.schemaJson()))).isFalse();
        assertThat(OpenAiLlmGateway.strictCompatible(JSON.readTree(
                "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"},\"b\":{\"type\":\"string\"}},"
                        + "\"required\":[\"a\"],\"additionalProperties\":false}"))).as("optional property").isFalse();
        assertThat(OpenAiLlmGateway.strictCompatible(JSON.readTree(
                "{\"type\":\"object\",\"properties\":{\"l\":{\"type\":\"array\",\"items\":{\"type\":\"object\","
                        + "\"properties\":{\"x\":{\"type\":\"string\"}},\"required\":[\"x\"]}}},"
                        + "\"required\":[\"l\"],\"additionalProperties\":false}"))).as("nested object lacks additionalProperties").isFalse();
        assertThat(OpenAiLlmGateway.strictCompatible(JSON.readTree("{\"type\":\"array\"}"))).as("root not object").isFalse();
    }

    @Test
    void rateLimitIsErrorResponseAndOtherClientErrorsAreRejected() {
        stubCompletion(429, "{\"error\":{\"message\":\"slow down\"}}");
        assertThatThrownBy(() -> create().chat(userRequest("u")))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.kind()).isEqualTo(ProviderException.Kind.ERROR_RESPONSE);
                    assertThat(e.retryable()).isTrue();
                });
        stubCompletion(503, "{}");
        assertThatThrownBy(() -> create().chat(userRequest("u")))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(ProviderException.Kind.ERROR_RESPONSE));
        stubCompletion(401, "{\"error\":{\"message\":\"bad key\"}}");
        assertThatThrownBy(() -> create().chat(userRequest("u")))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(ProviderException.Kind.REJECTED));
    }

    @Test
    void badPayloadsAndRefusals() {
        stubCompletion(200, "<html>proxy</html>");
        assertThatThrownBy(() -> create().chat(userRequest("u")))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(ProviderException.Kind.BAD_RESPONSE));
        stubCompletion(200, "{\"choices\":[]}");
        assertThatThrownBy(() -> create().chat(userRequest("u")))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(ProviderException.Kind.BAD_RESPONSE));
        stubCompletion(200, "{\"choices\":[{\"message\":{\"content\":null,\"refusal\":\"no\"}}]}");
        assertThatThrownBy(() -> create().chat(userRequest("u")))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(ProviderException.Kind.REJECTED));
    }

    @Test
    void timeoutIsUnreachable() {
        wm.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withFixedDelay(1500).withStatus(200).withBody("{}")));
        var slow = new OpenAiLlmGateway("http://localhost:" + port + "/v1", KEY, "m", Duration.ofMillis(300));
        assertThatThrownBy(() -> slow.chat(userRequest("u")))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(ProviderException.Kind.UNREACHABLE));
    }

    @Test
    void neitherKeyNorPromptAreLoggedOrInErrors() {
        String secret = "ZEBRA-PRIVATE-PROMPT-4711";
        stubCompletion(401, "{\"error\":\"" + secret + " " + KEY + "\"}");
        try (LogCapture logs = new LogCapture()) {
            assertThatThrownBy(() -> create().chat(userRequest(secret))).satisfies(e -> {
                List<String> texts = new ArrayList<>();
                for (Throwable t = e; t != null; t = t.getCause()) {
                    texts.add(String.valueOf(t.getMessage()));
                }
                assertThat(String.join(" ", texts)).doesNotContain(secret).doesNotContain(KEY);
            });
            assertThat(logs.all()).doesNotContain(secret).doesNotContain(KEY);
        }
    }

    @Test
    void isExternalAndValidatesKeyPresenceWithoutNetwork() {
        assertThat(create()).isInstanceOf(ExternalProvider.class);
        List<String> errors = new ArrayList<>();
        new OpenAiLlmGateway("https://api.openai.com/v1", "k", "m", Duration.ofSeconds(1)).validateConfiguration(errors);
        assertThat(errors).isEmpty();
        new OpenAiLlmGateway("::bad", " ", "m", Duration.ofSeconds(1)).validateConfiguration(errors);
        assertThat(errors).hasSize(2);
    }
}
