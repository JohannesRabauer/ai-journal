package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.settings.SettingsDto;
import dev.rabauer.ai.diary.settings.SettingsService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.rabauer.ai.diary.web.ui.PageTestSupport.count;
import static dev.rabauer.ai.diary.web.ui.PageTestSupport.get;
import static dev.rabauer.ai.diary.web.ui.PageTestSupport.html;
import static dev.rabauer.ai.diary.web.ui.PageTestSupport.session;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class SettingsPageTest {

    @Inject
    SettingsService settings;

    @BeforeEach
    @AfterEach
    void reset() {
        settings.update(new SettingsDto(Language.AUTO, Language.AUTO, true, true, null, null, SettingsService.DEFAULT_INTERVALS));
    }

    @Test
    void rendersGroupedCardsWithOneH1AndLabelledControls() {
        String body = html("/settings");

        assertThat(count(body, "<h1")).isEqualTo(1);
        assertThat(count(body, "<h2")).isEqualTo(6);
        assertThat(body).contains("lang=\"en\"").contains("aria-current=\"page\"");
        for (String label : List.of("Interface language", "Default entry language", "Read questions aloud automatically",
                "Keep audio recordings", "Review intervals (days)", "Confirmation phrase")) {
            assertThat(body).contains(label);
        }
        assertThat(body).contains("<form class=\"settings-form\" id=\"settings-form\" method=\"post\" action=\"/settings\">");
        assertThat(count(body, "name=\"_csrf\"")).isEqualTo(2); // settings form and delete form
        assertThat(body).contains("action=\"/api/data/export\"").contains("name=\"includeAudio\"");
        assertThat(body).contains("DELETE EVERYTHING").contains("value=\"1, 3, 7, 14, 30\"");
        assertThat(body).contains("Provider for llm: fake").contains("Profile: full");
        assertThat(body).doesNotContain("<script>").doesNotContain(" onclick=").doesNotContain("style=\"");
        assertThat(body).contains("src=\"/js/settings.js\"");
    }

    @Test
    void germanFromTheBrowserAndFromTheSetting() {
        assertThat(get("/settings", "de").asString()).contains("lang=\"de\"").contains("Einstellungen");
        given().contentType(ContentType.JSON).body("{\"uiLanguage\":\"DE\"}").put("/api/settings").then().statusCode(200);
        // the setting wins over the browser's Accept-Language on the next render
        assertThat(get("/settings", "en").asString()).contains("lang=\"de\"").contains("Einstellungen");
        assertThat(get("/memory", "en").asString()).contains("lang=\"de\"");
        // the JSON API is not affected
        given().header("Accept-Language", "en").get("/api/settings").then().statusCode(200);
    }

    @Test
    void flashCodesAreWhitelistedAndNeverEchoed() {
        assertThat(html("/settings?msg=saved")).contains("Settings saved.");
        String body = html("/settings?msg=%3Cscript%3Ealert(1)%3C/script%3E&error=%3Cb%3E");
        assertThat(body).doesNotContain("alert(1)").doesNotContain("<b>");
    }

    @Test
    void formPostWithoutTokenIsRejectedAndNothingChanges() {
        given().contentType(ContentType.URLENC).formParam("uiLanguage", "de").formParam("entryLanguage", "auto")
                .formParam("intervals", "1, 3").post("/settings").then().statusCode(403);
        assertThat(settings.get().uiLanguage()).isEqualTo(Language.AUTO);

        PageTestSupport.Session s = session("/settings");
        given().cookie("diary-csrf", s.cookie()).contentType(ContentType.URLENC).formParam("_csrf", "forged")
                .formParam("uiLanguage", "de").formParam("entryLanguage", "auto").formParam("intervals", "1, 3")
                .post("/settings").then().statusCode(403);
        assertThat(settings.get().uiLanguage()).isEqualTo(Language.AUTO);
    }

    @Test
    void formPostWithTokenSavesAndRedirects() {
        PageTestSupport.Session s = session("/settings");
        given().redirects().follow(false).cookie("diary-csrf", s.cookie()).contentType(ContentType.URLENC)
                .formParam("_csrf", s.token()).formParam("uiLanguage", "auto").formParam("entryLanguage", "de")
                .formParam("ttsAutoPlay", "true").formParam("intervals", "2, 5, 9")
                .post("/settings").then().statusCode(303).header("Location", org.hamcrest.Matchers.endsWith("/settings?msg=saved"));

        SettingsDto saved = settings.get();
        assertThat(saved.defaultEntryLanguage()).isEqualTo(Language.DE);
        assertThat(saved.keepAudio()).isFalse(); // an unchecked box is absent from the post
        assertThat(saved.ttsAutoPlay()).isTrue();
        assertThat(saved.reviewIntervalsDays()).containsExactly(2, 5, 9);

        // the same values again change nothing
        given().redirects().follow(false).cookie("diary-csrf", s.cookie()).contentType(ContentType.URLENC)
                .formParam("_csrf", s.token()).formParam("uiLanguage", "auto").formParam("entryLanguage", "de")
                .formParam("ttsAutoPlay", "true").formParam("intervals", "2, 5, 9")
                .post("/settings").then().statusCode(303).header("Location", org.hamcrest.Matchers.endsWith("msg=nothing"));
    }

    @Test
    void invalidIntervalsAreRefusedWithAMessageOnTheNextRender() {
        PageTestSupport.Session s = session("/settings");
        given().redirects().follow(false).cookie("diary-csrf", s.cookie()).contentType(ContentType.URLENC)
                .formParam("_csrf", s.token()).formParam("uiLanguage", "auto").formParam("entryLanguage", "auto")
                .formParam("intervals", "5, 2").post("/settings").then().statusCode(303)
                .header("Location", org.hamcrest.Matchers.endsWith("error=intervals-order"));
        assertThat(settings.get().reviewIntervalsDays()).isEqualTo(SettingsService.DEFAULT_INTERVALS);
        assertThat(html("/settings?error=intervals-order")).contains("Intervals must be in strictly ascending order.")
                .contains("aria-invalid=\"true\"");
    }

    @Test
    void deleteAllNeedsTheTokenAndTheExactPhrase() {
        given().contentType(ContentType.URLENC).formParam("confirmation", "DELETE EVERYTHING").post("/settings/delete-all")
                .then().statusCode(403);
        PageTestSupport.Session s = session("/settings");
        given().redirects().follow(false).cookie("diary-csrf", s.cookie()).contentType(ContentType.URLENC)
                .formParam("_csrf", s.token()).formParam("confirmation", "delete everything")
                .post("/settings/delete-all").then().statusCode(303)
                .header("Location", org.hamcrest.Matchers.endsWith("msg=confirmation"));
    }
}
