package dev.rabauer.ai.diary.coach;

import dev.rabauer.ai.diary.entry.EntryRepository;
import dev.rabauer.ai.diary.spi.LlmGateway;
import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.support.Fakes;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

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
import static org.hamcrest.Matchers.notNullValue;

/** Conversation behaviour of the coach (SPEC section 5) against the fake LLM. */
@QuarkusTest
class CoachFlowTest {

    private static final String EN_ENTRY = "Today I was tired at work and I could not focus on the project that we have to finish.";
    private static final String DE_ENTRY = "Heute war ich müde und habe mich bei der Arbeit nicht konzentrieren können, aber es war ein ruhiger Tag.";

    @Inject
    CoachDataOwner owner;
    @Inject
    EntryRepository entries;

    @BeforeEach
    void clean() {
        Fakes.reset();
        owner.deleteAll();
        QuarkusTransaction.requiringNew().run(() -> entries.deleteAll());
    }

    private static List<String> kinds(JsonPath c) {
        return c.getList("messages.kind");
    }

    private static List<String> texts(JsonPath c) {
        return c.getList("messages.content");
    }

    // ------------------------------------------------------------------ start

    @Test
    void startIsRejectedWith409UnlessTheEntryIsFinished() {
        long draft = createEntry(EN_ENTRY, null);
        start(draft).then().statusCode(409).body("code", equalTo("ENTRY_NOT_FINISHED"));
        start(987654).then().statusCode(404);
        assertThat(Fakes.llm().calls()).isEmpty();
        given().get("/api/entries/" + draft + "/conversations").then().statusCode(200).body("$", hasSize(0));
    }

    @Test
    void startPersistsAllOpeningQuestionsButShowsOnlyTheFirst() {
        long entry = finishedEntry(EN_ENTRY, null);
        Fakes.llm().enqueue(opening("What made focusing so hard today?", "What would help tomorrow?", "Who could you tell about it?"));

        JsonPath c = startOk(entry);

        assertThat(c.getString("status")).isEqualTo("ACTIVE");
        assertThat(c.getString("language")).isEqualTo("EN");
        assertThat(c.getLong("entryId")).isEqualTo(entry);
        assertThat(kinds(c)).containsExactly("OPENING_QUESTION");
        assertThat(texts(c)).containsExactly("What made focusing so hard today?");
        assertThat(c.getString("messages[0].role")).isEqualTo("COACH");
        assertThat(c.getString("messages[0].createdAt")).isNotBlank();
        assertThat(c.getString("endedAt")).isNull();

        // the planned questions exist in storage (export), not in the DTO
        given().get("/api/conversations/" + c.getLong("id")).then().statusCode(200).body("messages", hasSize(1));
        given().get("/api/entries/" + entry + "/conversations").then().statusCode(200)
                .body("[0].id", equalTo(c.getInt("id"))).body("[0].messages", hasSize(1));
        given().get("/api/entries/" + entry).then().statusCode(200).body("hasCoachConversation", equalTo(true));
    }

    @Test
    void conversationsOfAnEntryAreListedNewestFirst() {
        long entry = finishedEntry(EN_ENTRY, null);
        Fakes.llm().enqueue(opening("First?", "Second?")).enqueue(opening("Third?", "Fourth?"));
        long older = startOk(entry).getLong("id");
        long newer = startOk(entry).getLong("id");
        given().get("/api/entries/" + entry + "/conversations").then().statusCode(200)
                .body("id", org.hamcrest.Matchers.contains((int) newer, (int) older));
    }

    @Test
    void oneOrFourOpeningQuestionsAreRetriedThenRejectedWithNothingPersisted() {
        long entry = finishedEntry(EN_ENTRY, null);
        Fakes.llm().enqueue(opening("Only one?")).enqueue(opening("Only one?")).enqueue(opening("Only one?"));
        start(entry).then().statusCode(502).body("code", equalTo("LLM_OUTPUT_INVALID"));
        assertThat(Fakes.llm().calls()).hasSize(3);

        Fakes.llm().reset();
        Fakes.llm().enqueue(opening("a?", "b?", "c?", "d?")).enqueue(opening("a?", "b?", "c?", "d?")).enqueue(opening("a?", "b?", "c?", "d?"));
        start(entry).then().statusCode(502);
        assertThat(Fakes.llm().calls()).hasSize(3);

        given().get("/api/entries/" + entry + "/conversations").then().statusCode(200).body("$", hasSize(0));
        given().get("/api/entries/" + entry).then().body("hasCoachConversation", equalTo(false));
    }

