package dev.rabauer.ai.diary.transcription;

import dev.rabauer.ai.diary.api.audio.RecordingStatus;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.EntryStatus;
import dev.rabauer.ai.diary.api.entry.UpdateEntryRequest;
import dev.rabauer.ai.diary.api.transcription.StartTranscriptionRequest;
import dev.rabauer.ai.diary.api.transcription.TranscriptionJobDto;
import dev.rabauer.ai.diary.api.transcription.TranscriptionStatus;
import dev.rabauer.ai.diary.audio.RecordingEntity;
import dev.rabauer.ai.diary.audio.RecordingRepository;
import dev.rabauer.ai.diary.audio.RecordingService;
import dev.rabauer.ai.diary.entry.EntryService;
import dev.rabauer.ai.diary.spi.AudioSource;
import dev.rabauer.ai.diary.spi.AudioStorage;
import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.spi.SpeechToText;
import dev.rabauer.ai.diary.web.ApiException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs transcription jobs on a bounded background pool (never on the event loop). Retryable provider failures are
 * retried with exponential backoff; the audio is never touched on failure. Logs ids, counts and error kinds only.
 */
@ApplicationScoped
public class TranscriptionService {

    private static final Logger LOG = Logger.getLogger(TranscriptionService.class);

    @Inject
    TranscriptionJobRepository jobs;
    @Inject
    RecordingRepository recordings;
    @Inject
    SpeechToText stt;
    @Inject
    AudioStorage storage;
    @Inject
    EntryService entries;
    @Inject
    Clock clock;

    @ConfigProperty(name = "journal.transcription.max-attempts", defaultValue = "3")
    int maxAttempts = 3;
    @ConfigProperty(name = "journal.transcription.backoff", defaultValue = "5s")
    Duration backoff = Duration.ofSeconds(5);
    @ConfigProperty(name = "journal.transcription.threads", defaultValue = "1")
    int threads = 1;
    @ConfigProperty(name = "journal.transcription.queue-capacity", defaultValue = "100")
    int queueCapacity = 100;

    private ThreadPoolExecutor worker;
    private ScheduledExecutorService timer;
    private final AtomicInteger inflight = new AtomicInteger();

