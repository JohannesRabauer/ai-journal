package dev.rabauer.ai.diary.spi.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.spi.ProviderException.Kind;
import dev.rabauer.ai.diary.spi.ProviderName;
import dev.rabauer.ai.diary.spi.SelfValidating;
import dev.rabauer.ai.diary.spi.TextToSpeech;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Local speech output through the Piper service in {@code piper/} (docs/CONFIG.md): {@code POST {base}/synthesize}
 * with {@code {text, voice}} returns {@code audio/wav}; {@code GET {base}/voices} lists installed voices.
 * Nothing leaves the device.
 */
@ApplicationScoped
@ProviderName("piper")
@Unremovable
public class PiperTextToSpeech implements TextToSpeech, SelfValidating {

    static final String NAME = "piper";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long VOICES_TTL_MS = 60_000;

    @ConfigProperty(name = "journal.tts.piper.base-url", defaultValue = "http://localhost:5000")
    String baseUrl = "http://localhost:5000";
    @ConfigProperty(name = "journal.tts.piper.timeout", defaultValue = "30s")
    Duration timeout = Duration.ofSeconds(30);
    @ConfigProperty(name = "journal.tts.piper.voice-de", defaultValue = "de_DE-thorsten-medium")
    String voiceDe = "de_DE-thorsten-medium";
    @ConfigProperty(name = "journal.tts.piper.voice-en", defaultValue = "en_US-lessac-medium")
    String voiceEn = "en_US-lessac-medium";

    private volatile HttpClient client;
    private volatile List<Voice> cachedVoices;
    private volatile long cachedAt;

    @Override
    public void validateConfiguration(List<String> errors) {
        String e = TtsHttp.checkUrl("journal.tts.piper.base-url", baseUrl);
        if (e != null) {
            errors.add(e);
        }
    }

    @Override
    public Audio synthesize(Request request) {
        String voice = request.voice() != null && !request.voice().isBlank() ? request.voice() : defaultVoice(request.language());
        String body;
        try {
            body = JSON.writeValueAsString(java.util.Map.of("text", request.text(), "voice", voice));
        } catch (IOException e) {
            throw new ProviderException(NAME, Kind.REJECTED, "request could not be encoded");
        }
        HttpRequest http = HttpRequest.newBuilder(URI.create(TtsHttp.trimSlash(baseUrl) + "/synthesize"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "audio/wav")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = TtsHttp.send(client(), NAME, http);
        return TtsHttp.audio(NAME, response, "audio/wav");
    }

    /** Installed voices mapped to a language by their {@code de_}/{@code en_} prefix; empty on any failure. */
    @Override
    public List<Voice> voices() {
        List<Voice> cached = cachedVoices;
        if (cached != null && System.currentTimeMillis() - cachedAt < VOICES_TTL_MS) {
            return cached;
        }
        try {
            HttpRequest http = HttpRequest.newBuilder(URI.create(TtsHttp.trimSlash(baseUrl) + "/voices"))
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<byte[]> response = TtsHttp.send(client(), NAME, http);
            List<Voice> parsed = parseVoices(new String(response.body(), StandardCharsets.UTF_8));
            cachedVoices = parsed;
            cachedAt = System.currentTimeMillis();
            return parsed;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    static List<Voice> parseVoices(String json) {
        List<Voice> out = new ArrayList<>();
        try {
            JsonNode n = JSON.readTree(json);
            JsonNode arr = n == null ? null : n.get("voices");
            if (arr == null || !arr.isArray()) {
                return List.of();
            }
            for (JsonNode v : arr) {
                if (!v.isTextual()) {
                    continue;
                }
                String id = v.asText();
                String lower = id.toLowerCase(Locale.ROOT);
                Language lang = lower.startsWith("de_") ? Language.DE : lower.startsWith("en_") ? Language.EN : null;
                if (lang != null) {
                    out.add(new Voice(id, lang, id));
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        return List.copyOf(out);
    }

    String defaultVoice(Language language) {
        return language == Language.DE ? voiceDe : voiceEn;
    }

    private HttpClient client() {
        HttpClient c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    client = TtsHttp.newClient();
                }
                c = client;
            }
        }
        return c;
    }
}
