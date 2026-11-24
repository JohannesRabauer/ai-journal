package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.api.entry.CreateEntryRequest;
import dev.rabauer.ai.diary.entry.EntryRepository;
import dev.rabauer.ai.diary.entry.EntryService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** Shell, routes, shared JS wiring, headers and the browser time zone. */
@QuarkusTest
class ShellPageTest {

    @Inject
    EntryService entries;
    @Inject
    EntryRepository repository;

    private String html(String path) {
        return given().header("Accept-Language", "en").get(path).then().statusCode(200).contentType("text/html")
                .extract().asString();
    }

    @Test
    void navigationPointsAtTheFinalRoutes() {
        String body = html("/journal");
        for (String href : new String[]{"/", "/journal", "/memory", "/gym", "/weekly", "/settings"}) {
            assertThat(body).contains("href=\"" + href + "\"");
        }
        assertThat(body).doesNotContain("href=\"/today\"");
    }

    @Test
    void aNonNumericEntryIdIsNoRoute() {
        given().get("/journal/abc").then().statusCode(404);
    }

    @Test
    void layoutWiresTheSharedFoundation() {
        String body = html("/journal");
        // one polite live region for notifications, one offline banner (hidden until js/status.js shows it)
        assertThat(Pattern.compile("aria-live=\"polite\"").matcher(body).results().count()).isEqualTo(1);
        assertThat(body).contains("id=\"diary-notify\"").contains("id=\"diary-offline\"")
                .contains("data-text=\"The diary service is not reachable right now.").contains("data-action=\"retry\"")
                .contains("data-offline-text=\"Diary service not reachable.\"");
        assertThat(body).contains("<script type=\"module\" src=\"/js/shell.js\"></script>");
        // CSP: only external scripts
        assertThat(body).doesNotContain("<script>").doesNotContain(" style=\"").doesNotContain(" onclick=");
        // the cloud notice is rendered by the server from the same service as /api/system/info
        assertThat(body).doesNotContain("diary-banner-cloud");
    }

    @Test
    void sharedScriptsAndStylesAreServed() {
        for (String js : new String[]{"shell", "api", "notify", "confirm", "autosave", "poll", "status", "tz", "lib/backoff"}) {
            given().get("/js/" + js + ".js").then().statusCode(200);
        }
    }

    @Test
    void apiResponsesAreNoStoreAndNoSniffButAssetsAreNot() {
        var api = given().get("/api/entries");
        assertThat(api.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(api.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        // errors too
        var missing = given().get("/api/entries/999999");
        assertThat(missing.getHeader("Cache-Control")).isEqualTo("no-store");
        // static assets may be cached
        assertThat(given().get("/css/styles.css").getHeader("Cache-Control")).isNotEqualTo("no-store");
        // HTML keeps the full set
        var page = given().get("/journal");
        assertThat(page.getHeader("Content-Security-Policy")).contains("script-src 'self'");
        assertThat(page.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void ownOriginCanCallTheApiButOtherOriginsCannot() {
        given().header("Host", "localhost:8080").header("Origin", "http://localhost:8080").get("/api/entries").then().statusCode(200);
        given().header("Host", "127.0.0.1:8080").header("Origin", "http://127.0.0.1:8080").get("/api/entries").then().statusCode(200);
        given().header("Host", "localhost:8080").header("Origin", "http://localhost:9999").get("/api/entries").then().statusCode(403);
        given().header("Host", "localhost:8080").header("Origin", "http://127.0.0.1:8080").get("/api/entries").then().statusCode(403);
        given().header("Host", "localhost:8080").header("Origin", "https://evil.example").get("/api/entries").then().statusCode(403);
        // writes from the own origin still need the header
        given().header("Host", "localhost:8080").header("Origin", "http://localhost:8080").header("X-AI-Diary", "0")
                .contentType("application/json").body("{\"text\":\"x\"}").post("/api/entries").then().statusCode(403);
    }

    @Test
    void todayAndYesterdayFollowTheBrowserTimeZoneCookie() {
        QuarkusTransaction.requiringNew().run(() -> repository.deleteAll());
        // The two zones are 25 hours apart, so their calendar dates always differ.
        ZoneId ahead = ZoneId.of("Pacific/Kiritimati");
        ZoneId behind = ZoneId.of("Pacific/Pago_Pago");
        LocalDate dayAhead = LocalDate.now(ahead);
        assertThat(dayAhead).isAfter(LocalDate.now(behind));
        entries.create(new CreateEntryRequest("zone entry", dayAhead.atTime(12, 0), null, null));

        String forAhead = given().header("Accept-Language", "en").cookie("diary-tz", "Pacific/Kiritimati").get("/journal").asString();
        assertThat(forAhead).contains("entries-day__heading\"><span>Today</span>");
        String forBehind = given().header("Accept-Language", "en").cookie("diary-tz", "Pacific/Pago_Pago").get("/journal").asString();
        assertThat(forBehind).doesNotContain("entries-day__heading\"><span>Today</span>");
        // garbage falls back to the server zone instead of failing
        given().cookie("diary-tz", "Not/AZone").get("/journal").then().statusCode(200);
        given().cookie("diary-tz", "<script>").get("/journal").then().statusCode(200);
        QuarkusTransaction.requiringNew().run(() -> repository.deleteAll());
    }
}
