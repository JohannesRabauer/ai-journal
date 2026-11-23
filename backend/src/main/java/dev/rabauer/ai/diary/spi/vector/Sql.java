package dev.rabauer.ai.diary.spi.vector;

import io.quarkus.narayana.jta.QuarkusTransaction;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.SQLException;

/** Transaction helper and float/byte conversions shared by the JDBC vector stores. */
final class Sql {

    @FunctionalInterface
    interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private Sql() {
    }

    /**
     * Runs {@code work} on a connection inside a transaction. Managed mode (inside Quarkus) joins the caller's JTA
     * transaction or starts one; standalone mode (a plain DataSource, used by the opt-in pgvector test) commits itself.
     */
    static <T> T inTransaction(DataSource ds, boolean standalone, Work<T> work) {
        if (standalone) {
            try (Connection c = ds.getConnection()) {
                c.setAutoCommit(false);
                try {
                    T result = work.run(c);
                    c.commit();
                    return result;
                } catch (SQLException | RuntimeException e) {
                    c.rollback();
                    throw e;
                }
            } catch (SQLException e) {
                throw failure(e);
            }
        }
        return QuarkusTransaction.joiningExisting().call(() -> {
            try (Connection c = ds.getConnection()) {
                return work.run(c);
            } catch (SQLException e) {
                throw failure(e);
            }
        });
    }

    private static IllegalStateException failure(SQLException e) {
        // message deliberately without SQL text/parameters (they may contain journal text)
        return new IllegalStateException("vector store database operation failed (SQLState " + e.getSQLState() + ")", e);
    }

    static byte[] toBytes(float[] v) {
        ByteBuffer b = ByteBuffer.allocate(v.length * Float.BYTES);
        b.asFloatBuffer().put(v);
        return b.array();
    }

    static float[] fromBytes(byte[] bytes) {
        float[] v = new float[bytes.length / Float.BYTES];
        ByteBuffer.wrap(bytes).asFloatBuffer().get(v);
        return v;
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
