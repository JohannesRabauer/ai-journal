package dev.rabauer.ai.diary.memory;

import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.memory.CandidateDecisionRequest;
import dev.rabauer.ai.diary.api.memory.CandidateStatus;
import dev.rabauer.ai.diary.api.memory.ExtractionRequest;
import dev.rabauer.ai.diary.api.memory.LessonDto;
import dev.rabauer.ai.diary.api.memory.MemoryCandidateDto;
import dev.rabauer.ai.diary.api.memory.MemoryItemDto;
import dev.rabauer.ai.diary.api.memory.RevealDto;
import dev.rabauer.ai.diary.api.memory.ReviewAttemptRequest;
import dev.rabauer.ai.diary.api.memory.ReviewCardDto;
import dev.rabauer.ai.diary.api.memory.ReviewGradeRequest;
import dev.rabauer.ai.diary.rest.MemoryApi;
import dev.rabauer.ai.diary.settings.SettingsService;
import dev.rabauer.ai.diary.web.ApiException;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;

import java.util.List;
import java.util.Locale;

public class MemoryResource implements MemoryApi {

    @Inject
    MemoryService service;
    @Inject
    SettingsService settings;
    @Context
    HttpHeaders headers;

    @Override
    public List<MemoryCandidateDto> extract(ExtractionRequest request) {
        return service.extract(request);
    }

    @Override
    public List<MemoryCandidateDto> candidates(CandidateStatus status) {
        return service.candidates(status);
    }

    @Override
    public MemoryCandidateDto decide(long id, CandidateDecisionRequest request) {
        return service.decide(id, request);
    }

    @Override
    public List<MemoryItemDto> items() {
        return service.items();
    }

    @Override
    public void deleteItem(long id) {
        service.deleteItem(id);
    }

    @Override
    public List<ReviewCardDto> due() {
        return service.due();
    }

    @Override
    public RevealDto attempt(long itemId, ReviewAttemptRequest request) {
        return service.attempt(itemId, request);
    }

    @Override
    public MemoryItemDto grade(long itemId, ReviewGradeRequest request) {
        if (request == null) {
            throw ApiException.badRequest("INVALID_REQUEST", "quality is required.");
        }
        return service.grade(itemId, request.quality());
    }

    /** Language: Accept-Language of the request (de or en), else the UI language setting, else English. */
    @Override
    public List<LessonDto> lessons() {
        return service.lessons(isGerman());
    }

    private boolean isGerman() {
        Language fromHeader = null;
        try {
            for (Locale l : headers.getAcceptableLanguages()) {
                String lang = l.getLanguage();
                if ("de".equals(lang) || "en".equals(lang)) {
                    fromHeader = Language.fromCode(lang);
                    break;
                }
            }
        } catch (RuntimeException e) {
            // unusable header: fall back to the setting
        }
        if (fromHeader != null) {
            return fromHeader == Language.DE;
        }
        return settings.get().uiLanguage() == Language.DE;
    }
}
