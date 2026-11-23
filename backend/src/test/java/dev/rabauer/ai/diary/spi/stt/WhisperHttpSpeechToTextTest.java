package dev.rabauer.ai.diary.spi.stt;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.rabauer.ai.diary.api.common.Language;
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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WhisperHttpSpeechToTextTest extends SpeechToTextContract {

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

    @Override
    protected SpeechToText create() {
        WhisperHttpSpeechToText p = new WhisperHttpSpeechToText();
        p.baseUrl = "http://localhost:" + port + "/";
        p.timeout = Duration.ofSeconds(5);
        return p;
    }

    @Override
    protected void arrangeSuccess(String text, Language detected) {
        String lang = switch (detected) {
            case DE -> ",\"language\":\"german\"";
            case EN -> ",\"language\":\"english\"";
            case AUTO -> "";
        };
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(okJson("{\"text\":\"" + text + "\"" + lang + ",\"duration\":2.5}")));
    }

    @Override
    protected void arrangeUnreachable() {
        wm.stop(); // connection refused on the remembered port
    }

    @Override
    protected void arrangeServerError() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500).withBody("boom")));
    }

    private ProviderException failure() {
        Throwable t = org.assertj.core.api.Assertions.catchThrowable(
                () -> create().transcribe(new SpeechToText.Request(audio(), Language.AUTO)));
        assertThat(t).isInstanceOf(ProviderException.class);
        return (ProviderException) t;
    }

    @Test
    void sendsModelFormatAndFileAndOmitsLanguageForAuto() {
        arrangeSuccess("Hi", Language.EN);
        var result = create().transcribe(new SpeechToText.Request(audio(), Language.AUTO));
        assertThat(result.audioDuration()).isEqualTo(Duration.ofMillis(2500));
        wm.verify(postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(containing("name=\"model\""))
                .withRequestBody(containing("Systran/faster-whisper-small"))
                .withRequestBody(containing("verbose_json"))
                .withRequestBody(containing("name=\"file\"; filename=\"entry.webm\""))
                .withRequestBody(notContaining("name=\"language\"")));
    }

    @Test
    void sendsLanguageWhenGiven() {
        arrangeSuccess("Hallo", Language.DE);
        create().transcribe(new SpeechToText.Request(audio(), Language.DE));
        wm.verify(postRequestedFor(urlEqualTo(PATH)).withRequestBody(containing("name=\"language\"")));
    }

    @Test
    void clientErrorIsRejectedAndNotRetryable() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(422)));
        ProviderException e = failure();
        assertThat(e.kind()).isEqualTo(Kind.REJECTED);
        assertThat(e.retryable()).isFalse();
    }

    @Test
    void serverErrorIsRetryableErrorResponse() {
        arrangeServerError();
        ProviderException e = failure();
        assertThat(e.kind()).isEqualTo(Kind.ERROR_RESPONSE);
        assertThat(e.retryable()).isTrue();
        assertThat(e.getMessage()).doesNotContain("boom");
    }

    @Test
    void unparseableAnswerIsBadResponse() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody("<html>nope</html>")));
        assertThat(failure().kind()).isEqualTo(Kind.BAD_RESPONSE);
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(okJson("{\"nothing\":1}")));
        assertThat(failure().kind()).isEqualTo(Kind.BAD_RESPONSE);
    }

    @Test
    void timeoutIsUnreachable() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody("{\"text\":\"x\"}").withFixedDelay(2000)));
        WhisperHttpSpeechToText p = new WhisperHttpSpeechToText();
        p.baseUrl = "http://localhost:" + port;
        p.timeout = Duration.ofMillis(200);
        assertThatThrownBy(() -> p.transcribe(new SpeechToText.Request(audio(), Language.AUTO)))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UNREACHABLE));
    }

    @Test
    void validatesUrlSyntaxWithoutNetwork() {
        WhisperHttpSpeechToText p = new WhisperHttpSpeechToText();
        List<String> errors = new ArrayList<>();
        p.validateConfiguration(errors);
        assertThat(errors).isEmpty();
        p.baseUrl = "not a url";
        p.validateConfiguration(errors);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("journal.stt.whisper.base-url");
    }
}
