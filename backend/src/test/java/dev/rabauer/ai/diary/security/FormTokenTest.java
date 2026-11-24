package dev.rabauer.ai.diary.security;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** The no-JavaScript write path: session cookie + hidden {@code _csrf} field instead of the X-AI-Diary header. */
@QuarkusTest
class FormTokenTest {

    @TestHTTPResource
    URL base;

    private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String origin() {
        return "http://127.0.0.1:" + base.getPort();
    }

    private HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create(origin() + path));
    }

    private record Page(String cookie, String token, String setCookie) {
    }

    private Page loadForm(String existingCookie) throws Exception {
        HttpRequest.Builder b = req("/test-form").GET();
        if (existingCookie != null) {
            b.header("Cookie", "diary-csrf=" + existingCookie);
        }
        HttpResponse<String> r = send(b);
        assertThat(r.statusCode()).isEqualTo(200);
        Matcher t = Pattern.compile("name=\"_csrf\" value=\"([^\"]*)\"").matcher(r.body());
        assertThat(t.find()).as("hidden _csrf field").isTrue();
        String setCookie = r.headers().firstValue("Set-Cookie").orElse(null);
        String cookie = existingCookie;
        if (setCookie != null) {
            Matcher c = Pattern.compile("diary-csrf=([^;]+)").matcher(setCookie);
            assertThat(c.find()).isTrue();
            cookie = c.group(1);
        }
        return new Page(cookie, t.group(1), setCookie);
    }

    private HttpResponse<String> postForm(String path, String cookie, String body) throws Exception {
        HttpRequest.Builder b = req(path).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) {
            b.header("Cookie", "diary-csrf=" + cookie);
        }
        return send(b);
    }

    @Test
    void pageIssuesAHardenedSessionCookieAndMatchingToken() throws Exception {
        Page page = loadForm(null);
        assertThat(page.setCookie()).contains("HttpOnly").contains("SameSite=Strict").contains("Path=/").doesNotContain("Secure");
        assertThat(page.token()).isNotEmpty().isNotEqualTo(page.cookie());
        // A browser that already has the cookie keeps it and gets the same token.
        Page again = loadForm(page.cookie());
        assertThat(again.setCookie()).isNull();
        assertThat(again.token()).isEqualTo(page.token());
    }

    @Test
    void formPostWithValidTokenIsAcceptedWithoutTheHeader() throws Exception {
        Page page = loadForm(null);
        int before = TestFormResource.stored;
        HttpResponse<String> r = postForm("/test-form", page.cookie(), "note=hi&_csrf=" + page.token());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("stored");
        assertThat(TestFormResource.stored).isEqualTo(before + 1);
    }

    @Test
    void missingWrongOrForeignSessionTokenIsRejected() throws Exception {
        Page page = loadForm(null);
        Page other = loadForm(null);
        int before = TestFormResource.stored;
        assertThat(postForm("/test-form", page.cookie(), "note=hi").statusCode()).isEqualTo(403);
        assertThat(postForm("/test-form", page.cookie(), "note=hi&_csrf=").statusCode()).isEqualTo(403);
        assertThat(postForm("/test-form", page.cookie(), "note=hi&_csrf=forged").statusCode()).isEqualTo(403);
        // a token of another browser session does not fit this cookie
        assertThat(postForm("/test-form", page.cookie(), "note=hi&_csrf=" + other.token()).statusCode()).isEqualTo(403);
        assertThat(TestFormResource.stored).isEqualTo(before);
    }

    @Test
    void formPostWithoutSessionCookieIsRejectedByTheGuard() throws Exception {
        Page page = loadForm(null);
        HttpResponse<String> r = postForm("/test-form", null, "note=hi&_csrf=" + page.token());
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.body()).contains("CSRF_HEADER_REQUIRED");
    }

    @Test
    void foreignOriginIsRejectedEvenWithAValidToken() throws Exception {
        Page page = loadForm(null);
        HttpResponse<String> r = send(req("/test-form").header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", "diary-csrf=" + page.cookie()).header("Origin", "https://evil.example")
                .POST(HttpRequest.BodyPublishers.ofString("note=hi&_csrf=" + page.token())));
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.body()).contains("ORIGIN_NOT_ALLOWED");
    }

    @Test
    void ownOriginIsAcceptedOnFormPosts() throws Exception {
        Page page = loadForm(null);
        HttpResponse<String> r = send(req("/test-form").header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", "diary-csrf=" + page.cookie()).header("Origin", origin())
                .POST(HttpRequest.BodyPublishers.ofString("note=hi&_csrf=" + page.token())));
        assertThat(r.statusCode()).isEqualTo(200);
    }

    @Test
    void aRouteThatDoesNotDeclareTheTokenIsRefused() throws Exception {
        Page page = loadForm(null);
        int before = TestFormResource.stored;
        HttpResponse<String> r = postForm("/test-form/undeclared", page.cookie(), "note=hi&_csrf=" + page.token());
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(TestFormResource.stored).isEqualTo(before);
    }

    @Test
    void theTokenPathIsLimitedToFormEncodedPostsOutsideTheApi() throws Exception {
        Page page = loadForm(null);
        // JSON with the cookie but no header
        HttpResponse<String> json = send(req("/test-form").header("Content-Type", "application/json")
                .header("Cookie", "diary-csrf=" + page.cookie()).POST(HttpRequest.BodyPublishers.ofString("{}")));
        assertThat(json.statusCode()).isEqualTo(403);
        // the API never accepts a token
        HttpResponse<String> api = postForm("/api/entries", page.cookie(), "_csrf=" + page.token());
        assertThat(api.statusCode()).isEqualTo(403);
        assertThat(api.body()).contains("CSRF_HEADER_REQUIRED");
    }

    @Test
    void theHeaderStillWorksWithoutAnyToken() throws Exception {
        HttpResponse<String> r = send(req("/api/entries").header("X-AI-Diary", "1").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"via header\"}")));
        assertThat(r.statusCode()).isEqualTo(200);
    }
}
