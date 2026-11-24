package dev.rabauer.ai.diary.coach;

import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.spi.SpeechToText;
import dev.rabauer.ai.diary.entry.EntryRepository;
import dev.rabauer.ai.diary.support.Fakes;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class TranscribeResourceTest {

    @Inject
    EntryRepository entries;

    @BeforeEach
    void reset() {
        Fakes.reset();
    }

    @Test
    void transcribesTheAudioAndReturnsTextAndLanguage() throws Exception {
        Fakes.stt().respondWith(r -> new SpeechToText.Result("Das ist meine Antwort.", Language.DE, Duration.ofSeconds(3)));
        byte[] audio = "pretend-webm-bytes".getBytes();

        given().contentType("audio/webm;codecs=opus").body(audio).post("/api/transcribe?language=DE")
                .then().statusCode(200).body("text", equalTo("Das ist meine Antwort.")).body("detectedLanguage", equalTo("DE"));

        assertThat(Fakes.stt().requests()).hasSize(1);
        SpeechToText.Request r = Fakes.stt().requests().get(0);
        assertThat(r.language()).isEqualTo(Language.DE);
        assertThat(r.audio().mimeType()).isEqualTo("audio/webm");
        assertThat(r.audio().sizeBytes()).isEqualTo(audio.length);
        assertThat(r.audio().open().readAllBytes()).isEqualTo(audio);
    }

    @Test
    void languageDefaultsToAutoAndNothingIsPersisted() {
        long entriesBefore = QuarkusTransaction.requiringNew().call(() -> entries.count());
        given().contentType("audio/ogg").body(new byte[]{1, 2, 3}).post("/api/transcribe")
                .then().statusCode(200).body("text", equalTo("Fake transcript.")).body("detectedLanguage", equalTo("EN"));
        assertThat(Fakes.stt().requests().get(0).language()).isEqualTo(Language.AUTO);
        assertThat(QuarkusTransaction.requiringNew().call(() -> entries.count())).isEqualTo(entriesBefore);
        assertThat(Fakes.llm().calls()).isEmpty();
    }

    @Test
    void audioOverTenMegabytesIsRejectedWith413() {
        byte[] tooBig = new byte[10 * 1024 * 1024 + 1];
        given().contentType("audio/webm").body(tooBig).post("/api/transcribe")
                .then().statusCode(413).body("code", equalTo("AUDIO_TOO_LARGE"));
        assertThat(Fakes.stt().requests()).isEmpty();
    }

    @Test
    void audioOfExactlyTenMegabytesIsAccepted() {
        given().contentType("audio/webm").body(new byte[10 * 1024 * 1024]).post("/api/transcribe").then().statusCode(200);
    }

    @Test
    void emptyAudioIsA400() {
        given().contentType("audio/webm").body(new byte[0]).post("/api/transcribe").then().statusCode(400).body("code", equalTo("AUDIO_EMPTY"));
    }

    @Test
    void providerFailureIs503() {
        Fakes.stt().respondWith(r -> {
            throw new ProviderException("whisper", ProviderException.Kind.UNREACHABLE, "connection refused");
        });
        given().contentType("audio/webm").body(new byte[]{1}).post("/api/transcribe")
                .then().statusCode(503).body("code", equalTo("PROVIDER_UNREACHABLE"));
    }
}
