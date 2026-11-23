package dev.rabauer.ai.diary.transcription;

import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.audio.RecordingRepository;
import dev.rabauer.ai.diary.audio.RecordingTestSupport;
import dev.rabauer.ai.diary.entry.EntryRepository;
import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.spi.ProviderException.Kind;
import dev.rabauer.ai.diary.spi.SpeechToText;
import dev.rabauer.ai.diary.support.Fakes;
import dev.rabauer.ai.diary.support.LogCapture;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.rabauer.ai.diary.audio.RecordingTestSupport.entry;
import static dev.rabauer.ai.diary.audio.RecordingTestSupport.entryIdOf;
import static dev.rabauer.ai.diary.audio.RecordingTestSupport.recordComplete;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/** Failure, retry, concurrency and restart behaviour of transcription jobs (fake STT, in-memory audio). */
@QuarkusTest
class TranscriptionJobTest {

    private static final String MARKER = "ZEBRA-PRIVATE-9051";

    @Inject
    TranscriptionService service;
    @Inject
    TranscriptionJobRepository jobs;
    @Inject
    RecordingRepository recordings;
    @Inject
    EntryRepository entries;

    @BeforeEach
    void clean() {
        Fakes.reset();
        RecordingTestSupport.clearAll(recordings, jobs, entries);
    }

    @AfterEach
    void settle() {
        service.awaitIdle(Duration.ofSeconds(10));
    }

    private void idle() {
        service.awaitIdle(Duration.ofSeconds(10));
    }

    @Test
    void unreachableProviderRetriesThenFailsGracefullyAndKeepsAudio() {
        AtomicInteger calls = new AtomicInteger();
        Fakes.stt().respondWith(r -> {
            calls.incrementAndGet();
            throw new ProviderException("whisper", Kind.UNREACHABLE, "connection refused");
        });
        try (LogCapture logs = new LogCapture()) {
            UUID rec = recordComplete("{}", MARKER + "-audio");
            idle();
            long entryId = entryIdOf(rec);

            assertThat(calls.get()).isEqualTo(3);
            given().get("/api/entries/" + entryId + "/transcriptions/latest").then().statusCode(200)
                    .body("status", equalTo("FAILED")).body("errorCode", equalTo("PROVIDER_UNREACHABLE"))
                    .body("attempts", equalTo(3)).body("finishedAt", notNullValue());
            var e = entry(entryId);
            assertThat(e.getString("status")).isEqualTo("TRANSCRIPTION_FAILED");
            assertThat(e.getString("text")).isEmpty();
            // never lose audio on failure
            assertThat(Fakes.audio().hasAudio(rec)).isTrue();
            given().get("/api/recordings/" + rec + "/audio").then().statusCode(200);
            assertThat(Fakes.llm().calls()).isEmpty();
            assertThat(logs.all()).doesNotContain(MARKER);
        }
    }

    @Test
    void manualRetrySucceedsAfterFailureAndAppendsToExistingText() {
        Fakes.stt().respondWith(r -> {
            throw new ProviderException("whisper", Kind.ERROR_RESPONSE, "HTTP 500");
        });
        long entryId = given().contentType(ContentType.JSON).body("{\"text\":\"" + MARKER + " Vorher\"}").post("/api/entries")
                .then().statusCode(200).extract().jsonPath().getLong("id");
        UUID rec = recordComplete("{\"entryId\":" + entryId + "}", "A");
        idle();
        given().get("/api/entries/" + entryId + "/transcriptions/latest").then().body("errorCode", equalTo("PROVIDER_ERROR"));
        assertThat(entry(entryId).getString("status")).isEqualTo("TRANSCRIPTION_FAILED");

        try (LogCapture logs = new LogCapture()) {
            Fakes.stt().respondWith(r -> new SpeechToText.Result(MARKER + " nachher", Language.DE, Duration.ofSeconds(3)));
            given().contentType(ContentType.JSON).body("{\"language\":\"DE\"}").post("/api/entries/" + entryId + "/transcriptions")
                    .then().statusCode(200).body("requestedLanguage", equalTo("DE")).body("recordingId", equalTo(rec.toString()));
            idle();
            var e = entry(entryId);
            assertThat(e.getString("status")).isEqualTo("TRANSCRIBED");
            assertThat(e.getString("text")).isEqualTo(MARKER + " Vorher\n\n" + MARKER + " nachher");
            assertThat(e.getString("language")).isEqualTo("DE");
            given().get("/api/entries/" + entryId + "/transcriptions/latest").then()
                    .body("status", equalTo("SUCCEEDED")).body("detectedLanguage", equalTo("DE"));
            assertThat(logs.all()).doesNotContain(MARKER);
        }
    }

