package dev.rabauer.ai.diary.rest.stub;

import dev.rabauer.ai.diary.api.gym.AttemptDto;
import dev.rabauer.ai.diary.api.gym.AttemptRequest;
import dev.rabauer.ai.diary.api.gym.ExerciseDto;
import dev.rabauer.ai.diary.api.gym.GymCategory;

import java.util.List;
import dev.rabauer.ai.diary.rest.GymApi;
import dev.rabauer.ai.diary.web.ApiException;

/**
 * Placeholder returning 501 until the owning worker implements {@link GymApi}. DELETE this class in the same
 * commit that adds the real implementation.
 */
public class GymApiStub implements GymApi {
    @Override
    public ExerciseDto next(GymCategory category) {
        throw ApiException.notImplemented("Thinking Gym");
    }

    @Override
    public ExerciseDto get(long id) {
        throw ApiException.notImplemented("Thinking Gym");
    }

    @Override
    public AttemptDto attempt(long id, AttemptRequest request) {
        throw ApiException.notImplemented("Thinking Gym");
    }

    @Override
    public AttemptDto feedback(long attemptId) {
        throw ApiException.notImplemented("Thinking Gym");
    }

    @Override
    public List<AttemptDto> history(GymCategory category) {
        throw ApiException.notImplemented("Thinking Gym");
    }
}
