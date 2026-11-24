package dev.rabauer.ai.diary.entry;

import dev.rabauer.ai.diary.api.entry.EntrySource;
import dev.rabauer.ai.diary.api.entry.EntryStatus;
import dev.rabauer.ai.diary.privacy.PurgeGeneration;
import dev.rabauer.ai.diary.spi.EmbeddingGateway;
import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.spi.fake.FakeEmbeddingGateway;
import dev.rabauer.ai.diary.support.Fakes;
import dev.rabauer.ai.diary.support.LogCapture;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class EntryBackfillTest {

    private static final String SECRET = "ZEBRA-PRIVATE-ENTRY-9913";

    @Inject
    EntryRepository repository;
    @Inject
    EntryIndexState state;
    @Inject
    PurgeGeneration purge;

    /** Deterministic embeddings that can be switched to "provider down" or "reject texts containing X". */
    static final class ControlledEmbeddings implements EmbeddingGateway {
        final FakeEmbeddingGateway delegate = new FakeEmbeddingGateway();
        volatile boolean down;
        volatile String rejectContaining;
        volatile String model = "test-model";
        final AtomicInteger calls = new AtomicInteger();
        private volatile int dimension;

        @Override
        public float[] embed(String text) {
            calls.incrementAndGet();
            if (down) {
                throw new ProviderException("test", ProviderException.Kind.UNREACHABLE, "down");
            }
            if (rejectContaining != null && text.contains(rejectContaining)) {
                throw new ProviderException("test", ProviderException.Kind.REJECTED, "rejected");
            }
            float[] v = delegate.embed(text);
            dimension = v.length;
            return v;
        }

        @Override
        public String modelId() {
            return model + (dimension > 0 ? ":" + dimension : "");
        }
    }

    private ControlledEmbeddings embeddings;
    private EntryIndexer indexer;
    private EntryBackfill backfill;

    @BeforeEach
    void setUp() {
        Fakes.reset();
        QuarkusTransaction.requiringNew().run(() -> repository.deleteAll());
        state.deleteAll();
        embeddings = new ControlledEmbeddings();
        indexer = new EntryIndexer();
        indexer.embeddings = embeddings;
        indexer.vectors = Fakes.vectors();
        indexer.repository = repository;
        indexer.state = state;
        indexer.purge = purge;
        backfill = new EntryBackfill();
        backfill.embeddings = embeddings;
        backfill.indexer = indexer;
        backfill.state = state;
        backfill.pause = Duration.ZERO;
        backfill.interval = Duration.ofMinutes(15);
    }

    private long entry(String text, EntryStatus status) {
        return QuarkusTransaction.requiringNew().call(() -> {
            DiaryEntryEntity e = new DiaryEntryEntity();
            e.entryText = text;
            e.timestamp = LocalDateTime.of(2025, 1, 2, 3, 4);
            e.status = status;
            e.source = EntrySource.TEXT;
            e.createdAt = e.updatedAt = Instant.now();
            e.finishedAt = status == EntryStatus.FINISHED ? Instant.now() : null;
            repository.persist(e);
            return e.id;
        });
    }

    private int chunks(long id) {
        return Fakes.vectors().count(EntryIndexer.NAMESPACE, Long.toString(id));
    }

    @Test
    void legacyFinishedEntriesAreIndexedAndDraftsAreNot() {
        long a = entry("Walked along the river. It helped.", EntryStatus.FINISHED);
        long b = entry("Cooked dinner for friends.", EntryStatus.FINISHED);
        long draft = entry("Still writing this", EntryStatus.DRAFT);

        var result = backfill.runOnce();

        assertThat(result.indexed()).isEqualTo(2);
        assertThat(result.failed()).isZero();
        assertThat(result.stoppedUnreachable()).isFalse();
        assertThat(chunks(a)).isPositive();
        assertThat(chunks(b)).isPositive();
        assertThat(chunks(draft)).isZero();
        assertThat(state.unindexed(embeddings.modelId(), 0, 10)).isEmpty();

        int callsBefore = embeddings.calls.get();
        assertThat(backfill.runOnce().indexed()).as("nothing left to do").isZero();
        assertThat(embeddings.calls.get()).isEqualTo(callsBefore);
    }

    @Test
    void unreachableProviderLeavesEntriesForTheNextRun() {
        long a = entry("First entry.", EntryStatus.FINISHED);
        long b = entry("Second entry.", EntryStatus.FINISHED);
        embeddings.down = true;

        var failed = backfill.runOnce();
        assertThat(failed.stoppedUnreachable()).isTrue();
        assertThat(failed.indexed()).isZero();
        assertThat(chunks(a)).isZero();
        assertThat(chunks(b)).isZero();
        assertThat(embeddings.calls.get()).as("stops at the first unreachable call").isEqualTo(1);

        embeddings.down = false;
        var retry = backfill.runOnce();
        assertThat(retry.indexed()).isEqualTo(2);
        assertThat(chunks(a)).isPositive();
        assertThat(chunks(b)).isPositive();
    }

    @Test
    void failureOfOneEntryDoesNotBlockOthersAndIsRetriedLater() {
        long good = entry("A fine day.", EntryStatus.FINISHED);
        long bad = entry("Contains the " + SECRET + " marker.", EntryStatus.FINISHED);
        embeddings.rejectContaining = SECRET;

        try (LogCapture logs = new LogCapture()) {
            var result = backfill.runOnce();
            assertThat(result.indexed()).isEqualTo(1);
            assertThat(result.failed()).isEqualTo(1);
            assertThat(logs.all()).doesNotContain(SECRET);
        }
        assertThat(chunks(good)).isPositive();
        assertThat(chunks(bad)).isZero();

        embeddings.rejectContaining = null;
        assertThat(backfill.runOnce().indexed()).isEqualTo(1);
        assertThat(chunks(bad)).isPositive();
    }

    @Test
    void changingTheEmbeddingModelReindexesEverything() {
        long a = entry("Some entry text.", EntryStatus.FINISHED);
        backfill.runOnce();
        assertThat(chunks(a)).isPositive();

        embeddings.model = "other-model";
        var result = backfill.runOnce();
        assertThat(result.indexed()).isEqualTo(1);
        assertThat(Fakes.vectors().search(EntryIndexer.NAMESPACE, embeddings.delegate.embed("Some entry text."),
                "other-model:64", 5, 0.0)).hasSize(1);
    }

    @Test
    void entriesFinishedLaterAreIndexedByTheIndexerAndNeedNoBackfill() {
        long a = entry("Indexed straight away.", EntryStatus.FINISHED);
        assertThat(indexer.index(a)).isTrue();
        int before = embeddings.calls.get();
        assertThat(backfill.runOnce().indexed()).isZero();
        assertThat(embeddings.calls.get()).isEqualTo(before);
    }
}
