package dev.rabauer.ai.diary.settings;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class SettingsResourceTest {

    @BeforeEach
    void clean() {
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> AppSettingEntity.deleteAll());
    }

    @Test
    void defaultsApply() {
        given().get("/api/settings").then().statusCode(200)
                .body("keepAudio", equalTo(true))
                .body("ttsAutoPlay", equalTo(true))
                .body("reviewIntervalsDays", contains(1, 3, 7, 14, 30));
    }

    @Test
    void partialUpdateKeepsOtherValues() {
        given().contentType(ContentType.JSON).body("{\"keepAudio\":false,\"reviewIntervalsDays\":[2,4,8]}")
                .put("/api/settings").then().statusCode(200)
                .body("keepAudio", equalTo(false))
                .body("ttsAutoPlay", equalTo(true))
                .body("reviewIntervalsDays", contains(2, 4, 8));
        given().contentType(ContentType.JSON).body("{\"uiLanguage\":\"DE\"}").put("/api/settings").then().statusCode(200)
                .body("uiLanguage", equalTo("DE")).body("keepAudio", equalTo(false));
    }

    @Test
    void invalidIntervalsAreRejected() {
        given().contentType(ContentType.JSON).body("{\"reviewIntervalsDays\":[7,3]}")
                .put("/api/settings").then().statusCode(400).body("code", equalTo("INVALID_INTERVALS"));
    }
}
