package dev.rabauer.ai.diary.coach;

import dev.rabauer.ai.diary.client.BackendClientConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Same-origin proxy for the coach screen (D-010): {@code POST /upload/tts} to {@code /api/tts} (spoken questions)
 * and {@code POST /upload/transcribe} to {@code /api/transcribe} (spoken answers). Bodies are streamed and never
 * logged; log lines contain only the route.
 */
@RestController
@RequestMapping("/upload")
public class CoachProxyController {

    private static final Logger LOG = LoggerFactory.getLogger(CoachProxyController.class);
    private static final Set<String> LANGUAGES = Set.of("AUTO", "DE", "EN");
    private static final List<String> REQUEST_HEADERS = List.of(HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT);
    private static final List<String> RESPONSE_HEADERS = List.of(HttpHeaders.CONTENT_TYPE, HttpHeaders.CONTENT_LENGTH);

    private final RestClient backend;

    public CoachProxyController(RestClient backendRestClient) {
        this.backend = backendRestClient;
    }

    @PostMapping("/tts")
    public void speak(HttpServletRequest request, HttpServletResponse response) throws IOException {
        forward("/api/tts", null, request, response);
    }

    @PostMapping("/transcribe")
    public void transcribe(@RequestParam(name = "language", defaultValue = "AUTO") String language,
                           HttpServletRequest request, HttpServletResponse response) throws IOException {
        String normalized = language.toUpperCase(Locale.ROOT);
        if (!LANGUAGES.contains(normalized)) {
            normalized = "AUTO";
        }
        forward("/api/transcribe", normalized, request, response);
    }

    private void forward(String path, String language, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        try {
            RestClient.RequestBodySpec spec = backend.method(HttpMethod.POST)
                    .uri(b -> {
                        b.path(path);
                        if (language != null) {
                            b.queryParam("language", language);
                        }
                        return b.build();
                    })
                    .headers(h -> {
                        h.set(BackendClientConfig.WRITE_GUARD_HEADER, "1");
                        for (String name : REQUEST_HEADERS) {
                            String value = request.getHeader(name);
                            if (value != null) {
                                h.set(name, value);
                            }
                        }
                        if (request.getContentLengthLong() >= 0) {
                            h.setContentLength(request.getContentLengthLong());
                        }
                    });
            spec.body(out -> request.getInputStream().transferTo(out))
                    .exchange((req, res) -> {
                        response.setStatus(res.getStatusCode().value());
                        for (String name : RESPONSE_HEADERS) {
                            String value = res.getHeaders().getFirst(name);
                            if (value != null) {
                                response.setHeader(name, value);
                            }
                        }
                        try (InputStream in = res.getBody()) {
                            in.transferTo(response.getOutputStream());
                        }
                        return null;
                    });
        } catch (ResourceAccessException e) {
            LOG.warn("coach proxy: backend unreachable ({})", path);
            if (!response.isCommitted()) {
                response.reset();
                response.setStatus(HttpStatus.BAD_GATEWAY.value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"code\":\"BACKEND_UNREACHABLE\",\"message\":\"The diary service is not reachable right now.\"}");
            }
        }
    }
}
