package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.gym.GymDataOwner;
import dev.rabauer.ai.diary.support.Fakes;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static dev.rabauer.ai.diary.web.ui.PageTestSupport.count;
import static dev.rabauer.ai.diary.web.ui.PageTestSupport.get;
import static dev.rabauer.ai.diary.web.ui.PageTestSupport.html;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class GymPageTest {

    @Inject
    GymDataOwner owner;

    @BeforeEach
    void clean() {
        Fakes.reset();
        owner.deleteAll();
    }

    private static long saveAnswer(String category, String answer, int seconds) {
        long exercise = given().post("/api/gym/exercises?category=" + category).then().statusCode(200).extract().jsonPath().getLong("id");
        given().contentType(ContentType.JSON).body("{\"answer\":" + json(answer) + ",\"durationSeconds\":" + seconds + "}")
                .post("/api/gym/exercises/" + exercise + "/attempts").then().statusCode(200);
        return exercise;
    }

    private static String json(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    void rendersTheSevenCategoriesAsButtonsAndNoScoresOrCounts() {
        String body = html("/gym");

        assertThat(count(body, "<h1")).isEqualTo(1);
        assertThat(body).contains("lang=\"en\"").contains("aria-current=\"page\"");
        assertThat(count(body, "class=\"gym-card diary-card\"")).isEqualTo(7);
        assertThat(body).contains("data-category=\"ASSUMPTION_CHECK\"").contains("data-category=\"EXPLAIN_SIMPLY\"")
                .contains("Surprise me").contains("Assumption check");
        assertThat(body).contains("<noscript>").contains("need JavaScript");
        assertThat(body).contains("Nothing here yet. Answers you save will appear here.");
        // the exercise card is built from templates with labelled controls
        assertThat(body).contains("<label for=\"gym-answer\">Your thoughts</label>").contains("id=\"gym-tpl-exercise\"")
                .contains("Questions to think about");
        assertThat(body).contains("data-llm=\"false\""); // fake LLM: follow-up questions are not offered
        assertThat(body).doesNotContain("<script>").doesNotContain(" onclick=").doesNotContain("style=\"");
        assertThat(body).contains("src=\"/js/gym.js\"");
        assertThat(body.toLowerCase()).doesNotContain("streak").doesNotContain("score");
    }

    @Test
    void earlierAnswersAreListedEscapedWithFilterAndPaging() {
        saveAnswer("REFRAMING", "I tried <b>another</b> reading & it helped", 125);
        saveAnswer("BIAS_SPOTTING", "second answer", 30);

        String body = html("/gym");

        assertThat(count(body, "<details class=\"gym-history__item diary-card\"")).isEqualTo(2);
        assertThat(body).contains("I tried &lt;b&gt;another&lt;/b&gt; reading &amp; it helped").doesNotContain("<b>another</b>");
        assertThat(body).contains("2 min").contains("under a minute").contains("Reframing").contains("Bias spotting");
        // newest first
        assertThat(body.indexOf("second answer")).isLessThan(body.indexOf("I tried"));

        String filtered = html("/gym?category=REFRAMING");
        assertThat(count(filtered, "<details class=\"gym-history__item diary-card\"")).isEqualTo(1);
        assertThat(filtered).contains("<option value=\"REFRAMING\" selected>").doesNotContain("second answer");
        // an unknown category is ignored rather than failing
        assertThat(count(html("/gym?category=NOPE"), "<details class=\"gym-history__item diary-card\"")).isEqualTo(2);
    }

    @Test
    void historyShowsMoreWithAPlainLink() {
        for (int i = 0; i < 21; i++) {
            saveAnswer("REFRAMING", "answer number " + i, 10);
        }
        String body = html("/gym");
        assertThat(count(body, "<details class=\"gym-history__item diary-card\"")).isEqualTo(20);
        assertThat(body).contains("href=\"/gym?shown=40#gym-history\"").contains("Show earlier ones");
        assertThat(count(html("/gym?shown=40"), "<details class=\"gym-history__item diary-card\"")).isEqualTo(21);
    }

    @Test
    void germanTexts() {
        assertThat(get("/gym", "de").asString()).contains("lang=\"de\"").contains("Denktraining");
    }
}
