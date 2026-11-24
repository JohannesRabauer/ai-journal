package dev.rabauer.ai.diary.gym;

import dev.rabauer.ai.diary.api.gym.AttemptDto;
import dev.rabauer.ai.diary.api.gym.AttemptRequest;
import dev.rabauer.ai.diary.api.gym.ExerciseDto;
import dev.rabauer.ai.diary.api.gym.GymCategory;
import dev.rabauer.ai.diary.rest.GymApi;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.HeaderParam;

import java.util.List;

@RequestScoped
public class GymResource implements GymApi {

    @Inject
    GymService service;

    /** The pages send the UI language so that template exercises match it. */
    @HeaderParam("Accept-Language")
    String acceptLanguage;

    @Override
    public ExerciseDto next(GymCategory category) {
        return service.next(category, acceptLanguage);
    }

    @Override
    public ExerciseDto get(long id) {
        return service.get(id);
    }

    @Override
    public AttemptDto attempt(long id, AttemptRequest request) {
        return service.attempt(id, request);
    }

    @Override
    public AttemptDto feedback(long attemptId) {
        return service.feedback(attemptId);
    }

    @Override
    public List<AttemptDto> history(GymCategory category) {
        return service.history(category);
    }
}