    @Test
    void rejectedRequestFailsWithoutRetry() {
        AtomicInteger calls = new AtomicInteger();
        Fakes.stt().respondWith(r -> {
            calls.incrementAndGet();
            throw new ProviderException("whisper", Kind.REJECTED, "HTTP 422");
        });
        UUID rec = recordComplete("{}", "A");
        idle();
        assertThat(calls.get()).isEqualTo(1);
        given().get("/api/entries/" + entryIdOf(rec) + "/transcriptions/latest").then()
                .body("status", equalTo("FAILED")).body("errorCode", equalTo("PROVIDER_ERROR")).body("attempts", equalTo(1));
    }

    @Test
    void transientErrorIsRetriedAndSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        Fakes.stt().respondWith(r -> {
            if (calls.incrementAndGet() < 3) {
                throw new ProviderException("whisper", Kind.UNREACHABLE, "down");
            }
            return new SpeechToText.Result("endlich", Language.EN, Duration.ofSeconds(1));
        });
        UUID rec = recordComplete("{}", "A");
        idle();
        long entryId = entryIdOf(rec);
        given().get("/api/entries/" + entryId + "/transcriptions/latest").then()
                .body("status", equalTo("SUCCEEDED")).body("attempts", equalTo(3));
        assertThat(entry(entryId).getString("text")).isEqualTo("endlich");
    }

    @Test
    void secondStartWhileRunningIs409AndEntryWithoutRecordingIs409() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Fakes.stt().respondWith(r -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new SpeechToText.Result("fertig", Language.EN, Duration.ofSeconds(1));
        });
        UUID rec = recordComplete("{}", "A");
        long entryId = entryIdOf(rec);
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(entry(entryId).getString("status")).isEqualTo("TRANSCRIBING");

        given().contentType(ContentType.JSON).body("{}").post("/api/entries/" + entryId + "/transcriptions").then().statusCode(409)
                .body("code", equalTo("TRANSCRIPTION_RUNNING"));
        release.countDown();
        idle();
        assertThat(entry(entryId).getString("status")).isEqualTo("TRANSCRIBED");

        long textOnly = given().contentType(ContentType.JSON).body("{\"text\":\"nur text\"}").post("/api/entries")
                .then().statusCode(200).extract().jsonPath().getLong("id");
        given().contentType(ContentType.JSON).body("{}").post("/api/entries/" + textOnly + "/transcriptions").then().statusCode(409)
                .body("code", equalTo("NO_RECORDING"));
        given().get("/api/entries/" + textOnly + "/transcriptions/latest").then().statusCode(404);
        given().contentType(ContentType.JSON).body("{}").post("/api/entries/999999/transcriptions").then().statusCode(404);
    }

    @Test
    void recordingWithoutAssembledAudioFailsAsAudioMissing() {
        UUID rec = recordComplete("{}", "A");
        idle();
        long entryId = entryIdOf(rec);
        Fakes.audio().delete(rec); // audio vanished behind our back
        // direct enqueue (the API refuses to start without audio)
        service.enqueue(entryId, rec, Language.AUTO);
        idle();
        given().get("/api/entries/" + entryId + "/transcriptions/latest").then()
                .body("status", equalTo("FAILED")).body("errorCode", equalTo("AUDIO_MISSING"));
        assertThat(entry(entryId).getString("status")).isEqualTo("TRANSCRIPTION_FAILED");
        given().contentType(ContentType.JSON).body("{}").post("/api/entries/" + entryId + "/transcriptions").then().statusCode(409);
    }

    @Test
    void unfinishedJobsAreRequeuedAtStartup() {
        UUID rec = recordComplete("{}", "A");
        idle();
        long entryId = entryIdOf(rec);
        long jobId = given().get("/api/entries/" + entryId + "/transcriptions/latest").then().extract().jsonPath().getLong("id");
        // simulate a crash mid-transcription
        QuarkusTransaction.requiringNew().run(() -> {
            var j = jobs.findById(jobId);
            j.status = dev.rabauer.ai.diary.api.transcription.TranscriptionStatus.RUNNING;
            j.finishedAt = null;
        });
        Fakes.stt().reset();
        service.onStart(null);
        idle();
        given().get("/api/transcriptions/" + jobId).then().statusCode(200).body("status", equalTo("SUCCEEDED")).body("attempts", equalTo(2));
        assertThat(Fakes.stt().requests()).hasSize(1);
    }
}
