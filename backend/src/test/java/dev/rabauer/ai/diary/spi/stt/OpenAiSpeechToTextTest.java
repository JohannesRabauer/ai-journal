package dev.rabauer.ai.diary.spi.stt;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.spi.ExternalProvider;
import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.spi.ProviderException.Kind;
import dev.rabauer.ai.diary.spi.SpeechToText;
import dev.rabauer.ai.diary.spi.contract.SpeechToTextContract;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiSpeechToTextTest extends SpeechToTextContract {

    private static final String PATH = "/v1/audio/transcriptions";

    private WireMockServer wm;
    private int port;

    @BeforeEach
    void startServer() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        port = wm.port();
    }

    @AfterEach
    void stopServer() {
        wm.stop();
    }

    private OpenAiSpeechToText provider(String model) {
        OpenAiSpeechToText p = new OpenAiSpeechToText();
        p.baseUrl = "http://localhost:" + port + "/v1";
        p.apiKey = Optional.of("sk-test-key");
        p.model = model;
        p.timeout = Duration.ofSeconds(5);
        return p;
    }

    /** The contract expects a detected language, which only verbose_json (whisper-1) provides. */
    @Override
    protected SpeechToText create() {
        return provider("whisper-1");
    }

    @Override
    protected void arrangeSuccess(String text, Language detected) {
        String lang = switch (detected) {
            case DE -> ",\"language\":\"german\"";
            case EN -> ",\"language\":\"english\"";
            case AUTO -> "";
        };
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(okJson("{\"text\":\"" + text + "\"" + lang + ",\"duration\":1.0}")));
    }

    @Override
    protected void arrangeUnreachable() {
        wm.stop();
    }

    @Override
    protected void arrangeServerError() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(503)));
    }

    @Test
    void sendsBearerKeyAndVerboseJsonForWhisper1() {
        arrangeSuccess("Hi", Language.EN);
        provider("whisper-1").transcribe(new SpeechToText.Request(audio(), Language.EN));
        wm.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer sk-test-key"))
                .withRequestBody(containing("verbose_json"))
                .withRequestBody(containing("name=\"language\"")));
    }

    @Test
    void gpt4oModelUsesPlainJsonAndReportsAutoLanguage() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(okJson("{\"text\":\"Hallo\"}")));
        var r = provider("gpt-4o-mini-transcribe").transcribe(new SpeechToText.Request(audio(), Language.AUTO));
        assertThat(r.text()).isEqualTo("Hallo");
        assertThat(r.detectedLanguage()).isEqualTo(Language.AUTO);
        wm.verify(postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(containing("gpt-4o-mini-transcribe"))
                .withRequestBody(containing("name=\"response_format\"\r\n\r\njson\r\n")));
    }

    @Test
    void missingApiKeyIsRejectedWithoutNetwork() {
        OpenAiSpeechToText p = provider("whisper-1");
        p.apiKey = Optional.empty();
        assertThatThrownBy(() -> p.transcribe(new SpeechToText.Request(audio(), Language.AUTO)))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(Kind.REJECTED));
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test
    void clientErrorIsRejected() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(401)));
        assertThatThrownBy(() -> create().transcribe(new SpeechToText.Request(audio(), Language.AUTO)))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(Kind.REJECTED));
    }

    @Test
    void isExternalAndValidatesUrl() {
        OpenAiSpeechToText p = provider("whisper-1");
        assertThat(p).isInstanceOf(ExternalProvider.class);
        List<String> errors = new ArrayList<>();
        p.validateConfiguration(errors);
        assertThat(errors).isEmpty();
        p.baseUrl = "ftp://x";
        p.validateConfiguration(errors);
        assertThat(errors).hasSize(1);
    }
}
