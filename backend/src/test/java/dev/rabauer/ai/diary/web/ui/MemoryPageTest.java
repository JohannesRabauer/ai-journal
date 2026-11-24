package dev.rabauer.ai.diary.web.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rabauer.ai.diary.entry.EntryRepository;
import dev.rabauer.ai.diary.memory.MemoryDataOwner;
import dev.rabauer.ai.diary.memory.MemoryItemEntity;
import dev.rabauer.ai.diary.support.Fakes;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static dev.rabauer.ai.diary.web.ui.PageTestSupport.count;
import static dev.rabauer.ai.diary.web.ui.PageTestSupport.get;
import static dev.rabauer.ai.diary.web.ui.PageTestSupport.html;
import static dev.rabauer.ai.diary.web.ui.PageTestSupport.session;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class MemoryPageTest {

    private static final String ENTRY_TEXT = "Today I learned that spaced repetition beats cramming.\n"
            + "I decided to review my notes every weekend, because   short sessions work better for me.";
    private static final String PROMPT_1 = "What beats cramming?";
    private static final String ANSWER_1 = "ZEBRAANSWER-8841 spaced repetition";
    private static final String PROMPT_2 = "When do I review my notes <b>often</b>?";
    private static final String ANSWER_2 = "<script>alert(1)</script> every weekend";
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
    }

    private static String json(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static long finishedEntry() {
        long id = given().contentType(ContentType.JSON).body(json(Map.of("text", ENTRY_TEXT, "language", "EN"))).post("/api/entries")
                .then().statusCode(200).extract().jsonPath().getLong("id");
        given().post("/api/entries/" + id + "/finish").then().statusCode(200);
        return id;
    }

    /** Two pending candidates for one finished entry; returns their ids (the first about PROMPT_1). */
    private static List<Long> twoCandidates(long entry) {
        Fakes.llm().enqueue(json(Map.of("candidates", List.of(
                Map.of("type", "FACT", "prompt", PROMPT_1, "answer", ANSWER_1, "sourceQuote", "Spaced   Repetition BEATS cramming."),
                Map.of("type", "DECISION", "prompt", PROMPT_2, "answer", ANSWER_2, "sourceQuote", "I decided to review my notes every weekend")))));
        return given().contentType(ContentType.JSON).body(json(Map.of("sourceType", "ENTRY", "sourceId", entry)))
                .post("/api/memory/extractions").then().statusCode(200).extract().jsonPath().getList("id", Long.class);
    }

    private static long approve(long candidate) {
        given().contentType(ContentType.JSON).body("{\"decision\":\"APPROVE\"}").post("/api/memory/candidates/" + candidate + "/decision")
                .then().statusCode(200);
        return given().get("/api/memory/items").then().extract().jsonPath().getList("id", Long.class).stream().max(Long::compare).orElseThrow();
    }

    private static void makeDue(long itemId) {
        QuarkusTransaction.requiringNew().run(() -> {
            MemoryItemEntity i = MemoryItemEntity.findById(itemId);
            i.nextReviewOn = LocalDate.now().minusDays(1);
        });
    }

    @Test
    void emptyStateIsCalm() {
        String body = html("/memory");

        assertThat(count(body, "<h1")).isEqualTo(1);
        assertThat(body).contains("lang=\"en\"").contains("aria-current=\"page\"").contains("Nothing is due today.");
        assertThat(body).contains("That is all for today. Nothing else is waiting.");
        assertThat(count(body, "memory-tile\"")).isEqualTo(3);
        assertThat(body).contains("Suggestions from your writing.").contains("No cards yet.").contains("Memory techniques");
        assertThat(body).contains("<noscript>").contains("Reviewing cards needs JavaScript");
        // every "Also here" panel starts closed
        assertThat(count(body, "memory-section\" id=\"memory-panel-")).isEqualTo(3);
        assertThat(body).doesNotContain("<script>").doesNotContain(" onclick=").doesNotContain("style=\"");
        assertThat(body).contains("src=\"/js/memory.js\"");
    }

    @Test
    void dueCardShowsOnlyThePromptNeverTheAnswer() {
        long entry = finishedEntry();
        List<Long> ids = twoCandidates(entry);
        long item = approve(ids.get(0));
        makeDue(item);

        String body = html("/memory");

        assertThat(body).contains("One card due today").contains("Card 1 of 1").contains(PROMPT_1);
        assertThat(body).contains("data-id=\"" + item + "\"").contains("data-type=\"FACT\"");
        assertThat(body).doesNotContain("ZEBRAANSWER"); // retrieval practice: the answer appears only after an attempt
        assertThat(body).contains("id=\"memory-tpl-reveal\"");
        // the second candidate is still a suggestion: text escaped, answer visible there
        assertThat(body).contains("&lt;script&gt;alert(1)&lt;/script&gt; every weekend").doesNotContain("<script>alert");
        assertThat(body).contains("When do I review my notes &lt;b&gt;often&lt;/b&gt;?").doesNotContain("<b>often</b>");
        assertThat(body).contains("Your cards · 1").contains("Next review:");
        assertThat(body).contains("action=\"/memory/candidates/" + ids.get(1) + "/approve\"").contains("name=\"_csrf\"");
        assertThat(body).contains("action=\"/memory/items/" + item + "/delete\"").contains("Delete card: " + PROMPT_1);
    }

    @Test
    void panelsOpenFromTheAddressAndEditShowsLabelledFields() {
        long entry = finishedEntry();
        List<Long> ids = twoCandidates(entry);

        String closed = html("/memory");
        assertThat(closed).contains("id=\"memory-panel-candidates\" aria-labelledby=\"memory-candidates-title\" hidden");
        String open = html("/memory?panel=candidates");
        assertThat(open).doesNotContain("id=\"memory-panel-candidates\" aria-labelledby=\"memory-candidates-title\" hidden");
        assertThat(open).contains("Suggestions from your writing. Nothing is kept until you approve it.");

        String edit = html("/memory?edit=" + ids.get(0));
        assertThat(count(edit, "<h1")).isEqualTo(1);
        assertThat(edit).contains("<label for=\"memory-edit-prompt\">Question</label>").contains("<label for=\"memory-edit-answer\">Answer</label>")
                .contains("<label for=\"memory-edit-type\">Type</label>").contains("Keep with changes").contains(ANSWER_1);
        assertThat(html("/memory?edit=" + ids.get(0) + "&problem=promptEmpty")).contains("The question must not be empty.");
    }

    @Test
    void openingForExtractionAskedNothingAndOffersAFormWithoutJavaScript() {
        long entry = finishedEntry();

        String body = html("/memory?source=entry&id=" + entry);

        assertThat(body).contains("data-extract-source=\"entry\"").contains("data-extract-id=\"" + entry + "\"");
        assertThat(body).contains("action=\"/memory/extract\"").contains("Extract learnings");
        // loading the page never starts an extraction
        given().get("/api/memory/candidates").then().statusCode(200).body("$", hasSize(0));
        assertThat(html("/memory?source=bogus&id=3")).contains("data-extract-source=\"\"");
    }

    @Test
    void formPostsNeedTheToken() {
        long entry = finishedEntry();
        List<Long> ids = twoCandidates(entry);
        given().contentType(ContentType.URLENC).post("/memory/candidates/" + ids.get(0) + "/approve").then().statusCode(403);
        given().contentType(ContentType.URLENC).post("/memory/candidates/" + ids.get(0) + "/discard").then().statusCode(403);
        given().contentType(ContentType.URLENC).post("/memory/items/1/delete").then().statusCode(403);
        given().contentType(ContentType.URLENC).formParam("source", "entry").formParam("id", entry).post("/memory/extract").then().statusCode(403);
        given().get("/api/memory/items").then().body("$", hasSize(0));
    }

    @Test
    void approveEditDiscardAndDeleteWithoutJavaScript() {
        long entry = finishedEntry();
        List<Long> ids = twoCandidates(entry);
        PageTestSupport.Session s = session("/memory");

        // keep as is
        given().redirects().follow(false).cookie("diary-csrf", s.cookie()).contentType(ContentType.URLENC).formParam("_csrf", s.token())
                .post("/memory/candidates/" + ids.get(0) + "/approve").then().statusCode(303)
                .header("Location", endsWith("/memory?panel=candidates&msg=approved"));
        given().get("/api/memory/items").then().body("$", hasSize(1));

        // invalid edit: nothing changes, the form comes back with a message
        given().redirects().follow(false).cookie("diary-csrf", s.cookie()).contentType(ContentType.URLENC).formParam("_csrf", s.token())
                .formParam("prompt", "  ").formParam("answer", "x").formParam("type", "FACT")
                .post("/memory/candidates/" + ids.get(1) + "/approve").then().statusCode(303)
                .header("Location", endsWith("edit=" + ids.get(1) + "&problem=promptEmpty"));
        given().get("/api/memory/items").then().body("$", hasSize(1));

        // keep with changes
        given().redirects().follow(false).cookie("diary-csrf", s.cookie()).contentType(ContentType.URLENC).formParam("_csrf", s.token())
                .formParam("prompt", "When do I review?").formParam("answer", "Every weekend").formParam("type", "PRINCIPLE")
                .post("/memory/candidates/" + ids.get(1) + "/approve").then().statusCode(303);
        var items = given().get("/api/memory/items").then().body("$", hasSize(2)).extract().jsonPath();
        assertThat(items.getList("prompt", String.class)).contains("When do I review?");
        assertThat(items.getList("type", String.class)).contains("PRINCIPLE");

        // delete a card
        long first = items.getLong("[0].id");
        given().redirects().follow(false).cookie("diary-csrf", s.cookie()).contentType(ContentType.URLENC).formParam("_csrf", s.token())
                .post("/memory/items/" + first + "/delete").then().statusCode(303).header("Location", endsWith("msg=itemDeleted"));
        given().get("/api/memory/items").then().body("$", hasSize(1));
        assertThat(html("/memory?panel=items&msg=itemDeleted")).contains("Card deleted.");

        // unknown candidate: calm message, no 404 page
        given().redirects().follow(false).cookie("diary-csrf", s.cookie()).contentType(ContentType.URLENC).formParam("_csrf", s.token())
                .post("/memory/candidates/99999/discard").then().statusCode(303).header("Location", endsWith("error=notFound"));
    }

    @Test
    void extractPostCreatesSuggestionsOnlyOnRequest() {
        long entry = finishedEntry();
        Fakes.llm().enqueue(json(Map.of("candidates", List.of(
                Map.of("type", "FACT", "prompt", PROMPT_1, "answer", ANSWER_1, "sourceQuote", "Spaced   Repetition BEATS cramming.")))));
        PageTestSupport.Session s = session("/memory?source=entry&id=" + entry);
        given().redirects().follow(false).cookie("diary-csrf", s.cookie()).contentType(ContentType.URLENC).formParam("_csrf", s.token())
                .formParam("source", "entry").formParam("id", String.valueOf(entry)).post("/memory/extract").then().statusCode(303)
                .header("Location", endsWith("/memory?panel=candidates&msg=extracted&n=1"));
        given().get("/api/memory/candidates").then().body("$", hasSize(1));
        assertThat(html("/memory?panel=candidates&msg=extracted&n=1")).contains("One suggestion was added below.");
        given().redirects().follow(false).cookie("diary-csrf", s.cookie()).contentType(ContentType.URLENC).formParam("_csrf", s.token())
                .formParam("source", "nope").formParam("id", "1").post("/memory/extract").then().statusCode(303)
                .header("Location", endsWith("error=badInput"));
    }

    @Test
    void flashCodesAreWhitelisted() {
        String body = html("/memory?msg=%3Cscript%3E&error=%3Cb%3E&problem=%3Ci%3E");
        assertThat(body).doesNotContain("<script>alert").doesNotContain("<b>").doesNotContain("<i>");
    }

    @Test
    void germanTextsAndInterfaceLanguage() {
        String body = get("/memory", "de").asString();
        assertThat(body).contains("lang=\"de\"").contains("Erinnern").contains("Heute ist nichts fällig.");
    }
}
