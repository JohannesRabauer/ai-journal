package dev.rabauer.ai.diary.spi.vector;

import dev.rabauer.ai.diary.spi.ProviderName;
import dev.rabauer.ai.diary.spi.VectorStore;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL + pgvector. Table {@code vector_chunk_pg} (migration V32) with an unbounded {@code vector} column; the
 * search ranks by cosine distance ({@code <=>}) after filtering on namespace, model id and dimension, so rows of other
 * models/dimensions never reach the operator. Not related to the legacy {@code diary} table.
 */
@ApplicationScoped
@ProviderName("pgvector")
@Unremovable
public class PgVectorStore implements VectorStore {

    @Inject
    DataSource dataSource;
    private boolean standalone;

    public PgVectorStore() {
    }

    /** Standalone use with a plain DataSource (no Quarkus transaction manager), e.g. the opt-in contract test. */
    public PgVectorStore(DataSource dataSource) {
        this.dataSource = dataSource;
        this.standalone = true;
    }

    @Override
    public void upsert(String namespace, String ownerId, List<Chunk> chunks) {
        Sql.inTransaction(dataSource, standalone, c -> {
            deleteOwner(c, namespace, ownerId);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO vector_chunk_pg "
                    + "(namespace, owner_id, chunk_index, chunk_text, model_id, dimension, embedding) VALUES (?,?,?,?,?,?,?::vector)")) {
                for (Chunk ch : chunks) {
                    ps.setString(1, namespace);
                    ps.setString(2, ownerId);
                    ps.setInt(3, ch.index());
                    ps.setString(4, ch.text());
                    ps.setString(5, ch.modelId());
                    ps.setInt(6, ch.vector().length);
                    ps.setString(7, literal(ch.vector()));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    @Override
    public List<Match> search(String namespace, float[] query, String modelId, int limit, double minScore) {
        if (limit <= 0) {
            return List.of();
        }
        String q = literal(query);
        return Sql.inTransaction(dataSource, standalone, c -> {
            List<Match> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT owner_id, chunk_index, chunk_text, 1 - (embedding <=> ?::vector) AS score "
                    + "FROM vector_chunk_pg WHERE namespace = ? AND model_id = ? AND dimension = ? "
                    + "ORDER BY embedding <=> ?::vector LIMIT ?")) {
                ps.setString(1, q);
                ps.setString(2, namespace);
                ps.setString(3, modelId);
                ps.setInt(4, query.length);
                ps.setString(5, q);
                ps.setInt(6, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        double score = rs.getDouble(4);
                        if (score >= minScore) {
                            out.add(new Match(namespace, rs.getString(1), rs.getInt(2), rs.getString(3), score));
                        }
                    }
                }
            }
            return out;
        });
    }

    @Override
    public void delete(String namespace, String ownerId) {
        Sql.inTransaction(dataSource, standalone, c -> {
            deleteOwner(c, namespace, ownerId);
            return null;
        });
    }

    @Override
    public int count(String namespace, String ownerId) {
        return Sql.inTransaction(dataSource, standalone, c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM vector_chunk_pg WHERE namespace = ? AND owner_id = ?")) {
                ps.setString(1, namespace);
                ps.setString(2, ownerId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    @Override
    public long deleteAll() {
        return Sql.inTransaction(dataSource, standalone, c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM vector_chunk_pg")) {
                return (long) ps.executeUpdate();
            }
        });
    }

    private static void deleteOwner(Connection c, String namespace, String ownerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM vector_chunk_pg WHERE namespace = ? AND owner_id = ?")) {
            ps.setString(1, namespace);
            ps.setString(2, ownerId);
            ps.executeUpdate();
        }
    }

    /** pgvector text input format: {@code [0.1,0.2,...]}. */
    static String literal(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