    @Test
    void anInvalidFirstAnswerIsRetriedAndTheSecondOneIsUsed() {
        long entry = finishedEntry(EN_ENTRY, null);
        Fakes.llm().enqueue(opening("Only one?")).enqueue(opening("What stayed with you?", "What do you need next?"));
        JsonPath c = startOk(entry);
        assertThat(Fakes.llm().calls()).hasSize(2);
        assertThat(texts(c)).containsExactly("What stayed with you?");
    }

    @Test
    void openingQuestionsWithPraiseAreRejected() {
        long entry = finishedEntry(EN_ENTRY, null);
        String bad = opening("Great insight to write this down! What happened?", "What do you need?");
        Fakes.llm().enqueue(bad).enqueue(bad).enqueue(bad);
        start(entry).then().statusCode(502);
    }

    @Test
    void providerFailureOnStartIs503AndNothingIsPersisted() {
        long entry = finishedEntry(EN_ENTRY, null);
        Fakes.llm().enqueueFailure(new ProviderException("ollama", ProviderException.Kind.UNREACHABLE, "down"));
        start(entry).then().statusCode(503).body("code", equalTo("PROVIDER_UNREACHABLE"));
        given().get("/api/entries/" + entry + "/conversations").then().body("$", hasSize(0));
    }

    // ------------------------------------------------------------------ progression

    @Test
    void questionsAreAskedOneAtATimeAndTheNextIsRevealedAfterTheFollowUp() {
        long entry = finishedEntry(EN_ENTRY, null);
        Fakes.llm().enqueue(opening("Q1 what was hard?", "Q2 what helps?", "Q3 who knows?"));
        long id = startOk(entry).getLong("id");

        Fakes.llm().enqueue(turn("FOLLOW_UP_QUESTION", "What exactly pulled your attention away?"));
        JsonPath afterFirst = answerOk(id, "The noise in the office.");
        assertThat(kinds(afterFirst)).containsExactly("OPENING_QUESTION", "USER_ANSWER", "FOLLOW_UP_QUESTION");
        assertThat(Fakes.llm().calls()).hasSize(2);

        // the answer to the follow-up reveals the next planned question without an LLM call
        JsonPath afterSecond = answerOk(id, "Mostly phone calls.");
        assertThat(kinds(afterSecond)).containsExactly("OPENING_QUESTION", "USER_ANSWER", "FOLLOW_UP_QUESTION", "USER_ANSWER", "OPENING_QUESTION");
        assertThat(texts(afterSecond).get(4)).isEqualTo("Q2 what helps?");
        assertThat(Fakes.llm().calls()).hasSize(2);

        Fakes.llm().enqueue(turn("FOLLOW_UP_QUESTION", "Which of those would you try first?"));
        answerOk(id, "Quiet hours in the morning.");
        JsonPath third = answerOk(id, "Yes, I think so.");
        assertThat(texts(third).get(third.getList("messages").size() - 1)).isEqualTo("Q3 who knows?");

        // all questions revealed: the turn may now be a follow-up or a closing
        Fakes.llm().enqueue(turn("FOLLOW_UP_QUESTION", "What would you tell your colleague?"));
        answerOk(id, "My team lead.");
        Fakes.llm().enqueue(turn("CLOSING", "Thank you for taking this time to look at your day."));
        JsonPath closed = answerOk(id, "That's all for today.");
        assertThat(kinds(closed).get(closed.getList("messages").size() - 1)).isEqualTo("CLOSING");
        assertThat(closed.getString("status")).as("CLOSING is a message, /end ends the conversation").isEqualTo("ACTIVE");

        // timestamps are present for the whole transcript
        assertThat(closed.getList("messages.createdAt")).allMatch(t -> t != null);
    }

