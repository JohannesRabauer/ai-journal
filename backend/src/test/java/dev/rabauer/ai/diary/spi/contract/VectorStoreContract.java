package dev.rabauer.ai.diary.spi.contract;

import dev.rabauer.ai.diary.spi.VectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Every VectorStore implementation must pass this. Extend it and implement {@link #create()}. */
public abstract class VectorStoreContract {

    protected VectorStore store;

    /** Return an empty store (call deleteAll if it is shared). */
    protected abstract VectorStore create() throws Exception;

    @BeforeEach
    void setUpStore() throws Exception {
        store = create();
        store.deleteAll();
    }

    static VectorStore.Chunk chunk(int i, String text, String model, float... v) {
        return new VectorStore.Chunk(i, text, v, model);
    }

    @Test
    void searchReturnsMostSimilarFirst() {
        store.upsert("entry", "1", List.of(chunk(0, "a", "m", 1, 0, 0)));
        store.upsert("entry", "2", List.of(chunk(0, "b", "m", 0.7f, 0.7f, 0)));
        store.upsert("entry", "3", List.of(chunk(0, "c", "m", 0, 0, 1)));
        var matches = store.search("entry", new float[]{1, 0, 0}, "m", 10, 0.0);
        assertThat(matches).extracting(VectorStore.Match::ownerId).startsWith("1", "2");
        assertThat(matches.get(0).score()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(matches.get(0).text()).isEqualTo("a");
    }

    @Test
    void limitAndMinScoreAreApplied() {
        store.upsert("entry", "1", List.of(chunk(0, "a", "m", 1, 0), chunk(1, "b", "m", 0.9f, 0.1f), chunk(2, "c", "m", 0, 1)));
        assertThat(store.search("entry", new float[]{1, 0}, "m", 1, 0.0)).hasSize(1);
        assertThat(store.search("entry", new float[]{1, 0}, "m", 10, 0.5)).hasSize(2);
    }

    @Test
    void otherModelsAndNamespacesAreIgnored() {
        store.upsert("entry", "1", List.of(chunk(0, "a", "old-model", 1, 0)));
        store.upsert("other", "2", List.of(chunk(0, "b", "m", 1, 0)));
        assertThat(store.search("entry", new float[]{1, 0}, "m", 10, 0.0)).isEmpty();
    }

    @Test
    void upsertReplacesAllChunksOfOwner() {
        store.upsert("entry", "1", List.of(chunk(0, "a", "m", 1, 0), chunk(1, "b", "m", 1, 0)));
        store.upsert("entry", "1", List.of(chunk(0, "new", "m", 1, 0)));
        assertThat(store.count("entry", "1")).isEqualTo(1);
        assertThat(store.search("entry", new float[]{1, 0}, "m", 10, 0.0)).extracting(VectorStore.Match::text).containsExactly("new");
    }

    @Test
    void deleteAndDeleteAll() {
        store.upsert("entry", "1", List.of(chunk(0, "a", "m", 1, 0)));
        store.upsert("entry", "2", List.of(chunk(0, "b", "m", 1, 0), chunk(1, "c", "m", 0, 1)));
        store.delete("entry", "1");
        assertThat(store.count("entry", "1")).isZero();
        assertThat(store.deleteAll()).isEqualTo(2);
        assertThat(store.count("entry", "2")).isZero();
    }
}
