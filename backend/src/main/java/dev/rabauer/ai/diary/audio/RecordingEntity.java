package dev.rabauer.ai.diary.audio;

import dev.rabauer.ai.diary.api.audio.RecordingStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One recording of an entry. Audio bytes live in {@code AudioStorage}; this row holds the metadata. */
@Entity
@Table(name = "recording")
public class RecordingEntity extends PanacheEntityBase {

    @Id
    @Column(name = "id")
    public UUID id;

    @Column(name = "entry_id", nullable = false)
    public long entryId;

    @Column(name = "mime_type", length = 100, nullable = false)
    public String mimeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    public RecordingStatus status;

    @Column(name = "total_chunks")
    public Integer totalChunks;

    @Column(name = "duration_ms")
    public Long durationMs;

    @Column(name = "size_bytes", nullable = false)
    public long sizeBytes;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
