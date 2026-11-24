package dev.rabauer.ai.diary.security;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class RequestGuardTest {

    private static final String DELETE_BODY = "{\"confirmation\":\"DELETE EVERYTHING\"}";

    @TestHTTPResource
    URL base;

    private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + base.getPort() + path));
    }

    private int entryCount() {
        return given().get("/api/entries").then().statusCode(200).extract().path("size()");
    }

    @Test
    void deleteAllWithoutContentTypeAndHeaderIsForbiddenAndDeletesNothing() throws Exception {
        given().contentType("application/json").body("{\"text\":\"guard-test\"}").post("/api/entries").then().statusCode(200);
        int before = entryCount();
        // the cross-site "simple request" shape: no Content-Type, no custom header
        HttpResponse<String> r = send(req("/api/data/delete-all").POST(HttpRequest.BodyPublishers.ofString(DELETE_BODY)));
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.body()).contains("CSRF_HEADER_REQUIRED");
        assertThat(entryCount()).isEqualTo(before);
    }

    @Test
    void textPlainWriteWithoutHeaderIsForbidden() throws Exception {
        HttpResponse<String> r = send(req("/api/entries/00000000-0000-0000-0000-000000000001/finish")
                .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString("x")));
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.body()).contains("CSRF_HEADER_REQUIRED");
    }

    @Test
    void foreignOriginIsForbiddenEvenForReads() {
        given().header("Origin", "https://evil.example").get("/api/entries").then().statusCode(403);
        given().header("Origin", "https://evil.example").post("/api/data/delete-all").then().statusCode(403);
    }

    @Test
    void crossSiteFetchMetadataIsForbidden() {
        given().header("Sec-Fetch-Site", "cross-site").get("/api/entries").then().statusCode(403);
        given().header("Sec-Fetch-Site", "same-origin").get("/api/entries").then().statusCode(200);
    }

    @Test
    void unexpectedHostIsRejected() {
        given().header("Host", "attacker.example").get("/api/entries").then().statusCode(421);
        given().header("Host", "attacker.example:8080").get("/api/entries").then().statusCode(421);
    }

    @Test
    void loopbackAndServiceHostsAreAccepted() {
        for (String host : new String[]{"127.0.0.1:8080", "localhost", "localhost:8080", "[::1]:8080", "backend:8080"}) {
            given().header("Host", host).get("/api/entries").then().statusCode(200);
        }
    }

    @Test
    void writesWithTheHeaderWork() {
        given().contentType("application/json").body("{\"text\":\"ok\"}").post("/api/entries")
                .then().statusCode(200);
        given().header("X-AI-Diary", "0").contentType("application/json").body("{\"text\":\"x\"}").post("/api/entries")
                .then().statusCode(403).body("code", equalTo("CSRF_HEADER_REQUIRED"));
    }
}