    @Test
    void closingIsOnlyOfferedWhenQuestionsAreExhaustedOrTheUserIsDone() {
        long entry = finishedEntry(EN_ENTRY, null);
        Fakes.llm().enqueue(opening("Q1 what was hard?", "Q2 what helps?"));
        long id = startOk(entry).getLong("id");

        Fakes.llm().enqueue(turn("FOLLOW_UP_QUESTION", "What pulled your attention away?"));
        answerOk(id, "The noise.");
        assertThat(Fakes.llm().calls().get(1).schema().schemaJson()).doesNotContain("CLOSING").doesNotContain("EXPLANATION");

        // the user says they are done while a question is still planned: only CLOSING is allowed
        Fakes.llm().enqueue(turn("CLOSING", "Thank you for your time. We can stop here."));
        JsonPath closed = answerOk(id, "I'm done for today.");
        assertThat(Fakes.llm().calls().get(2).schema().schemaJson()).contains("CLOSING").doesNotContain("FOLLOW_UP_QUESTION");
        assertThat(kinds(closed).get(closed.getList("messages").size() - 1)).isEqualTo("CLOSING");
    }

    @Test
    void voiceFlagIsStoredOnTheAnswer() {
        long entry = finishedEntry(EN_ENTRY, null);
        Fakes.llm().enqueue(opening("Q1 what?", "Q2 why?")).enqueue(turn("FOLLOW_UP_QUESTION", "What else comes to mind?"));
        long id = startOk(entry).getLong("id");
        JsonPath c = answer(id, "Spoken answer.", true).then().statusCode(200).extract().jsonPath();
        assertThat(c.getBoolean("messages[1].voice")).isTrue();
        assertThat(c.getString("messages[1].kind")).isEqualTo("USER_ANSWER");
        assertThat(c.getString("messages[1].role")).isEqualTo("USER");
        assertThat(c.getBoolean("messages[0].voice")).isFalse();
    }

    // ------------------------------------------------------------------ explanations, praise

    @Test
    void explanationIsRefusedUnlessTheUserExplicitlyAsked() {
        long entry = finishedEntry(EN_ENTRY, null);
        Fakes.llm().enqueue(opening("Q1 what?", "Q2 why?"));
        long id = startOk(entry).getLong("id");

        Fakes.llm().enqueue(turn("EXPLANATION", "Tiredness often follows stress. Does that fit your week?"))
                .enqueue(turn("FOLLOW_UP_QUESTION", "What was your week like?"));
        JsonPath c = answerOk(id, "I was exhausted.");

        List<dev.rabauer.ai.diary.spi.fake.FakeLlmGateway.Call> calls = Fakes.llm().calls();
        assertThat(calls).hasSize(3); // opening + rejected explanation + accepted follow-up
        assertThat(calls.get(1).schema().schemaJson()).doesNotContain("EXPLANATION");
        assertThat(calls.get(2).request().messages().stream().map(LlmGateway.Message::content).collect(Collectors.joining("\n")))
                .contains("EXPLANATION is not allowed");
        assertThat(kinds(c)).containsExactly("OPENING_QUESTION", "USER_ANSWER", "FOLLOW_UP_QUESTION");
    }

    @Test
    void explanationAfterExplicitRequestIsAcceptedInEnglishAndEndsWithAQuestion() {
        long entry = finishedEntry(EN_ENTRY, null);
        Fakes.llm().enqueue(opening("Q1 what?", "Q2 why?"));
        long id = startOk(entry).getLong("id");

        Fakes.llm().enqueue(turn("EXPLANATION", "Tiredness often follows a long period of stress. Does that match your last weeks?"));
        JsonPath c = answerOk(id, "Can you explain why I am so tired?");

        assertThat(Fakes.llm().calls().get(1).schema().schemaJson()).contains("EXPLANATION");
        assertThat(kinds(c)).containsExactly("OPENING_QUESTION", "USER_ANSWER", "EXPLANATION");
        assertThat(texts(c).get(2)).endsWith("?");
    }

