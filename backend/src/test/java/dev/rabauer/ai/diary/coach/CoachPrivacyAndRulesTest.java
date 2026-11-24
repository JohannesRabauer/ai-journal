package dev.rabauer.ai.diary.coach;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rabauer.ai.diary.audio.RecordingRepository;
import dev.rabauer.ai.diary.audio.RecordingTestSupport;
import dev.rabauer.ai.diary.entry.EntryIndexer;
import dev.rabauer.ai.diary.entry.EntryRepository;
import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.support.Fakes;
import dev.rabauer.ai.diary.support.LogCapture;
import dev.rabauer.ai.diary.transcription.TranscriptionJobRepository;
import dev.rabauer.ai.diary.transcription.TranscriptionService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static dev.rabauer.ai.diary.coach.CoachTestSupport.answer;
import static dev.rabauer.ai.diary.coach.CoachTestSupport.answerOk;
import static dev.rabauer.ai.diary.coach.CoachTestSupport.createEntry;
import static dev.rabauer.ai.diary.coach.CoachTestSupport.finishedEntry;
import static dev.rabauer.ai.diary.coach.CoachTestSupport.opening;
import static dev.rabauer.ai.diary.coach.CoachTestSupport.start;
import static dev.rabauer.ai.diary.coach.CoachTestSupport.startOk;
import static dev.rabauer.ai.diary.coach.CoachTestSupport.turn;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/** Hard rule (no coach/LLM path from saving), data ownership (entry delete, export, delete-all) and log hygiene. */
@QuarkusTest
class CoachPrivacyAndRulesTest {

    private static final String MARKER = "ZEBRA-COACH-PRIVATE-5521";
    private static final String EN_ENTRY = "Today I was tired at work and I could not focus on the project that we have to finish.";

    @Inject
    CoachDataOwner owner;
    @Inject
    CoachMessageRepository messageRepository;
    @Inject
    CoachConversationRepository conversationRepository;
    @Inject
    EntryRepository entries;
    @Inject
    EntryIndexer indexer;
    @Inject
    RecordingRepository recordings;
    @Inject
    TranscriptionJobRepository jobs;
    @Inject
    TranscriptionService transcription;
    @Inject
    ObjectMapper mapper;

    @BeforeEach
    void clean() {
        Fakes.reset();
        owner.deleteAll();
        RecordingTestSupport.clearAll(recordings, jobs, entries);
    }

    @Test
    void savingRecordingAndTranscribingNeverCallsTheLlm() {
        long text = createEntry(EN_ENTRY, null);
        given().contentType(ContentType.JSON).body("{\"text\":\"" + EN_ENTRY + " More.\"}").put("/api/entries/" + text).then().statusCode(200);
        given().post("/api/entries/" + text + "/finish").then().statusCode(200);
        indexer.awaitIdle();

        UUID rec = RecordingTestSupport.recordComplete("{}", "audio-chunk");
        transcription.awaitIdle(Duration.ofSeconds(10));
        long voice = RecordingTestSupport.entryIdOf(rec);
        assertThat(RecordingTestSupport.entry(voice).getString("status")).isEqualTo("TRANSCRIBED");
        given().post("/api/entries/" + voice + "/finish").then().statusCode(200);
        indexer.awaitIdle();

        assertThat(Fakes.stt().requests()).isNotEmpty();
        assertThat(Fakes.llm().calls()).as("no coach or LLM call from create/update/finish/transcription").isEmpty();
        // and no conversation was started on anybody's behalf
        given().get("/api/entries/" + text + "/conversations").then().body("$", hasSize(0));
        given().get("/api/entries/" + voice + "/conversations").then().body("$", hasSize(0));
    }

    @Test
    void deletingAnEntryRemovesItsConversationsAndMessages() {
        long keep = finishedEntry(EN_ENTRY + " keep", null);
        long gone = finishedEntry(EN_ENTRY, null);
        Fakes.llm().enqueue(opening("Q1 what?", "Q2 why?")).enqueue(opening("Q3 what?", "Q4 why?"));
        long keepConv = startOk(keep).getLong("id");
        long goneConv = startOk(gone).getLong("id");
        assertThat(count("coach_message")).isEqualTo(4);

        given().delete("/api/entries/" + gone).then().statusCode(204);

        given().get("/api/conversations/" + goneConv).then().statusCode(404);
        given().get("/api/conversations/" + keepConv).then().statusCode(200);
        assertThat(count("coach_message")).as("planned and shown messages of the deleted entry are gone").isEqualTo(2);
        assertThat(count("coach_conversation")).isEqualTo(1);
    }

