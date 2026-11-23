package dev.rabauer.ai.diary.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rabauer.ai.diary.api.privacy.DeleteAllRequest;
import dev.rabauer.ai.diary.api.privacy.DeleteAllResultDto;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;

/** Typed client for {@code /api/data} (backend PrivacyApi): export and delete-everything. */
@Component
public class PrivacyClient {

    private static final MediaType ZIP = MediaType.parseMediaType("application/zip");

    private final RestClient client;
    private final ObjectMapper mapper;

    public PrivacyClient(RestClient backendRestClient, ObjectMapper mapper) {
        this.client = backendRestClient;
        this.mapper = mapper;
    }

    /** Streams the export zip into {@code out}. Throws {@link BackendException} (e.g. 501 while not implemented). */
    public void export(boolean includeAudio, OutputStream out) {
        client.get().uri(b -> b.path("/api/data/export").queryParam("includeAudio", includeAudio).build())
                .accept(ZIP)
                .exchange((request, response) -> {
                    if (response.getStatusCode().isError()) {
                        throw BackendErrors.from(mapper, response);
                    }
                    try {
                        response.getBody().transferTo(out);
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                    return null;
                });
    }

    public DeleteAllResultDto deleteAll(DeleteAllRequest request) {
        return client.post().uri("/api/data/delete-all").contentType(MediaType.APPLICATION_JSON).body(request)
                .retrieve().onStatus(s -> s.isError(), (req, res) -> BackendErrors.fail(mapper, req, res))
                .body(DeleteAllResultDto.class);
    }

    public static boolean isNotImplemented(RuntimeException e) {
        return e instanceof BackendException be && be.status() == 501;
    }
}