    @Test
    void explanationAfterExplicitRequestIsAcceptedInGermanWithGermanPrompt() {
        long entry = finishedEntry(DE_ENTRY, null);
        Fakes.llm().enqueue(opening("Was hat dich heute am meisten beschäftigt?", "Was würde dir morgen helfen?"));
        long id = startOk(entry).getLong("id");

        Fakes.llm().enqueue(turn("EXPLANATION", "Müdigkeit folgt oft auf Anspannung. Passt das zu deiner Woche?"));
        JsonPath c = answerOk(id, "Was denkst du, warum ich so müde bin?");

        assertThat(kinds(c)).containsExactly("OPENING_QUESTION", "USER_ANSWER", "EXPLANATION");
        String system = Fakes.llm().calls().get(1).request().messages().get(0).content();
        assertThat(system).contains("Antworte auf Deutsch").contains("ausdrücklich um eine Erklärung");
    }

    @Test
    void anExplanationWithoutATrailingQuestionIsRejected() {
        long entry = finishedEntry(EN_ENTRY, null);
        Fakes.llm().enqueue(opening("Q1 what?", "Q2 why?"));
        long id = startOk(entry).getLong("id");

        String bad = turn("EXPLANATION", "Tiredness often follows stress.");
        Fakes.llm().enqueue(bad).enqueue(bad).enqueue(bad);
        answer(id, "Please explain why I am tired.", false).then().statusCode(502);

        // the user's answer is kept, nothing from the LLM was stored
        JsonPath c = given().get("/api/conversations/" + id).then().statusCode(200).extract().jsonPath();
        assertThat(kinds(c)).containsExactly("OPENING_QUESTION", "USER_ANSWER");
    }

    @Test
    void genericPraiseIsRejectedAndRetried() {
        long entry = finishedEntry(EN_ENTRY, null);
        Fakes.llm().enqueue(opening("Q1 what?", "Q2 why?"));
        long id = startOk(entry).getLong("id");

        Fakes.llm().enqueue(turn("FOLLOW_UP_QUESTION", "Great insight! What made you notice that?"))
                .enqueue(turn("FOLLOW_UP_QUESTION", "What made you notice that?"));
        JsonPath c = answerOk(id, "I noticed the noise bothers me.");
        assertThat(Fakes.llm().calls()).hasSize(3);
        assertThat(texts(c).get(2)).isEqualTo("What made you notice that?");
    }

    @Test
    void persistentPraiseIsRejectedWith502() {
        long entry = finishedEntry(DE_ENTRY, null);
        Fakes.llm().enqueue(opening("Was beschäftigt dich?", "Was brauchst du?"));
        long id = startOk(entry).getLong("id");
        String bad = turn("FOLLOW_UP_QUESTION", "Das ist wirklich großartig. Was hat dir geholfen?");
        Fakes.llm().enqueue(bad).enqueue(bad).enqueue(bad);
        answer(id, "Ich habe Pause gemacht.", false).then().statusCode(502).body("code", equalTo("LLM_OUTPUT_INVALID"));
    }

    // ------------------------------------------------------------------ crisis

    @Test
    void crisisInTheEntryMakesNoLlmCallAndReturnsTheFixedEnglishMessage() {
        long entry = finishedEntry("I am so alone and sometimes I want to kill myself.", null);
        JsonPath c = startOk(entry);
        assertThat(Fakes.llm().calls()).isEmpty();
        assertThat(c.getString("status")).isEqualTo("ENDED");
        assertThat(c.getString("endedAt")).isNotNull();
        assertThat(kinds(c)).containsExactly("CLOSING");
        assertThat(texts(c).get(0)).contains("crisis line").contains("116 123");
        assertThat(c.getString("language")).isEqualTo("EN");
    }

    @Test
    void crisisInAGermanEntryReturnsTheGermanMessageWithTelefonseelsorge() {
        long entry = finishedEntry("Ich bin so müde und ich will mich umbringen, es ist alles nicht mehr gut.", null);
        JsonPath c = startOk(entry);
        assertThat(Fakes.llm().calls()).isEmpty();
        assertThat(c.getString("language")).isEqualTo("DE");
        assertThat(texts(c).get(0)).contains("Telefonseelsorge").contains("0800 111 0 111").contains("0800 111 0 222").contains("116 123");
        given().post("/api/conversations/" + c.getLong("id") + "/pause").then().statusCode(409);
    }

