package dev.rabauer.ai.diary.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A pre-v1 database (tables created by Hibernate "update", no Flyway history) is baselined and upgraded without
 * losing data. Mirrors the production settings baseline-on-migrate=true, baseline-version=1.
 * The PostgreSQL variant is covered by the opt-in Docker smoke test (scripts/smoke-*.ps1).
 */
class LegacyDatabaseMigrationTest {

    @Test
    void legacyDataSurvivesAndBecomesFinished() throws Exception {
        String url = "jdbc:h2:mem:legacy" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            var st = c.createStatement();
            st.execute("CREATE TABLE documents (id bigint PRIMARY KEY, aicontent text, entrytext text, \"TIMESTAMP\" timestamp(6))");
            st.execute("CREATE SEQUENCE documents_seq START WITH 1 INCREMENT BY 50");
            st.execute("CREATE TABLE chat_history (id bigint PRIMARY KEY, serializedmessage text, sessionid varchar(255))");
            st.execute("CREATE SEQUENCE chat_history_seq START WITH 1 INCREMENT BY 50");
            st.execute("INSERT INTO documents VALUES (1, 'Mood: calm', 'my old entry', TIMESTAMP '2025-03-01 09:30:00')");
            st.execute("INSERT INTO chat_history VALUES (1, '{}', '1')");
        }

        var result = Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:db/migration/h2")
                .baselineOnMigrate(true).baselineVersion("1")
                .load().migrate();
        assertThat(result.success).isTrue();

        try (Connection c = DriverManager.getConnection(url, "sa", "");
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT entrytext, aicontent, status, source, created_at, finished_at FROM documents WHERE id = 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("entrytext")).isEqualTo("my old entry");
            assertThat(rs.getString("aicontent")).isEqualTo("Mood: calm");
            assertThat(rs.getString("status")).isEqualTo("FINISHED");
            assertThat(rs.getString("source")).isEqualTo("TEXT");
            assertThat(rs.getObject("created_at")).isNotNull();
            assertThat(rs.getObject("finished_at")).isNotNull();
        }
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             ResultSet rs = c.createStatement().executeQuery("SELECT count(*) FROM chat_history")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void freshDatabaseGetsFullSchema() {
        String url = "jdbc:h2:mem:fresh" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        var result = Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration/h2")
                .baselineOnMigrate(true).baselineVersion("1").load().migrate();
        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(2);
    }

    @Test
    void everyMigrationExistsForBothVendors() throws Exception {
        var pg = listMigrations("db/migration/postgresql");
        var h2 = listMigrations("db/migration/h2");
        assertThat(h2).as("each PostgreSQL migration needs an H2 twin with the same version and name").isEqualTo(pg);
    }

    private static java.util.SortedSet<String> listMigrations(String location) throws Exception {
        var url = Thread.currentThread().getContextClassLoader().getResource(location);
        assertThat(url).isNotNull();
        try (var files = java.nio.file.Files.list(java.nio.file.Path.of(url.toURI()))) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".sql"))
                    .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        }
    }
}
