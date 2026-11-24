package dev.rabauer.ai.diary.gym;

import dev.rabauer.ai.diary.entry.EntryRepository;
import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.support.Fakes;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.rabauer.ai.diary.gym.GymTestSupport.EN_ENTRY;
import static dev.rabauer.ai.diary.gym.GymTestSupport.EN_EXCERPT;
import static dev.rabauer.ai.diary.gym.GymTestSupport.attempt;
import static dev.rabauer.ai.diary.gym.GymTestSupport.attemptOk;
import static dev.rabauer.ai.diary.gym.GymTestSupport.draftEntry;
import static dev.rabauer.ai.diary.gym.GymTestSupport.exerciseJson;
import static dev.rabauer.ai.diary.gym.GymTestSupport.feedbackJson;
import static dev.rabauer.ai.diary.gym.GymTestSupport.finishedEntry;
import static dev.rabauer.ai.diary.gym.GymTestSupport.json;
import static dev.rabauer.ai.diary.gym.GymTestSupport.next;
import static dev.rabauer.ai.diary.gym.GymTestSupport.nextOk;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/** Thinking Gym behaviour (SPEC section 7) against the fake LLM. */
@QuarkusTest
class GymFlowTest {

    @Inject
    GymDataOwner owner;
    @Inject
    EntryRepository entries;

    @BeforeEach
    void clean() {
        Fakes.reset();
        owner.deleteAll();
        QuarkusTransaction.requiringNew().run(() -> entries.deleteAll());
    }

    private static void assertTemplate(JsonPath ex, String category) {
        assertThat(ex.getBoolean("personalized")).isFalse();
        assertThat(ex.getString("category")).isEqualTo(category);
        assertThat(ex.getString("sourceExcerpt")).isNull();
        assertThat(ex.getString("sourceEntryId")).isNull();
        assertThat(ex.getString("title")).isNotBlank();
        assertThat(ex.getString("prompt")).isNotBlank();
        assertThat(ex.getList("steps")).hasSizeBetween(2, 5);
        assertThat(ex.getInt("estimatedMinutes")).isBetween(5, 10);
    }

    // ------------------------------------------------------------------ fallback (acceptance 9)

    @Test
    void worksWithoutAnyEntryAndNeverCallsTheLlm() {
        JsonPath ex = nextOk("REFRAMING");
        assertTemplate(ex, "REFRAMING");
        assertThat(Fakes.llm().calls()).isEmpty();
        given().get("/api/gym/exercises/" + ex.getLong("id")).then().statusCode(200)
                .body("title", equalTo(ex.getString("title")));
    }

    @Test
    void draftEntriesAreNeverUsed() {
        draftEntry(EN_ENTRY);
        assertTemplate(nextOk("ASSUMPTION_CHECK"), "ASSUMPTION_CHECK");
        assertThat(Fakes.llm().calls()).isEmpty();
    }

    @Test
    void fallsBackToATemplateWhenTheProviderIsUnavailable() {
        finishedEntry(EN_ENTRY, "EN");
        Fakes.llm().enqueueFailure(new ProviderException("ollama", ProviderException.Kind.UNREACHABLE, "down"));
        assertTemplate(nextOk("ASSUMPTION_CHECK"), "ASSUMPTION_CHECK");
        assertThat(Fakes.llm().calls()).hasSize(1);
    }

    @Test
    void fallsBackToATemplateOnInvalidJsonAfterOneRetry() {
        finishedEntry(EN_ENTRY, "EN");
        Fakes.llm().enqueue("this is not json").enqueue("{\"title\": \"x\"}");
        assertTemplate(nextOk("DECISION_REVIEW"), "DECISION_REVIEW");
        assertThat(Fakes.llm().calls()).hasSize(2);
    }

    @Test
    void fallsBackWhenTheProviderIsTheDefaultFake() {
        finishedEntry(EN_ENTRY, "EN");
        assertTemplate(nextOk("BIAS_SPOTTING"), "BIAS_SPOTTING"); // the fake answers "{}", which is invalid
    }

    @Test
    void inventedExcerptIsRejectedAndFallsBackToATemplate() {
        finishedEntry(EN_ENTRY, "EN");
        String invented = exerciseJson("Everyone laughed at my idea in the meeting yesterday");
        Fakes.llm().enqueue(invented).enqueue(invented);
        JsonPath ex = nextOk("ASSUMPTION_CHECK");
        assertTemplate(ex, "ASSUMPTION_CHECK");
        assertThat(Fakes.llm().calls()).hasSize(2);
        assertThat(Fakes.llm().calls().get(1).request().messages().toString()).contains("sourceExcerpt");
    }

