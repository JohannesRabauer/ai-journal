package dev.rabauer.ai.diary.coach;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;

/** REST and JSON helpers for the coach tests. */
final class CoachTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CoachTestSupport() {
    }

    static String json(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static long createEntry(String text, String language) {
        Map<String, Object> body = language == null ? Map.of("text", text) : Map.of("text", text, "language", language);
        return given().contentType(ContentType.JSON).body(json(body)).post("/api/entries")
                .then().statusCode(200).extract().jsonPath().getLong("id");
    }

    static long finishedEntry(String text, String language) {
        long id = createEntry(text, language);
        given().post("/api/entries/" + id + "/finish").then().statusCode(200);
        return id;
    }

    static io.restassured.response.Response start(long entryId) {
        return given().contentType(ContentType.JSON).body("{\"entryId\":" + entryId + "}").post("/api/conversations");
    }

    static JsonPath startOk(long entryId) {
        return start(entryId).then().statusCode(200).extract().jsonPath();
    }

    static io.restassured.response.Response answer(long conversationId, String text, boolean voice) {
        return given().contentType(ContentType.JSON).body(json(Map.of("text", text, "voice", voice)))
                .post("/api/conversations/" + conversationId + "/answers");
    }

    static JsonPath answerOk(long conversationId, String text) {
        return answer(conversationId, text, false).then().statusCode(200).extract().jsonPath();
    }

    static String opening(String... questions) {
        return json(Map.of("questions", List.of(questions)));
    }

    static String turn(String kind, String text) {
        return json(Map.of("kind", kind, "text", text));
    }
}
