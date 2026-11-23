package dev.rabauer.ai.diary.privacy;

import dev.rabauer.ai.diary.entry.EntryIndexer;
import dev.rabauer.ai.diary.llm.InvalidLlmOutputException;
import dev.rabauer.ai.diary.llm.StructuredOutputService;
import dev.rabauer.ai.diary.llm.StructuredRequest;
import dev.rabauer.ai.diary.spi.LlmGateway;
import dev.rabauer.ai.diary.support.Fakes;
import dev.rabauer.ai.diary.support.LogCapture;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Non-negotiable 4: journal content never reaches the logs, including on failure paths. */
@QuarkusTest
class NoContentInLogsTest {

    public record Needs(@jakarta.validation.constraints.NotBlank String q) {
    }

    private static final String MARKER = "ZEBRA-PRIVATE-7731";

    @Inject
    EntryIndexer indexer;
    @Inject
    StructuredOutputService structured;

    @Test
    void entryLifecycleAndLlmFailuresDoNotLogContent() {
        Fakes.reset();
        try (LogCapture logs = new LogCapture()) {
            long id = given().contentType(ContentType.JSON).body("{\"text\":\"" + MARKER + " my secret\"}")
                    .post("/api/entries").then().statusCode(200).extract().jsonPath().getLong("id");
            given().contentType(ContentType.JSON).body("{\"text\":\"" + MARKER + " edited\"}").put("/api/entries/" + id).then().statusCode(200);
            given().post("/api/entries/" + id + "/finish").then().statusCode(200);
            indexer.awaitIdle();

            Fakes.llm().enqueue("{\"bad\":\"" + MARKER + "\"}").enqueue("{\"bad\":\"" + MARKER + "\"}");
            assertThatThrownBy(() -> structured.generate(StructuredRequest.of(
                    List.of(LlmGateway.Message.user(MARKER)), new LlmGateway.JsonSchema("x", "{}"), Needs.class).withMaxAttempts(2)))
                    .isInstanceOf(InvalidLlmOutputException.class);

            given().get("/api/entries/" + id).then().statusCode(200);
            assertThat(logs.all()).doesNotContain(MARKER);
        }
    }
}
