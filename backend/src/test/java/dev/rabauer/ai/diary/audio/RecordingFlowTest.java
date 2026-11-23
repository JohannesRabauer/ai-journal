package dev.rabauer.ai.diary.audio;

import dev.rabauer.ai.diary.entry.EntryRepository;
import dev.rabauer.ai.diary.privacy.ExportSink;
import dev.rabauer.ai.diary.spi.AudioSource;
import dev.rabauer.ai.diary.support.Fakes;
import dev.rabauer.ai.diary.support.LogCapture;
import dev.rabauer.ai.diary.transcription.TranscriptionDataOwner;
import dev.rabauer.ai.diary.transcription.TranscriptionJobRepository;
import dev.rabauer.ai.diary.transcription.TranscriptionService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static dev.rabauer.ai.diary.audio.RecordingTestSupport.chunk;
import static dev.rabauer.ai.diary.audio.RecordingTestSupport.chunkIsDuplicate;
import static dev.rabauer.ai.diary.audio.RecordingTestSupport.complete;
import static dev.rabauer.ai.diary.audio.RecordingTestSupport.entry;
import static dev.rabauer.ai.diary.audio.RecordingTestSupport.entryIdOf;
import static dev.rabauer.ai.diary.audio.RecordingTestSupport.recordComplete;
import static dev.rabauer.ai.diary.audio.RecordingTestSupport.start;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/** Recording upload, resume, completion, retention and data ownership with fake providers (no network). */
@QuarkusTest
class RecordingFlowTest {

    private static final String MARKER = "ZEBRA-PRIVATE-4412";

    @Inject
    RecordingRepository recordings;
    @Inject
    TranscriptionJobRepository jobs;
    @Inject
    EntryRepository entries;
    @Inject
    TranscriptionService transcriptions;
    @Inject
    RecordingDataOwner recordingOwner;
    @Inject
    TranscriptionDataOwner jobOwner;

    @BeforeEach
    void clean() {
        Fakes.reset();
        RecordingTestSupport.clearAll(recordings, jobs, entries);
    }

    @AfterEach
    void settle() {
        transcriptions.awaitIdle(Duration.ofSeconds(10));
        given().contentType(ContentType.JSON).body("{\"keepAudio\":true}").put("/api/settings").then().statusCode(200);
    }

    @Test
    void fullFlowWithOutOfOrderAndDuplicateChunks() {
        try (LogCapture logs = new LogCapture()) {
            var started = start("{\"mimeType\":\"audio/webm;codecs=opus\",\"language\":\"DE\"}");
            UUID id = UUID.fromString(started.getString("id"));
            long entryId = started.getLong("entryId");
            assertThat(started.getString("status")).isEqualTo("RECORDING");
            assertThat(started.getList("receivedChunks")).isEmpty();
            given().get("/api/entries/" + entryId).then().statusCode(200)
                    .body("status", equalTo("RECORDING")).body("source", equalTo("VOICE"))
                    .body("language", equalTo("DE")).body("recordingId", equalTo(id.toString()));

            chunk(id, 2, "C", 200);
            chunk(id, 0, "A", 200);
            assertThat(chunkIsDuplicate(id, 0, "A")).isTrue();
            assertThat(chunkIsDuplicate(id, 1, "B")).isFalse();

            given().get("/api/recordings/" + id).then().statusCode(200)
                    .body("receivedChunks", hasItems(0, 1, 2)).body("status", equalTo("RECORDING"));

            complete(id, 3).statusCode(200).body("status", equalTo("COMPLETE")).body("totalChunks", equalTo(3))
                    .body("durationMs", equalTo(4200)).body("sizeBytes", equalTo(3));
            transcriptions.awaitIdle(Duration.ofSeconds(10));

            var e = entry(entryId);
            assertThat(e.getString("status")).isEqualTo("TRANSCRIBED");
            assertThat(e.getString("text")).isEqualTo("Fake transcript.");
            assertThat(Fakes.stt().requests()).hasSize(1);
            assertThat(Fakes.stt().requests().get(0).language().name()).isEqualTo("DE");
            given().get("/api/entries/" + entryId + "/transcriptions/latest").then().statusCode(200)
                    .body("status", equalTo("SUCCEEDED")).body("attempts", equalTo(1)).body("requestedLanguage", equalTo("DE"))
                    .body("detectedLanguage", equalTo("DE")).body("errorCode", nullValue()).body("recordingId", equalTo(id.toString()));

            byte[] audio = given().get("/api/recordings/" + id + "/audio").then().statusCode(200)
                    .contentType(containsString("audio/webm")).extract().asByteArray();
            assertThat(new String(audio)).isEqualTo("ABC");

            // chunk after completion is rejected, completing again is idempotent and does not queue a second job
            chunk(id, 3, "D", 409);
            complete(id, 3).statusCode(200);
            transcriptions.awaitIdle(Duration.ofSeconds(10));
            assertThat(Fakes.stt().requests()).hasSize(1);

            assertThat(Fakes.llm().calls()).isEmpty();
            assertThat(logs.all()).doesNotContain("Fake transcript");
        }
    }

