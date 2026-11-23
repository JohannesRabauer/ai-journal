package dev.rabauer.ai.diary.spi.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.rabauer.ai.diary.spi.EmbeddingGateway;
import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.spi.contract.EmbeddingGatewayContract;
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

class OllamaEmbeddingGatewayTest extends EmbeddingGatewayContract {

    private final EmbeddingStub stub = new EmbeddingStub(false);
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
        return new OllamaEmbeddingGateway("http://localhost:" + port, "qwen3-embedding:0.6b", Duration.ofSeconds(5));
    }

    @Override
    protected void arrangeVector(float[] vector) {
        stub.vector(vector);
        wm.stubFor(post(urlEqualTo("/api/embed")).willReturn(aResponse().withBody("{}").withTransformers("embedding-stub")));
    }

    @Override
    protected void arrangeUnreachable() {
        wm.stop();
    }

    @Test
    void sendsBatchInputAndModelIdGainsDimension() throws Exception {
        arrangeVector(new float[]{1, 2, 3, 4});
        var g = create();
        assertThat(g.modelId()).isEqualTo("ollama:qwen3-embedding:0.6b");
        assertThat(g.embedAll(List.of("a", "b"))).hasSize(2);
        assertThat(g.modelId()).isEqualTo("ollama:qwen3-embedding:0.6b:4");
        var body = new ObjectMapper().readTree(wm.findAll(postRequestedFor(urlEqualTo("/api/embed"))).get(0).getBodyAsString());
        assertThat(body.path("model").asText()).isEqualTo("qwen3-embedding:0.6b");
        assertThat(body.path("input")).hasSize(2);
        assertThat(wm.findAll(postRequestedFor(urlEqualTo("/api/embed")))).as("one batched request").hasSize(1);
    }

    @Test
    void errorsMapToProviderExceptionKinds() {
        wm.stubFor(post(urlEqualTo("/api/embed")).willReturn(aResponse().withStatus(500)));
        assertKind(ProviderException.Kind.ERROR_RESPONSE);
        wm.stubFor(post(urlEqualTo("/api/embed")).willReturn(aResponse().withStatus(404)));
        assertKind(ProviderException.Kind.REJECTED);
        wm.stubFor(post(urlEqualTo("/api/embed")).willReturn(aResponse().withBody("{\"embeddings\":[]}")));
        assertKind(ProviderException.Kind.BAD_RESPONSE);
        wm.stubFor(post(urlEqualTo("/api/embed")).willReturn(aResponse().withBody("garbage")));
        assertKind(ProviderException.Kind.BAD_RESPONSE);
    }

    private void assertKind(ProviderException.Kind kind) {
        assertThatThrownBy(() -> create().embed("x"))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(kind));
    }

    @Test
    void timeoutIsUnreachable() {
        wm.stubFor(post(urlEqualTo("/api/embed")).willReturn(aResponse().withFixedDelay(1500).withBody("{}")));
        var slow = new OllamaEmbeddingGateway("http://localhost:" + port, "m", Duration.ofMillis(300));
        assertThatThrownBy(() -> slow.embed("x"))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(ProviderException.Kind.UNREACHABLE));
    }

    @Test
    void selfValidation() {
        List<String> errors = new ArrayList<>();
        new OllamaEmbeddingGateway("ftp://x", "m", Duration.ofSeconds(1)).validateConfiguration(errors);
        assertThat(errors).hasSize(1);
    }
}
