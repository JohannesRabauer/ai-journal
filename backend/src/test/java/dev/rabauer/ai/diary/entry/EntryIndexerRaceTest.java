package dev.rabauer.ai.diary.entry;

import dev.rabauer.ai.diary.api.entry.EntrySource;
import dev.rabauer.ai.diary.api.entry.EntryStatus;
import dev.rabauer.ai.diary.privacy.PrivacyService;
import dev.rabauer.ai.diary.privacy.PurgeGeneration;
import dev.rabauer.ai.diary.spi.EmbeddingGateway;
import dev.rabauer.ai.diary.spi.fake.FakeEmbeddingGateway;
import dev.rabauer.ai.diary.support.Fakes;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** M1: an indexing run that is blocked in the embedder must not resurrect text that was deleted meanwhile. */
@QuarkusTest
class EntryIndexerRaceTest {

    @Inject
    EntryRepository repository;
    @Inject
    EntryIndexState state;
    @Inject
    PurgeGeneration purge;
    @Inject
    PrivacyService privacy;

    /** Blocks inside embedAll until released. */
    static final class GatedEmbeddings implements EmbeddingGateway {
        final FakeEmbeddingGateway delegate = new FakeEmbeddingGateway();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public float[] embed(String text) {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("not released");
                }
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return delegate.embed(text);
        }

        @Override
        public String modelId() {
            return "gated";
        }
    }

    private GatedEmbeddings embeddings;
    private EntryIndexer indexer;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    @BeforeEach
    void setUp() {
        Fakes.reset();
        QuarkusTransaction.requiringNew().run(() -> repository.deleteAll());
        state.deleteAll();
        embeddings = new GatedEmbeddings();
        indexer = new EntryIndexer();
        indexer.embeddings = embeddings;
        indexer.vectors = Fakes.vectors();
        indexer.repository = repository;
        indexer.state = state;
        indexer.purge = purge;
    }

    private long entry(String text) {
        return QuarkusTransaction.requiringNew().call(() -> {
            DiaryEntryEntity e = new DiaryEntryEntity();
            e.entryText = text;
            e.timestamp = LocalDateTime.of(2025, 1, 2, 3, 4);
            e.status = EntryStatus.FINISHED;
            e.source = EntrySource.TEXT;
            e.createdAt = e.updatedAt = Instant.now();
            e.finishedAt = Instant.now();
            repository.persist(e);
            return e.id;
        });
    }

    private int chunks(long id) {
        return Fakes.vectors().count(EntryIndexer.NAMESPACE, Long.toString(id));
    }

    private Future<EntryIndexer.Outcome> startIndexing(long id) throws InterruptedException {
        Future<EntryIndexer.Outcome> f = pool.submit(() -> indexer.attempt(id));
        assertThat(embeddings.entered.await(10, TimeUnit.SECONDS)).isTrue();
        return f;
    }

    @Test
    void entryDeletedWhileEmbeddingLeavesNoVectorsOrState() throws Exception {
        long id = entry("Private words about the river.");
        Future<EntryIndexer.Outcome> run = startIndexing(id);
        QuarkusTransaction.requiringNew().run(() -> repository.deleteById(id));
        embeddings.release.countDown();
        run.get(10, TimeUnit.SECONDS);
        assertThat(chunks(id)).isZero();
        assertThat(state.unindexed("gated", 0, 10)).isEmpty();
    }

    @Test
    void deleteAllWhileEmbeddingDropsTheResult() throws Exception {
        long id = entry("Private words about the river.");
        Future<EntryIndexer.Outcome> run = startIndexing(id);
        privacy.deleteAll();
        embeddings.release.countDown();
        run.get(10, TimeUnit.SECONDS);
        assertThat(chunks(id)).isZero();
        assertThat(Fakes.vectors().count(EntryIndexer.NAMESPACE, Long.toString(id))).isZero();
    }

    @Test
    void undisturbedIndexingStillWrites() throws Exception {
        long id = entry("Private words about the river.");
        embeddings.release.countDown();
        assertThat(indexer.attempt(id)).isEqualTo(EntryIndexer.Outcome.INDEXED);
        assertThat(chunks(id)).isPositive();
    }
}
