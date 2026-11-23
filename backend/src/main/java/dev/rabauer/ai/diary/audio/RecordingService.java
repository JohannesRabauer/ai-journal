package dev.rabauer.ai.diary.audio;

import dev.rabauer.ai.diary.api.audio.ChunkAckDto;
import dev.rabauer.ai.diary.api.audio.CompleteRecordingRequest;
import dev.rabauer.ai.diary.api.audio.RecordingDto;
import dev.rabauer.ai.diary.api.audio.RecordingStatus;
import dev.rabauer.ai.diary.api.audio.StartRecordingRequest;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.entry.CreateEntryRequest;
import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.EntrySource;
import dev.rabauer.ai.diary.api.entry.EntryStatus;
import dev.rabauer.ai.diary.entry.EntryFinished;
import dev.rabauer.ai.diary.entry.EntryService;
import dev.rabauer.ai.diary.settings.SettingsService;
import dev.rabauer.ai.diary.spi.AudioStorage;
import dev.rabauer.ai.diary.transcription.TranscriptionService;
import dev.rabauer.ai.diary.web.ApiException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Chunked, resumable recording upload (SPEC 4.1). Logs ids and counts only. */
@ApplicationScoped
public class RecordingService {

    private static final Logger LOG = Logger.getLogger(RecordingService.class);
    static final String DEFAULT_MIME = "audio/webm";
    /** type/subtype with optional parameters such as ;codecs=opus (no control characters, no quotes). */
    private static final Pattern MIME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.+-]*/[A-Za-z0-9][A-Za-z0-9.+-]*(\\s*;\\s*[A-Za-z0-9=._+, -]+)?");
    private static final int MAX_LISTED_MISSING = 20;

    @Inject
    RecordingRepository repository;
    @Inject
    AudioStorage storage;
    @Inject
    EntryService entries;
    @Inject
    TranscriptionService transcriptions;
    @Inject
    SettingsService settings;
    @Inject
    Clock clock;

    public RecordingDto start(StartRecordingRequest request) {
        StartRecordingRequest req = request == null ? new StartRecordingRequest(null, null, null, null) : request;
        String mime = req.mimeType() == null || req.mimeType().isBlank() ? DEFAULT_MIME : req.mimeType().strip();
        if (mime.length() > 100 || !MIME.matcher(mime).matches()) {
            throw ApiException.badRequest("INVALID_MIME_TYPE", "The audio format is not valid.");
        }
        RecordingEntity r = QuarkusTransaction.requiringNew().call(() -> {
            long entryId;
            if (req.entryId() == null) {
                entryId = entries.create(new CreateEntryRequest("", req.entryTimestamp(), req.language(), EntrySource.VOICE)).id();
            } else {
                entryId = entries.get(req.entryId()).id(); // 404 if missing
            }
            entries.setStatus(entryId, EntryStatus.RECORDING);
            RecordingEntity e = new RecordingEntity();
            e.id = UUID.randomUUID();
            e.entryId = entryId;
            e.mimeType = mime;
            e.status = RecordingStatus.RECORDING;
            e.createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
            repository.persist(e);
            return e;
        });
        LOG.infof("Recording %s started for entry %d", r.id, r.entryId);
        return toDto(r);
    }

    public ChunkAckDto uploadChunk(UUID id, int index, InputStream body) {
        if (index < 0 || index > FilesystemAudioStorage.MAX_CHUNK_INDEX) {
            throw ApiException.badRequest("INVALID_CHUNK_INDEX", "Chunk index is out of range.");
        }
        RecordingEntity r = find(id);
        if (r.status != RecordingStatus.RECORDING) {
            throw ApiException.conflict("RECORDING_COMPLETE", "This recording is already complete.");
        }
        boolean duplicate = storage.chunkIndices(id).contains(index);
        try {
            storage.writeChunk(id, index, body == null ? InputStream.nullInputStream() : body);
        } catch (IOException e) {
            LOG.warnf("Storing chunk %d of recording %s failed (%s)", index, id, e.getClass().getSimpleName());
            throw new ApiException(Response.Status.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "The audio chunk could not be stored. Please retry.");
        }
        return new ChunkAckDto(id, index, duplicate, storage.chunkIndices(id).size());
    }

    public RecordingDto get(UUID id) {
        return toDto(find(id));
    }

    public RecordingDto complete(UUID id, CompleteRecordingRequest request) {
        if (request == null || request.totalChunks() <= 0 || request.durationMs() < 0) {
            throw ApiException.badRequest("INVALID_REQUEST", "totalChunks must be positive.");
        }
        RecordingEntity r = find(id);
        if (r.status == RecordingStatus.AUDIO_DELETED) {
            throw ApiException.conflict("AUDIO_DELETED", "The audio of this recording was deleted.");
        }
        if (r.status == RecordingStatus.COMPLETE) {
            return toDto(r); // idempotent
        }
        int total = request.totalChunks();
        List<Integer> have = storage.chunkIndices(id);
        List<Integer> missing = IntStream.range(0, total).filter(i -> !have.contains(i)).boxed().toList();
        if (!missing.isEmpty()) {
            throw missingChunks(missing);
        }
        try {
            storage.assemble(id, total);
        } catch (IllegalStateException e) {
            List<Integer> now = storage.chunkIndices(id);
            throw missingChunks(IntStream.range(0, total).filter(i -> !now.contains(i)).boxed().toList());
        } catch (IOException e) {
            LOG.warnf("Assembling recording %s failed (%s)", id, e.getClass().getSimpleName());
            throw new ApiException(Response.Status.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "The recording could not be assembled. Please retry.");
        }
        long size = storage.audioSize(id);
        EntryDto entry = QuarkusTransaction.requiringNew().call(() -> {
            RecordingEntity e = repository.findById(id);
            e.status = RecordingStatus.COMPLETE;
            e.totalChunks = total;
            e.durationMs = request.durationMs();
            e.sizeBytes = size;
            return entries.setStatus(e.entryId, EntryStatus.RECORDED);
        });
        LOG.infof("Recording %s complete (%d chunks, %d bytes) for entry %d", id, total, size, entry.id());
        transcriptions.enqueue(entry.id(), id, entry.language() == null ? Language.AUTO : entry.language());
        return toDto(find(id));
    }

    public Response audio(UUID id) {
        RecordingEntity r = find(id);
        if (r.status != RecordingStatus.COMPLETE || !storage.hasAudio(id)) {
            throw ApiException.notFound("Audio");
        }
        StreamingOutput stream = out -> {
            try (InputStream in = storage.openAudio(id)) {
                in.transferTo(out);
            }
        };
        return Response.ok(stream, mediaType(r.mimeType))
                .header("Content-Length", storage.audioSize(id))
                .header("Cache-Control", "private, no-store")
                .build();
    }

    public RecordingDto deleteAudio(UUID id) {
        find(id);
        return toDto(removeAudio(id));
    }

    /** Observes finishing: with keepAudio=false the entry's audio is deleted; entry and transcript remain. */
    void onFinished(@Observes(during = TransactionPhase.AFTER_SUCCESS) EntryFinished event) {
        try {
            if (!settings.get().keepAudio()) {
                int n = deleteAudioOfEntry(event.entryId());
                if (n > 0) {
                    LOG.infof("Deleted audio of finished entry %d (keepAudio=false)", event.entryId());
                }
            }
        } catch (RuntimeException e) {
            LOG.warnf("Audio retention for entry %d failed (%s)", event.entryId(), e.getClass().getSimpleName());
        }
    }

    /** Removes recordings (rows and audio files) of a deleted entry. */
    void onEntryDeleted(@Observes(during = TransactionPhase.AFTER_SUCCESS) dev.rabauer.ai.diary.entry.EntryDeleted event) {
        try {
            List<UUID> ids = QuarkusTransaction.requiringNew().call(() -> {
                List<UUID> found = repository.forEntry(event.entryId()).stream().map(r -> r.id).toList();
                found.forEach(repository::deleteById);
                return found;
            });
            ids.forEach(storage::delete);
        } catch (RuntimeException e) {
            LOG.warnf("Removing recordings of deleted entry %d failed (%s)", event.entryId(), e.getClass().getSimpleName());
        }
    }

    int deleteAudioOfEntry(long entryId) {
        List<UUID> ids = QuarkusTransaction.requiringNew().call(() ->
                repository.forEntry(entryId).stream().filter(r -> r.status != RecordingStatus.AUDIO_DELETED).map(r -> r.id).toList());
        ids.forEach(this::removeAudio);
        return ids.size();
    }

    private RecordingEntity removeAudio(UUID id) {
        storage.delete(id);
        return QuarkusTransaction.requiringNew().call(() -> {
            RecordingEntity e = repository.findById(id);
            if (e != null) {
                e.status = RecordingStatus.AUDIO_DELETED;
            }
            return e;
        });
    }

    RecordingEntity find(UUID id) {
        RecordingEntity r = QuarkusTransaction.requiringNew().call(() -> repository.findById(id));
        if (r == null) {
            throw ApiException.notFound("Recording");
        }
        return r;
    }

    RecordingDto toDto(RecordingEntity r) {
        List<Integer> received = switch (r.status) {
            case RECORDING -> storage.chunkIndices(r.id);
            case COMPLETE -> r.totalChunks == null ? List.of() : IntStream.range(0, r.totalChunks).boxed().toList();
            case AUDIO_DELETED -> List.of();
        };
        return new RecordingDto(r.id, r.entryId, r.mimeType, r.status, new ArrayList<>(received), r.totalChunks, r.durationMs,
                r.sizeBytes, r.createdAt);
    }

    private static ApiException missingChunks(List<Integer> missing) {
        String list = missing.stream().limit(MAX_LISTED_MISSING).map(String::valueOf).collect(Collectors.joining(", "));
        if (missing.size() > MAX_LISTED_MISSING) {
            list += ", ... (" + missing.size() + " missing)";
        }
        return ApiException.conflict("CHUNKS_MISSING", "Missing chunks: " + list);
    }

    static String mediaType(String mime) {
        try {
            MediaType.valueOf(mime);
            return mime;
        } catch (RuntimeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    public static String extensionFor(String mime) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.startsWith("audio/webm")) return "webm";
        if (m.startsWith("audio/ogg")) return "ogg";
        if (m.startsWith("audio/mp4") || m.startsWith("audio/x-m4a")) return "m4a";
        if (m.startsWith("audio/wav") || m.startsWith("audio/x-wav") || m.startsWith("audio/wave")) return "wav";
        if (m.startsWith("audio/mpeg") || m.startsWith("audio/mp3")) return "mp3";
        if (m.startsWith("audio/flac")) return "flac";
        return "bin";
    }
}
