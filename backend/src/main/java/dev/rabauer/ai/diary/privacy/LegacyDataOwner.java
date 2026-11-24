package dev.rabauer.ai.diary.privacy;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pre-v1 tables that v1 no longer writes: {@code chat_history} (LLM memory of the removed auto-analysis) and the
 * pgvector table {@code diary} (PostgreSQL only, may not exist). Kept for data continuity; covered by export
 * ("legacy/chat_history.json", "legacy/diary.json") and delete-all.
 */
@ApplicationScoped
public class LegacyDataOwner implements UserDataOwner {

    @Inject
    AgroalDataSource dataSource;

    @Override
    public String name() {
        return "legacy";
    }

    @Override
    public int deleteOrder() {
        return 900;
    }

    @Override
    public long deleteAll() {
        long n = 0;
        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            n += st.executeUpdate("DELETE FROM chat_history");
            if (tableExists(c, "diary")) {
                n += st.executeUpdate("DELETE FROM diary");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Deleting legacy data failed: " + e.getSQLState(), e);
        }
        return n;
    }

    @Override
    public void export(ExportSink sink) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); var st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, sessionid, serializedmessage FROM chat_history ORDER BY id")) {
            while (rs.next()) {
                rows.add(Map.of("id", rs.getLong(1), "sessionId", String.valueOf(rs.getString(2)), "message", String.valueOf(rs.getString(3))));
            }
        } catch (SQLException e) {
            throw new IOException("Exporting legacy data failed: " + e.getSQLState(), e);
        }
        sink.json("legacy/chat_history.json", rows);
        sink.json("legacy/diary.json", exportDiary());
    }

    /**
     * The pre-v1 pgvector table {@code diary} (PostgreSQL only, may not exist): text and metadata of each row (the
     * embedding itself is derived data and not exported). The metadata may identify the source entry; rows whose
     * metadata does not are only removed by delete-all (docs/PRIVACY.md).
     */
    private List<Map<String, Object>> exportDiary() throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            if (!tableExists(c, "diary")) {
                return rows;
            }
            try (var st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT CAST(embedding_id AS VARCHAR(64)), text, CAST(metadata AS VARCHAR(100000)) FROM diary ORDER BY 1")) {
                while (rs.next()) {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("embeddingId", rs.getString(1));
                    row.put("text", rs.getString(2));
                    row.put("metadata", rs.getString(3));
                    rows.add(row);
                }
            }
        } catch (SQLException e) {
            throw new IOException("Exporting legacy diary rows failed: " + e.getSQLState(), e);
        }
        return rows;
    }

    static boolean tableExists(Connection c, String table) throws SQLException {
        try (ResultSet rs = c.getMetaData().getTables(null, null, null, new String[]{"TABLE"})) {
            while (rs.next()) {
                if (table.equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }
}
