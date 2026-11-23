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
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Vector store on plain JDBC tables ({@code vector_chunk}, migration V31) that works on H2 and PostgreSQL.
 * Vectors are stored as big-endian float32 bytes; cosine similarity is computed in Java while streaming only the rows
 * of the requested namespace + model + dimension (fine at journal scale: thousands of chunks).
 */
@ApplicationScoped
@ProviderName("jdbc")
@Unremovable
public class JdbcVectorStore implements VectorStore {

    private static final int FETCH_SIZE = 500;

    @Inject
    DataSource dataSource;
    private boolean standalone;

    public JdbcVectorStore() {
    }

    /** Standalone use with a plain DataSource (no Quarkus transaction manager). */
    public JdbcVectorStore(DataSource dataSource) {
        this.dataSource = dataSource;
        this.standalone = true;
    }

    @Override
    public void upsert(String namespace, String ownerId, List<Chunk> chunks) {
        Sql.inTransaction(dataSource, standalone, c -> {
            deleteOwner(c, namespace, ownerId);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO vector_chunk "
                    + "(namespace, owner_id, chunk_index, chunk_text, model_id, dimension, vector_data) VALUES (?,?,?,?,?,?,?)")) {
                for (Chunk ch : chunks) {
                    ps.setString(1, namespace);
                    ps.setString(2, ownerId);
                    ps.setInt(3, ch.index());
                    ps.setString(4, ch.text());
                    ps.setString(5, ch.modelId());
                    ps.setInt(6, ch.vector().length);
                    ps.setBytes(7, Sql.toBytes(ch.vector()));
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
        return Sql.inTransaction(dataSource, standalone, c -> {
            PriorityQueue<Match> best = new PriorityQueue<>(Comparator.comparingDouble(Match::score));
            try (PreparedStatement ps = c.prepareStatement("SELECT owner_id, chunk_index, chunk_text, vector_data FROM vector_chunk "
                    + "WHERE namespace = ? AND model_id = ? AND dimension = ?")) {
                ps.setFetchSize(FETCH_SIZE);
                ps.setString(1, namespace);
                ps.setString(2, modelId);
                ps.setInt(3, query.length);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        double score = Sql.cosine(query, Sql.fromBytes(rs.getBytes(4)));
                        if (score < minScore || (best.size() >= limit && score <= best.peek().score())) {
                            continue;
                        }
                        best.add(new Match(namespace, rs.getString(1), rs.getInt(2), rs.getString(3), score));
                        if (best.size() > limit) {
                            best.poll();
                        }
                    }
                }
            }
            List<Match> out = new ArrayList<>(best);
            out.sort(Comparator.comparingDouble(Match::score).reversed());
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
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM vector_chunk WHERE namespace = ? AND owner_id = ?")) {
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
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM vector_chunk")) {
                return (long) ps.executeUpdate();
            }
        });
    }

    private static void deleteOwner(Connection c, String namespace, String ownerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM vector_chunk WHERE namespace = ? AND owner_id = ?")) {
            ps.setString(1, namespace);
            ps.setString(2, ownerId);
            ps.executeUpdate();
        }
    }
}
