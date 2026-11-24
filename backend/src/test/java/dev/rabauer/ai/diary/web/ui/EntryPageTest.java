package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.api.entry.CreateEntryRequest;
import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.EntryStatus;
import dev.rabauer.ai.diary.audio.RecordingRepository;
import dev.rabauer.ai.diary.audio.RecordingTestSupport;
import dev.rabauer.ai.diary.entry.EntryRepository;
import dev.rabauer.ai.diary.entry.EntryService;
import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.support.Fakes;
import dev.rabauer.ai.diary.transcription.TranscriptionJobRepository;
import dev.rabauer.ai.diary.transcription.TranscriptionService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** The entry detail page /journal/{id} and its no-JavaScript form actions. */
@QuarkusTest
class EntryPageTest {

    @Inject
    EntryService entries;
    @Inject
    EntryRepository repository;
    @Inject
    RecordingRepository recordings;
    @Inject
    TranscriptionJobRepository jobs;
    @Inject
    TranscriptionService transcriptions;

    @BeforeEach
    void clean() {
        Fakes.reset();
        RecordingTestSupport.clearAll(recordings, jobs, repository);
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

    private EntryDto create(String text) {
        return entries.create(new CreateEntryRequest(text, LocalDate.of(2026, 11, 24).atTime(7, 15), null, null));
    }

    private record Form(String cookie, String token) {
    }

    private static Form loadForm(String path) {
        Response r = given().get(path);
        Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]*)\"").matcher(r.asString());
        assertThat(m.find()).as("hidden _csrf field").isTrue();
        return new Form(r.cookie("diary-csrf"), m.group(1));
    }

    private UUID voiceEntryTranscribed() {
        UUID id = RecordingTestSupport.recordComplete("{\"mimeType\":\"audio/webm\"}", "A", "B");
        transcriptions.awaitIdle(Duration.ofSeconds(10));
        return id;
    }

    @Test
    void rendersHeadingChipsEyebrowAndEscapedText() {
        EntryDto e = create("<script>alert(1)</script> & <b>bold</b>. Second sentence.");
        String body = html("/journal/" + e.id());
        assertThat(count(body, "<h1")).isEqualTo(1);
        assertThat(body).containsPattern("<h1 [^>]*id=\"entry-title\"[^>]*>&lt;script&gt;alert\\(1\\)&lt;/script&gt; &amp; &lt;b&gt;bold&lt;/b&gt;</h1>");
        assertThat(body).doesNotContain("<script>alert").doesNotContain("<b>bold</b>");
        assertThat(body).contains("Tuesday, November 24, 2026").contains("Back to journal").contains("href=\"/journal\"");
        // a draft is editable, not finished: status chip, finish button, no voice chip, no audio player
        assertThat(body).contains("entries-chip-status").contains("<span>Draft</span>").doesNotContain("<span>Voice</span>");
        assertThat(body).contains("<label for=\"entry-text\">Entry text</label>").contains("Finish entry");
        assertThat(body).doesNotContain("<diary-audio-player");
        // the page is the journal's: Journal stays the current navigation item
        assertThat(body).contains("aria-current=\"page\"").contains("/js/entry.js").contains("/css/entry.css");
        assertThat(body).doesNotContain("<script>").doesNotContain(" style=\"").doesNotContainPattern(" on[a-z]+=\"");
    }

    @Test
    void finishedEntryLinksToReflectAndExtractLearnings() {
        EntryDto e = create("Done and dusted.");
        entries.finish(e.id());
        String body = html("/journal/" + e.id());
        assertThat(body).doesNotContain("entries-chip-status").doesNotContain("formaction=");
        assertThat(body).contains("href=\"/journal/" + e.id() + "/reflect\"").contains("Start reflecting");
        assertThat(body).contains("href=\"/memory?source=entry&amp;id=" + e.id() + "\"").contains("Extract learnings");
        assertThat(body).contains("Delete entry").doesNotContain("Delete audio");
    }

    @Test
    void unfinishedEntryHasNoReflectLink() {
        EntryDto e = create("Not yet.");
        String body = html("/journal/" + e.id());
        assertThat(body).doesNotContain("href=\"/journal/" + e.id() + "/reflect\"").contains("You can reflect on an entry once you have finished it.");
        assertThat(body).doesNotContain("data-coach-form=\"start\"");
    }

    @Test
    void voiceEntryShowsPlayerReviewPanelAndDeleteAudio() {
        UUID recording = voiceEntryTranscribed();
        long id = RecordingTestSupport.entryIdOf(recording);
        assertThat(entries.get(id).status()).isEqualTo(EntryStatus.TRANSCRIBED);
        String body = html("/journal/" + id);
        assertThat(body).contains("<span>Voice</span>").contains("<diary-audio-player")
                .contains("src=\"/api/recordings/" + recording + "/audio\"");
        assertThat(body).contains("<label for=\"entry-text\">Transcript</label>").contains("Read through and correct anything");
        assertThat(body).contains("action=\"/journal/" + id + "/delete-audio\"").contains("Delete audio");
        assertThat(body).contains("data-has-audio=\"true\"").contains("&quot;position&quot;:&quot;{0} of {1}&quot;");
    }

    @Test
    void failedTranscriptionNoticeIsReadableAndOffersAWayWithoutScripts() {
        Fakes.stt().respondWith(r -> {
            throw new ProviderException("fake", ProviderException.Kind.BAD_RESPONSE, "unusable answer");
        });
        UUID recording = RecordingTestSupport.recordComplete("{\"mimeType\":\"audio/webm\"}", "A");
        transcriptions.awaitIdle(Duration.ofSeconds(10));
        long id = RecordingTestSupport.entryIdOf(recording);
        assertThat(entries.get(id).status()).isEqualTo(EntryStatus.TRANSCRIPTION_FAILED);
        String body = html("/journal/" + id);
        assertThat(body).contains("The transcription did not work this time.").contains("href=\"/journal/" + id + "?manual=1#entry-text\"");
        String manual = html("/journal/" + id + "?manual=1");
        assertThat(manual).contains("<label for=\"entry-text\">Entry text</label>").doesNotContain("entries-notice-box");
    }

    @Test
    void unknownEntryIs404WithAPage() {
        String body = given().header("Accept-Language", "en").get("/journal/987654").then().statusCode(404)
                .contentType("text/html").extract().asString();
        assertThat(count(body, "<h1")).isEqualTo(1);
        assertThat(body).contains("This entry does not exist.").contains("Back to journal");
    }

    @Test
    void germanFromAcceptLanguage() {
        EntryDto e = create("Hallo.");
        String body = given().header("Accept-Language", "de").get("/journal/" + e.id()).then().statusCode(200).extract().asString();
        assertThat(body).contains("lang=\"de\"").contains("Zurück zum Tagebuch").contains("Dienstag, 24. November 2026")
                .doesNotContain("??");
    }

    // ---- no-JavaScript actions

    @Test
    void saveTextFormStoresTextAndFinishFormFinishes() {
        EntryDto e = create("first");
        Form form = loadForm("/journal/" + e.id());
        given().redirects().follow(false).cookie("diary-csrf", form.cookie()).formParam("_csrf", form.token())
                .formParam("text", "edited text").post("/journal/" + e.id() + "/text")
                .then().statusCode(303).header("Location", org.hamcrest.Matchers.endsWith("/journal/" + e.id() + "?notice=saved#entry-title"));
        assertThat(entries.get(e.id()).text()).isEqualTo("edited text");
        assertThat(html("/journal/" + e.id() + "?notice=saved")).contains("Saved.");

        given().redirects().follow(false).cookie("diary-csrf", form.cookie()).formParam("_csrf", form.token())
                .formParam("text", "final text").post("/journal/" + e.id() + "/finish").then().statusCode(303);
        EntryDto finished = entries.get(e.id());
        assertThat(finished.status()).isEqualTo(EntryStatus.FINISHED);
        assertThat(finished.text()).isEqualTo("final text");
    }

    @Test
    void deleteFormsRejectMissingTokenAndWorkWithOne() {
        EntryDto e = create("to delete");
        Form form = loadForm("/journal/" + e.id());
        given().redirects().follow(false).cookie("diary-csrf", form.cookie()).post("/journal/" + e.id() + "/delete")
                .then().statusCode(403);
        given().redirects().follow(false).cookie("diary-csrf", form.cookie()).formParam("_csrf", "nope")
                .post("/journal/" + e.id() + "/delete").then().statusCode(403);
        assertThat(entries.get(e.id())).isNotNull();

        given().redirects().follow(false).cookie("diary-csrf", form.cookie()).formParam("_csrf", form.token())
                .post("/journal/" + e.id() + "/delete").then().statusCode(303).header("Location", org.hamcrest.Matchers.endsWith("/journal"));
        given().get("/journal/" + e.id()).then().statusCode(404);
    }

    @Test
    void deleteAudioFormRemovesTheAudioButKeepsTheEntry() {
        UUID recording = voiceEntryTranscribed();
        long id = RecordingTestSupport.entryIdOf(recording);
        Form form = loadForm("/journal/" + id);
        given().redirects().follow(false).cookie("diary-csrf", form.cookie()).post("/journal/" + id + "/delete-audio")
                .then().statusCode(403);
        given().redirects().follow(false).cookie("diary-csrf", form.cookie()).formParam("_csrf", form.token())
                .post("/journal/" + id + "/delete-audio").then().statusCode(303);
        String body = html("/journal/" + id + "?notice=audioDeleted");
        assertThat(body).contains("The audio of this entry was deleted.").doesNotContain("<diary-audio-player");
        assertThat(entries.get(id).text()).isNotBlank();
    }

    @Test
    void deleteOfAnUnknownEntryJustGoesBackToTheJournal() {
        Form form = loadForm("/");
        given().redirects().follow(false).cookie("diary-csrf", form.cookie()).formParam("_csrf", form.token())
                .post("/journal/424242/delete").then().statusCode(303);
    }
}