    @Test
    void completeWithMissingChunkIs409ListingTheIndexAndChangesNothing() {
        UUID id = UUID.fromString(start().getString("id"));
        long entryId = entryIdOf(id);
        chunk(id, 0, MARKER + "A", 200);
        chunk(id, 2, MARKER + "C", 200);

        complete(id, 3).statusCode(409).body("message", containsString("1")).body("message", not(containsString(MARKER)))
                .body("code", equalTo("CHUNKS_MISSING"));
        assertThat(entry(entryId).getString("status")).isEqualTo("RECORDING");
        assertThat(Fakes.stt().requests()).isEmpty();

        // the client uploads the missing chunk and completes
        chunk(id, 1, "B", 200);
        complete(id, 3).statusCode(200);
        transcriptions.awaitIdle(Duration.ofSeconds(10));
        assertThat(entry(entryId).getString("status")).isEqualTo("TRANSCRIBED");
        assertThat(Fakes.llm().calls()).isEmpty();
    }

    @Test
    void interruptedUploadCanBeResumedFromServerState() {
        UUID id = UUID.fromString(start().getString("id"));
        chunk(id, 0, "A", 200);
        chunk(id, 1, "B", 200);
        // "page reload": the client only knows the recording id and asks what the server has
        var state = given().get("/api/recordings/" + id).then().statusCode(200).extract().jsonPath();
        assertThat(state.getList("receivedChunks", Integer.class)).containsExactly(0, 1);
        assertThat(state.getString("status")).isEqualTo("RECORDING");
        chunk(id, 2, "C", 200);
        complete(id, 3).statusCode(200);
        assertThat(new String(given().get("/api/recordings/" + id + "/audio").asByteArray())).isEqualTo("ABC");
    }

    @Test
    void startOnExistingEntryAndValidation() {
        long entryId = given().contentType(ContentType.JSON).body("{\"text\":\"vorhanden\"}").post("/api/entries")
                .then().statusCode(200).extract().jsonPath().getLong("id");
        var p = start("{\"entryId\":" + entryId + "}");
        assertThat(p.getLong("entryId")).isEqualTo(entryId);
        assertThat(p.getString("mimeType")).isEqualTo("audio/webm");

        given().contentType(ContentType.JSON).body("{\"entryId\":999999}").post("/api/recordings").then().statusCode(404);
        given().contentType(ContentType.JSON).body("{\"mimeType\":\"not a mime\"}").post("/api/recordings").then().statusCode(400);
        given().get("/api/recordings/" + UUID.randomUUID()).then().statusCode(404);
        chunk(UUID.fromString(p.getString("id")), -1, "x", 400);
        given().contentType(ContentType.JSON).body("{\"totalChunks\":0,\"durationMs\":0}")
                .post("/api/recordings/" + p.getString("id") + "/complete").then().statusCode(400);
    }

    @Test
    void deleteAudioKeepsEntryAndTranscript() {
        UUID id = recordComplete("{}", "A", "B");
        transcriptions.awaitIdle(Duration.ofSeconds(10));
        long entryId = entryIdOf(id);

        given().delete("/api/recordings/" + id + "/audio").then().statusCode(200).body("status", equalTo("AUDIO_DELETED"));
        assertThat(Fakes.audio().hasAudio(id)).isFalse();
        given().get("/api/recordings/" + id + "/audio").then().statusCode(404);
        var e = entry(entryId);
        assertThat(e.getString("text")).isEqualTo("Fake transcript.");
        assertThat(e.getString("recordingId")).isEqualTo(id.toString());
        chunk(id, 5, "x", 409);
    }

    @Test
    void keepAudioFalseDeletesAudioWhenEntryIsFinished() {
        given().contentType(ContentType.JSON).body("{\"keepAudio\":false}").put("/api/settings").then().statusCode(200);
        UUID id = recordComplete("{}", "A");
        transcriptions.awaitIdle(Duration.ofSeconds(10));
        long entryId = entryIdOf(id);
        // not deleted before the user confirms (finishes) the entry
        assertThat(Fakes.audio().hasAudio(id)).isTrue();

        given().post("/api/entries/" + entryId + "/finish").then().statusCode(200).body("status", equalTo("FINISHED"));
        assertThat(Fakes.audio().hasAudio(id)).isFalse();
        given().get("/api/recordings/" + id).then().statusCode(200).body("status", equalTo("AUDIO_DELETED"));
        assertThat(entry(entryId).getString("text")).isEqualTo("Fake transcript.");
    }

