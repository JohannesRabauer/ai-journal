package dev.rabauer.ai.diary.web.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rabauer.ai.diary.entry.EntryIndexer;
import dev.rabauer.ai.diary.insights.WeeklyDataOwner;
import dev.rabauer.ai.diary.support.Fakes;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.rabauer.ai.diary.web.ui.PageTestSupport.count;
import static dev.rabauer.ai.diary.web.ui.PageTestSupport.get;
import static dev.rabauer.ai.diary.web.ui.PageTestSupport.html;
import static dev.rabauer.ai.diary.web.ui.PageTestSupport.session;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;

/** The week page end to end (H2, fake LLM). Uses 2024 weeks so entries of other tests never fall into them. */
@QuarkusTest
class WeeklyPageTest {

    private static final String TEXT_1 = "I had a long meeting with Anna about the new budget plan and I felt unsure about the numbers.";
    private static final String TEXT_2 = "Walked along the river after work. The budget plan keeps coming back to me while I walk.";
    private static final String QUOTE_1 = "I felt unsure about the numbers";
    private static final String QUOTE_2 = "The budget plan keeps coming back to me";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // 2024-W10 = Monday 2024-03-04 .. Sunday 2024-03-10
    private static final String WEEK = "2024-W10";

    @Inject
    WeeklyDataOwner owner;
    @Inject
    EntryIndexer indexer;

    private final List<Long> created = new ArrayList<>();

    @BeforeEach
    void clean() {
        Fakes.reset();
        owner.deleteAll();
    }

    @AfterEach
    void cleanup() {
        for (Long id : created) {
            given().delete("/api/entries/" + id);
        }
        created.clear();
        owner.deleteAll();
    }

    private static String json(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private long finished(String text, String timestamp) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        body.put("entryTimestamp", timestamp);
        body.put("language", "EN");
        long id = given().contentType(ContentType.JSON).body(json(body)).post("/api/entries").then().statusCode(200).extract().jsonPath().getLong("id");
        created.add(id);
        given().post("/api/entries/" + id + "/finish").then().statusCode(200);
        indexer.awaitIdle();
        return id;
    }

