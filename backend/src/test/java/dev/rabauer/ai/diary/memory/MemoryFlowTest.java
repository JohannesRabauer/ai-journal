package dev.rabauer.ai.diary.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rabauer.ai.diary.entry.EntryRepository;
import dev.rabauer.ai.diary.privacy.ExportSink;
import dev.rabauer.ai.diary.spi.AudioSource;
import dev.rabauer.ai.diary.support.Fakes;
import dev.rabauer.ai.diary.support.LogCapture;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/** Memory feature end to end against the fake LLM (SPEC section 6, acceptance 7 and 8). */
@QuarkusTest
class MemoryFlowTest {

    private static final String ENTRY_TEXT = "Today I learned that spaced repetition beats cramming.\n"
            + "I decided to review my notes every weekend, because   short sessions work better for me.";
    private static final String QUOTE = "spaced repetition beats cramming";
    private static final String PROMPT_1 = "What beats cramming?";
    private static final String ANSWER_1 = "ZEBRAANSWER-8841 spaced repetition";
    private static final String PROMPT_2 = "When do I review my notes?";
    private static final String ANSWER_2 = "Every weekend";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    MemoryDataOwner owner;
    @Inject
    EntryRepository entries;

    @BeforeEach
    void clean() {
        Fakes.reset();
        owner.deleteAll();
        QuarkusTransaction.requiringNew().run(() -> entries.deleteAll());
        settings("[1,3,7,14,30]");
    }

    // ------------------------------------------------------------------ helpers

