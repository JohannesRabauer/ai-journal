package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.api.entry.CreateEntryRequest;
import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.entry.EntryRepository;
import dev.rabauer.ai.diary.entry.EntryService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class JournalPageTest {

    @Inject
    EntryService entries;
    @Inject
    EntryRepository repository;

    @BeforeEach
    void clean() {
        deleteAll();
    }

    private void deleteAll() {
        QuarkusTransaction.requiringNew().run(() -> repository.deleteAll());
    }

    private EntryDto create(String text, LocalDateTime when) {
        return entries.create(new CreateEntryRequest(text, when, null, null));
    }

    private static int count(String haystack, String needle) {
        Matcher m = Pattern.compile(Pattern.quote(needle)).matcher(haystack);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private String html(String path) {
        return given().header("Accept-Language", "en").get(path).then().statusCode(200)
                .contentType("text/html").extract().asString();
    }

    @Test
    void rendersGroupedByDayWithOneH1AndEscapedText() {
        LocalDate today = LocalDate.now();
        create("morning thoughts", today.atTime(7, 15));
        create("<script>alert(1)</script> & <b>bold</b>", today.atTime(21, 5));
        create("yesterday entry", today.minusDays(1).atTime(12, 0));
        create("long ago", LocalDate.of(2020, 3, 9).atTime(9, 0));
        EntryDto finished = create("done one", today.minusDays(1).atTime(8, 0));
        entries.finish(finished.id());

        String body = html("/journal");

        assertThat(count(body, "<h1")).isEqualTo(1);
        assertThat(body).contains("lang=\"en\"").contains("<main id=\"main-content\"").contains("aria-current=\"page\"");
        assertThat(count(body, "<h2")).isEqualTo(3);
        assertThat(body).contains("<span>Today</span>").contains("<span>Yesterday</span>").contains("March 9, 2020");
        // Newest first: today's section before yesterday's.
        assertThat(body.indexOf("<span>Today</span>")).isLessThan(body.indexOf("<span>Yesterday</span>"));
        assertThat(count(body, "class=\"diary-card entries-card\"")).isEqualTo(5);
        // Entry text is escaped, never raw.
        assertThat(body).doesNotContain("<script>alert").doesNotContain("<b>bold</b>");
        assertThat(body).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        // Only /js files: no inline script.
        assertThat(body).doesNotContain("<script>");
        assertThat(body).contains("Local. Nothing leaves this device.");
        assertThat(body).contains("Showing 5 of 5 entries.").contains("5 entries");
        // Unfinished entries carry a status chip, finished ones do not.
        assertThat(count(body, "entries-chip-status")).isEqualTo(4);
    }

    @Test
    void germanFromAcceptLanguage() {
        create("hallo", LocalDate.now().atTime(LocalTime.NOON));
        String body = given().header("Accept-Language", "de-DE,de;q=0.9").get("/journal").then().statusCode(200)
                .extract().asString();
        assertThat(body).contains("lang=\"de\"").contains("<span>Heute</span>").contains("Tagebuch")
                .contains("Zum Hauptinhalt springen");
    }

    @Test
    void pagingWorksWithoutJavaScriptAndAsFragment() {
        LocalDate day = LocalDate.now().minusDays(30);
        for (int i = 0; i < 25; i++) {
            create("entry-" + i, day.atTime(0, 0).plusMinutes(i));
        }
        String first = html("/journal");
        assertThat(count(first, "entries-card\"")).isEqualTo(20);
        assertThat(first).contains("Showing 20 of 25 entries.").contains("href=\"/journal?page=1\"");

        String second = html("/journal?page=1");
        assertThat(count(second, "entries-card\"")).isEqualTo(25);
        assertThat(second).contains("Showing 25 of 25 entries.").doesNotContain("entries-load-older");
        assertThat(count(second, "<h2")).isEqualTo(1);
    }

    @Test
    void fragmentContainsOnlyTheRequestedPage() {
        LocalDate day = LocalDate.now().minusDays(30);
        for (int i = 0; i < 25; i++) {
            create("entry-" + i, day.atTime(0, 0).plusMinutes(i));
        }
        String fragment = html("/journal?page=1&fragment=1");
        assertThat(fragment).doesNotContain("<html").doesNotContain("<h1").doesNotContain("<nav");
        assertThat(count(fragment, "entries-card\"")).isEqualTo(5);
        assertThat(fragment).contains("data-day=\"" + day + "\"").contains("Showing 25 of 25 entries.");
    }

    @Test
    void emptyJournalShowsMessage() {
        String body = html("/journal");
        assertThat(body).contains("No entries yet.");
        assertThat(count(body, "<h1")).isEqualTo(1);
    }

    @Test
    void securityHeadersAreSet() {
        given().get("/journal").then().statusCode(200)
                .header("Content-Security-Policy", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("default-src 'self'"),
                        org.hamcrest.Matchers.containsString("frame-ancestors 'none'"),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("unsafe-inline"))))
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .header("Cache-Control", "no-store");
        // JSON API responses are not touched.
        assertThat(given().get("/api/entries").getHeader("Content-Security-Policy")).isNull();
    }

    // ---- Search and filters (phase 2) ----------------------------------------------------------

    private EntryDto createVoice(String text, LocalDateTime when) {
        return entries.create(new CreateEntryRequest(text, when, null, dev.rabauer.ai.diary.api.entry.EntrySource.VOICE));
    }

    @Test
    void toolbarIsLabelledAndWorksAsGetFormWithoutJavaScript() {
        create("hello", LocalDate.now().atTime(8, 0));
        String body = html("/journal");
        assertThat(body).contains("role=\"search\"").contains("method=\"get\"").contains("action=\"/journal\"");
        assertThat(body).contains("<label class=\"entries-search__label\" for=\"entries-q\">Search your entries</label>");
        assertThat(body).contains("type=\"search\"").contains("name=\"q\"").contains("name=\"filter\"");
        assertThat(count(body, "aria-current=\"true\"")).isEqualTo(1);
        assertThat(body).containsPattern("data-filter=\"all\" aria-current=\"true\"");
        assertThat(body).contains("href=\"/journal?filter=voice\"").contains("href=\"/journal?filter=reflected\"");
        assertThat(body).contains("<script type=\"module\" src=\"/js/journal.js\">").doesNotContain("<script>");
    }

    @Test
    void searchTextFiltersAndIsEscapedWhenEchoedBack() {
        LocalDate today = LocalDate.now();
        create("a quiet walk", today.atTime(8, 0));
        create("grocery list", today.atTime(9, 0));
        String body = html("/journal?q=walk");
        assertThat(body).contains("1 entry matches").contains("a quiet walk").doesNotContain("grocery list");
        assertThat(body).contains("value=\"walk\"");
        assertThat(count(body, "class=\"diary-card entries-card\"")).isEqualTo(1);

        String evil = "\"><script>alert(1)</script>";
        String escaped = given().header("Accept-Language", "en").queryParam("q", evil).get("/journal")
                .then().statusCode(200).extract().asString();
        assertThat(escaped).doesNotContain("<script>alert").contains("No entries match your search.");
        assertThat(escaped).contains("value=\"&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;\"");
        assertThat(escaped).contains("href=\"/journal\"").contains("Clear search");
    }

    @Test
    void filterLinksReflectStateAndFilterTheList() {
        LocalDate today = LocalDate.now();
        create("typed thought", today.atTime(8, 0));
        createVoice("spoken thought", today.atTime(9, 0));
        String body = html("/journal?filter=voice");
        assertThat(body).contains("1 entry matches").contains("spoken thought").doesNotContain("typed thought");
        assertThat(body).containsPattern("data-filter=\"voice\" aria-current=\"true\"");
        assertThat(count(body, "aria-current=\"true\"")).isEqualTo(1);

        String unknown = html("/journal?filter=bogus");
        assertThat(unknown).contains("typed thought").contains("spoken thought").contains("2 entries");

        String reflected = html("/journal?filter=reflected");
        assertThat(reflected).contains("No entries match your search.").doesNotContain("spoken thought");
    }

    @Test
    void combinedFilterKeepsSearchTextInLinksAndPaging() {
        LocalDate day = LocalDate.now().minusDays(30);
        for (int i = 0; i < 25; i++) {
            createVoice("river " + i, day.atTime(0, 0).plusMinutes(i));
        }
        create("river text", day.atTime(5, 0));
        String first = html("/journal?q=river&filter=voice");
        assertThat(count(first, "entries-card\"")).isEqualTo(20);
        assertThat(first).contains("25 entries match").contains("Showing 20 of 25 entries.")
                .contains("href=\"/journal?q=river&amp;filter=voice&amp;page=1\"")
                .contains("href=\"/journal?q=river&amp;filter=reflected\"");
        String fragment = html("/journal?q=river&filter=voice&page=1&fragment=1");
        assertThat(fragment).doesNotContain("<html").doesNotContain("<form");
        assertThat(count(fragment, "entries-card\"")).isEqualTo(5);
        assertThat(fragment).contains("Showing 25 of 25 entries.");
    }

    @Test
    void fragmentModeCarriesTheResultsWrapperAndNoMatchState() {
        create("something", LocalDate.now().atTime(8, 0));
        String fragment = html("/journal?q=zzz&fragment=1");
        assertThat(fragment).contains("class=\"entries-results\"").contains("entries-nomatch-text")
                .contains("No entries match your search.").contains("class=\"entries-clear\"")
                .doesNotContain("<h1").doesNotContain("No entries yet.");
    }

    @Test
    void germanSearchTexts() {
        create("hallo welt", LocalDate.now().atTime(8, 0));
        String body = given().header("Accept-Language", "de").queryParam("q", "hallo").get("/journal")
                .then().statusCode(200).extract().asString();
        assertThat(body).contains("Einträge durchsuchen").contains("1 Eintrag gefunden").contains("Sprache")
                .contains("Reflektiert");
    }

    @Test
    void overlongSearchTextIsTruncatedNotAnError() {
        create("x", LocalDate.now().atTime(8, 0));
        html("/journal?q=" + "a".repeat(500));
    }

    @Test
    void guardStillAppliesToJournalAndApi() {
        given().header("Origin", "https://evil.example").get("/journal").then().statusCode(403);
        given().header("Host", "evil.example").get("/journal").then().statusCode(421);
    }

    @Test
    void staticAssetsAreServed() {
        given().get("/css/styles.css").then().statusCode(200);
        given().get("/css/journal.css").then().statusCode(200);
        given().get("/js/journal.js").then().statusCode(200);
        given().get("/js/theme-init.js").then().statusCode(200);
        given().get("/icons/diary-icons.svg").then().statusCode(200);
    }
}
