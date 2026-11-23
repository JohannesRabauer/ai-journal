package dev.rabauer.ai.diary.spi.vector;

import dev.rabauer.ai.diary.spi.ProviderName;
import dev.rabauer.ai.diary.spi.VectorStore;
import dev.rabauer.ai.diary.spi.contract.VectorStoreContract;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The jdbc store on the test database (H2), through the real CDI bean. */
@QuarkusTest
class JdbcVectorStoreTest extends VectorStoreContract {

    @Inject
    @ProviderName("jdbc")
    JdbcVectorStore jdbc;

    @Override
    protected VectorStore create() {
        return jdbc;
    }

    @Test
    void differentDimensionsOfTheSameModelAreSkippedNotFailed() {
        store.upsert("entry", "1", List.of(new VectorStore.Chunk(0, "a", new float[]{1, 0, 0}, "m")));
        store.upsert("entry", "2", List.of(new VectorStore.Chunk(0, "b", new float[]{1, 0}, "m")));
        assertThat(store.search("entry", new float[]{1, 0}, "m", 10, 0.0)).extracting(VectorStore.Match::ownerId).containsExactly("2");
    }

    @Test
    void vectorsSurviveStorageExactly() {
        float[] v = {0.123456789f, -3.5f, 1e-7f};
        store.upsert("entry", "1", List.of(new VectorStore.Chunk(0, "t", v, "m")));
        var m = store.search("entry", v, "m", 1, 0.0);
        assertThat(m).hasSize(1);
        assertThat(m.get(0).score()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void searchKeepsOnlyTheBestLimitAcrossManyRows() {
        for (int i = 0; i < 200; i++) {
            store.upsert("entry", Integer.toString(i), List.of(new VectorStore.Chunk(0, "c" + i, new float[]{1, i / 100f}, "m")));
        }
        var top = store.search("entry", new float[]{1, 0}, "m", 3, 0.0);
        assertThat(top).extracting(VectorStore.Match::ownerId).containsExactly("0", "1", "2");
    }
}