    private static String json(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void settings(String ladderJson) {
        given().contentType(ContentType.JSON).body("{\"reviewIntervalsDays\":" + ladderJson + "}").put("/api/settings").then().statusCode(200);
    }

    private static long finishedEntry(String text) {
        long id = given().contentType(ContentType.JSON).body(json(Map.of("text", text, "language", "EN"))).post("/api/entries")
                .then().statusCode(200).extract().jsonPath().getLong("id");
        given().post("/api/entries/" + id + "/finish").then().statusCode(200);
        return id;
    }

    private static String candidates(String... triples) {
        List<Map<String, String>> list = new java.util.ArrayList<>();
        for (int i = 0; i < triples.length; i += 4) {
            list.add(Map.of("type", triples[i], "prompt", triples[i + 1], "answer", triples[i + 2], "sourceQuote", triples[i + 3]));
        }
        return json(Map.of("candidates", list));
    }

    private static String validTwo() {
        return candidates("FACT", PROMPT_1, ANSWER_1, "Spaced   Repetition BEATS cramming.",
                "DECISION", PROMPT_2, ANSWER_2, "I decided to review my notes every weekend");
    }

    private static Response extract(String type, long id) {
        return given().contentType(ContentType.JSON).body(json(Map.of("sourceType", type, "sourceId", id))).post("/api/memory/extractions");
    }

    private static JsonPath extractOk(long entryId) {
        return extract("ENTRY", entryId).then().statusCode(200).extract().jsonPath();
    }

    private static Response decide(long id, Map<String, Object> body) {
        return given().contentType(ContentType.JSON).body(json(body)).post("/api/memory/candidates/" + id + "/decision");
    }

    private static long approve(long candidateId) {
        decide(candidateId, Map.of("decision", "APPROVE")).then().statusCode(200).body("status", equalTo("APPROVED"));
        return given().get("/api/memory/items").then().statusCode(200).extract().jsonPath()
                .getList("id", Long.class).stream().max(Long::compare).orElseThrow();
    }

    private static long approvedItem(long entryId) {
        Fakes.llm().enqueue(candidates("FACT", PROMPT_1, ANSWER_1, QUOTE));
        long cid = extractOk(entryId).getLong("[0].id");
        return approve(cid);
    }

    private static void makeDue(long itemId) {
        QuarkusTransaction.requiringNew().run(() -> {
            MemoryItemEntity i = MemoryItemEntity.findById(itemId);
            i.nextReviewOn = LocalDate.now().minusDays(1);
        });
    }

    // ------------------------------------------------------------------ extraction

    @Test
    void extractionStoresOnlyPendingCandidatesAndNoItems() {
        long entry = finishedEntry(ENTRY_TEXT);
        Fakes.llm().enqueue(validTwo());

        JsonPath out = extractOk(entry);

        assertThat(out.getList("status")).containsExactly("PENDING", "PENDING");
        assertThat(out.getString("[0].sourceType")).isEqualTo("ENTRY");
        assertThat(out.getLong("[0].sourceId")).isEqualTo(entry);
        assertThat(out.getString("[0].type")).isEqualTo("FACT");
        assertThat(out.getString("[1].type")).isEqualTo("DECISION");
        given().get("/api/memory/candidates").then().statusCode(200).body("$", hasSize(2));
        given().get("/api/memory/items").then().statusCode(200).body("$", hasSize(0));
        given().get("/api/memory/reviews/due").then().statusCode(200).body("$", hasSize(0));
        // the prompt asks for the source language (English entry) and sends the entry as data
        var call = Fakes.llm().calls().get(0);
        assertThat(call.structured()).isTrue();
        assertThat(call.request().messages().get(1).content()).contains(ENTRY_TEXT);
    }

    @Test
    void extractionAllowsZeroCandidates() {
        long entry = finishedEntry(ENTRY_TEXT);
        Fakes.llm().enqueue("{\"candidates\":[]}");
        assertThat(extractOk(entry).getList("$")).isEmpty();
    }

    @Test
    void extractionRequiresAFinishedEntry() {
        long draft = given().contentType(ContentType.JSON).body("{\"text\":\"" + QUOTE + "\"}").post("/api/entries")
                .then().statusCode(200).extract().jsonPath().getLong("id");
        extract("ENTRY", draft).then().statusCode(409).body("code", equalTo("ENTRY_NOT_FINISHED"));
        extract("ENTRY", 987654).then().statusCode(404);
        extract("CONVERSATION", 987654).then().statusCode(404);
        given().contentType(ContentType.JSON).body("{}").post("/api/memory/extractions").then().statusCode(400);
        assertThat(Fakes.llm().calls()).isEmpty();
    }

    @Test
    void inventedQuoteIsRejectedAndRetriedWithFeedback() {
        long entry = finishedEntry(ENTRY_TEXT);
        Fakes.llm().enqueue(candidates("FACT", PROMPT_1, ANSWER_1, "A sentence the user never wrote"));
        Fakes.llm().enqueue(validTwo());

        assertThat(extractOk(entry).getList("$")).hasSize(2);

        assertThat(Fakes.llm().calls()).hasSize(2);
        var retry = Fakes.llm().calls().get(1).request().messages();
        assertThat(retry.get(retry.size() - 1).content()).contains("sourceQuote must be copied word for word");
    }

    @Test
    void inventedQuoteThreeTimesIs502AndNothingIsStored() {
        long entry = finishedEntry(ENTRY_TEXT);
        String bad = candidates("FACT", PROMPT_1, ANSWER_1, "A sentence the user never wrote");
        Fakes.llm().enqueue(bad).enqueue(bad).enqueue(bad);

        extract("ENTRY", entry).then().statusCode(502).body("code", equalTo("LLM_OUTPUT_INVALID"));

        assertThat(Fakes.llm().calls()).hasSize(3);
        given().get("/api/memory/candidates").then().body("$", hasSize(0));
        given().get("/api/memory/candidates?status=APPROVED").then().body("$", hasSize(0));
        given().get("/api/memory/items").then().body("$", hasSize(0));
    }

    @Test
    void invalidJsonThreeTimesIs502AndNothingIsStored() {
        long entry = finishedEntry(ENTRY_TEXT);
        Fakes.llm().enqueue("not json").enqueue("{\"candidates\":[{\"type\":\"NOPE\"}]}").enqueue("{\"candidates\":\"x\"}");

        extract("ENTRY", entry).then().statusCode(502);

        assertThat(Fakes.llm().calls()).hasSize(3);
        given().get("/api/memory/candidates").then().body("$", hasSize(0));
    }

    @Test
    void semanticChecksRejectStatementsAsPromptsAndTooManyCandidates() {
        long entry = finishedEntry(ENTRY_TEXT);
        String statement = candidates("FACT", "Spaced repetition beats cramming", ANSWER_1, QUOTE);
        Fakes.llm().enqueue(statement).enqueue(statement).enqueue(statement);
        extract("ENTRY", entry).then().statusCode(502);

        String tooMany = candidates("FACT", "a1?", "x", QUOTE, "FACT", "a2?", "x", QUOTE, "FACT", "a3?", "x", QUOTE, "FACT", "a4?", "x", QUOTE,
                "FACT", "a5?", "x", QUOTE, "FACT", "a6?", "x", QUOTE, "FACT", "a7?", "x", QUOTE, "FACT", "a8?", "x", QUOTE);
        Fakes.llm().enqueue(tooMany).enqueue(tooMany).enqueue(tooMany);
        extract("ENTRY", entry).then().statusCode(502);
        given().get("/api/memory/candidates").then().body("$", hasSize(0));
    }

    @Test
    void repeatedExtractionDoesNotDuplicatePendingCandidates() {
        long entry = finishedEntry(ENTRY_TEXT);
        Fakes.llm().enqueue(validTwo()).enqueue(validTwo());
        assertThat(extractOk(entry).getList("$")).hasSize(2);
        assertThat(extractOk(entry).getList("$")).isEmpty();
        given().get("/api/memory/candidates").then().body("$", hasSize(2));
    }

    @Test
    void conversationSourceUsesTheTranscriptAndRejectsEntryQuotesNotInIt() {
        long entry = finishedEntry(ENTRY_TEXT);
        Fakes.llm().enqueue(json(Map.of("questions", List.of("What will you change next week?", "What made weekends work for you?"))));
        long conversation = given().contentType(ContentType.JSON).body(json(Map.of("entryId", entry))).post("/api/conversations")
                .then().statusCode(200).extract().jsonPath().getLong("id");
        Fakes.llm().enqueue(json(Map.of("kind", "FOLLOW_UP_QUESTION", "text", "What makes weekends work for you?")));
        given().contentType(ContentType.JSON).body(json(Map.of("text", "I will plan a quiet hour on Sunday evening", "voice", false)))
                .post("/api/conversations/" + conversation + "/answers").then().statusCode(200);
        Fakes.llm().enqueue(candidates("DECISION", "When will I plan my review hour?", "Sunday evening", "plan a quiet hour on Sunday evening"));

        JsonPath out = extract("CONVERSATION", conversation).then().statusCode(200).extract().jsonPath();

        assertThat(out.getString("[0].sourceType")).isEqualTo("CONVERSATION");
        assertThat(out.getLong("[0].sourceId")).isEqualTo(conversation);
    }

    // ------------------------------------------------------------------ decisions

    @Test
    void onlyApproveCreatesAMemoryItem() {
        long entry = finishedEntry(ENTRY_TEXT);
        Fakes.llm().enqueue(validTwo());
        JsonPath out = extractOk(entry);
        long first = out.getLong("[0].id");
        long second = out.getLong("[1].id");

        decide(first, Map.of("decision", "DISCARD")).then().statusCode(200).body("status", equalTo("DISCARDED"));
        given().get("/api/memory/items").then().body("$", hasSize(0));
        given().get("/api/memory/candidates?status=DISCARDED").then().body("$", hasSize(1));
        given().get("/api/memory/candidates").then().body("$", hasSize(1));

        decide(second, Map.of("decision", "APPROVE", "prompt", "Edited prompt?", "answer", "Edited answer", "type", "PRINCIPLE"))
                .then().statusCode(200).body("status", equalTo("APPROVED")).body("prompt", equalTo("Edited prompt?"));
        JsonPath items = given().get("/api/memory/items").then().statusCode(200).body("$", hasSize(1)).extract().jsonPath();
        assertThat(items.getString("[0].prompt")).isEqualTo("Edited prompt?");
        assertThat(items.getString("[0].answer")).isEqualTo("Edited answer");
        assertThat(items.getString("[0].type")).isEqualTo("PRINCIPLE");
        assertThat(items.getString("[0].sourceType")).isEqualTo("ENTRY");
        assertThat(items.getLong("[0].sourceId")).isEqualTo(entry);
        assertThat(items.getString("[0].sourceQuote")).isNotBlank();
        assertThat(items.getInt("[0].repetitions")).isZero();
        assertThat(items.getInt("[0].intervalDays")).isEqualTo(1);
        assertThat(items.getDouble("[0].easeFactor")).isEqualTo(2.5);
        assertThat(items.getString("[0].nextReviewOn")).isEqualTo(LocalDate.now().plusDays(1).toString());
    }

    @Test
    void decidingTwiceIs409AndBadDecisionsAre4xx() {
        long entry = finishedEntry(ENTRY_TEXT);
        Fakes.llm().enqueue(validTwo());
        JsonPath out = extractOk(entry);
        long id = out.getLong("[0].id");

        decide(id, Map.of("decision", "APPROVE")).then().statusCode(200);
        decide(id, Map.of("decision", "APPROVE")).then().statusCode(409).body("code", equalTo("CANDIDATE_DECIDED"));
        decide(id, Map.of("decision", "DISCARD")).then().statusCode(409);
        given().get("/api/memory/items").then().body("$", hasSize(1));

        long other = out.getLong("[1].id");
        decide(other, Map.of("decision", "APPROVE", "prompt", "  ")).then().statusCode(400);
        given().contentType(ContentType.JSON).body("{}").post("/api/memory/candidates/" + other + "/decision").then().statusCode(400);
        decide(99999, Map.of("decision", "DISCARD")).then().statusCode(404);
        given().get("/api/memory/items").then().body("$", hasSize(1));
    }

    @Test
    void approvalUsesTheConfiguredInitialIntervals() {
        settings("[2,5]");
        long entry = finishedEntry(ENTRY_TEXT);
        long item = approvedItem(entry);
        var items = given().get("/api/memory/items").then().extract().jsonPath();
        assertThat(items.getInt("[0].intervalDays")).isEqualTo(2);
        assertThat(items.getString("[0].nextReviewOn")).isEqualTo(LocalDate.now().plusDays(2).toString());
        assertThat(item).isPositive();
    }

    // ------------------------------------------------------------------ reviews

    @Test
    void dueCardsNeverContainTheAnswerOrSourceAndGradingNeedsAnAttempt() {
        long entry = finishedEntry(ENTRY_TEXT);
        long item = approvedItem(entry);
        given().get("/api/memory/reviews/due").then().body("$", hasSize(0)); // first review is tomorrow
        makeDue(item);

        String dueJson = given().get("/api/memory/reviews/due").then().statusCode(200).body("$", hasSize(1)).extract().asString();
        assertThat(dueJson).contains(PROMPT_1);
        assertThat(dueJson).doesNotContain(ANSWER_1).doesNotContain("ZEBRAANSWER").doesNotContain(QUOTE)
                .doesNotContain("answer").doesNotContain("sourceQuote").doesNotContain("sourceId");

        given().contentType(ContentType.JSON).body("{\"quality\":4}").post("/api/memory/reviews/" + item + "/grade")
                .then().statusCode(409).body("code", equalTo("ATTEMPT_REQUIRED"));
        given().contentType(ContentType.JSON).body("{\"quality\":9}").post("/api/memory/reviews/" + item + "/grade")
                .then().statusCode(400);
        given().contentType(ContentType.JSON).body("{\"quality\":-1}").post("/api/memory/reviews/" + item + "/grade")
                .then().statusCode(400);
        given().contentType(ContentType.JSON).body("{\"quality\":4}").post("/api/memory/reviews/55555/grade").then().statusCode(404);
    }

    @Test
    void attemptRevealsTheAnswerAndGradeAppliesSm2WithTheConfiguredLadder() {
        settings("[2,5]");
        long entry = finishedEntry(ENTRY_TEXT);
        long item = approvedItem(entry);
        makeDue(item);

        JsonPath reveal = given().contentType(ContentType.JSON).body("{\"attemptText\":\"my guess\"}")
                .post("/api/memory/reviews/" + item + "/attempt").then().statusCode(200).extract().jsonPath();
        assertThat(reveal.getLong("itemId")).isEqualTo(item);
        assertThat(reveal.getString("answer")).isEqualTo(ANSWER_1);
        assertThat(reveal.getString("sourceQuote")).isEqualTo(QUOTE);
        assertThat(reveal.getString("sourceType")).isEqualTo("ENTRY");
        assertThat(reveal.getLong("sourceId")).isEqualTo(entry);
        assertThat(reveal.getString("attemptText")).isEqualTo("my guess");

        JsonPath graded = given().contentType(ContentType.JSON).body("{\"quality\":4}").post("/api/memory/reviews/" + item + "/grade")
                .then().statusCode(200).extract().jsonPath();
        assertThat(graded.getInt("repetitions")).isEqualTo(1);
        assertThat(graded.getInt("intervalDays")).isEqualTo(5);
        assertThat(graded.getString("nextReviewOn")).isEqualTo(LocalDate.now().plusDays(5).toString());
        given().get("/api/memory/reviews/due").then().body("$", hasSize(0));

        // a second grade without a new attempt is a conflict (the cycle is closed)
        given().contentType(ContentType.JSON).body("{\"quality\":4}").post("/api/memory/reviews/" + item + "/grade").then().statusCode(409);

        // next cycle: "in my head" (no text), then a lapse restarts at the first ladder step
        makeDue(item);
        given().contentType(ContentType.JSON).body("{}").post("/api/memory/reviews/" + item + "/attempt")
                .then().statusCode(200).body("attemptText", org.hamcrest.Matchers.nullValue());
        JsonPath lapsed = given().contentType(ContentType.JSON).body("{\"quality\":1}").post("/api/memory/reviews/" + item + "/grade")
                .then().statusCode(200).extract().jsonPath();
        assertThat(lapsed.getInt("repetitions")).isZero();
        assertThat(lapsed.getInt("intervalDays")).isEqualTo(2);
    }

    @Test
    void itemsCanBeDeleted() {
        long item = approvedItem(finishedEntry(ENTRY_TEXT));
        given().delete("/api/memory/items/" + item).then().statusCode(204);
        given().get("/api/memory/items").then().body("$", hasSize(0));
        given().delete("/api/memory/items/" + item).then().statusCode(404);
        given().contentType(ContentType.JSON).body("{}").post("/api/memory/reviews/" + item + "/attempt").then().statusCode(404);
    }

    // ------------------------------------------------------------------ lessons

    @Test
    void lessonsUnlockByUsageAndAreLocalized() {
        List<Map<String, Object>> en = given().header("Accept-Language", "en").get("/api/memory/lessons").then().statusCode(200)
                .body("$", hasSize(6)).extract().jsonPath().getList("$");
        assertThat(en).hasSize(6);
        JsonPath fresh = given().header("Accept-Language", "en").get("/api/memory/lessons").jsonPath();
        assertThat(fresh.getList("unlocked", Boolean.class)).containsExactly(true, false, false, false, false, false);
        assertThat(fresh.getString("[0].title")).isEqualTo("Retrieval instead of rereading");
        assertThat(fresh.getString("[1].unlockHint")).isNotBlank();
        assertThat(fresh.getString("[1].body")).isEmpty();
        assertThat(fresh.getString("[0].unlockHint")).isNull();

        long entry = finishedEntry(ENTRY_TEXT);
        long item = approvedItem(entry);
        JsonPath afterApproval = given().header("Accept-Language", "de").get("/api/memory/lessons").jsonPath();
        assertThat(afterApproval.getList("unlocked", Boolean.class)).containsExactly(true, true, false, false, false, false);
        assertThat(afterApproval.getString("[0].title")).isEqualTo("Abrufen statt Nachlesen");

        for (int i = 0; i < 3; i++) {
            makeDue(item);
            given().contentType(ContentType.JSON).body("{}").post("/api/memory/reviews/" + item + "/attempt").then().statusCode(200);
            given().contentType(ContentType.JSON).body("{\"quality\":3}").post("/api/memory/reviews/" + item + "/grade").then().statusCode(200);
        }
        JsonPath afterReviews = given().get("/api/memory/lessons").jsonPath();
        assertThat(afterReviews.getList("unlocked", Boolean.class)).containsExactly(true, true, true, false, false, false);
        // deleting the item never locks a lesson again
        given().delete("/api/memory/items/" + item).then().statusCode(204);
        assertThat(given().get("/api/memory/lessons").jsonPath().getList("unlocked", Boolean.class))
                .containsExactly(true, true, true, false, false, false);
    }

    @Test
    void lessonLanguageFallsBackToTheUiLanguageSetting() {
        given().contentType(ContentType.JSON).body("{\"uiLanguage\":\"DE\"}").put("/api/settings").then().statusCode(200);
        try {
            assertThat(given().get("/api/memory/lessons").jsonPath().getString("[0].title")).isEqualTo("Abrufen statt Nachlesen");
        } finally {
            given().contentType(ContentType.JSON).body("{\"uiLanguage\":\"AUTO\"}").put("/api/settings").then().statusCode(200);
        }
        assertThat(given().get("/api/memory/lessons").jsonPath().getString("[0].title")).isEqualTo("Retrieval instead of rereading");
    }

    // ------------------------------------------------------------------ stats, export, delete

    @Test
    void memoryStatsCountItemsReviewsAndPromptsOnly() {
        long item = approvedItem(finishedEntry(ENTRY_TEXT));
        makeDue(item);
        given().contentType(ContentType.JSON).body("{}").post("/api/memory/reviews/" + item + "/attempt").then().statusCode(200);
        given().contentType(ContentType.JSON).body("{\"quality\":5}").post("/api/memory/reviews/" + item + "/grade").then().statusCode(200);
        // a recorded but ungraded attempt is not a done review
        makeDue(item);
        given().contentType(ContentType.JSON).body("{}").post("/api/memory/reviews/" + item + "/attempt").then().statusCode(200);

        MemoryStats.Learned now = owner.learnedBetween(Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        assertThat(now.itemsCreated()).isEqualTo(1);
        assertThat(now.reviewsDone()).isEqualTo(1);
        assertThat(now.itemPrompts()).containsExactly(PROMPT_1);
        assertThat(now.toString()).doesNotContain("ZEBRAANSWER");

        MemoryStats.Learned earlier = owner.learnedBetween(Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));
        assertThat(earlier).isEqualTo(MemoryStats.Learned.none());
    }

    @Test
    void exportAndDeleteAllCoverEverything() throws Exception {
        long item = approvedItem(finishedEntry(ENTRY_TEXT));
        makeDue(item);
        given().contentType(ContentType.JSON).body("{\"attemptText\":\"typed\"}").post("/api/memory/reviews/" + item + "/attempt").then().statusCode(200);
        Fakes.llm().enqueue(candidates("INSIGHT", PROMPT_2, ANSWER_2, "review my notes every weekend"));
        extractOk(given().get("/api/entries").jsonPath().getLong("items[0].id"));

        Map<String, Object> files = new HashMap<>();
        owner.export(new ExportSink() {
            @Override
            public void json(String path, Object value) {
                files.put(path, value);
            }

            @Override
            public void binary(String path, AudioSource.Opener content) {
                throw new AssertionError("no binaries");
            }

            @Override
            public boolean includeAudio() {
                return false;
            }
        });
        assertThat(files.keySet()).containsExactlyInAnyOrder("memory/candidates.json", "memory/items.json", "memory/reviews.json");
        assertThat((List<?>) files.get("memory/candidates.json")).hasSize(2);
        assertThat((List<?>) files.get("memory/items.json")).hasSize(1);
        assertThat((List<?>) files.get("memory/reviews.json")).hasSize(1);
        assertThat(owner.name()).isEqualTo("memory");
        assertThat(owner.deleteOrder()).isBetween(100, 900);

        assertThat(owner.deleteAll()).isEqualTo(4);
        given().get("/api/memory/items").then().body("$", hasSize(0));
        given().get("/api/memory/candidates").then().body("$", hasSize(0));
        given().get("/api/memory/candidates?status=APPROVED").then().body("$", hasSize(0));
        assertThat(owner.learnedBetween(Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600)).reviewsDone()).isZero();

        // delete-all through the public endpoint also reaches memory
        approvedItem(finishedEntry(ENTRY_TEXT));
        given().contentType(ContentType.JSON).body("{\"confirmation\":\"DELETE EVERYTHING\"}").post("/api/data/delete-all").then().statusCode(200);
        given().get("/api/memory/items").then().body("$", hasSize(0));
    }

