package dev.rabauer.ai.diary.recorder;

import dev.rabauer.ai.diary.client.BackendClientConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;

/**
 * Same-origin proxy so the browser never talks to the backend directly (D-010). Forwards the recordings API
 * ({@code /upload/recordings/**} to {@code /api/recordings/**}). Bodies are streamed in both directions and never
 * logged; only ids and status codes appear in log lines.
 */
@RestController
@RequestMapping("/upload/recordings")
public class RecordingsProxyController {

    private static final Logger LOG = LoggerFactory.getLogger(RecordingsProxyController.class);
    private static final String BACKEND = "/api/recordings";
    private static final List<String> REQUEST_HEADERS = List.of(HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT, HttpHeaders.RANGE);
    private static final List<String> RESPONSE_HEADERS = List.of(HttpHeaders.CONTENT_TYPE, HttpHeaders.CONTENT_LENGTH,
            HttpHeaders.CONTENT_RANGE, HttpHeaders.ACCEPT_RANGES, HttpHeaders.CONTENT_DISPOSITION);

    private final RestClient backend;

    public RecordingsProxyController(RestClient backendRestClient) {
        this.backend = backendRestClient;
    }

    @PostMapping
    public void start(HttpServletRequest request, HttpServletResponse response) throws IOException {
        forward(HttpMethod.POST, BACKEND, request, response);
    }

    @PutMapping("/{id}/chunks/{index}")
    public void chunk(@PathVariable UUID id, @PathVariable int index, HttpServletRequest request,
                      HttpServletResponse response) throws IOException {
        forward(HttpMethod.PUT, BACKEND + "/" + id + "/chunks/" + index, request, response);
    }

    @GetMapping("/{id}")
    public void status(@PathVariable UUID id, HttpServletRequest request, HttpServletResponse response) throws IOException {
        forward(HttpMethod.GET, BACKEND + "/" + id, request, response);
    }

    @PostMapping("/{id}/complete")
    public void complete(@PathVariable UUID id, HttpServletRequest request, HttpServletResponse response) throws IOException {
        forward(HttpMethod.POST, BACKEND + "/" + id + "/complete", request, response);
    }

    @GetMapping("/{id}/audio")
    public void audio(@PathVariable UUID id, HttpServletRequest request, HttpServletResponse response) throws IOException {
        forward(HttpMethod.GET, BACKEND + "/" + id + "/audio", request, response);
    }

    @DeleteMapping("/{id}/audio")
    public void deleteAudio(@PathVariable UUID id, HttpServletRequest request, HttpServletResponse response) throws IOException {
        forward(HttpMethod.DELETE, BACKEND + "/" + id + "/audio", request, response);
    }

    private void forward(HttpMethod method, String path, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        boolean hasBody = method == HttpMethod.POST || method == HttpMethod.PUT;
        try {
            RestClient.RequestBodySpec spec = backend.method(method).uri(path)
                    .headers(h -> {
                        h.set(BackendClientConfig.WRITE_GUARD_HEADER, "1");
                        for (String name : REQUEST_HEADERS) {
                            String value = request.getHeader(name);
                            if (value != null) {
                                h.set(name, value);
                            }
                        }
                        if (hasBody && request.getContentLengthLong() >= 0) {
                            h.setContentLength(request.getContentLengthLong());
                        }
                    });
            if (hasBody) {
                spec = spec.body(out -> copy(request.getInputStream(), out));
            }
            spec.exchange((req, res) -> {
                        response.setStatus(res.getStatusCode().value());
                        for (String name : RESPONSE_HEADERS) {
                            String value = res.getHeaders().getFirst(name);
                            if (value != null) {
                                response.setHeader(name, value);
                            }
                        }
                        try (InputStream in = res.getBody()) {
                            copy(in, response.getOutputStream());
                        }
                        return null;
                    });
        } catch (ResourceAccessException e) {
            // Backend not reachable. Never include request details (they may relate to journal audio).
            LOG.warn("recordings proxy: backend unreachable ({} {})", method, path.replaceAll("[0-9a-f-]{36}", "{id}"));
            if (!response.isCommitted()) {
                response.reset();
                response.setStatus(HttpStatus.BAD_GATEWAY.value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"code\":\"BACKEND_UNREACHABLE\",\"message\":\"The diary service is not reachable right now.\"}");
            }
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        in.transferTo(out);
    }
}
