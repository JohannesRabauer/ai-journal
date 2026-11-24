package dev.rabauer.ai.diary.system;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class SystemResourceTest {

    @Test
    void reportsLocalProfileAndProviders() {
        given().get("/api/system/info").then().statusCode(200)
                .body("profile", equalTo("full"))
                .body("cloudMode", equalTo(false))
                .body("cloudNotice", nullValue())
                .body("providers.stt", equalTo("fake"))
                .body("providers.vector", equalTo("memory"));
    }

    @Test
    void openApiDocumentIsServed() {
        given().get("/q/openapi?format=json").then().statusCode(200)
                .body("paths.'/api/entries'.post.summary", equalTo("Create an entry immediately (status DRAFT). No AI processing."));
    }

    @Test
    void unimplementedFeaturesAnswer501WithErrorBody() {
        given().get("/api/gym/exercises/1").then().statusCode(501).body("code", equalTo("NOT_IMPLEMENTED"));
    }
}