    @Test
    void crisisInAnAnswerEndsTheConversationWithoutAnLlmCall() {
        long entry = finishedEntry(DE_ENTRY, null);
        Fakes.llm().enqueue(opening("Was beschäftigt dich?", "Was brauchst du?"));
        long id = startOk(entry).getLong("id");
        int callsBefore = Fakes.llm().calls().size();

        JsonPath c = answerOk(id, "Manchmal denke ich an Suizid.");

        assertThat(Fakes.llm().calls()).hasSize(callsBefore);
        assertThat(c.getString("status")).isEqualTo("ENDED");
        assertThat(kinds(c)).containsExactly("OPENING_QUESTION", "USER_ANSWER", "CLOSING");
        assertThat(texts(c).get(2)).contains("Telefonseelsorge");
        answer(id, "Noch etwas.", false).then().statusCode(409).body("code", equalTo("CONVERSATION_ENDED"));
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    void pauseResumeEndTransitionsAndConflicts() {
        long entry = finishedEntry(EN_ENTRY, null);
        Fakes.llm().enqueue(opening("Q1 what?", "Q2 why?"));
        long id = startOk(entry).getLong("id");
        String base = "/api/conversations/" + id;

        given().post(base + "/pause").then().statusCode(200).body("status", equalTo("PAUSED"));
        given().post(base + "/pause").then().statusCode(200).body("status", equalTo("PAUSED")); // idempotent
        answer(id, "Hello.", false).then().statusCode(409).body("code", equalTo("CONVERSATION_PAUSED"));
        given().get(base).then().body("messages", hasSize(1)); // rejected answer was not stored

        given().post(base + "/resume").then().statusCode(200).body("status", equalTo("ACTIVE"));
        given().post(base + "/resume").then().statusCode(200).body("status", equalTo("ACTIVE"));

        String endedAt = given().post(base + "/end").then().statusCode(200).body("status", equalTo("ENDED")).body("endedAt", notNullValue())
                .extract().jsonPath().getString("endedAt");
        given().post(base + "/end").then().statusCode(200).body("endedAt", equalTo(endedAt)); // idempotent
        given().post(base + "/pause").then().statusCode(409).body("code", equalTo("CONVERSATION_ENDED"));
        given().post(base + "/resume").then().statusCode(409);
        answer(id, "Hello.", false).then().statusCode(409).body("code", equalTo("CONVERSATION_ENDED"));
    }

    @Test
    void aPausedConversationCanBeEndedDirectly() {
        long entry = finishedEntry(EN_ENTRY, null);
        Fakes.llm().enqueue(opening("Q1 what?", "Q2 why?"));
        long id = startOk(entry).getLong("id");
        given().post("/api/conversations/" + id + "/pause").then().statusCode(200);
        given().post("/api/conversations/" + id + "/end").then().statusCode(200).body("status", equalTo("ENDED"));
    }

    @Test
    void unknownConversationIs404AndBlankAnswersAre400() {
        given().get("/api/conversations/987654").then().statusCode(404);
        given().post("/api/conversations/987654/pause").then().statusCode(404);
        given().contentType("application/json").body("{\"text\":\"x\",\"voice\":false}").post("/api/conversations/987654/answers").then().statusCode(404);

        long entry = finishedEntry(EN_ENTRY, null);
        Fakes.llm().enqueue(opening("Q1 what?", "Q2 why?"));
        long id = startOk(entry).getLong("id");
        answer(id, "   ", false).then().statusCode(400).body("code", equalTo("EMPTY_ANSWER"));
    }

    @Test
    void anAnswerIsKeptWhenTheProviderFails() {
        long entry = finishedEntry(EN_ENTRY, null);
        Fakes.llm().enqueue(opening("Q1 what?", "Q2 why?"));
        long id = startOk(entry).getLong("id");
        Fakes.llm().enqueueFailure(new ProviderException("ollama", ProviderException.Kind.UNREACHABLE, "down"));
        answer(id, "My answer.", false).then().statusCode(503);
        JsonPath c = given().get("/api/conversations/" + id).then().extract().jsonPath();
        assertThat(kinds(c)).containsExactly("OPENING_QUESTION", "USER_ANSWER");
        assertThat(c.getString("status")).isEqualTo("ACTIVE");
        assertThat(c.getString("endedAt")).isNull();
    }
}
