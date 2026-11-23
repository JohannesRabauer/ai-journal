package dev.rabauer.ai.diary.audio;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;

import java.util.UUID;

import static io.restassured.RestAssured.given;

/** REST helpers shared by the voice-capture tests. */
public final class RecordingTestSupport {

    private RecordingTestSupport() {
    }

    public static JsonPath start(String body) {
        return given().contentType(ContentType.JSON).body(body).post("/api/recordings")
                .then().statusCode(200).extract().jsonPath();
    }

    public static JsonPath start() {
        return start("{\"mimeType\":\"audio/webm;codecs=opus\"}");
    }

    public static void chunk(UUID recording, int index, String content, int expectedStatus) {
        given().contentType("application/octet-stream").body(content.getBytes())
                .put("/api/recordings/" + recording + "/chunks/" + index)
                .then().statusCode(expectedStatus);
    }

    public static boolean chunkIsDuplicate(UUID recording, int index, String content) {
        return given().contentType("application/octet-stream").body(content.getBytes())
                .put("/api/recordings/" + recording + "/chunks/" + index)
                .then().statusCode(200).extract().jsonPath().getBoolean("duplicate");
    }

    public static io.restassured.response.ValidatableResponse complete(UUID recording, int totalChunks) {
        return given().contentType(ContentType.JSON).body("{\"totalChunks\":" + totalChunks + ",\"durationMs\":4200}")
                .post("/api/recordings/" + recording + "/complete").then();
    }

    /** Starts a recording, uploads the given chunk contents in order and completes it. @return recording id */
    public static UUID recordComplete(String startBody, String... chunks) {
        JsonPath p = start(startBody);
        UUID id = UUID.fromString(p.getString("id"));
        for (int i = 0; i < chunks.length; i++) {
            chunk(id, i, chunks[i], 200);
        }
        complete(id, chunks.length).statusCode(200);
        return id;
    }

    public static long entryIdOf(UUID recording) {
        return given().get("/api/recordings/" + recording).then().statusCode(200).extract().jsonPath().getLong("entryId");
    }

    public static JsonPath entry(long id) {
        return given().get("/api/entries/" + id).then().statusCode(200).extract().jsonPath();
    }

    /** Removes every row of the voice-capture tables and all entries. */
    public static void clearAll(RecordingRepository recordings,
                                dev.rabauer.ai.diary.transcription.TranscriptionJobRepository jobs,
                                dev.rabauer.ai.diary.entry.EntryRepository entries) {
        QuarkusTransaction.requiringNew().run(() -> {
            jobs.deleteAll();
            recordings.deleteAll();
            entries.deleteAll();
        });
    }
}