    @Test
    void anInventedExcerptFollowedByAValidOneIsAccepted() {
        long entry = finishedEntry(EN_ENTRY, "EN");
        Fakes.llm().enqueue(exerciseJson("Nobody said anything about my idea at all")).enqueue(exerciseJson(EN_EXCERPT));
        JsonPath ex = nextOk("ASSUMPTION_CHECK");
        assertThat(ex.getBoolean("personalized")).isTrue();
        assertThat(ex.getLong("sourceEntryId")).isEqualTo(entry);
    }

    @Test
    void praiseAndScoresInTheGeneratedTextAreRejected() {
        finishedEntry(EN_ENTRY, "EN");
        String praise = json(Map.of("title", "Great insight", "prompt", "Well done, you scored 9/10 on this.",
                "steps", List.of("One.", "Two."), "sourceExcerpt", EN_EXCERPT));
        Fakes.llm().enqueue(praise).enqueue(praise);
        assertTemplate(nextOk("ASSUMPTION_CHECK"), "ASSUMPTION_CHECK");
    }

    // ------------------------------------------------------------------ personalization

    @Test
    void personalizesFromAFinishedEntryAndStoresTheVerbatimExcerpt() {
        long entry = finishedEntry(EN_ENTRY, "EN");
        // different case in the model output; the stored excerpt is the entry's own wording
        Fakes.llm().enqueue(exerciseJson(EN_EXCERPT.toUpperCase()));
        JsonPath ex = nextOk("ASSUMPTION_CHECK");

        assertThat(ex.getBoolean("personalized")).isTrue();
        assertThat(ex.getString("language")).isEqualTo("EN");
        assertThat(ex.getLong("sourceEntryId")).isEqualTo(entry);
        assertThat(ex.getString("sourceExcerpt")).isEqualTo(EN_EXCERPT);
        assertThat(ex.getString("title")).isEqualTo("Check the story");
        assertThat(ex.getList("steps")).hasSize(2);
        assertThat(ex.getInt("estimatedMinutes")).isBetween(5, 10);

        var calls = Fakes.llm().calls();
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).structured()).isTrue();
        assertThat(calls.get(0).request().messages().get(0).content()).contains("Assumption check");
        assertThat(calls.get(0).request().messages().get(1).content()).contains(EN_ENTRY);

        given().get("/api/gym/exercises/" + ex.getLong("id")).then().statusCode(200)
                .body("personalized", equalTo(true)).body("sourceExcerpt", equalTo(EN_EXCERPT));
    }

    @Test
    void usesTheLanguageOfTheEntry() {
        finishedEntry("Heute war ich sicher, dass alle meine Idee schwach fanden, und ich habe geschwiegen.", "DE");
        Fakes.llm().enqueue(exerciseJson("ich sicher, dass alle meine Idee schwach fanden"));
        JsonPath ex = nextOk("ASSUMPTION_CHECK");
        assertThat(ex.getString("language")).isEqualTo("DE");
        assertThat(Fakes.llm().calls().get(0).request().messages().get(0).content()).contains("Deutsch");
    }

    @Test
    void doesNotReuseTheSameEntryWhileOthersExist() {
        long older = finishedEntry("I decided to stop checking my email in the evening and I will see how it goes.", "EN");
        long newer = finishedEntry(EN_ENTRY, "EN");
        Fakes.llm().enqueue(exerciseJson(EN_EXCERPT)).enqueue(exerciseJson("stop checking my email in the evening"));
        assertThat(nextOk("ASSUMPTION_CHECK").getLong("sourceEntryId")).isEqualTo(newer);
        assertThat(nextOk("SECOND_ORDER_THINKING").getLong("sourceEntryId")).isEqualTo(older);
    }

    @Test
    void crisisTextsAreNeverUsedAsASource() {
        finishedEntry("I feel like I want to kill myself and nothing seems to matter anymore today.", "EN");
        assertTemplate(nextOk("REFRAMING"), "REFRAMING");
        assertThat(Fakes.llm().calls()).isEmpty();
    }

    // ------------------------------------------------------------------ templates and rotation

    @Test
    void templatesInTheUiLanguageRotateWithinACategory() {
        Set<String> titles = new LinkedHashSet<>();
        for (int i = 0; i < 3; i++) {
            JsonPath ex = next("PERSPECTIVE_SHIFT", "de-DE,de;q=0.9").then().statusCode(200).extract().jsonPath();
            assertThat(ex.getString("language")).isEqualTo("DE");
            titles.add(ex.getString("title"));
        }
        assertThat(titles).as("three different templates before one repeats").hasSize(3);
        JsonPath english = next("PERSPECTIVE_SHIFT", "en-US").then().statusCode(200).extract().jsonPath();
        assertThat(english.getString("language")).isEqualTo("EN");
    }

    @Test
    void settingsLanguageWinsOverTheHeader() {
        given().contentType("application/json").body("{\"uiLanguage\":\"EN\"}").put("/api/settings").then().statusCode(200);
        try {
            JsonPath ex = next("REFRAMING", "DE").then().statusCode(200).extract().jsonPath();
            assertThat(ex.getString("language")).isEqualTo("EN");
        } finally {
            given().contentType("application/json").body("{\"uiLanguage\":\"AUTO\"}").put("/api/settings").then().statusCode(200);
        }
    }

    @Test
    void withoutACategoryTheLeastRecentlyPracticedOneComesNext() {
        List<String> order = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            JsonPath ex = nextOk(null);
            order.add(ex.getString("category"));
            attemptOk(ex.getLong("id"), "answer " + i);
        }
        assertThat(order).as("every category once before any repeats").doesNotHaveDuplicates().hasSize(7);
        assertThat(order.get(0)).isEqualTo("ASSUMPTION_CHECK");
        // the first one practiced is the least recently practiced again
        assertThat(nextOk(null).getString("category")).isEqualTo(order.get(0));
    }

    @Test
    void practicingACategoryExplicitlyMovesItToTheBackOfTheRotation() {
        JsonPath ex = nextOk("ASSUMPTION_CHECK");
        attemptOk(ex.getLong("id"), "something");
        assertThat(nextOk(null).getString("category")).isEqualTo("PERSPECTIVE_SHIFT");
    }

    @Test
    void skippingAnExerciseStillMovesOnToAnotherCategory() {
        String first = nextOk(null).getString("category");
        String second = nextOk(null).getString("category");
        assertThat(second).isNotEqualTo(first);
    }

    // ------------------------------------------------------------------ attempts and history

    @Test
    void attemptsAreStoredAndHistoryIsNewestFirstWithCategoryFilter() {
        long a = nextOk("REFRAMING").getLong("id");
        long b = nextOk("BIAS_SPOTTING").getLong("id");
        JsonPath first = attempt(a, "  first answer  ", 90).then().statusCode(200).extract().jsonPath();
        assertThat(first.getString("answer")).isEqualTo("first answer");
        assertThat(first.getInt("durationSeconds")).isEqualTo(90);
        assertThat(first.getString("category")).isEqualTo("REFRAMING");
        assertThat(first.getLong("exerciseId")).isEqualTo(a);
        assertThat(first.getList("feedbackQuestions")).isEmpty();
        assertThat(first.getString("createdAt")).isNotBlank();
        attemptOk(b, "second answer");
        attemptOk(a, "third answer");

        given().get("/api/gym/attempts").then().statusCode(200).body("$", hasSize(3))
                .body("answer", contains("third answer", "second answer", "first answer"));
        given().get("/api/gym/attempts?category=REFRAMING").then().statusCode(200)
                .body("answer", contains("third answer", "first answer"));
        given().get("/api/gym/attempts?category=EXPLAIN_SIMPLY").then().statusCode(200).body("$", hasSize(0));
    }

    @Test
    void attemptValidation() {
        long id = nextOk("REFRAMING").getLong("id");
        attempt(id, "   ", 10).then().statusCode(400).body("code", equalTo("ANSWER_EMPTY"));
        given().contentType("application/json").post("/api/gym/exercises/" + id + "/attempts").then().statusCode(400);
        attempt(id + 1000, "x", 10).then().statusCode(404);
        attempt(id, "x".repeat(GymService.MAX_ANSWER_CHARS + 1), 10).then().statusCode(400).body("code", equalTo("ANSWER_TOO_LONG"));
        assertThat(attempt(id, "ok", -5).then().statusCode(200).extract().jsonPath().getInt("durationSeconds")).isZero();
        given().get("/api/gym/exercises/" + (id + 1000)).then().statusCode(404);
    }

    // ------------------------------------------------------------------ feedback

    @Test
    void feedbackIsNeverGeneratedUnlessRequested() {
        long id = nextOk("REFRAMING").getLong("id");
        attemptOk(id, "my thoughts");
        given().get("/api/gym/attempts").then().body("[0].feedbackQuestions", hasSize(0));
        assertThat(Fakes.llm().calls()).isEmpty();
    }

    @Test
    void feedbackStoresOneOrTwoQuestionsAndIsNotRegeneratedWithoutNeed() {
        long id = nextOk("REFRAMING").getLong("id");
        long attemptId = attemptOk(id, "I think the meeting went badly because of me.").getLong("id");
        Fakes.llm().enqueue(feedbackJson("What did you notice first when you read your own answer?", "Who else was in the room?"));

        JsonPath fb = given().post("/api/gym/attempts/" + attemptId + "/feedback").then().statusCode(200).extract().jsonPath();
        assertThat(fb.getList("feedbackQuestions", String.class)).hasSize(2).allMatch(q -> q.endsWith("?"));
        assertThat(Fakes.llm().calls()).hasSize(1);
        assertThat(Fakes.llm().calls().get(0).request().messages().get(1).content()).contains("the meeting went badly");

        given().post("/api/gym/attempts/" + attemptId + "/feedback").then().statusCode(200)
                .body("feedbackQuestions", hasSize(2));
        assertThat(Fakes.llm().calls()).as("the stored questions are returned without a new LLM call").hasSize(1);
        given().get("/api/gym/attempts").then().body("[0].feedbackQuestions", hasSize(2));
    }

    @Test
    void feedbackRejectsPraiseAndStatementsThenRetries() {
        long id = nextOk("REFRAMING").getLong("id");
        long attemptId = attemptOk(id, "answer").getLong("id");
        Fakes.llm().enqueue(feedbackJson("Great insight! What else?"))
                .enqueue(feedbackJson("You did well here."))
                .enqueue(feedbackJson("What would change if the other reading were true?"));
        given().post("/api/gym/attempts/" + attemptId + "/feedback").then().statusCode(200)
                .body("feedbackQuestions[0]", equalTo("What would change if the other reading were true?"));
        assertThat(Fakes.llm().calls()).hasSize(3);
    }

    @Test
    void feedbackWithTwoQuestionsInOneItemIsRejected() {
        long id = nextOk("REFRAMING").getLong("id");
        long attemptId = attemptOk(id, "answer").getLong("id");
        String bad = feedbackJson("What happened? And why did it matter?");
        Fakes.llm().enqueue(bad).enqueue(bad).enqueue(bad);
        given().post("/api/gym/attempts/" + attemptId + "/feedback").then().statusCode(502).body("code", equalTo("LLM_OUTPUT_INVALID"));
        given().get("/api/gym/attempts").then().body("[0].feedbackQuestions", hasSize(0));
    }

    @Test
    void feedbackFailureIsCalmAndLeavesTheAttemptUnchanged() {
        long id = nextOk("REFRAMING").getLong("id");
        long attemptId = attemptOk(id, "my answer").getLong("id");
        Fakes.llm().enqueueFailure(new ProviderException("ollama", ProviderException.Kind.UNREACHABLE, "down"));
        given().post("/api/gym/attempts/" + attemptId + "/feedback").then().statusCode(503).body("code", equalTo("PROVIDER_UNREACHABLE"));
        given().get("/api/gym/attempts").then().body("[0].answer", equalTo("my answer")).body("[0].feedbackQuestions", hasSize(0));

        // it can be asked again later
        Fakes.llm().enqueue(feedbackJson("What was the hardest part?"));
        given().post("/api/gym/attempts/" + attemptId + "/feedback").then().statusCode(200).body("feedbackQuestions", hasSize(1));
        given().post("/api/gym/attempts/987654/feedback").then().statusCode(404);
    }

    @Test
    void feedbackForAGermanExerciseIsRequestedInGerman() {
        JsonPath ex = next("REFRAMING", "DE").then().statusCode(200).extract().jsonPath();
        long attemptId = attemptOk(ex.getLong("id"), "Meine Antwort").getLong("id");
        Fakes.llm().enqueue(feedbackJson("Was fällt dir an deiner Antwort als Erstes auf?"));
        given().post("/api/gym/attempts/" + attemptId + "/feedback").then().statusCode(200).body("feedbackQuestions", hasSize(1));
        assertThat(Fakes.llm().calls().get(0).request().messages().get(0).content()).contains("Deutsch");
    }
}
