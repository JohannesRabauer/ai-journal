package dev.rabauer.ai.diary.entry;

import dev.rabauer.ai.diary.api.common.PageDto;
import dev.rabauer.ai.diary.api.entry.CreateEntryRequest;
import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.UpdateEntryRequest;
import dev.rabauer.ai.diary.rest.EntriesApi;
import jakarta.inject.Inject;

public class EntryResource implements EntriesApi {

    @Inject
    EntryService service;

    @Override
    public EntryDto create(CreateEntryRequest request) {
        return service.create(request == null ? new CreateEntryRequest(null, null, null, null) : request);
    }

    @Override
    public PageDto<EntryDto> list(int page, int size, String q, String source, Boolean reflected) {
        return service.list(page, size, q, source, reflected);
    }

    @Override
    public EntryDto get(long id) {
        return service.get(id);
    }

    @Override
    public EntryDto update(long id, UpdateEntryRequest request) {
        return service.update(id, request);
    }

    @Override
    public EntryDto finish(long id) {
        return service.finish(id);
    }

    @Override
    public void delete(long id) {
        service.delete(id);
    }
}