    private long count(String table) {
        return QuarkusTransaction.requiringNew().call(() -> table.equals("coach_message") ? messageRepository.count() : conversationRepository.count());
    }

    @Test
    void exportContainsConversationsAndDeleteAllRemovesThem() throws Exception {
        long entry = finishedEntry(EN_ENTRY, null);
        Fakes.llm().enqueue(opening("Q1 " + MARKER + " what?", "Q2 why?")).enqueue(turn("FOLLOW_UP_QUESTION", "What exactly?"));
        long id = startOk(entry).getLong("id");
        answerOk(id, "An answer.");

        byte[] zip = given().get("/api/data/export?includeAudio=false").then().statusCode(200).extract().asByteArray();
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                files.put(e.getName(), in.readAllBytes());
            }
        }
        assertThat(files).containsKey("coach/conversations.json");
        JsonNode root = mapper.readTree(files.get("coach/conversations.json"));
        assertThat(root).hasSize(1);
        JsonNode conv = root.get(0).get("conversation");
        assertThat(conv.get("entryId").asLong()).isEqualTo(entry);
        assertThat(conv.get("messages")).hasSize(3);
        assertThat(conv.get("messages").get(0).get("createdAt").asText()).isNotBlank();
        assertThat(root.get(0).get("unrevealedOpeningQuestions")).hasSize(1);
        assertThat(new String(files.get("coach/conversations.json"), java.nio.charset.StandardCharsets.UTF_8)).contains(MARKER);
        assertThat(mapper.readTree(files.get("manifest.json")).get("owners").toString()).contains("\"coach\"");

        var result = given().contentType(ContentType.JSON).body("{\"confirmation\":\"DELETE EVERYTHING\"}")
                .post("/api/data/delete-all").then().statusCode(200).extract().jsonPath();
        assertThat(result.getLong("deleted.coach")).isEqualTo(5); // 4 messages (one planned, never shown) + 1 conversation
        assertThat(count("coach_message")).isZero();
        assertThat(count("coach_conversation")).isZero();
    }

    @Test
    void noEntryOrAnswerTextReachesTheLogsOnFailurePaths() {
        try (LogCapture logs = new LogCapture()) {
            long entry = finishedEntry(EN_ENTRY + " " + MARKER, null);
            // invalid output on start (marker also inside the raw model output)
            String bad = opening("Only " + MARKER + "?");
            Fakes.llm().enqueue(bad).enqueue(bad).enqueue(bad);
            start(entry).then().statusCode(502);

            // provider failure on start
            Fakes.llm().enqueueFailure(new ProviderException("ollama", ProviderException.Kind.UNREACHABLE, "down"));
            start(entry).then().statusCode(503);

            // success, then invalid and failing turns with the marker in the answer
            Fakes.llm().enqueue(opening("What stayed with you?", "What do you need?"));
            long id = startOk(entry).getLong("id");
            String praise = turn("FOLLOW_UP_QUESTION", "Great insight about " + MARKER + "! What next?");
            Fakes.llm().enqueue(praise).enqueue(praise).enqueue(praise);
            answer(id, "My answer " + MARKER, false).then().statusCode(502);
            Fakes.llm().enqueueFailure(new ProviderException("ollama", ProviderException.Kind.ERROR_RESPONSE, "500"));
            answer(id, "Another " + MARKER, false).then().statusCode(503);

            // crisis path and retrieval
            answer(id, "I want to kill myself " + MARKER, false).then().statusCode(200);
            Fakes.stt().respondWith(r -> {
                throw new ProviderException("whisper", ProviderException.Kind.UNREACHABLE, "down");
            });
            given().contentType("audio/webm").body((MARKER).getBytes()).post("/api/transcribe").then().statusCode(503);

            assertThat(logs.all()).doesNotContain(MARKER);
            assertThat(logs.all()).doesNotContain("kill myself");
        }
    }

    @Test
    void conversationJsonShapeIsStable() {
        long entry = finishedEntry(EN_ENTRY, null);
        Fakes.llm().enqueue(opening("Q1 what?", "Q2 why?"));
        JsonPath c = startOk(entry);
        assertThat(c.getMap("")).containsKeys("id", "entryId", "status", "language", "messages", "startedAt", "updatedAt", "endedAt");
        given().get("/api/conversations/" + c.getLong("id")).then().body("status", equalTo("ACTIVE"));
    }
}