    @Test
    void deletingTheEntryRemovesItsCandidatesAndDetachesApprovedItems() {
        long entry = finishedEntry(ENTRY_TEXT);
        Fakes.llm().enqueue(validTwo());
        JsonPath out = extractOk(entry);
        approve(out.getLong("[0].id")); // the second candidate stays pending
        long otherEntry = finishedEntry("Another entry about " + QUOTE + " for sure.");
        Fakes.llm().enqueue(candidates("FACT", "Other question?", "Other answer", QUOTE));
        extractOk(otherEntry);

        given().delete("/api/entries/" + entry).then().statusCode(204);

        // pending candidate of the deleted entry is gone, the other entry's candidate remains
        List<Long> sources = given().get("/api/memory/candidates").jsonPath().getList("sourceId", Long.class);
        assertThat(sources).containsExactly(otherEntry);
        assertThat(given().get("/api/memory/candidates?status=APPROVED").jsonPath().getList("$")).isEmpty();
        // the approved item is kept without its source and quote
        JsonPath items = given().get("/api/memory/items").then().body("$", hasSize(1)).extract().jsonPath();
        assertThat(items.getString("[0].prompt")).isEqualTo(PROMPT_1);
        assertThat(items.getString("[0].answer")).isEqualTo(ANSWER_1);
        assertThat(items.getString("[0].sourceQuote")).isNull();
        assertThat(items.getString("[0].sourceType")).isNull();
        assertThat(items.getString("[0].sourceId")).isNull();
        long item = items.getLong("[0].id");
        makeDue(item);
        given().contentType(ContentType.JSON).body("{}").post("/api/memory/reviews/" + item + "/attempt")
                .then().statusCode(200).body("sourceQuote", org.hamcrest.Matchers.nullValue());
    }

