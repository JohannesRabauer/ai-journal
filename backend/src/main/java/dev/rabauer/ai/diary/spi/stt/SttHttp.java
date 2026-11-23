package dev.rabauer.ai.diary.spi.stt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.spi.AudioSource;
import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.spi.ProviderException.Kind;
import dev.rabauer.ai.diary.spi.SpeechToText;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

/**
 * Shared plumbing for OpenAI-compatible {@code /audio/transcriptions} endpoints (multipart upload, JSON answer).
 * Error messages carry only status codes and kinds, never transcript text or audio.
 */
final class SttHttp {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SttHttp() {
    }

    static HttpClient newClient(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** URL syntax check only (no network); returns an error line or null. */
    static String checkUrl(String property, String value) {
        try {
            URI u = URI.create(value == null ? "" : value.trim());
            if (u.getHost() == null || u.getScheme() == null
                    || !(u.getScheme().equalsIgnoreCase("http") || u.getScheme().equalsIgnoreCase("https"))) {
                return property + " is not a valid http(s) URL: " + value;
            }
            return null;
        } catch (IllegalArgumentException e) {
            return property + " is not a valid URL: " + value;
        }
    }

    static String trimSlash(String s) {
        String t = s.trim();
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    /**
     * @param responseFormat {@code verbose_json} or {@code json}
     * @param bearer         API key or null
     */
    static SpeechToText.Result post(HttpClient client, String provider, String url, Duration timeout, String bearer,
                                    String model, String responseFormat, SpeechToText.Request request) {
        String boundary = "----diary" + UUID.randomUUID().toString().replace("-", "");
        AudioSource audio = request.audio();
        StringBuilder head = new StringBuilder();
        field(head, boundary, "model", model);
        field(head, boundary, "response_format", responseFormat);
        if (request.language() != null && request.language() != Language.AUTO) {
            field(head, boundary, "language", request.language().code());
        }
        head.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(safeName(audio.fileName())).append("\"\r\n")
                .append("Content-Type: ").append(baseMime(audio.mimeType())).append("\r\n\r\n");
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofByteArray(head.toString().getBytes(StandardCharsets.UTF_8)),
                HttpRequest.BodyPublishers.ofInputStream(() -> {
                    try {
                        return audio.open();
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }),
                HttpRequest.BodyPublishers.ofByteArray(tail));

        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "application/json")
                .POST(body);
        if (bearer != null && !bearer.isBlank()) {
            rb.header("Authorization", "Bearer " + bearer.trim());
        }

        HttpResponse<String> response;
        try {
            response = client.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(provider, Kind.UNREACHABLE, "interrupted while waiting for the provider", e);
        } catch (java.io.UncheckedIOException e) {
            throw new ProviderException(provider, Kind.REJECTED, "audio could not be read", e);
        } catch (IOException e) {
            throw new ProviderException(provider, Kind.UNREACHABLE, "provider not reachable (" + e.getClass().getSimpleName() + ")", e);
        }
        int status = response.statusCode();
        if (status >= 500) {
            throw new ProviderException(provider, Kind.ERROR_RESPONSE, "provider answered HTTP " + status);
        }
        if (status >= 400) {
            throw new ProviderException(provider, Kind.REJECTED, "provider rejected the request: HTTP " + status);
        }
        if (status < 200 || status >= 300) {
            throw new ProviderException(provider, Kind.BAD_RESPONSE, "unexpected HTTP " + status);
        }
        return parse(provider, response.body());
    }

    static SpeechToText.Result parse(String provider, String body) {
        JsonNode n;
        try {
            n = JSON.readTree(body);
        } catch (IOException e) {
            throw new ProviderException(provider, Kind.BAD_RESPONSE, "response is not valid JSON");
        }
        if (n == null || !n.isObject() || !n.hasNonNull("text") || !n.get("text").isTextual()) {
            throw new ProviderException(provider, Kind.BAD_RESPONSE, "response has no text field");
        }
        Duration duration = Duration.ZERO;
        if (n.hasNonNull("duration") && n.get("duration").isNumber()) {
            duration = Duration.ofMillis(Math.round(n.get("duration").asDouble() * 1000));
        }
        return new SpeechToText.Result(n.get("text").asText().strip(),
                mapLanguage(n.hasNonNull("language") ? n.get("language").asText() : null), duration);
    }

    /** Accepts ISO codes ("de", "de-DE") and Whisper language names ("german"); anything else is AUTO. */
    static Language mapLanguage(String value) {
        if (value == null) {
            return Language.AUTO;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.equals("de") || v.equals("deu") || v.equals("ger") || v.startsWith("de-") || v.startsWith("de_")
                || v.equals("german") || v.equals("deutsch")) {
            return Language.DE;
        }
        if (v.equals("en") || v.equals("eng") || v.startsWith("en-") || v.startsWith("en_") || v.equals("english")) {
            return Language.EN;
        }
        return Language.AUTO;
    }

    private static void field(StringBuilder sb, String boundary, String name, String value) {
        sb.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
                .append(value).append("\r\n");
    }

    private static String safeName(String name) {
        String n = name == null || name.isBlank() ? "audio.bin" : name;
        return n.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String baseMime(String mime) {
        if (mime == null || mime.isBlank()) {
            return "application/octet-stream";
        }
        int i = mime.indexOf(';');
        return (i < 0 ? mime : mime.substring(0, i)).strip();
    }
}
