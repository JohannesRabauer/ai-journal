package dev.rabauer.ai.diary.privacy;

import dev.rabauer.ai.diary.spi.AudioStorage;
import dev.rabauer.ai.diary.spi.VectorStore;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

/** Registers the non-DB stores (vectors, audio) for delete-all. Audio export is done by the recordings owner. */
@ApplicationScoped
public class StoreDataOwners {

    @Inject
    VectorStore vectors;
    @Inject
    AudioStorage audio;
    @Inject
    AgroalDataSource dataSource;

    @Produces
    @ApplicationScoped
    UserDataOwner vectorOwner() {
        return new UserDataOwner() {
            @Override
            public String name() {
                return "vectors";
            }

            @Override
            public int deleteOrder() {
                return 2000;
            }

            @Override
            public long deleteAll() {
                // The active store clears its own table; a store that was active earlier (e.g. after switching
                // jdbc <-> pgvector) may have left rows in the other table: clear both when they exist.
                return vectors.deleteAll() + clearVectorTables();
            }

            @Override
            public void export(ExportSink sink) {
                // derived data, rebuilt from entries; not exported
            }
        };
    }

    private long clearVectorTables() {
        long n = 0;
        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            for (String table : new String[]{"vector_chunk", "vector_chunk_pg"}) {
                if (LegacyDataOwner.tableExists(c, table)) {
                    n += st.executeUpdate("DELETE FROM " + table);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Deleting vector tables failed: " + e.getSQLState(), e);
        }
        return n;
    }

    @Produces
    @ApplicationScoped
    UserDataOwner audioOwner() {
        return new UserDataOwner() {
            @Override
            public String name() {
                return "audio-files";
            }

            @Override
            public int deleteOrder() {
                return 2000;
            }

            @Override
            public long deleteAll() {
                return audio.deleteAll();
            }

            @Override
            public void export(ExportSink sink) throws IOException {
                // exported per recording by the recordings feature (W1a), which knows the ids
            }
        };
    }
}