    @Test
    void deletingTheEntryAlsoRemovesCandidatesOfItsConversations() {
        long entry = finishedEntry(ENTRY_TEXT);
        Fakes.llm().enqueue(json(Map.of("questions", List.of("What will you change next week?", "What made weekends work for you?"))));
        long conversation = given().contentType(ContentType.JSON).body(json(Map.of("entryId", entry))).post("/api/conversations")
                .then().statusCode(200).extract().jsonPath().getLong("id");
        Fakes.llm().enqueue(candidates("INSIGHT", "What beats cramming?", "Spacing", QUOTE));
        extract("CONVERSATION", conversation).then().statusCode(200);
        given().get("/api/memory/candidates").then().body("$", hasSize(1));

        given().delete("/api/entries/" + entry).then().statusCode(204);

        given().get("/api/memory/candidates").then().body("$", hasSize(0));
    }

    // ------------------------------------------------------------------ privacy

    @Test
    void noPromptAnswerQuoteOrAttemptReachesTheLogs() {
        String marker = "QUOKKA-PRIVATE-5521";
        try (LogCapture logs = new LogCapture()) {
            long entry = finishedEntry(ENTRY_TEXT + " " + marker);
            // rejected extraction (invented quote)
            String bad = candidates("FACT", "Question " + marker + "?", "Answer " + marker, "invented " + marker);
            Fakes.llm().enqueue(bad).enqueue(bad).enqueue(bad);
            extract("ENTRY", entry).then().statusCode(502);
            // successful extraction, approval, review
            Fakes.llm().enqueue(candidates("FACT", "Question " + marker + "?", "Answer " + marker, "decided to review my notes"));
            long cid = extractOk(entry).getLong("[0].id");
            long item = approve(cid);
            makeDue(item);
            given().contentType(ContentType.JSON).body("{\"attemptText\":\"Attempt " + marker + "\"}").post("/api/memory/reviews/" + item + "/attempt").then().statusCode(200);
            given().contentType(ContentType.JSON).body("{\"quality\":4}").post("/api/memory/reviews/" + item + "/grade").then().statusCode(200);
            // failing paths
            given().contentType(ContentType.JSON).body("{\"quality\":4}").post("/api/memory/reviews/" + item + "/grade").then().statusCode(409);
            decide(cid, Map.of("decision", "APPROVE", "prompt", marker)).then().statusCode(409);
            given().delete("/api/entries/" + entry).then().statusCode(204);

            assertThat(logs.all()).doesNotContain(marker).doesNotContain("QUOKKA").doesNotContain("ZEBRAANSWER");
        }
    }
}
