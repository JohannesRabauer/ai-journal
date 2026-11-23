package dev.rabauer.ai.diary.rest.stub;

import dev.rabauer.ai.diary.api.memory.CandidateDecisionRequest;
import dev.rabauer.ai.diary.api.memory.CandidateStatus;
import dev.rabauer.ai.diary.api.memory.ExtractionRequest;
import dev.rabauer.ai.diary.api.memory.LessonDto;
import dev.rabauer.ai.diary.api.memory.MemoryCandidateDto;
import dev.rabauer.ai.diary.api.memory.MemoryItemDto;
import dev.rabauer.ai.diary.api.memory.ReviewAttemptRequest;
import dev.rabauer.ai.diary.api.memory.ReviewCardDto;
import dev.rabauer.ai.diary.api.memory.ReviewGradeRequest;
import dev.rabauer.ai.diary.api.memory.RevealDto;

import java.util.List;
import dev.rabauer.ai.diary.rest.MemoryApi;
import dev.rabauer.ai.diary.web.ApiException;

/**
 * Placeholder returning 501 until the owning worker implements {@link MemoryApi}. DELETE this class in the same
 * commit that adds the real implementation.
 */
public class MemoryApiStub implements MemoryApi {
    @Override
    public List<MemoryCandidateDto> extract(ExtractionRequest request) {
        throw ApiException.notImplemented("Memory");
    }

    @Override
    public List<MemoryCandidateDto> candidates(CandidateStatus status) {
        throw ApiException.notImplemented("Memory");
    }

    @Override
    public MemoryCandidateDto decide(long id, CandidateDecisionRequest request) {
        throw ApiException.notImplemented("Memory");
    }

    @Override
    public List<MemoryItemDto> items() {
        throw ApiException.notImplemented("Memory");
    }

    @Override
    public void deleteItem(long id) {
        throw ApiException.notImplemented("Memory");
    }

    @Override
    public List<ReviewCardDto> due() {
        throw ApiException.notImplemented("Memory");
    }

    @Override
    public RevealDto attempt(long itemId, ReviewAttemptRequest request) {
        throw ApiException.notImplemented("Memory");
    }

    @Override
    public MemoryItemDto grade(long itemId, ReviewGradeRequest request) {
        throw ApiException.notImplemented("Memory");
    }

    @Override
    public List<LessonDto> lessons() {
        throw ApiException.notImplemented("Memory");
    }
}
