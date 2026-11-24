package dev.rabauer.ai.diary.coach;

import dev.rabauer.ai.diary.api.coach.ConversationStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "coach_conversation")
public class CoachConversationEntity extends PanacheEntity {

    @Column(name = "entry_id", nullable = false)
    public long entryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    public ConversationStatus status;

    /** de or en (never auto: the coach always knows its language). */
    @Column(name = "language", length = 8, nullable = false)
    public String language;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @Column(name = "ended_at")
    public Instant endedAt;
}