    private static String output(long e1, long e2, String pattern) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recurringThemes", List.of(
                Map.of("title", "The budget plan", "description", "Mentioned on Monday and Wednesday.", "entryIds", List.of(e1, e2)),
                Map.of("title", "Walking", "description", "Along the river.", "entryIds", List.of(e2))));
        out.put("inYourOwnWords", List.of(Map.of("text", QUOTE_1, "entryId", e1), Map.of("text", QUOTE_2, "entryId", e2)));
        out.put("openThreads", List.of("How the budget numbers add up"));
        out.put("noticedPatterns", List.of(pattern));
        out.put("questionForNextWeek", "What would you like to understand better?");
        return json(out);
    }

    /** Two finished entries in 2024-W10 and a generated report; returns the entry ids. */
    private long[] reportWithEntries(String pattern) {
        long e1 = finished(TEXT_1, "2024-03-04T09:00:00");
        long e2 = finished(TEXT_2, "2024-03-06T18:30:00");
        Fakes.llm().enqueue(output(e1, e2, pattern));
        given().contentType(ContentType.JSON).body("{\"isoYear\":2024,\"isoWeek\":10}").post("/api/weekly-reports").then().statusCode(200);
        return new long[] {e1, e2};
    }

    @Test
    void weekWithoutReportOffersCreationAsAForm() {
        String body = html("/weekly?week=" + WEEK);

        // 2024-W10 is not in the picker list (no report, older than 12 weeks): falls back to the last completed week
        assertThat(count(body, "<h1")).isEqualTo(1);
        assertThat(body).contains("lang=\"en\"").contains("aria-current=\"page\"");
        assertThat(body).contains("action=\"/weekly/create\"").contains("name=\"_csrf\"").contains("Create this week");
        assertThat(body).contains("<label for=\"weekly-week\">Week</label>").contains("last completed week");
        assertThat(count(body, "<option ")).isEqualTo(13);
        assertThat(body).contains("There is no reflection for this week yet.").contains("No reflections yet.");
        assertThat(body).doesNotContain("<script>").doesNotContain(" onclick=").doesNotContain("style=\"");
    }

    @Test
    void storedReportRendersAsAnArticleWithEscapedTextAndEntryLinks() {
        long[] ids = reportWithEntries("<script>alert(1)</script> & <b>bold</b>");

        String body = html("/weekly?week=" + WEEK);

        assertThat(count(body, "<h1")).isEqualTo(1);
        assertThat(body).contains("Week 10").contains("Mar 4, 2024").contains("Mar 10, 2024");
        for (String section : List.of("Recurring themes", "In your own words", "Open threads", "What you learned",
                "Noticed patterns", "A question for next week")) {
            assertThat(body).contains(section);
        }
        assertThat(count(body, "<h2")).isEqualTo(7); // six sections and "Past weeks"
        assertThat(count(body, "<blockquote")).isEqualTo(2);
        assertThat(body).contains(QUOTE_1).contains("href=\"/journal/" + ids[0] + "\"").contains("Entry from Mon, 4 Mar");
        assertThat(body).contains("The budget plan").contains("What would you like to understand better?");
        assertThat(body).contains("&lt;script&gt;alert(1)&lt;/script&gt;").doesNotContain("<script>alert").doesNotContain("<b>bold</b>");
        // actions: edit link and two confirm boxes that post forms
        assertThat(body).contains("href=\"/weekly?week=" + WEEK + "&amp;edit=1\"");
        assertThat(count(body, "action=\"/weekly/create\"")).isEqualTo(1);
        assertThat(count(body, "action=\"/weekly/delete\"")).isEqualTo(1);
        assertThat(count(body, "<details")).isEqualTo(2);
        // past weeks list this report and marks it current
        assertThat(body).contains("Week 10, 2024").contains("aria-current=\"true\"");
        assertThat(body).contains("src=\"/js/weekly.js\"");
    }

    @Test
    void navigationLinksFollowTheList() {
        reportWithEntries("Entries on Monday and Wednesday.");
        String body = html("/weekly?week=" + WEEK);
        // 2024-W10 is the oldest week of the list: there is a "newer" link and no "older" one
        assertThat(body).contains("aria-label=\"Next week\"").doesNotContain("aria-label=\"Previous week\"");
        assertThat(html("/weekly")).contains("aria-label=\"Previous week\"");
    }

    @Test
    void editFormPrefillsEverySectionWithLabelledFields() {
        reportWithEntries("Entries on Monday and Wednesday.");

        String body = html("/weekly?week=" + WEEK + "&edit=1");

        assertThat(count(body, "<h1")).isEqualTo(1);
        assertThat(body).contains("action=\"/weekly/save\"").contains("name=\"_csrf\"");
        assertThat(body).contains("value=\"The budget plan\"").contains(">" + QUOTE_1 + "</textarea>");
        assertThat(count(body, "name=\"themeTitle\"")).isEqualTo(3); // two themes and one empty row
        assertThat(count(body, "name=\"quoteEntry\"")).isEqualTo(2);
        assertThat(body).contains("<label for=\"weekly-question\">Question for next week</label>").contains("Entries on Monday and Wednesday.");
    }

    @Test
    void savePostNeedsTheTokenAndWritesTheEdit() {
        long[] ids = reportWithEntries("Entries on Monday and Wednesday.");
        given().contentType(ContentType.URLENC).formParam("week", WEEK).formParam("question", "x").post("/weekly/save").then().statusCode(403);

        PageTestSupport.Session s = session("/weekly?week=" + WEEK + "&edit=1");
        given().redirects().follow(false).cookie("diary-csrf", s.cookie()).contentType(ContentType.URLENC)
                .formParam("_csrf", s.token()).formParam("week", WEEK)
                .formParam("themeTitle", "The budget plan", "Walking", "").formParam("themeDescription", "Edited description.", "Along the river.", "")
                .formParam("themeEntries", ids[0] + "," + ids[1], String.valueOf(ids[1]), "")
                .formParam("quoteText", QUOTE_1, QUOTE_2).formParam("quoteEntry", ids[0], ids[1])
                .formParam("thread", "How the budget numbers add up", "").formParam("pattern", "My own pattern", "")
                .formParam("itemsCreated", "0").formParam("reviewsDone", "0").formParam("question", "My own question")
                .post("/weekly/save").then().statusCode(303).header("Location", endsWith("/weekly?week=" + WEEK + "&msg=saved"));

        String body = html("/weekly?week=" + WEEK + "&msg=saved");
        assertThat(body).contains("Edited description.").contains("My own pattern").contains("My own question")
                .contains("Your changes are saved.").contains("Edited by you");
        assertThat(count(body, "weekly-theme diary-card")).isEqualTo(2); // the empty row was dropped
    }

    @Test
    void deletePostRemovesTheReportAfterTheTokenCheck() {
        reportWithEntries("Entries on Monday and Wednesday.");
        given().contentType(ContentType.URLENC).formParam("week", WEEK).post("/weekly/delete").then().statusCode(403);
        assertThat(html("/weekly?week=" + WEEK)).contains("weekly-article");

        PageTestSupport.Session s = session("/weekly?week=" + WEEK);
        given().redirects().follow(false).cookie("diary-csrf", s.cookie()).contentType(ContentType.URLENC)
                .formParam("_csrf", s.token()).formParam("week", WEEK).post("/weekly/delete")
                .then().statusCode(303).header("Location", endsWith("msg=deleted"));
        given().get("/api/weekly-reports").then().statusCode(200).body("$", org.hamcrest.Matchers.hasSize(0));
    }

    @Test
    void createPostGeneratesAndAWeekWithoutEntriesRedirectsWithACalmError() {
        PageTestSupport.Session s = session("/weekly");
        given().redirects().follow(false).cookie("diary-csrf", s.cookie()).contentType(ContentType.URLENC)
                .formParam("_csrf", s.token()).formParam("week", "2024-W20").post("/weekly/create")
                .then().statusCode(303).header("Location", endsWith("/weekly?week=2024-W20&error=noEntries"));
        // the error code (and nothing else from the query) selects a fixed text
        assertThat(html("/weekly?error=noEntries")).contains("There are no finished entries in this week yet.");
        assertThat(html("/weekly?error=%3Cb%3Ex")).doesNotContain("<b>x");

        given().redirects().follow(false).cookie("diary-csrf", s.cookie()).contentType(ContentType.URLENC)
                .formParam("_csrf", s.token()).formParam("week", "nonsense").post("/weekly/create")
                .then().statusCode(303).header("Location", endsWith("error=badWeek"));
    }

    @Test
    void createPostWithEntriesMakesTheReport() {
        long e1 = finished(TEXT_1, "2024-03-04T09:00:00");
        long e2 = finished(TEXT_2, "2024-03-06T18:30:00");
        Fakes.llm().enqueue(output(e1, e2, "Entries on Monday and Wednesday."));
        PageTestSupport.Session s = session("/weekly");
        given().redirects().follow(false).cookie("diary-csrf", s.cookie()).contentType(ContentType.URLENC)
                .formParam("_csrf", s.token()).formParam("week", WEEK).post("/weekly/create")
                .then().statusCode(303).header("Location", endsWith("/weekly?week=" + WEEK + "&msg=created"));
        assertThat(html("/weekly?week=" + WEEK + "&msg=created")).contains("The reflection is ready.").contains("weekly-article");
    }

    @Test
    void germanTexts() {
        assertThat(get("/weekly", "de").asString()).contains("lang=\"de\"").contains("Woche anzeigen");
    }
}
