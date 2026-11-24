package dev.rabauer.ai.diary.privacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rabauer.ai.diary.spi.VectorStore;
import dev.rabauer.ai.diary.support.Fakes;
import dev.rabauer.ai.diary.support.LogCapture;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class PrivacyResourceTest {

    private static final String MARKER = "KANGAROO-PRIVATE-4412";
    private static final String DELETE_BODY = "{\"confirmation\":\"DELETE EVERYTHING\"}";

    @Inject
    AgroalDataSource ds;
    @Inject
    ObjectMapper mapper;

    @BeforeEach
    void clean() {
        FailingTestOwner.FAIL.set(false);
        Fakes.reset();
        given().contentType(ContentType.JSON).body(DELETE_BODY).post("/api/data/delete-all").then().statusCode(200);
    }

    @AfterEach
    void reset() {
        FailingTestOwner.FAIL.set(false);
    }

    private Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                files.put(e.getName(), in.readAllBytes());
            }
        }
        return files;
    }

    @Test
    void exportContainsEntriesSettingsAndManifest() throws Exception {
        given().contentType(ContentType.JSON).body("{\"text\":\"" + MARKER + " hello\"}").post("/api/entries").then().statusCode(200);
        given().contentType(ContentType.JSON).body("{\"keepAudio\":false}").put("/api/settings").then().statusCode(200);

        var response = given().get("/api/data/export?includeAudio=false").then().statusCode(200)
                .contentType("application/zip").extract();
        assertThat(response.header("Content-Disposition")).contains("attachment").contains(".zip");
        Map<String, byte[]> files = unzip(response.asByteArray());

        assertThat(files).containsKeys("entries.json", "settings.json", "manifest.json");
        assertThat(new String(files.get("entries.json"))).contains(MARKER);
        assertThat(mapper.readTree(files.get("settings.json")).get("keepAudio").asBoolean()).isFalse();
        JsonNode manifest = mapper.readTree(files.get("manifest.json"));
        assertThat(manifest.get("profile").asText()).isEqualTo("full");
        assertThat(manifest.get("complete").asBoolean()).isTrue();
        assertThat(manifest.get("exportedAt").asText()).isNotBlank();
        List<String> names = manifest.get("owners").findValuesAsText("name");
        assertThat(names).contains("entries", "settings", "legacy");
    }

    @Test
    void exportReportsFailedOwnerAndStaysReadable() throws Exception {
        FailingTestOwner.FAIL.set(true);
        Map<String, byte[]> files = unzip(given().get("/api/data/export").then().statusCode(200).extract().asByteArray());
        JsonNode manifest = mapper.readTree(files.get("manifest.json"));
        assertThat(manifest.get("complete").asBoolean()).isFalse();
        assertThat(manifest.get("owners").toString()).contains("test-failing").contains("failed");
        assertThat(files).containsKey("entries.json");
    }

    @Test
    void wrongPhraseIsRejectedAndNothingDeleted() {
        given().contentType(ContentType.JSON).body("{\"text\":\"keep me\"}").post("/api/entries").then().statusCode(200);
        for (String body : List.of("{\"confirmation\":\"delete everything\"}", "{\"confirmation\":\"\"}", "{}")) {
            given().contentType(ContentType.JSON).body(body).post("/api/data/delete-all").then().statusCode(400)
                    .body("code", equalTo("CONFIRMATION_REQUIRED"));
        }
        given().get("/api/entries").then().statusCode(200).body("total", equalTo(1));
    }

    @Test
    void deleteAllEmptiesEverything() throws Exception {
        given().contentType(ContentType.JSON).body("{\"text\":\"to be deleted\"}").post("/api/entries").then().statusCode(200);
        given().contentType(ContentType.JSON).body("{\"keepAudio\":false}").put("/api/settings").then().statusCode(200);
        UUID rec = UUID.randomUUID();
        Fakes.audio().writeChunk(rec, 0, new ByteArrayInputStream(new byte[]{1, 2, 3}));
        Fakes.audio().assemble(rec, 1);
        Fakes.vectors().upsert("entry", "1", List.of(new VectorStore.Chunk(0, "t", new float[]{1f, 0f}, "m")));
        try (Connection c = ds.getConnection(); var st = c.createStatement()) {
            st.executeUpdate("INSERT INTO chat_history (id, serializedmessage, sessionid) VALUES (990001, 'x', 's')");
        }

        var result = given().contentType(ContentType.JSON).body(DELETE_BODY)
                .post("/api/data/delete-all").then().statusCode(200).extract().jsonPath();
        assertThat(result.getLong("deleted.entries")).isEqualTo(1);
        assertThat(result.getLong("deleted.settings")).isGreaterThanOrEqualTo(1);
        assertThat(result.getLong("deleted.legacy")).isEqualTo(1);
        assertThat(result.getLong("deleted.vectors")).isEqualTo(1);
        assertThat(result.getLong("deleted.audio-files")).isEqualTo(1);

        given().get("/api/entries").then().statusCode(200).body("total", equalTo(0));
        given().get("/api/settings").then().statusCode(200).body("keepAudio", equalTo(true));
        assertThat(Fakes.audio().hasAudio(rec)).isFalse();
        assertThat(Fakes.vectors().count("entry", "1")).isZero();
        try (Connection c = ds.getConnection(); var st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM chat_history")) {
            rs.next();
            assertThat(rs.getLong(1)).isZero();
        }
    }

    @Test
    void deleteAllClearsBothVectorTablesAndExportCarriesLegacyDiaryRows() throws Exception {
        try (Connection c = ds.getConnection(); var st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS diary (embedding_id uuid PRIMARY KEY, text varchar(1000), metadata varchar(1000))");
            st.executeUpdate("DELETE FROM diary");
            st.executeUpdate("INSERT INTO diary VALUES (random_uuid(), 'legacy words " + MARKER + "', '{\"entry_id\":\"7\"}')");
            st.executeUpdate("INSERT INTO vector_chunk (namespace, owner_id, chunk_index, chunk_text, model_id, dimension, vector_data) "
                    + "VALUES ('entry','900',0,'t','m',1,X'00000000')");
            // H2 has no pgvector table (the migration creates nothing); simulate the PostgreSQL twin
            st.executeUpdate("CREATE TABLE IF NOT EXISTS vector_chunk_pg (namespace varchar(50), owner_id varchar(50), chunk_text varchar(100))");
            st.executeUpdate("INSERT INTO vector_chunk_pg VALUES ('entry','901','t')");
        }
        Map<String, byte[]> files = unzip(given().get("/api/data/export").then().statusCode(200).extract().asByteArray());
        assertThat(files).containsKey("legacy/diary.json");
        assertThat(new String(files.get("legacy/diary.json"))).contains(MARKER).contains("entry_id");

        given().contentType(ContentType.JSON).body(DELETE_BODY).post("/api/data/delete-all").then().statusCode(200);
        try (Connection c = ds.getConnection(); var st = c.createStatement()) {
            for (String table : List.of("diary", "vector_chunk", "vector_chunk_pg")) {
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
                    rs.next();
                    assertThat(rs.getLong(1)).as(table).isZero();
                }
            }
            st.executeUpdate("DROP TABLE diary");
            st.executeUpdate("DROP TABLE vector_chunk_pg");
        }
    }

    @Test
    void failureInOneOwnerContinuesWithOthersAndIsReported() {
        given().contentType(ContentType.JSON).body("{\"text\":\"to be deleted\"}").post("/api/entries").then().statusCode(200);
        FailingTestOwner.FAIL.set(true);
        given().contentType(ContentType.JSON).body(DELETE_BODY)
                .post("/api/data/delete-all").then().statusCode(500)
                .body("code", equalTo("DELETE_INCOMPLETE"))
                .body("message", containsString("test-failing"));
        FailingTestOwner.FAIL.set(false);
        given().get("/api/entries").then().statusCode(200).body("total", equalTo(0));
    }

    @Test
    void exportAndDeleteDoNotLogContent() {
        try (LogCapture logs = new LogCapture()) {
            given().contentType(ContentType.JSON).body("{\"text\":\"" + MARKER + "\"}").post("/api/entries").then().statusCode(200);
            FailingTestOwner.FAIL.set(true);
            given().get("/api/data/export").then().statusCode(200);
            given().contentType(ContentType.JSON).body(DELETE_BODY).post("/api/data/delete-all").then().statusCode(500);
            assertThat(logs.all()).doesNotContain(MARKER);
        }
    }
}
