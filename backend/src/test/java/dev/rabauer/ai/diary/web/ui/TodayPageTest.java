package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.api.entry.CreateEntryRequest;
import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.EntryStatus;
import dev.rabauer.ai.diary.entry.EntryRepository;
import dev.rabauer.ai.diary.entry.EntryService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class TodayPageTest {

    @Inject
    EntryService entries;
    @Inject
    EntryRepository repository;

    @BeforeEach
    void clean() {
        QuarkusTransaction.requiringNew().run(() -> repository.deleteAll());
    }

    private static int count(String haystack, String needle) {
        Matcher m = Pattern.compile(Pattern.quote(needle)).matcher(haystack);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static String html(String path) {
        return given().header("Accept-Language", "en").get(path).then().statusCode(200).contentType("text/html")
                .extract().asString();
    }

    @Test
    void rendersGreetingComposerAndEmptyTimelineWithOneH1() {
        String body = html("/");
        assertThat(count(body, "<h1")).isEqualTo(1);
        assertThat(body).containsPattern("<h1 [^>]*>Good (morning|afternoon|evening)\\.</h1>");
        assertThat(body).contains("<main id=\"main-content\"").contains("lang=\"en\"");
        // composer: segmented control with aria-pressed, language select, recorder element on the backend's API
        assertThat(body).contains("aria-pressed=\"true\"").contains("aria-pressed=\"false\"");
        assertThat(body).contains("<diary-recorder").contains("upload-url=\"/api/recordings\"");
        assertThat(body).contains("<label for=\"today-lang\">Language</label>").contains("<label for=\"today-text\">Your entry</label>");
        assertThat(body).contains("Nothing written yet today.");
        assertThat(body).contains("Earlier today");
        // Hard rule SPEC 4.4: nothing on this page is AI output and nothing asks for it.
        assertThat(body.toLowerCase()).doesNotContain("coach").doesNotContain("reflect");
        // Active navigation item and no inline script, style or handler (CSP).
        assertThat(body).contains("aria-current=\"page\"");
        assertThat(body).doesNotContain("<script>").doesNotContain(" style=\"").doesNotContainPattern(" on[a-z]+=\"");
        assertThat(body).contains("/js/today.js").contains("/css/today.css");
    }

    @Test
    void recorderLabelsAreRenderedAsEscapedJson() {
        String body = html("/");
        assertThat(body).contains("labels=\"{&quot;record&quot;:&quot;Record&quot;").contains("&quot;stop&quot;:&quot;Stop and save&quot;");
        assertThat(body).contains("data-texts=\"{&quot;entries.transcription.running&quot;");
    }

    @Test
    void timelineShowsOnlyTodaysEntriesNewestFirstWithEscapedText() {
        LocalDate today = LocalDate.now();
        entries.create(new CreateEntryRequest("morning thoughts", today.atTime(7, 15), null, null));
        EntryDto evening = entries.create(new CreateEntryRequest("<script>alert(1)</script> & <b>bold</b>", today.atTime(21, 5), null, null));
        entries.finish(evening.id());
        entries.create(new CreateEntryRequest("yesterday entry", today.minusDays(1).atTime(12, 0), null, null));

        String body = html("/");
        assertThat(count(body, "class=\"today-tl-item\"")).isEqualTo(2);
        assertThat(body).contains("morning thoughts").doesNotContain("yesterday entry").doesNotContain("Nothing written yet today.");
        assertThat(body.indexOf("21:05")).isLessThan(body.indexOf("07:15"));
        assertThat(body).contains("&lt;script&gt;alert(1)&lt;/script&gt;").doesNotContain("<script>alert").doesNotContain("<b>bold</b>");
        assertThat(body).contains("href=\"/journal/" + evening.id() + "\"");
        // the unfinished one carries a status, the finished one does not
        assertThat(count(body, "today-tl-status")).isEqualTo(1);
        assertThat(body).contains("<span>Draft</span>");
    }

    @Test
    void todayFollowsTheBrowsersTimeZoneCookie() {
        ZoneId far = ZoneId.of("Pacific/Kiritimati");
        LocalDate there = LocalDate.now(far);
        entries.create(new CreateEntryRequest("local to the browser", there.atTime(12, 0), null, null));
        String withCookie = given().cookie("diary-tz", far.getId()).get("/").then().statusCode(200).extract().asString();
        assertThat(withCookie).contains("local to the browser").contains("data-zone=\"Pacific/Kiritimati\"");
        // an invalid cookie falls back to the server zone instead of failing
        given().cookie("diary-tz", "Not/AZone!").get("/").then().statusCode(200);
    }

    @Test
    void fragmentIsOnlyTheTimeline() {
        entries.create(new CreateEntryRequest("hello", LocalDate.now().atTime(1, 0), null, null));
        String fragment = html("/?fragment=timeline");
        assertThat(fragment).doesNotContain("<html").doesNotContain("<h1").doesNotContain("<nav").contains("today-timeline");
    }

    @Test
    void germanFromAcceptLanguage() {
        String body = given().header("Accept-Language", "de-DE,de;q=0.9").get("/").then().statusCode(200).extract().asString();
        assertThat(body).contains("lang=\"de\"").containsPattern("<h1 [^>]*>Guten (Morgen|Tag|Abend)\\.</h1>")
                .contains("Heute schon").contains("Sprechen").contains("Schreiben").doesNotContain("??");
    }

    // ---- no-JavaScript write form

    private record Form(String cookie, String token) {
    }

    private static Form loadForm(String path) {
        Response r = given().get(path);
        Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]*)\"").matcher(r.asString());
        assertThat(m.find()).as("hidden _csrf field").isTrue();
        return new Form(r.cookie("diary-csrf"), m.group(1));
    }

    @Test
    void writeFormCreatesAndFinishesTheEntryAndRedirects() {
        Form form = loadForm("/");
        given().redirects().follow(false).cookie("diary-csrf", form.cookie()).cookie("diary-tz", "UTC")
                .formParam("_csrf", form.token()).formParam("text", "Written without scripts.").formParam("language", "AUTO")
                .post("/").then().statusCode(303).header("Location", org.hamcrest.Matchers.endsWith("/?saved=1"));
        List<EntryDto> items = entries.list(0, 10).items();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).text()).isEqualTo("Written without scripts.");
        assertThat(items.get(0).status()).isEqualTo(EntryStatus.FINISHED);
        assertThat(html("/?saved=1")).contains("Entry saved to your journal.");
    }

    @Test
    void writeFormWithoutOrWithWrongTokenChangesNothing() {
        Form form = loadForm("/");
        given().redirects().follow(false).cookie("diary-csrf", form.cookie()).formParam("text", "no token").post("/")
                .then().statusCode(403);
        given().redirects().follow(false).cookie("diary-csrf", form.cookie()).formParam("_csrf", "forged")
                .formParam("text", "forged token").post("/").then().statusCode(403);
        given().redirects().follow(false).formParam("_csrf", form.token()).formParam("text", "no session cookie").post("/")
                .then().statusCode(403);
        assertThat(entries.list(0, 10).items()).isEmpty();
    }

    @Test
    void blankWriteFormCreatesNothing() {
        Form form = loadForm("/");
        given().redirects().follow(false).cookie("diary-csrf", form.cookie()).formParam("_csrf", form.token())
                .formParam("text", "  \n ").post("/").then().statusCode(303);
        assertThat(entries.list(0, 10).items()).isEmpty();
    }

    @Test
    void languageParameterIsTolerant() {
        assertThat(TodayResource.languageOrNull(null)).isNull();
        assertThat(TodayResource.languageOrNull("AUTO")).isNull();
        assertThat(TodayResource.languageOrNull("de")).isEqualTo(dev.rabauer.ai.diary.api.common.Language.DE);
        assertThat(TodayResource.languageOrNull("klingon")).isNull();
    }
}
