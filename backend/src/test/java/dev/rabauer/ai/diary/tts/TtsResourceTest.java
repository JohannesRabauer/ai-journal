package dev.rabauer.ai.diary.tts;

import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.privacy.UserDataOwner;
import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.spi.TextToSpeech;
import dev.rabauer.ai.diary.support.Fakes;
import dev.rabauer.ai.diary.support.LogCapture;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

@QuarkusTest
class TtsResourceTest {

    @Inject
    TtsCache cache;
    @Inject
    @Any
    Instance<UserDataOwner> owners;

    private static long calls(String text) {
        return Fakes.tts().requests().stream().filter(r -> r.text().equals(text)).count();
    }

    private static String unique(String base) {
        return base + " " + UUID.randomUUID();
    }

    private static String body(String text, String lang) {
        return "{\"text\":\"" + text + "\",\"language\":\"" + lang + "\"}";
    }

    @BeforeEach
    void clean() {
        cache.clear();
    }

    @AfterEach
    void restoreFake() {
        Fakes.tts().respondWith(r -> new TextToSpeech.Audio(("FAKEWAV:" + r.text().length()).getBytes(StandardCharsets.US_ASCII), "audio/wav"));
        cache.clear();
    }

    @Test
    void returnsAudioWithProviderContentType() {
        String text = unique("Was war heute wichtig?");
        Fakes.tts().respondWith(r -> new TextToSpeech.Audio(new byte[]{9, 8, 7}, "audio/mpeg"));
        byte[] data = given().contentType(ContentType.JSON).body(body(text, "DE")).post("/api/tts")
                .then().statusCode(200).contentType("audio/mpeg").extract().asByteArray();
        assertThat(data).containsExactly(9, 8, 7);
        assertThat(calls(text)).isEqualTo(1);
    }

    @Test
    void cacheHitDoesNotCallTheProviderAgain() {
        String text = unique("Wie war dein Tag?");
        given().contentType(ContentType.JSON).body(body(text, "DE")).post("/api/tts").then().statusCode(200).contentType("audio/wav");
        given().contentType(ContentType.JSON).body(body(text, "DE")).post("/api/tts").then().statusCode(200).contentType("audio/wav");
        assertThat(calls(text)).isEqualTo(1);
        // another language is another cache entry
        given().contentType(ContentType.JSON).body(body(text, "EN")).post("/api/tts").then().statusCode(200);
        assertThat(calls(text)).isEqualTo(2);
    }

    @Test
    void autoLanguageIsResolved() {
        String text = unique("Was war heute wichtig für dich?");
        given().contentType(ContentType.JSON).body("{\"text\":\"" + text + "\"}").post("/api/tts").then().statusCode(200);
        assertThat(Fakes.tts().requests()).filteredOn(r -> r.text().equals(text)).extracting(TextToSpeech.Request::language)
                .containsExactly(Language.DE);
    }

    @Test
    void providerFailureAnswersNoContent() {
        for (var kind : ProviderException.Kind.values()) {
            Fakes.tts().respondWith(r -> {
                throw new ProviderException("fake", kind, "down");
            });
            given().contentType(ContentType.JSON).body(body(unique("Hi?"), "EN")).post("/api/tts").then().statusCode(204);
        }
    }

    @Test
    void failureIsNotCached() {
        String text = unique("Retry me?");
        Fakes.tts().respondWith(r -> {
            throw new ProviderException("fake", ProviderException.Kind.UNREACHABLE, "down");
        });
        given().contentType(ContentType.JSON).body(body(text, "EN")).post("/api/tts").then().statusCode(204);
        Fakes.tts().respondWith(r -> new TextToSpeech.Audio(new byte[]{1}, "audio/wav"));
        given().contentType(ContentType.JSON).body(body(text, "EN")).post("/api/tts").then().statusCode(200);
    }

    @Test
    void invalidTextIsRejected() {
        given().contentType(ContentType.JSON).body(body("   ", "EN")).post("/api/tts").then().statusCode(400);
        given().contentType(ContentType.JSON).body("{}").post("/api/tts").then().statusCode(400);
        given().contentType(ContentType.JSON).body(body("x".repeat(1001), "EN")).post("/api/tts").then().statusCode(400)
                .body("code", equalTo("TEXT_TOO_LONG"));
        given().contentType(ContentType.JSON).body(body("x".repeat(1000), "EN")).post("/api/tts").then().statusCode(200);
    }

    @Test
    void voicesEndpointMapsProviderVoices() {
        given().get("/api/tts/voices").then().statusCode(200)
                .body("id", hasItem("fake-de")).body("language", hasItem("DE")).body("label", hasItem("Fake (en)"));
    }

    @Test
    void spokenTextIsNeverLogged() {
        String marker = "ZEBRA-SPOKEN-4471";
        try (LogCapture logs = new LogCapture()) {
            given().contentType(ContentType.JSON).body(body(marker + " ok", "EN")).post("/api/tts").then().statusCode(200);
            Fakes.tts().respondWith(r -> {
                throw new ProviderException("fake", ProviderException.Kind.ERROR_RESPONSE, "HTTP 500");
            });
            given().contentType(ContentType.JSON).body(body(marker + " fail", "EN")).post("/api/tts").then().statusCode(204);
            given().contentType(ContentType.JSON).body(body("x".repeat(1001) + marker, "EN")).post("/api/tts").then().statusCode(400);
            assertThat(logs.all()).doesNotContain(marker);
        }
    }

    @Test
    void deleteAllClearsTheCache() {
        given().contentType(ContentType.JSON).body(body(unique("Cached?"), "EN")).post("/api/tts").then().statusCode(200);
        assertThat(cache.size()).isEqualTo(1);
        UserDataOwner owner = owners.stream().filter(o -> o.name().equals("tts-cache")).findFirst().orElseThrow();
        assertThat(owner.deleteOrder()).isEqualTo(2000);
        assertThat(owner.deleteAll()).isEqualTo(1);
        assertThat(cache.size()).isZero();
    }
}
