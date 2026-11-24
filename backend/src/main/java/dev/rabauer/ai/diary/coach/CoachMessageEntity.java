package dev.rabauer.ai.diary.coach;

import dev.rabauer.ai.diary.api.coach.CoachMessageKind;
import dev.rabauer.ai.diary.api.coach.CoachRole;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "coach_message")
public class CoachMessageEntity extends PanacheEntity {

    @Column(name = "conversation_id", nullable = false)
    public long conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 8, nullable = false)
    public CoachRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 24, nullable = false)
    public CoachMessageKind kind;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    public String content;

    @Column(name = "voice", nullable = false)
    public boolean voice;

    /** Position in the visible transcript (1, 2, ...); null = planned opening question not yet shown. */
    @Column(name = "seq_no")
    public Integer seqNo;

    /** For revealed messages: when the user could first see it. */
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    public boolean revealed() {
        return seqNo != null;
    }
}
