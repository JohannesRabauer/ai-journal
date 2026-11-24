package dev.rabauer.ai.diary.gym;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;

/** REST and JSON helpers for the gym tests. */
final class GymTestSupport {

    static final String EN_ENTRY = "I was sure that everyone at the meeting thought my idea was weak, so I stayed quiet for the rest of the day.";
    static final String EN_EXCERPT = "I was sure that everyone at the meeting thought my idea was weak";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GymTestSupport() {
    }

    static String json(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static long finishedEntry(String text, String language) {
        Map<String, Object> body = language == null ? Map.of("text", text) : Map.of("text", text, "language", language);
        long id = given().contentType(ContentType.JSON).body(json(body)).post("/api/entries")
                .then().statusCode(200).extract().jsonPath().getLong("id");
        given().post("/api/entries/" + id + "/finish").then().statusCode(200);
        return id;
    }

    static long draftEntry(String text) {
        return given().contentType(ContentType.JSON).body(json(Map.of("text", text))).post("/api/entries")
                .then().statusCode(200).extract().jsonPath().getLong("id");
    }

    static Response next(String category, String acceptLanguage) {
        var req = given();
        if (acceptLanguage != null) {
            req = req.header("Accept-Language", acceptLanguage);
        }
        return category == null ? req.post("/api/gym/exercises") : req.post("/api/gym/exercises?category=" + category);
    }

    static JsonPath nextOk(String category) {
        return next(category, null).then().statusCode(200).extract().jsonPath();
    }

    static Response attempt(long exerciseId, String answer, int seconds) {
        return given().contentType(ContentType.JSON).body(json(Map.of("answer", answer, "durationSeconds", seconds)))
                .post("/api/gym/exercises/" + exerciseId + "/attempts");
    }

    static JsonPath attemptOk(long exerciseId, String answer) {
        return attempt(exerciseId, answer, 120).then().statusCode(200).extract().jsonPath();
    }

    static String exerciseJson(String excerpt) {
        return json(Map.of("title", "Check the story", "prompt", "Look at what you wrote and test it.",
                "steps", List.of("Write the statement.", "List what would have to be true."), "sourceExcerpt", excerpt));
    }

    static String feedbackJson(String... questions) {
        return json(Map.of("questions", List.of(questions)));
    }
}
