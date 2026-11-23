package dev.rabauer.ai.diary.entry;

import dev.rabauer.ai.diary.spi.EmbeddingGateway;
import dev.rabauer.ai.diary.spi.ProviderException;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import dev.rabauer.ai.diary.spi.llm.W1eConfigKeys;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background (re-)indexing of FINISHED entries that are not yet indexed for the CURRENT embedding model: legacy
 * entries, entries whose indexing failed, and everything after an embedding model change. Runs shortly after startup
 * (never blocks it) and then periodically. Rate limited; stops at the first UNREACHABLE provider and retries on the
 * next run. Logs ids/counts only, never content.
 */
@ApplicationScoped
public class EntryBackfill {

    private static final Logger LOG = Logger.getLogger(EntryBackfill.class);
    private static final int PAGE = 50;

    @Inject
    EmbeddingGateway embeddings;
    @Inject
    EntryIndexer indexer;
    @Inject
    EntryIndexState state;

    /** Read from {@code journal.index.backfill.*} at startup (see W1eConfigKeys); plain fields so tests can set them. */
    @Inject
    W1eConfigKeys keys;
    boolean enabled = true;
    Duration initialDelay = Duration.ofSeconds(10);
    Duration interval = Duration.ofMinutes(15);
    /** Pause between two entries, so a large backlog does not saturate the (local) embedding model. */
    Duration pause = Duration.ofMillis(250);

    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ScheduledExecutorService scheduler;

    /** Result of one run, for tests and logs. */
    public record Result(int indexed, int failed, boolean stoppedUnreachable) {
    }

    void onStart(@Observes StartupEvent event) {
        var cfg = keys.index().backfill();
        enabled = cfg.enabled();
        initialDelay = cfg.initialDelay();
        interval = cfg.interval();
        pause = cfg.pause();
        if (!enabled) {
            LOG.info("Entry backfill disabled (journal.index.backfill.enabled=false).");
            return;
        }
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "entry-backfill");
            t.setDaemon(true);
            return t;
        });
        scheduler = s;
        s.scheduleWithFixedDelay(() -> {
            try {
                runOnce();
            } catch (RuntimeException e) {
                LOG.warnf("Entry backfill run failed: %s", e.getClass().getSimpleName());
            }
        }, initialDelay.toMillis(), Math.max(1000, interval.toMillis()), TimeUnit.MILLISECONDS);
    }

    /** One synchronous pass over all entries missing from the current embedding model. */
    public Result runOnce() {
        if (!running.compareAndSet(false, true)) {
            return new Result(0, 0, false);
        }
        try {
            return doRun();
        } finally {
            running.set(false);
        }
    }

    private Result doRun() {
        int indexed = 0;
        int failed = 0;
        String modelId = embeddings.modelId();
        boolean probed = false;
        long after = 0;
        while (!Thread.currentThread().isInterrupted()) {
            List<Long> ids = state.unindexed(modelId, after, PAGE);
            if (ids.isEmpty()) {
                break;
            }
            if (!probed) {
                // The model id includes the vector dimension once known, so learn it first (content-free probe).
                probed = true;
                try {
                    embeddings.embed("dimension probe");
                } catch (ProviderException e) {
                    return stopped(indexed, failed, e);
                }
                String actual = embeddings.modelId();
                if (!actual.equals(modelId)) {
                    modelId = actual;
                    continue; // candidate list depends on the model id: query again from the start
                }
            }
            for (long id : ids) {
                after = id;
                EntryIndexer.Outcome outcome = indexer.attempt(id);
                switch (outcome) {
                    case INDEXED -> indexed++;
                    case FAILED -> failed++;
                    case UNREACHABLE -> {
                        return stopped(indexed, failed, null);
                    }
                }
                if (!pause()) {
                    return new Result(indexed, failed, false);
                }
            }
        }
        if (indexed > 0 || failed > 0) {
            LOG.infof("Entry backfill: indexed %d entries, %d failed (retried on next run).", indexed, failed);
        }
        return new Result(indexed, failed, false);
    }

    private Result stopped(int indexed, int failed, ProviderException e) {
        LOG.infof("Entry backfill paused: embedding provider unreachable; indexed %d, will retry in %s.", indexed, interval);
        return new Result(indexed, failed, true);
    }

    private boolean pause() {
        long ms = pause.toMillis();
        if (ms <= 0) {
            return true;
        }
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @PreDestroy
    void shutdown() {
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            s.shutdownNow();
        }
    }
}
