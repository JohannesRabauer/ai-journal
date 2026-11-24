package dev.rabauer.ai.diary.spi.tts;

import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.spi.ProviderException.Kind;
import dev.rabauer.ai.diary.spi.TextToSpeech;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * Shared HTTP plumbing for the TTS providers. Error messages carry only status codes and kinds, never the spoken text.
 */
final class TtsHttp {

    private TtsHttp() {
    }

    static HttpClient newClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
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

    /** Sends the request and maps transport and status failures to {@link ProviderException}. */
    static HttpResponse<byte[]> send(HttpClient client, String provider, HttpRequest request) {
        HttpResponse<byte[]> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(provider, Kind.UNREACHABLE, "interrupted while waiting for the provider", e);
        } catch (IOException e) {
            throw new ProviderException(provider, Kind.UNREACHABLE, "provider not reachable (" + e.getClass().getSimpleName() + ")", e);
        }
        int status = response.statusCode();
        if (status >= 500 || status == 429) {
            // 429 (rate limit) is retryable, consistent with the other gateways (D-020)
            throw new ProviderException(provider, Kind.ERROR_RESPONSE, "provider answered HTTP " + status);
        }
        if (status >= 400) {
            throw new ProviderException(provider, Kind.REJECTED, "provider rejected the request: HTTP " + status);
        }
        if (status < 200 || status >= 300) {
            throw new ProviderException(provider, Kind.BAD_RESPONSE, "unexpected HTTP " + status);
        }
        return response;
    }

    /** Audio from a 2xx response; content type from the header when it is audio/*, else {@code fallbackType}. */
    static TextToSpeech.Audio audio(String provider, HttpResponse<byte[]> response, String fallbackType) {
        byte[] body = response.body();
        if (body == null || body.length == 0) {
            throw new ProviderException(provider, Kind.BAD_RESPONSE, "response has no audio data");
        }
        String type = response.headers().firstValue("Content-Type").map(v -> {
            int i = v.indexOf(';');
            return (i < 0 ? v : v.substring(0, i)).strip().toLowerCase(Locale.ROOT);
        }).orElse("");
        if (!type.startsWith("audio/")) {
            if (type.contains("json") || type.startsWith("text/")) {
                throw new ProviderException(provider, Kind.BAD_RESPONSE, "response is not audio (" + type + ")");
            }
            type = fallbackType;
        }
        return new TextToSpeech.Audio(body, type);
    }
}
