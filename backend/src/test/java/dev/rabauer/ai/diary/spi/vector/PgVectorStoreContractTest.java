package dev.rabauer.ai.diary.spi.vector;

import dev.rabauer.ai.diary.spi.VectorStore;
import dev.rabauer.ai.diary.spi.contract.VectorStoreContract;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;

/**
 * Opt-in: runs {@link VectorStoreContract} against a real PostgreSQL with the pgvector extension. Skipped unless
 * {@code JOURNAL_TEST_PGVECTOR_URL} is set, so {@code scripts/verify.ps1} stays Docker-free.
 *
 * <pre>
 * docker run --rm -d --name pgv -p 55432:5432 -e POSTGRES_PASSWORD=pw -e POSTGRES_DB=test pgvector/pgvector:pg17
 * $env:JOURNAL_TEST_PGVECTOR_URL = 'jdbc:postgresql://localhost:55432/test'
 * $env:JOURNAL_TEST_PGVECTOR_USER = 'postgres'; $env:JOURNAL_TEST_PGVECTOR_PASSWORD = 'pw'
 * ./mvnw -pl backend -am test -Dtest=PgVectorStoreContractTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * The test applies migration V32 itself (idempotent) and empties {@code vector_chunk_pg}; use a throw-away database.
 */
@EnabledIfEnvironmentVariable(named = "JOURNAL_TEST_PGVECTOR_URL", matches = ".+")
class PgVectorStoreContractTest extends VectorStoreContract {

    @Override
    protected VectorStore create() throws Exception {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(System.getenv("JOURNAL_TEST_PGVECTOR_URL"));
        ds.setUser(System.getenv().getOrDefault("JOURNAL_TEST_PGVECTOR_USER", "postgres"));
        ds.setPassword(System.getenv().getOrDefault("JOURNAL_TEST_PGVECTOR_PASSWORD", ""));
        migrate(ds);
        return new PgVectorStore(ds);
    }

    private static void migrate(DataSource ds) throws Exception {
        try (InputStream in = PgVectorStoreContractTest.class.getResourceAsStream("/db/migration/postgresql/V32__vector_chunk_pg.sql");
             Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
