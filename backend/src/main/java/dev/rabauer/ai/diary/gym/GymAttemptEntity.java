package dev.rabauer.ai.diary.gym;

import dev.rabauer.ai.diary.api.gym.GymCategory;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "gym_attempt")
public class GymAttemptEntity extends PanacheEntity {

    @Column(name = "exercise_id", nullable = false)
    public long exerciseId;

    /** Copied from the exercise so history can be filtered without a join. */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 32, nullable = false)
    public GymCategory category;

    @Column(name = "answer", columnDefinition = "TEXT", nullable = false)
    public String answer;

    @Column(name = "duration_seconds", nullable = false)
    public int durationSeconds;

    /** JSON array of strings, null until the user asked for follow-up questions. */
    @Column(name = "feedback_questions", columnDefinition = "TEXT")
    public String feedbackQuestions;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
