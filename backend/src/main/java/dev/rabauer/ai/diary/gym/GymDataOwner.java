package dev.rabauer.ai.diary.gym;

import dev.rabauer.ai.diary.api.gym.AttemptDto;
import dev.rabauer.ai.diary.api.gym.ExerciseDto;
import dev.rabauer.ai.diary.privacy.ExportSink;
import dev.rabauer.ai.diary.privacy.UserDataOwner;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** Export ({@code gym/exercises.json}, {@code gym/attempts.json}) and delete-all for the Thinking Gym. */
@ApplicationScoped
public class GymDataOwner implements UserDataOwner {

    @Inject
    GymExerciseRepository exercises;
    @Inject
    GymAttemptRepository attempts;
    @Inject
    GymService service;

    @Override
    public String name() {
        return "gym";
    }

    @Override
    public int deleteOrder() {
        return 400;
    }

    @Override
    public long deleteAll() {
        return QuarkusTransaction.requiringNew().call(() -> attempts.deleteAll() + exercises.deleteAll());
    }

    @Override
    public void export(ExportSink sink) throws IOException {
        List<ExportedExercise> ex = QuarkusTransaction.requiringNew().call(() -> exercises.listAll().stream()
                .sorted(Comparator.comparingLong(e -> e.id))
                .map(e -> new ExportedExercise(service.toDto(e), e.templateId, e.createdAt)).toList());
        List<AttemptDto> at = QuarkusTransaction.requiringNew().call(() -> attempts.listAll().stream()
                .sorted(Comparator.comparingLong(a -> a.id)).map(service::toDto).toList());
        sink.json("gym/exercises.json", ex);
        sink.json("gym/attempts.json", at);
    }

    /** @param templateId library id for template exercises, null when personalized */
    record ExportedExercise(ExerciseDto exercise, String templateId, Instant createdAt) {
    }
}