    @Test
    void deletingTheEntryRemovesRecordingJobsAndAudio() {
        UUID id = recordComplete("{}", "A", "B");
        transcriptions.awaitIdle(Duration.ofSeconds(10));
        long entryId = entryIdOf(id);
        given().get("/api/entries/" + entryId + "/transcriptions/latest").then().statusCode(200);

        given().delete("/api/entries/" + entryId).then().statusCode(204);

        assertThat(Fakes.audio().hasAudio(id)).isFalse();
        given().get("/api/recordings/" + id).then().statusCode(404);
    }

    @Test
    void transcriptionRoutesUnderEntriesAreReachable() {
        UUID id = recordComplete("{}", "A");
        transcriptions.awaitIdle(Duration.ofSeconds(10));
        long entryId = entryIdOf(id);
        long jobId = given().get("/api/entries/" + entryId + "/transcriptions/latest").then().statusCode(200)
                .extract().jsonPath().getLong("id");
        given().get("/api/transcriptions/" + jobId).then().statusCode(200).body("entryId", equalTo((int) entryId));
        given().get("/api/entries/" + entryId + "/conversations").then().statusCode(200);
    }

    @Test
    void keepAudioTrueKeepsAudioOnFinish() {
        UUID id = recordComplete("{}", "A");
        transcriptions.awaitIdle(Duration.ofSeconds(10));
        given().post("/api/entries/" + entryIdOf(id) + "/finish").then().statusCode(200);
        assertThat(Fakes.audio().hasAudio(id)).isTrue();
        given().get("/api/recordings/" + id).then().statusCode(200).body("status", equalTo("COMPLETE"));
    }

    @Test
    void dataOwnersExportAndDeleteEverything() throws Exception {
        UUID a = recordComplete("{}", "AA", "BB");
        UUID b = recordComplete("{}", "CC");
        transcriptions.awaitIdle(Duration.ofSeconds(10));
        // an unfinished upload counts as well
        UUID c = UUID.fromString(start().getString("id"));
        chunk(c, 0, "partial", 200);

        List<String> jsonPaths = new ArrayList<>();
        List<String> binaries = new ArrayList<>();
        ByteArrayOutputStream audioA = new ByteArrayOutputStream();
        recordingOwner.export(sink(true, jsonPaths, binaries, a, audioA));
        jobOwner.export(sink(true, jsonPaths, binaries, a, new ByteArrayOutputStream()));
        assertThat(jsonPaths).containsExactlyInAnyOrder("recordings.json", "transcription-jobs.json");
        assertThat(binaries).containsExactlyInAnyOrder("audio/" + a + ".webm", "audio/" + b + ".webm");
        assertThat(audioA.toString()).isEqualTo("AABB");

        List<String> noAudio = new ArrayList<>();
        recordingOwner.export(sink(false, new ArrayList<>(), noAudio, a, new ByteArrayOutputStream()));
        assertThat(noAudio).isEmpty();

        assertThat(jobOwner.deleteAll()).isEqualTo(2);
        assertThat(recordingOwner.deleteAll()).isEqualTo(3);
        assertThat(Fakes.audio().hasAudio(a)).isFalse();
        assertThat(Fakes.audio().hasAudio(b)).isFalse();
        assertThat(Fakes.audio().chunkIndices(c)).isEmpty();
        assertThat(QuarkusTransaction.requiringNew().call(() -> recordings.count() + jobs.count())).isZero();
        assertThat(recordingOwner.deleteOrder()).isBetween(100, 900);
        assertThat(jobOwner.deleteOrder()).isBetween(100, 900);
    }

    private static ExportSink sink(boolean includeAudio, List<String> jsonPaths, List<String> binaryPaths, UUID capture,
                                   ByteArrayOutputStream captured) {
        return new ExportSink() {
            @Override
            public void json(String path, Object value) {
                jsonPaths.add(path);
            }

            @Override
            public void binary(String path, AudioSource.Opener content) throws java.io.IOException {
                binaryPaths.add(path);
                if (path.contains(capture.toString())) {
                    try (var in = content.open()) {
                        in.transferTo(captured);
                    }
                }
            }

            @Override
            public boolean includeAudio() {
                return includeAudio;
            }
        };
    }
}
