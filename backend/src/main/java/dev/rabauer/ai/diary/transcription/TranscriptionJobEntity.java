package dev.rabauer.ai.diary.transcription;

import dev.rabauer.ai.diary.api.transcription.TranscriptionStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transcription_job")
public class TranscriptionJobEntity extends PanacheEntity {

    @Column(name = "entry_id", nullable = false)
    public long entryId;

    @Column(name = "recording_id", nullable = false)
    public UUID recordingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    public TranscriptionStatus status;

    /** de, en or auto. */
    @Column(name = "requested_language", length = 8, nullable = false)
    public String requestedLanguage;

    @Column(name = "detected_language", length = 8)
    public String detectedLanguage;

    @Column(name = "attempts", nullable = false)
    public int attempts;

    @Column(name = "error_code", length = 40)
    public String errorCode;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "finished_at")
    public Instant finishedAt;
}