    @PostConstruct
    void init() {
        worker = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), daemon("transcription-worker"));
        ScheduledThreadPoolExecutor t = new ScheduledThreadPoolExecutor(1, daemon("transcription-retry"));
        t.setRemoveOnCancelPolicy(true);
        timer = t;
    }

    @PreDestroy
    void shutdown() {
        timer.shutdownNow();
        worker.shutdownNow();
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, name + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /** Jobs left QUEUED or RUNNING by a crash or restart are run again. Runs after the migration in StartupValidation. */
    void onStart(@Observes @Priority(100) StartupEvent event) {
        try {
            List<Long> ids = QuarkusTransaction.requiringNew().call(() -> jobs.unfinished().stream().map(j -> {
                j.status = TranscriptionStatus.QUEUED;
                return j.id;
            }).toList());
            ids.forEach(this::submit);
            if (!ids.isEmpty()) {
                LOG.infof("Re-queued %d unfinished transcription job(s)", ids.size());
            }
        } catch (RuntimeException e) {
            LOG.warnf("Could not re-queue transcription jobs (%s)", e.getClass().getSimpleName());
        }
    }

    // ---- API -----------------------------------------------------------------------------------------------

    /** Manual start / retry. */
    public synchronized TranscriptionJobDto start(long entryId, StartTranscriptionRequest request) {
        EntryDto entry = entries.get(entryId); // 404 if missing
        RecordingEntity rec = QuarkusTransaction.requiringNew().call(() -> recordings.forEntry(entryId).stream()
                .filter(r -> r.status == RecordingStatus.COMPLETE).findFirst().orElse(null));
        if (rec == null || !storage.hasAudio(rec.id)) {
            throw ApiException.conflict("NO_RECORDING", "This entry has no complete recording to transcribe.");
        }
        if (QuarkusTransaction.requiringNew().call(() -> jobs.hasActive(entryId))) {
            throw ApiException.conflict("TRANSCRIPTION_RUNNING", "A transcription is already running for this entry.");
        }
        Language lang = request != null && request.language() != null ? request.language()
                : entry.language() != null ? entry.language() : Language.AUTO;
        return enqueue(entryId, rec.id, lang);
    }

    /** Creates and queues a job; the entry becomes TRANSCRIBING. */
    public TranscriptionJobDto enqueue(long entryId, UUID recordingId, Language language) {
        TranscriptionJobEntity job = QuarkusTransaction.requiringNew().call(() -> {
            TranscriptionJobEntity j = new TranscriptionJobEntity();
            j.entryId = entryId;
            j.recordingId = recordingId;
            j.status = TranscriptionStatus.QUEUED;
            j.requestedLanguage = (language == null ? Language.AUTO : language).code();
            j.attempts = 0;
            j.createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
            jobs.persist(j);
            entries.setStatus(entryId, EntryStatus.TRANSCRIBING);
            return j;
        });
        submit(job.id);
        return toDto(job);
    }

    /** Removes the job history of a deleted entry. */
    void onEntryDeleted(@jakarta.enterprise.event.Observes(during = jakarta.enterprise.event.TransactionPhase.AFTER_SUCCESS)
                        dev.rabauer.ai.diary.entry.EntryDeleted event) {
        try {
            io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> jobs.delete("entryId", event.entryId()));
        } catch (RuntimeException e) {
            LOG.warnf("Removing transcription jobs of deleted entry %d failed (%s)", event.entryId(), e.getClass().getSimpleName());
        }
    }

    public TranscriptionJobDto latest(long entryId) {
        TranscriptionJobEntity j = QuarkusTransaction.requiringNew().call(() -> jobs.latestForEntry(entryId));
        if (j == null) {
            throw ApiException.notFound("Transcription");
        }
        return toDto(j);
    }

    public TranscriptionJobDto get(long id) {
        TranscriptionJobEntity j = QuarkusTransaction.requiringNew().call(() -> jobs.findById(id));
        if (j == null) {
            throw ApiException.notFound("Transcription");
        }
        return toDto(j);
    }

    /** For tests: wait until no job is queued, running or waiting for a retry. */
    public void awaitIdle(Duration timeout) {
        long end = System.nanoTime() + timeout.toNanos();
        while (inflight.get() > 0) {
            if (System.nanoTime() > end) {
                throw new IllegalStateException("transcription still busy");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    // ---- execution -----------------------------------------------------------------------------------------

    private void submit(long jobId) {
        inflight.incrementAndGet();
        try {
            worker.execute(() -> run(jobId));
        } catch (RejectedExecutionException e) {
            inflight.decrementAndGet();
            LOG.warnf("Transcription queue full; job %d marked failed (retry manually)", jobId);
            fail(jobId, "PROVIDER_ERROR");
        }
    }

    private void scheduleRetry(long jobId, Duration delay) {
        inflight.incrementAndGet();
        try {
            timer.schedule(() -> {
                inflight.decrementAndGet(); // the run below counts itself via submit()
                submit(jobId);
            }, delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            inflight.decrementAndGet(); // shutting down; startup re-queues the job
        }
    }

    private record Prepared(long entryId, UUID recordingId, Language language, String mimeType, boolean audioPresent, int attempt) {
    }

    private void run(long jobId) {
        try {
            execute(jobId);
        } catch (RuntimeException e) {
            LOG.warnf("Transcription job %d crashed (%s)", jobId, e.getClass().getSimpleName());
            try {
                fail(jobId, "PROVIDER_ERROR");
            } catch (RuntimeException ignored) {
                // nothing more to do; the job is re-queued on the next start
            }
        } finally {
            inflight.decrementAndGet();
        }
    }

    private void execute(long jobId) {
        Prepared p = QuarkusTransaction.requiringNew().call(() -> {
            TranscriptionJobEntity j = jobs.findById(jobId);
            if (j == null || j.status == TranscriptionStatus.SUCCEEDED || j.status == TranscriptionStatus.FAILED) {
                return null;
            }
            j.status = TranscriptionStatus.RUNNING;
            j.attempts++;
            RecordingEntity r = recordings.findById(j.recordingId);
            entries.setStatus(j.entryId, EntryStatus.TRANSCRIBING);
            boolean present = r != null && r.status == RecordingStatus.COMPLETE && storage.hasAudio(r.id);
            return new Prepared(j.entryId, j.recordingId, Language.fromCode(j.requestedLanguage),
                    r == null ? null : r.mimeType, present, j.attempts);
        });
        if (p == null) {
            return;
        }
        if (!p.audioPresent()) {
            LOG.warnf("Transcription job %d: audio of recording %s is missing", jobId, p.recordingId());
            fail(jobId, "AUDIO_MISSING");
            return;
        }
        AudioSource source = new AudioSource(() -> storage.openAudio(p.recordingId()), storage.audioSize(p.recordingId()),
                p.mimeType(), "recording." + RecordingService.extensionFor(p.mimeType()));
        SpeechToText.Result result;
        try {
            result = stt.transcribe(new SpeechToText.Request(source, p.language()));
        } catch (ProviderException e) {
            LOG.warnf("Transcription job %d attempt %d failed (%s/%s)", jobId, p.attempt(), e.provider(), e.kind());
            if (e.retryable() && p.attempt() < maxAttempts) {
                QuarkusTransaction.requiringNew().run(() -> {
                    TranscriptionJobEntity j = jobs.findById(jobId);
                    if (j != null) {
                        j.status = TranscriptionStatus.QUEUED;
                    }
                });
                scheduleRetry(jobId, Backoff.delay(p.attempt(), backoff));
            } else {
                fail(jobId, e.kind() == ProviderException.Kind.UNREACHABLE ? "PROVIDER_UNREACHABLE" : "PROVIDER_ERROR");
            }
            return;
        }
        succeed(jobId, p.entryId(), result);
    }

    private void succeed(long jobId, long entryId, SpeechToText.Result result) {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                EntryDto e = entries.get(entryId);
                entries.update(entryId, new UpdateEntryRequest(merge(e.text(), result.text()),
                        e.language() == null && result.detectedLanguage() != Language.AUTO ? result.detectedLanguage() : null));
                entries.setStatus(entryId, EntryStatus.TRANSCRIBED);
                TranscriptionJobEntity j = jobs.findById(jobId);
                if (j != null) {
                    j.status = TranscriptionStatus.SUCCEEDED;
                    j.detectedLanguage = result.detectedLanguage().code();
                    j.errorCode = null;
                    j.finishedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
                }
            });
            LOG.infof("Transcription job %d succeeded for entry %d (%d characters)", jobId, entryId, result.text().length());
        } catch (ApiException e) {
            LOG.warnf("Transcription job %d: entry %d is gone (%s)", jobId, entryId, e.code());
            fail(jobId, "PROVIDER_ERROR");
        }
    }

    /** Appends to existing text after a blank line. */
    static String merge(String existing, String transcript) {
        String t = transcript == null ? "" : transcript.strip();
        if (t.isEmpty()) {
            return existing == null ? "" : existing;
        }
        if (existing == null || existing.isBlank()) {
            return t;
        }
        return existing.stripTrailing() + "\n\n" + t;
    }

    private void fail(long jobId, String errorCode) {
        QuarkusTransaction.requiringNew().run(() -> {
            TranscriptionJobEntity j = jobs.findById(jobId);
            if (j == null) {
                return;
            }
            j.status = TranscriptionStatus.FAILED;
            j.errorCode = errorCode;
            j.finishedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
            try {
                entries.setStatus(j.entryId, EntryStatus.TRANSCRIPTION_FAILED);
            } catch (ApiException e) {
                // entry deleted meanwhile
            }
        });
    }

    static TranscriptionJobDto toDto(TranscriptionJobEntity j) {
        return new TranscriptionJobDto(j.id, j.entryId, j.recordingId, j.status, Language.fromCode(j.requestedLanguage),
                j.detectedLanguage == null ? null : Language.fromCode(j.detectedLanguage), j.attempts, j.errorCode,
                j.createdAt, j.finishedAt);
    }
}
