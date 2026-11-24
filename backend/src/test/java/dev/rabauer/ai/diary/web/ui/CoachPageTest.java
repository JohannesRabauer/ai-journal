package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.api.coach.ConversationDto;
import dev.rabauer.ai.diary.api.coach.StartConversationRequest;
import dev.rabauer.ai.diary.api.entry.CreateEntryRequest;
import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.coach.CoachDataOwner;
import dev.rabauer.ai.diary.coach.CoachService;
import dev.rabauer.ai.diary.entry.EntryRepository;
import dev.rabauer.ai.diary.entry.EntryService;
import dev.rabauer.ai.diary.support.Fakes;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** The coach conversation as a full page and as the panel beside the entry, including the no-JavaScript forms. */
@QuarkusTest
class CoachPageTest {

    @Inject
    EntryService entries;
    @Inject
    EntryRepository repository;
    @Inject
    CoachService coach;
    @Inject
    CoachDataOwner owner;

    @BeforeEach
    void clean() {
        Fakes.reset();
        owner.deleteAll();
        QuarkusTransaction.requiringNew().run(() -> repository.deleteAll());
    }

    private static String opening(String... questions) {
        return "{\"questions\":[" + String.join(",", java.util.Arrays.stream(questions).map(q -> "\"" + q + "\"").toList()) + "]}";
    }

    private static String followUp(String text) {
        return "{\"kind\":\"FOLLOW_UP_QUESTION\",\"text\":\"" + text + "\"}";
    }

    private EntryDto finished(String text) {
        EntryDto e = entries.create(new CreateEntryRequest(text, LocalDate.of(2026, 11, 24).atTime(7, 15), null, null));
        entries.finish(e.id());
        return e;
    }

    private static String html(String path) {
        return given().header("Accept-Language", "en").get(path).then().statusCode(200).contentType("text/html")
                .extract().asString();
    }

    private record Form(String cookie, String token) {
    }

