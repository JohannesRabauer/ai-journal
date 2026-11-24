package dev.rabauer.ai.diary.spi.tts;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.spi.ExternalProvider;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * OpenAI speech (lite-cloud only; the spoken text leaves the device). {@code POST {journal.openai.base-url}/audio/speech}.
 *
 * <p>Format choice: {@code mp3} by default (about 10x smaller than wav, which matters for the on-disk cache on a Pi, and
 * every browser plays it). {@code journal.tts.openai.format=wav} is supported; the content type follows the format
 * ({@code audio/mpeg} or {@code audio/wav}). OpenAI voices are language independent, so each is listed for DE and EN.
 */
@ApplicationScoped
@ProviderName("openai")
@Unremovable
public class OpenAiTextToSpeech implements TextToSpeech, SelfValidating, ExternalProvider {

    static final String NAME = "openai";
    private static final ObjectMapper JSON = new ObjectMapper();
    static final List<String> VOICE_IDS = List.of("alloy", "ash", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer");

    @ConfigProperty(name = "journal.openai.base-url", defaultValue = "https://api.openai.com/v1")
    String baseUrl = "https://api.openai.com/v1";
    @ConfigProperty(name = "journal.openai.api-key")
    Optional<String> apiKey = Optional.empty();
    @ConfigProperty(name = "journal.tts.openai.model", defaultValue = "gpt-4o-mini-tts")
    String model = "gpt-4o-mini-tts";
    @ConfigProperty(name = "journal.tts.openai.voice", defaultValue = "alloy")
    String voice = "alloy";
    @ConfigProperty(name = "journal.tts.openai.format", defaultValue = "mp3")
    String format = "mp3";
    @ConfigProperty(name = "journal.tts.openai.timeout", defaultValue = "30s")
    Duration timeout = Duration.ofSeconds(30);

    private volatile HttpClient client;

    @Override
    public void validateConfiguration(List<String> errors) {
        String e = TtsHttp.checkUrl("journal.openai.base-url", baseUrl);
        if (e != null) {
            errors.add(e);
        }
        String f = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        if (!f.equals("mp3") && !f.equals("wav")) {
            errors.add("journal.tts.openai.format must be mp3 or wav: " + format);
        }
    }

    @Override
    public Audio synthesize(Request request) {
        String key = apiKey.filter(k -> !k.isBlank()).orElseThrow(() ->
                new ProviderException(NAME, Kind.REJECTED, "journal.openai.api-key is not configured"));
        boolean wav = "wav".equalsIgnoreCase(format == null ? "" : format.trim());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("input", request.text());
        payload.put("voice", request.voice() != null && !request.voice().isBlank() ? request.voice() : voice);
        payload.put("response_format", wav ? "wav" : "mp3");
        String body;
        try {
            body = JSON.writeValueAsString(payload);
        } catch (IOException e) {
            throw new ProviderException(NAME, Kind.REJECTED, "request could not be encoded");
        }
        HttpRequest http = HttpRequest.newBuilder(URI.create(TtsHttp.trimSlash(baseUrl) + "/audio/speech"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key.trim())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = TtsHttp.send(client(), NAME, http);
        return TtsHttp.audio(NAME, response, wav ? "audio/wav" : "audio/mpeg");
    }

    @Override
    public List<Voice> voices() {
        List<Voice> out = new ArrayList<>();
        for (String id : VOICE_IDS) {
            out.add(new Voice(id, Language.DE, id));
            out.add(new Voice(id, Language.EN, id));
        }
        return List.copyOf(out);
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
