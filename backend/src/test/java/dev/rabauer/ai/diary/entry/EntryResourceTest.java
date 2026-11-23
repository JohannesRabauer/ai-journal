package dev.rabauer.ai.diary.entry;

import dev.rabauer.ai.diary.api.entry.EntryStatus;
import dev.rabauer.ai.diary.support.Fakes;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class EntryResourceTest {

    @Inject
    AgroalDataSource ds;
    @Inject
    EntryIndexer indexer;
    @Inject
    EntryRepository repository;

    @BeforeEach
    void clean() {
        Fakes.reset();
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> repository.deleteAll());
    }

    private long create(String text, String timestamp) {
        return given().contentType(ContentType.JSON)
                .body("{\"text\":\"" + text + "\",\"entryTimestamp\":\"" + timestamp + "\"}")
                .post("/api/entries")
                .then().statusCode(200)
                .body("status", equalTo("DRAFT"))
                .body("source", equalTo("TEXT"))
                .extract().jsonPath().getLong("id");
    }

    @Test
    void entryIsPersistedImmediatelyWithoutAnyAiCall() throws Exception {
        long id = create("Heute war ein langer Tag", "2026-11-20T08:00:00");

        try (Connection c = ds.getConnection();
             ResultSet rs = c.createStatement().executeQuery("SELECT entrytext, status FROM documents WHERE id = " + id)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("Heute war ein langer Tag");
            assertThat(rs.getString(2)).isEqualTo("DRAFT");
        }
        given().contentType(ContentType.JSON).body("{\"text\":\"Heute war ein langer Tag. Mehr.\"}")
                .put("/api/entries/" + id).then().statusCode(200);
        given().post("/api/entries/" + id + "/finish").then().statusCode(200).body("status", equalTo("FINISHED"));
        indexer.awaitIdle();

        assertThat(Fakes.llm().calls()).as("no LLM call on save/update/finish (two-stage rule)").isEmpty();
        assertThat(Fakes.stt().requests()).isEmpty();
    }

    @Test
    void finishingIndexesTheEntryInTheBackground() {
        long id = create("Walking by the river helps me think", "2026-11-20T08:00:00");
        given().post("/api/entries/" + id + "/finish").then().statusCode(200);
        indexer.awaitIdle();
        assertThat(Fakes.vectors().count(EntryIndexer.NAMESPACE, Long.toString(id))).isPositive();
    }

    @Test
    void finishIsIdempotent() {
        long id = create("x", "2026-11-20T08:00:00");
        String first = given().post("/api/entries/" + id + "/finish").then().statusCode(200).extract().jsonPath().getString("finishedAt");
        String second = given().post("/api/entries/" + id + "/finish").then().statusCode(200).extract().jsonPath().getString("finishedAt");
        assertThat(second).isEqualTo(first);
    }

    @Test
    void listIsNewestFirstAndPaged() {
        long older = create("older", "2026-11-01T08:00:00");
        long newest = create("newest", "2026-11-03T08:00:00");
        long middle = create("middle", "2026-11-02T08:00:00");

        given().get("/api/entries?page=0&size=2").then().statusCode(200)
                .body("items.id", contains((int) newest, (int) middle))
                .body("total", equalTo(3))
                .body("size", equalTo(2));
        given().get("/api/entries?page=1&size=2").then().statusCode(200)
                .body("items.id", contains((int) older));
    }

    @Test
    void unknownEntryIs404WithErrorBody() {
        given().get("/api/entries/987654").then().statusCode(404).body("code", equalTo("NOT_FOUND"));
    }

    @Test
    void legacyEntriesAreFinishedAndKeepTheirAiRemark() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.createStatement().executeUpdate("INSERT INTO documents (id, entrytext, \"TIMESTAMP\", aicontent, status, source, created_at, updated_at, finished_at) "
                    + "VALUES (424242, 'old entry', TIMESTAMP '2025-01-01 10:00:00', 'Mood: calm', 'FINISHED', 'TEXT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }
        given().get("/api/entries/424242").then().statusCode(200)
                .body("status", equalTo(EntryStatus.FINISHED.name()))
                .body("legacyAiRemark", equalTo("Mood: calm"))
                .body("recordingId", nullValue());
    }

    @Test
    void updateNeverChangesLegacyAiRemark() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.createStatement().executeUpdate("INSERT INTO documents (id, entrytext, \"TIMESTAMP\", aicontent, status, source) "
                    + "VALUES (434343, 'old', TIMESTAMP '2025-01-01 10:00:00', 'remark', 'FINISHED', 'TEXT')");
        }
        given().contentType(ContentType.JSON).body("{\"text\":\"edited\"}").put("/api/entries/434343")
                .then().statusCode(200).body("text", equalTo("edited")).body("legacyAiRemark", equalTo("remark"));
    }

    @Test
    void deleteRemovesEntry() {
        long id = create("to delete", "2026-11-20T08:00:00");
        given().delete("/api/entries/" + id).then().statusCode(204);
        given().get("/api/entries/" + id).then().statusCode(404);
        assertThat(List.of(id)).isNotEmpty();
    }
}
