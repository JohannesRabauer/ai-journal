package dev.rabauer.ai.diary.entry;

import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.common.PageDto;
import dev.rabauer.ai.diary.api.entry.CreateEntryRequest;
import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.EntrySource;
import dev.rabauer.ai.diary.api.entry.EntryStatus;
import dev.rabauer.ai.diary.api.entry.UpdateEntryRequest;
import dev.rabauer.ai.diary.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.LongPredicate;

/**
 * Save-first entry handling (SPEC section 4). Nothing in here calls an LLM; finishing only fires
 * {@link EntryFinished} for background indexing.
 */
@ApplicationScoped
public class EntryService {

    public static final int MAX_PAGE_SIZE = 100;

    @Inject
    EntryRepository repository;
    @Inject
    Event<EntryFinished> finished;
    @Inject
    Clock clock;

    /** Features with per-entry data (e.g. coach) can register here to fill {@code hasCoachConversation}. */
    @Inject
    jakarta.enterprise.inject.Instance<EntryConversationIndicator> conversationIndicators;

    @Transactional
    public EntryDto create(CreateEntryRequest request) {
        Instant now = now();
        DiaryEntryEntity e = new DiaryEntryEntity();
        e.entryText = request.text() == null ? "" : request.text();
        e.timestamp = (request.entryTimestamp() != null ? request.entryTimestamp() : LocalDateTime.now(clock)).truncatedTo(ChronoUnit.MICROS);
        e.source = request.source() != null ? request.source() : EntrySource.TEXT;
        e.status = EntryStatus.DRAFT;
        e.language = toCode(request.language());
        e.createdAt = now;
        e.updatedAt = now;
        repository.persist(e);
        return toDto(e);
    }

    @Transactional
    public EntryDto update(long id, UpdateEntryRequest request) {
        DiaryEntryEntity e = find(id);
        if (request.text() != null) {
            e.entryText = request.text();
        }
        if (request.language() != null) {
            e.language = toCode(request.language());
        }
        e.updatedAt = now();
        return toDto(e);
    }

    /** Status transitions used by recording/transcription (W1a). Never FINISHED; use {@link #finish}. */
    @Transactional
    public EntryDto setStatus(long id, EntryStatus status) {
        if (status == EntryStatus.FINISHED) {
            throw new IllegalArgumentException("use finish()");
        }
        DiaryEntryEntity e = find(id);
        e.status = status;
        e.updatedAt = now();
        return toDto(e);
    }

    @Transactional
    public EntryDto finish(long id) {
        DiaryEntryEntity e = find(id);
        if (e.status == EntryStatus.RECORDING || e.status == EntryStatus.TRANSCRIBING) {
            throw ApiException.conflict("ENTRY_BUSY", "The entry is still being recorded or transcribed.");
        }
        if (e.status != EntryStatus.FINISHED) {
            e.status = EntryStatus.FINISHED;
            e.finishedAt = now();
            e.updatedAt = e.finishedAt;
        }
        finished.fire(new EntryFinished(id));
        return toDto(e);
    }

    @Transactional
    public EntryDto get(long id) {
        return toDto(find(id));
    }

    @Transactional
    public PageDto<EntryDto> list(int page, int size) {
        int p = Math.max(0, page);
        int s = size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);
        List<EntryDto> items = repository.newestFirst(p, s).stream().map(this::toDto).toList();
        return new PageDto<>(items, p, s, repository.count());
    }

    @Inject
    EntryIndexState indexState;
    @Inject
    dev.rabauer.ai.diary.spi.VectorStore vectors;

    /** Deletes the entry with its derived data (vectors, index state). */
    @Transactional
    public void delete(long id) {
        if (!repository.deleteById(id)) {
            throw ApiException.notFound("Entry");
        }
        indexState.forget(id);
        vectors.delete(EntryIndexer.NAMESPACE, Long.toString(id));
    }

    DiaryEntryEntity find(long id) {
        DiaryEntryEntity e = repository.findById(id);
        if (e == null) {
            throw ApiException.notFound("Entry");
        }
        return e;
    }

    EntryDto toDto(DiaryEntryEntity e) {
        LongPredicate hasConversation = id -> conversationIndicators.stream().anyMatch(i -> i.hasConversation(id));
        return new EntryDto(
                e.id,
                e.timestamp,
                e.status,
                e.source,
                e.language == null ? null : Language.fromCode(e.language),
                e.entryText,
                e.aiContent,
                recordingIdOf(e.id),
                hasConversation.test(e.id),
                e.createdAt,
                e.updatedAt,
                e.finishedAt);
    }

    @Inject
    jakarta.enterprise.inject.Instance<EntryRecordingLookup> recordingLookups;

    private java.util.UUID recordingIdOf(long entryId) {
        return recordingLookups.stream().map(l -> l.recordingIdOf(entryId)).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    /** Microsecond precision so values round-trip unchanged through the database. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static String toCode(Language l) {
        return l == null || l == Language.AUTO ? null : l.code();
    }
}
