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
    void notImplementedMapsTo501WithErrorBody() {
        // All REST stubs are implemented since Wave 3; check the mapping itself.
        var response = new dev.rabauer.ai.diary.web.ErrorMappers().api(dev.rabauer.ai.diary.web.ApiException.notImplemented("X"));
        org.assertj.core.api.Assertions.assertThat(response.getStatus()).isEqualTo(501);
        org.assertj.core.api.Assertions.assertThat(((dev.rabauer.ai.diary.api.common.ErrorDto) response.getEntity()).code())
                .isEqualTo("NOT_IMPLEMENTED");
    }

    @Test
    void everyFeatureRouteIsImplemented() {
        given().get("/api/memory/lessons").then().statusCode(200);
        given().get("/api/gym/attempts").then().statusCode(200);
        given().get("/api/weekly-reports").then().statusCode(200);
    }
}
