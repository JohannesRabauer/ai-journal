package dev.rabauer.ai.diary.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rabauer.ai.diary.api.common.ErrorDto;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/** Maps an error response to a {@link BackendException}; shared by the shell/settings clients. */
final class BackendErrors {

    private BackendErrors() {
    }

    static BackendException from(ObjectMapper mapper, ClientHttpResponse response) throws IOException {
        ErrorDto error;
        try {
            error = mapper.readValue(response.getBody(), ErrorDto.class);
            if (error == null || error.message() == null) {
                throw new IOException("no error body");
            }
        } catch (IOException e) {
            error = new ErrorDto("HTTP_" + response.getStatusCode().value(), "The diary service is not reachable right now.");
        }
        return new BackendException(response.getStatusCode().value(), error);
    }

    static void fail(ObjectMapper mapper, HttpRequest request, ClientHttpResponse response) throws IOException {
        throw from(mapper, response);
    }
}
