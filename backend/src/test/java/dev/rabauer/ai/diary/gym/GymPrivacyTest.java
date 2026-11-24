package dev.rabauer.ai.diary.gym;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rabauer.ai.diary.entry.EntryRepository;
import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.support.Fakes;
import dev.rabauer.ai.diary.support.LogCapture;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static dev.rabauer.ai.diary.gym.GymTestSupport.EN_ENTRY;
import static dev.rabauer.ai.diary.gym.GymTestSupport.EN_EXCERPT;
import static dev.rabauer.ai.diary.gym.GymTestSupport.attemptOk;
import static dev.rabauer.ai.diary.gym.GymTestSupport.exerciseJson;
import static dev.rabauer.ai.diary.gym.GymTestSupport.feedbackJson;
import static dev.rabauer.ai.diary.gym.GymTestSupport.finishedEntry;
import static dev.rabauer.ai.diary.gym.GymTestSupport.nextOk;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;

/** Data ownership (entry delete, export, delete-all) and log hygiene of the gym. */
@QuarkusTest
class GymPrivacyTest {

    private static final String MARKER = "ZEBRAQUARTZ";
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    GymDataOwner owner;
    @Inject
    EntryRepository entries;
    @Inject
    GymExerciseRepository exercises;
    @Inject
    GymAttemptRepository attempts;

    @BeforeEach
    void clean() {
        Fakes.reset();
        owner.deleteAll();
        QuarkusTransaction.requiringNew().run(() -> entries.deleteAll());
    }

    private long count(boolean exercisesTable) {
        return QuarkusTransaction.requiringNew().call(() -> exercisesTable ? exercises.count() : attempts.count());
    }

    @Test
    void deletingAnEntryRemovesExercisesDrawnFromItWithTheirAttemptsAndKeepsTemplates() {
        long entry = finishedEntry(EN_ENTRY, "EN");
        Fakes.llm().enqueue(exerciseJson(EN_EXCERPT));
        long personalized = nextOk("ASSUMPTION_CHECK").getLong("id");
        long personalizedAttempt = attemptOk(personalized, "my answer on the entry").getLong("id");
        long template = nextOk("REFRAMING").getLong("id");
        attemptOk(template, "my answer on the template");
        assertThat(count(true)).isEqualTo(2);
        assertThat(count(false)).isEqualTo(2);

        given().delete("/api/entries/" + entry).then().statusCode(204);

        given().get("/api/gym/exercises/" + personalized).then().statusCode(404);
        given().post("/api/gym/attempts/" + personalizedAttempt + "/feedback").then().statusCode(404);
        given().get("/api/gym/exercises/" + template).then().statusCode(200);
        given().get("/api/gym/attempts").then().body("$", hasSize(1));
        assertThat(count(true)).isEqualTo(1);
        assertThat(count(false)).isEqualTo(1);
    }

    @Test
    void exportContainsExercisesAndAttemptsAndDeleteAllRemovesThem() throws Exception {
        long entry = finishedEntry(EN_ENTRY, "EN");
        Fakes.llm().enqueue(exerciseJson(EN_EXCERPT)).enqueue(feedbackJson("What stands out to you now?"));
        long id = nextOk("ASSUMPTION_CHECK").getLong("id");
        long attemptId = attemptOk(id, "An answer " + MARKER).getLong("id");
        given().post("/api/gym/attempts/" + attemptId + "/feedback").then().statusCode(200);
        nextOk("REFRAMING");

        byte[] zip = given().get("/api/data/export?includeAudio=false").then().statusCode(200).extract().asByteArray();
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                files.put(e.getName(), in.readAllBytes());
            }
        }
        assertThat(files).containsKeys("gym/exercises.json", "gym/attempts.json");
        JsonNode exportedExercises = mapper.readTree(files.get("gym/exercises.json"));
        assertThat(exportedExercises).hasSize(2);
        assertThat(exportedExercises.get(0).get("exercise").get("sourceEntryId").asLong()).isEqualTo(entry);
        assertThat(exportedExercises.get(0).get("createdAt").asText()).isNotBlank();
        assertThat(exportedExercises.get(1).get("templateId").asText()).isNotBlank();
        JsonNode exportedAttempts = mapper.readTree(files.get("gym/attempts.json"));
        assertThat(exportedAttempts).hasSize(1);
        assertThat(exportedAttempts.get(0).get("answer").asText()).contains(MARKER);
        assertThat(exportedAttempts.get(0).get("feedbackQuestions")).hasSize(1);
        assertThat(mapper.readTree(files.get("manifest.json")).get("owners").toString()).contains("\"gym\"");
        assertThat(new String(files.get("gym/attempts.json"), StandardCharsets.UTF_8)).contains(MARKER);

        JsonPath result = given().contentType(ContentType.JSON).body("{\"confirmation\":\"DELETE EVERYTHING\"}")
                .post("/api/data/delete-all").then().statusCode(200).extract().jsonPath();
        assertThat(result.getLong("deleted.gym")).isEqualTo(3); // 2 exercises + 1 attempt
        assertThat(count(true)).isZero();
        assertThat(count(false)).isZero();
        given().get("/api/gym/attempts").then().body("$", hasSize(0));
    }

    @Test
    void noEntryAnswerOrLlmTextReachesTheLogs() {
        try (LogCapture logs = new LogCapture()) {
            long entry = finishedEntry("I was sure that " + MARKER + " would never work out, so I gave up early on that day.", "EN");

            // invalid output (marker also inside the raw model output)
            String bad = exerciseJson("an invented passage with " + MARKER + " inside it");
            Fakes.llm().enqueue(bad).enqueue(bad);
            nextOk("ASSUMPTION_CHECK");

            // provider failure
            Fakes.llm().enqueueFailure(new ProviderException("ollama", ProviderException.Kind.UNREACHABLE, "down"));
            nextOk("REFRAMING");

            // success
            Fakes.llm().enqueue(exerciseJson("I was sure that " + MARKER + " would never work out"));
            long id = nextOk("BIAS_SPOTTING").getLong("id");

            // attempts, feedback success and failures with the marker in the answer
            long attemptId = attemptOk(id, "My answer about " + MARKER).getLong("id");
            String badQuestion = feedbackJson("Great job with " + MARKER + "! Tell me more.");
            Fakes.llm().enqueue(badQuestion).enqueue(badQuestion).enqueue(badQuestion);
            given().post("/api/gym/attempts/" + attemptId + "/feedback").then().statusCode(502);
            Fakes.llm().enqueueFailure(new ProviderException("ollama", ProviderException.Kind.ERROR_RESPONSE, "500"));
            given().post("/api/gym/attempts/" + attemptId + "/feedback").then().statusCode(503);
            Fakes.llm().enqueue(feedbackJson("What do you mean by " + MARKER + "?"));
            given().post("/api/gym/attempts/" + attemptId + "/feedback").then().statusCode(200);

            given().delete("/api/entries/" + entry).then().statusCode(204);

            assertThat(logs.all()).doesNotContain(MARKER);
            assertThat(logs.all()).doesNotContain("gave up early");
        }
    }
}
