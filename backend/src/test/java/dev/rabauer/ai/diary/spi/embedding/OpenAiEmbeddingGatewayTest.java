package dev.rabauer.ai.diary.spi.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.rabauer.ai.diary.spi.EmbeddingGateway;
import dev.rabauer.ai.diary.spi.ExternalProvider;
import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.spi.contract.EmbeddingGatewayContract;
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

class OpenAiEmbeddingGatewayTest extends EmbeddingGatewayContract {

    private static final String KEY = "sk-test-KEY-123";
    private final EmbeddingStub stub = new EmbeddingStub(true);
    private WireMockServer wm;
    private int port;

    @BeforeEach
    void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort().extensions(stub));
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
    protected EmbeddingGateway create() {
        return new OpenAiEmbeddingGateway("http://localhost:" + port + "/v1", KEY, "text-embedding-3-small", Duration.ofSeconds(5));
    }

    @Override
    protected void arrangeVector(float[] vector) {
        stub.vector(vector);
        wm.stubFor(post(urlEqualTo("/v1/embeddings")).willReturn(aResponse().withBody("{}").withTransformers("embedding-stub")));
    }

    @Override
    protected void arrangeUnreachable() {
        wm.stop();
    }

    @Test
    void sendsBearerKeyBatchedInputAndModelIdGainsDimension() throws Exception {
        arrangeVector(new float[]{1, 2});
        var g = create();
        assertThat(g.modelId()).isEqualTo("openai:text-embedding-3-small");
        assertThat(g.embedAll(List.of("a", "b", "c"))).hasSize(3);
        assertThat(g.modelId()).isEqualTo("openai:text-embedding-3-small:2");
        wm.verify(postRequestedFor(urlEqualTo("/v1/embeddings")).withHeader("Authorization", equalTo("Bearer " + KEY)));
        var body = new ObjectMapper().readTree(wm.findAll(postRequestedFor(urlEqualTo("/v1/embeddings"))).get(0).getBodyAsString());
        assertThat(body.path("model").asText()).isEqualTo("text-embedding-3-small");
        assertThat(body.path("input")).hasSize(3);
    }

    @Test
    void errorsMapToProviderExceptionKinds() {
        wm.stubFor(post(urlEqualTo("/v1/embeddings")).willReturn(aResponse().withStatus(429)));
        assertKind(ProviderException.Kind.ERROR_RESPONSE);
        wm.stubFor(post(urlEqualTo("/v1/embeddings")).willReturn(aResponse().withStatus(401)));
        assertKind(ProviderException.Kind.REJECTED);
        wm.stubFor(post(urlEqualTo("/v1/embeddings")).willReturn(aResponse().withBody("{\"data\":[]}")));
        assertKind(ProviderException.Kind.BAD_RESPONSE);
    }

    private void assertKind(ProviderException.Kind kind) {
        assertThatThrownBy(() -> create().embed("x"))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(kind));
    }

    @Test
    void timeoutIsUnreachable() {
        wm.stubFor(post(urlEqualTo("/v1/embeddings")).willReturn(aResponse().withFixedDelay(1500).withBody("{}")));
        var slow = new OpenAiEmbeddingGateway("http://localhost:" + port + "/v1", KEY, "m", Duration.ofMillis(300));
        assertThatThrownBy(() -> slow.embed("x"))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(ProviderException.Kind.UNREACHABLE));
    }

    @Test
    void neverLogsKeyOrText() {
        String secret = "ZEBRA-PRIVATE-TEXT-4711";
        wm.stubFor(post(urlEqualTo("/v1/embeddings")).willReturn(aResponse().withStatus(500).withBody(secret)));
        try (LogCapture logs = new LogCapture()) {
            assertThatThrownBy(() -> create().embed(secret)).isInstanceOf(ProviderException.class)
                    .hasMessageNotContaining(secret).hasMessageNotContaining(KEY);
            assertThat(logs.all()).doesNotContain(secret).doesNotContain(KEY);
        }
    }

    @Test
    void isExternalAndValidatesKeyPresence() {
        assertThat(create()).isInstanceOf(ExternalProvider.class);
        List<String> errors = new ArrayList<>();
        new OpenAiEmbeddingGateway("https://api.openai.com/v1", null, "m", Duration.ofSeconds(1)).validateConfiguration(errors);
        assertThat(errors).hasSize(1).first().asString().contains("OPENAI_API_KEY");
    }
}