    private static Form loadForm(String path) {
        Response r = given().get(path);
        Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]*)\"").matcher(r.asString());
        assertThat(m.find()).as("hidden _csrf field").isTrue();
        return new Form(r.cookie("diary-csrf"), m.group(1));
    }

    private static int count(String haystack, String needle) {
        Matcher m = Pattern.compile(Pattern.quote(needle)).matcher(haystack);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    // ---- states ----

    @Test
    void unfinishedEntryShowsTheHintAndNoStartButton() {
        EntryDto e = entries.create(new CreateEntryRequest("Not yet.", LocalDate.of(2026, 11, 24).atTime(7, 15), null, null));
        for (String path : new String[]{"/journal/" + e.id() + "/reflect", "/journal/" + e.id()}) {
            String body = html(path);
            assertThat(body).contains("You can reflect on an entry once you have finished it.")
                    .doesNotContain("data-coach-form=\"start\"");
        }
    }

    @Test
    void fullPageStartStateHasOneH1AndAStartForm() {
        EntryDto e = finished("Done and dusted.");
        String body = html("/journal/" + e.id() + "/reflect");
        assertThat(count(body, "<h1")).isEqualTo(1);
        assertThat(body).contains("<h1 id=\"coach-page-title\">Reflect</h1>").contains("Start reflecting")
                .contains("action=\"/journal/" + e.id() + "/reflect/start\"").contains("name=\"_csrf\"")
                .contains("href=\"/journal/" + e.id() + "\"").contains("/js/coach.js").contains("/css/coach.css");
        assertThat(body).doesNotContain("<script>").doesNotContain(" style=\"").doesNotContainPattern(" on[a-z]+=\"");
        assertThat(body).contains("<noscript>").contains("aria-current=\"page\"");
    }

    @Test
    void entryPageShowsThePanelBesideTheEntryWithTheStartState() {
        EntryDto e = finished("Done and dusted.");
        String body = html("/journal/" + e.id());
        assertThat(body).contains("class=\"entries-layout\"").contains("id=\"coach\"").contains("Start reflecting")
                .contains("Open as full page").contains("href=\"/journal/" + e.id() + "/reflect\"");
        assertThat(count(body, "<h1")).isEqualTo(1);
        assertThat(body).doesNotContain("Answer a few open questions");
    }

    @Test
    void activeConversationListsLabelledBubblesAndTheAnswerForm() {
        EntryDto e = finished("Today I was tired at work and I could not focus on the project that we have to finish.");
        Fakes.llm().enqueue(opening("What made focusing so hard today?", "What would help tomorrow?"));
        ConversationDto c = coach.start(new StartConversationRequest(e.id()));
        String body = html("/journal/" + e.id() + "/reflect");
        assertThat(body).contains("Reflection in progress.").contains("<ol class=\"coach-transcript\"")
                .contains("<span class=\"coach-message__speaker\">Coach</span>").contains("Question 1")
                .contains("Current question").contains("What made focusing so hard today?")
                .contains("<label for=\"coach-answer\">Your answer</label>").contains("<textarea id=\"coach-answer\"")
                .contains("action=\"/journal/" + e.id() + "/reflect/answer\"").contains("<diary-answer-recorder")
                .contains("data-coach-form=\"pause\"").contains("data-coach-form=\"end\"").contains("End reflection")
                .contains("data-conversation-id=\"" + c.id() + "\"");
        // the panel on the entry page is the same conversation
        assertThat(html("/journal/" + e.id())).contains("What made focusing so hard today?").contains("Reflection in progress.");
    }

    @Test
    void pausedEndedAndStartNewStates() {
        EntryDto e = finished("Today I was tired at work and I could not focus on the project that we have to finish.");
        Fakes.llm().enqueue(opening("First?", "Second?"));
        ConversationDto c = coach.start(new StartConversationRequest(e.id()));
        coach.pause(c.id());
        String paused = html("/journal/" + e.id() + "/reflect");
        assertThat(paused).contains("Paused. Nothing happens until you continue.").contains("data-coach-form=\"resume\"")
                .doesNotContain("id=\"coach-answer\"");
        coach.resume(c.id());
        coach.end(c.id());
        String ended = html("/journal/" + e.id() + "/reflect");
        assertThat(ended).contains("This reflection has ended.").contains("Start a new reflection")
                .contains("?new=1#coach").doesNotContain("id=\"coach-answer\"");
        // "Start a new reflection" shows the calm start state and starts nothing
        String fresh = html("/journal/" + e.id() + "/reflect?new=1");
        assertThat(fresh).contains("data-coach-form=\"start\"").doesNotContain("coach-transcript");
        assertThat(coach.forEntry(e.id())).hasSize(1);
    }

    @Test
    void fragmentIsOnlyThePanelBody() {
        EntryDto e = finished("Done and dusted.");
        String body = html("/journal/" + e.id() + "/reflect?fragment=1");
        assertThat(body).startsWith("<div class=\"coach-body\"").doesNotContain("<html").doesNotContain("<h1");
        given().get("/journal/987654/reflect?fragment=1").then().statusCode(404);
    }

    @Test
    void coachAndUserTextIsEscaped() {
        EntryDto e = finished("Today I was tired at work and I could not focus on the project that we have to finish.");
        Fakes.llm().enqueue(opening("Why <script>alert(1)</script> &amp; more?", "Second?"));
        ConversationDto c = coach.start(new StartConversationRequest(e.id()));
        Fakes.llm().enqueue(followUp("And then?"));
        coach.answer(c.id(), new dev.rabauer.ai.diary.api.coach.AnswerRequest("I said <b>bold</b> & \"quoted\"", false));
        for (String path : new String[]{"/journal/" + e.id() + "/reflect", "/journal/" + e.id()}) {
            String body = html(path);
            assertThat(body).doesNotContain("<script>alert").doesNotContain("<b>bold</b>")
                    .contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp;amp; more?").contains("I said &lt;b&gt;bold&lt;/b&gt; &amp;");
        }
    }

    @Test
    void unknownEntryIs404() {
        given().get("/journal/987654/reflect").then().statusCode(404);
        given().get("/journal/987654").then().statusCode(404);
    }

    @Test
    void germanTexts() {
        EntryDto e = finished("Fertig.");
        String body = given().header("Accept-Language", "de").get("/journal/" + e.id() + "/reflect").then().statusCode(200)
                .extract().asString();
        assertThat(body).contains("lang=\"de\"").contains("Reflektieren").doesNotContain("??coach");
    }

    // ---- no-JavaScript forms ----

    @Test
    void startAnswerPauseResumeEndWorkWithFormsAndRedirectBack() {
        EntryDto e = finished("Today I was tired at work and I could not focus on the project that we have to finish.");
        String page = "/journal/" + e.id() + "/reflect";
        Form form = loadForm(page);
        Fakes.llm().enqueue(opening("What made focusing so hard today?", "Second?"));
        given().redirects().follow(false).cookie("diary-csrf", form.cookie()).formParam("_csrf", form.token())
                .post(page + "/start").then().statusCode(303).header("Location", org.hamcrest.Matchers.endsWith(page + "#coach"));
        assertThat(coach.forEntry(e.id())).hasSize(1);

        Fakes.llm().enqueue(followUp("What exactly pulled your attention away?"));
        given().redirects().follow(false).cookie("diary-csrf", form.cookie()).formParam("_csrf", form.token())
                .formParam("text", "Meetings, mostly.").post(page + "/answer").then().statusCode(303);
        ConversationDto c = coach.forEntry(e.id()).get(0);
        assertThat(c.messages()).extracting(m -> m.content()).contains("Meetings, mostly.", "What exactly pulled your attention away?");
        assertThat(c.messages().get(c.messages().size() - 1).content()).isEqualTo("What exactly pulled your attention away?");

        given().redirects().follow(false).cookie("diary-csrf", form.cookie()).formParam("_csrf", form.token())
                .post(page + "/pause").then().statusCode(303);
        assertThat(coach.forEntry(e.id()).get(0).status().name()).isEqualTo("PAUSED");
        given().redirects().follow(false).cookie("diary-csrf", form.cookie()).formParam("_csrf", form.token())
                .post(page + "/resume").then().statusCode(303);
        given().redirects().follow(false).cookie("diary-csrf", form.cookie()).formParam("_csrf", form.token())
                .post(page + "/end").then().statusCode(303);
        assertThat(coach.forEntry(e.id()).get(0).status().name()).isEqualTo("ENDED");
    }

    @Test
    void blankAnswerIsIgnored() {
        EntryDto e = finished("Today I was tired at work and I could not focus on the project that we have to finish.");
        Fakes.llm().enqueue(opening("First?", "Second?"));
        coach.start(new StartConversationRequest(e.id()));
        String page = "/journal/" + e.id() + "/reflect";
        Form form = loadForm(page);
        given().redirects().follow(false).cookie("diary-csrf", form.cookie()).formParam("_csrf", form.token())
                .formParam("text", "   ").post(page + "/answer").then().statusCode(303);
        assertThat(coach.forEntry(e.id()).get(0).messages()).hasSize(1);
    }

    @Test
    void aFailedStartShowsACalmErrorAndKeepsTheAnswerText() {
        EntryDto e = finished("Today I was tired at work and I could not focus on the project that we have to finish.");
        Fakes.llm().enqueue(opening("First?", "Second?"));
        coach.start(new StartConversationRequest(e.id()));
        coach.end(coach.forEntry(e.id()).get(0).id());
        String page = "/journal/" + e.id() + "/reflect";
        Form form = loadForm(page + "?new=1");
        // answering an ended conversation is a conflict: the page shows the state again, with the explanation
        String body = given().cookie("diary-csrf", form.cookie()).formParam("_csrf", form.token())
                .formParam("text", "my <i>draft</i>").post(page + "/answer").then().statusCode(200).extract().asString();
        assertThat(body).contains("This conversation has changed in the meantime.").contains("role=\"alert\"")
                .doesNotContain("<i>draft</i>");
    }

    @Test
    void formsWithoutAValidTokenAreRejectedAndChangeNothing() {
        EntryDto e = finished("Today I was tired at work and I could not focus on the project that we have to finish.");
        String page = "/journal/" + e.id() + "/reflect";
        Form form = loadForm(page);
        for (String action : new String[]{"start", "answer", "pause", "resume", "end"}) {
            given().redirects().follow(false).cookie("diary-csrf", form.cookie()).post(page + "/" + action)
                    .then().statusCode(403);
            given().redirects().follow(false).cookie("diary-csrf", form.cookie()).formParam("_csrf", "nope")
                    .post(page + "/" + action).then().statusCode(403);
        }
        assertThat(coach.forEntry(e.id())).isEmpty();
        assertThat(Fakes.llm().calls()).isEmpty();
    }
}
