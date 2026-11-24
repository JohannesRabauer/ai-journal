package dev.rabauer.ai.diary.spi.tts;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.spi.ProviderException.Kind;
import dev.rabauer.ai.diary.spi.SelfValidating;
import dev.rabauer.ai.diary.spi.TextToSpeech;
import dev.rabauer.ai.diary.spi.contract.TextToSpeechContract;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PiperTextToSpeechTest extends TextToSpeechContract {

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

    @Override
    protected TextToSpeech create() {
        return piper();
    }

    private PiperTextToSpeech piper() {
        PiperTextToSpeech p = new PiperTextToSpeech();
        p.baseUrl = "http://localhost:" + port + "/";
        p.timeout = Duration.ofSeconds(5);
        return p;
    }

    @Override
    protected void arrangeAudio(byte[] audio, String contentType) {
        wm.stubFor(post(urlEqualTo("/synthesize")).willReturn(aResponse().withHeader("Content-Type", contentType).withBody(audio)));
    }

    @Override
    protected void arrangeUnreachable() {
        wm.stop();
    }

    @Test
    void sendsLanguageDefaultVoiceAndExplicitVoice() {
        arrangeAudio(new byte[]{1, 2}, "audio/wav");
        piper().synthesize(new TextToSpeech.Request("Wie war dein Tag?", Language.DE, null));
        piper().synthesize(new TextToSpeech.Request("How was your day?", Language.EN, null));
        piper().synthesize(new TextToSpeech.Request("Hi", Language.EN, "en_US-amy-medium"));
        wm.verify(postRequestedFor(urlEqualTo("/synthesize"))
                .withRequestBody(equalToJson("{\"text\":\"Wie war dein Tag?\",\"voice\":\"de_DE-thorsten-medium\"}")));
        wm.verify(postRequestedFor(urlEqualTo("/synthesize"))
                .withRequestBody(equalToJson("{\"text\":\"How was your day?\",\"voice\":\"en_US-lessac-medium\"}")));
        wm.verify(postRequestedFor(urlEqualTo("/synthesize"))
                .withRequestBody(equalToJson("{\"text\":\"Hi\",\"voice\":\"en_US-amy-medium\"}")));
    }

    @Test
    void returnsContentTypeAsDelivered() {
        arrangeAudio(new byte[]{1}, "audio/wav");
        assertThat(piper().synthesize(new TextToSpeech.Request("x", Language.EN, null)).contentType()).isEqualTo("audio/wav");
    }

    @Test
    void errorStatusesAreMapped() {
        assertKind(503, Kind.ERROR_RESPONSE);
        assertKind(429, Kind.ERROR_RESPONSE);
        assertKind(400, Kind.REJECTED);
        assertKind(404, Kind.REJECTED);
    }

    private void assertKind(int status, Kind kind) {
        wm.stubFor(post(urlEqualTo("/synthesize")).willReturn(aResponse().withStatus(status)));
        assertThatThrownBy(() -> piper().synthesize(new TextToSpeech.Request("x", Language.EN, null)))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(kind));
    }

    @Test
    void emptyOrJsonBodyIsBadResponse() {
        wm.stubFor(post(urlEqualTo("/synthesize")).willReturn(aResponse().withHeader("Content-Type", "audio/wav")));
        assertThatThrownBy(() -> piper().synthesize(new TextToSpeech.Request("x", Language.EN, null)))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(Kind.BAD_RESPONSE));
        wm.stubFor(post(urlEqualTo("/synthesize")).willReturn(okJson("{\"error\":\"x\"}")));
        assertThatThrownBy(() -> piper().synthesize(new TextToSpeech.Request("x", Language.EN, null)))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(Kind.BAD_RESPONSE));
    }

    @Test
    void voicesAreMappedByPrefixAndCached() {
        wm.stubFor(get(urlEqualTo("/voices")).willReturn(okJson(
                "{\"voices\":[\"de_DE-thorsten-medium\",\"en_US-lessac-medium\",\"fr_FR-siwis-medium\"],\"default\":\"x\"}")));
        PiperTextToSpeech p = piper();
        var voices = p.voices();
        assertThat(voices).extracting(TextToSpeech.Voice::id).containsExactly("de_DE-thorsten-medium", "en_US-lessac-medium");
        assertThat(voices).extracting(TextToSpeech.Voice::language).containsExactly(Language.DE, Language.EN);
        p.voices();
        wm.verify(1, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlEqualTo("/voices")));
    }

    @Test
    void voicesNeverThrow() {
        wm.stubFor(get(urlEqualTo("/voices")).willReturn(aResponse().withStatus(500)));
        assertThat(piper().voices()).isEmpty();
        wm.stubFor(get(urlEqualTo("/voices")).willReturn(okJson("not json")));
        assertThat(piper().voices()).isEmpty();
        wm.stop();
        assertThat(piper().voices()).isEmpty();
    }

    @Test
    void validatesUrlWithoutNetwork() {
        PiperTextToSpeech p = piper();
        assertThat(p).isNotInstanceOf(dev.rabauer.ai.diary.spi.ExternalProvider.class);
        List<String> errors = new ArrayList<>();
        ((SelfValidating) p).validateConfiguration(errors);
        assertThat(errors).isEmpty();
        p.baseUrl = "piper:5000";
        ((SelfValidating) p).validateConfiguration(errors);
        assertThat(errors).hasSize(1);
        assertThat(wm.getAllServeEvents()).isEmpty();
    }
}
