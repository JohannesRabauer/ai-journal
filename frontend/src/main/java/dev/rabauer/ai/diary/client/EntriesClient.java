package dev.rabauer.ai.diary.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rabauer.ai.diary.api.common.ErrorDto;
import dev.rabauer.ai.diary.api.common.PageDto;
import dev.rabauer.ai.diary.api.entry.CreateEntryRequest;
import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.UpdateEntryRequest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;

/** Typed client for {@code /api/entries} (backend EntriesApi). */
@Component
public class EntriesClient {

    private static final ParameterizedTypeReference<PageDto<EntryDto>> PAGE = new ParameterizedTypeReference<>() {
    };

    private final RestClient client;
    private final ObjectMapper mapper;

    public EntriesClient(RestClient backendRestClient, ObjectMapper mapper) {
        this.client = backendRestClient;
        this.mapper = mapper;
    }

    public EntryDto create(CreateEntryRequest request) {
        return client.post().uri("/api/entries").contentType(MediaType.APPLICATION_JSON).body(request)
                .retrieve().onStatus(s -> s.isError(), this::fail).body(EntryDto.class);
    }

    public EntryDto update(long id, UpdateEntryRequest request) {
        return client.put().uri("/api/entries/{id}", id).contentType(MediaType.APPLICATION_JSON).body(request)
                .retrieve().onStatus(s -> s.isError(), this::fail).body(EntryDto.class);
    }

    public EntryDto finish(long id) {
        return client.post().uri("/api/entries/{id}/finish", id)
                .retrieve().onStatus(s -> s.isError(), this::fail).body(EntryDto.class);
    }

    public EntryDto get(long id) {
        return client.get().uri("/api/entries/{id}", id)
                .retrieve().onStatus(s -> s.isError(), this::fail).body(EntryDto.class);
    }

    /** Newest first. */
    public PageDto<EntryDto> list(int page, int size) {
        return client.get().uri(b -> b.path("/api/entries").queryParam("page", page).queryParam("size", size).build())
                .retrieve().onStatus(s -> s.isError(), this::fail).body(PAGE);
    }

    public void delete(long id) {
        client.delete().uri("/api/entries/{id}", id).retrieve().onStatus(s -> s.isError(), this::fail).toBodilessEntity();
    }

    private void fail(org.springframework.http.HttpRequest request, org.springframework.http.client.ClientHttpResponse response) throws IOException {
        ErrorDto error;
        try {
            error = mapper.readValue(response.getBody(), ErrorDto.class);
        } catch (IOException e) {
            error = new ErrorDto("HTTP_" + response.getStatusCode().value(), "The diary service is not reachable right now.");
        }
        throw new BackendException(response.getStatusCode().value(), error);
    }
}
