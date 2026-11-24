package dev.rabauer.ai.diary.spi.tts;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.spi.ExternalProvider;
import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.spi.ProviderException.Kind;
import dev.rabauer.ai.diary.spi.TextToSpeech;
import dev.rabauer.ai.diary.spi.contract.TextToSpeechContract;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiTextToSpeechTest extends TextToSpeechContract {

    private static final String PATH = "/v1/audio/speech";
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
        wm.stop();
    }

    private OpenAiTextToSpeech openai() {
        OpenAiTextToSpeech p = new OpenAiTextToSpeech();
        p.baseUrl = "http://localhost:" + port + "/v1";
        p.apiKey = Optional.of("sk-test-key");
        p.timeout = Duration.ofSeconds(5);
        return p;
    }

    @Override
    protected TextToSpeech create() {
        return openai();
    }

    @Override
    protected void arrangeAudio(byte[] audio, String contentType) {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withHeader("Content-Type", contentType).withBody(audio)));
    }

    @Override
    protected void arrangeUnreachable() {
        wm.stop();
    }

    @Test
    void sendsBearerKeyAndMp3RequestBodyByDefault() {
        arrangeAudio(new byte[]{1, 2, 3}, "audio/mpeg");
        var audio = openai().synthesize(new TextToSpeech.Request("Hallo", Language.DE, null));
        assertThat(audio.contentType()).isEqualTo("audio/mpeg");
        wm.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer sk-test-key"))
                .withRequestBody(equalToJson(
                        "{\"model\":\"gpt-4o-mini-tts\",\"input\":\"Hallo\",\"voice\":\"alloy\",\"response_format\":\"mp3\"}")));
    }

    @Test
    void wavFormatAndExplicitVoice() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withBody(new byte[]{1})));
        OpenAiTextToSpeech p = openai();
        p.format = "wav";
        var audio = p.synthesize(new TextToSpeech.Request("Hi", Language.EN, "nova"));
        assertThat(audio.contentType()).isEqualTo("audio/wav");
        wm.verify(postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(equalToJson("{\"model\":\"gpt-4o-mini-tts\",\"input\":\"Hi\",\"voice\":\"nova\",\"response_format\":\"wav\"}")));
    }

    @Test
    void errorStatusesAreMapped() {
        assertKind(500, Kind.ERROR_RESPONSE);
        assertKind(429, Kind.ERROR_RESPONSE);
        assertKind(401, Kind.REJECTED);
        assertKind(400, Kind.REJECTED);
    }

    private void assertKind(int status, Kind kind) {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)));
        assertThatThrownBy(() -> openai().synthesize(new TextToSpeech.Request("x", Language.EN, null)))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(kind));
    }

    @Test
    void badBodyIsBadResponse() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{\"x\":1}")));
        assertThatThrownBy(() -> openai().synthesize(new TextToSpeech.Request("x", Language.EN, null)))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(Kind.BAD_RESPONSE));
    }

    @Test
    void missingApiKeyIsRejectedWithoutNetwork() {
        OpenAiTextToSpeech p = openai();
        p.apiKey = Optional.empty();
        assertThatThrownBy(() -> p.synthesize(new TextToSpeech.Request("x", Language.EN, null)))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(Kind.REJECTED));
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test
    void isExternalAndValidatesConfigWithoutNetwork() {
        OpenAiTextToSpeech p = openai();
        assertThat(p).isInstanceOf(ExternalProvider.class);
        List<String> errors = new ArrayList<>();
        p.validateConfiguration(errors);
        assertThat(errors).isEmpty();
        p.baseUrl = "ftp://x";
        p.format = "flac";
        p.validateConfiguration(errors);
        assertThat(errors).hasSize(2);
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test
    void voicesAreListedForBothLanguages() {
        assertThat(openai().voices()).extracting(TextToSpeech.Voice::language).contains(Language.DE, Language.EN);
    }
}
