package dev.rabauer.ai.diary.spi.stt;

import dev.rabauer.ai.diary.spi.ExternalProvider;
import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.spi.ProviderName;
import dev.rabauer.ai.diary.spi.SelfValidating;
import dev.rabauer.ai.diary.spi.SpeechToText;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * OpenAI transcription (lite-cloud only; audio leaves the device). {@code POST {journal.openai.base-url}/audio/transcriptions}.
 *
 * <p>Format choice: {@code gpt-4o-*-transcribe} models only support {@code json} (text only, no language, no duration),
 * so the detected language is reported as AUTO. {@code whisper-1} supports {@code verbose_json} and therefore reports
 * the detected language and duration. The format is chosen from the model name; the default is
 * {@code gpt-4o-mini-transcribe}.
 */
@ApplicationScoped
@ProviderName("openai")
@Unremovable
public class OpenAiSpeechToText implements SpeechToText, SelfValidating, ExternalProvider {

    static final String NAME = "openai";

    @ConfigProperty(name = "journal.openai.base-url", defaultValue = "https://api.openai.com/v1")
    String baseUrl = "https://api.openai.com/v1";
    @ConfigProperty(name = "journal.openai.api-key")
    Optional<String> apiKey = Optional.empty();
    @ConfigProperty(name = "journal.stt.openai.model", defaultValue = "gpt-4o-mini-transcribe")
    String model = "gpt-4o-mini-transcribe";
    @ConfigProperty(name = "journal.stt.openai.timeout", defaultValue = "5m")
    Duration timeout = Duration.ofMinutes(5);

    private volatile HttpClient client;

    @Override
    public void validateConfiguration(List<String> errors) {
        String e = SttHttp.checkUrl("journal.openai.base-url", baseUrl);
        if (e != null) {
            errors.add(e);
        }
    }

    static boolean supportsVerboseJson(String model) {
        return model != null && model.startsWith("whisper");
    }

    @Override
    public Result transcribe(Request request) {
        String key = apiKey.filter(k -> !k.isBlank()).orElseThrow(() ->
                new ProviderException(NAME, ProviderException.Kind.REJECTED, "journal.openai.api-key is not configured"));
        return SttHttp.post(client(), NAME, SttHttp.trimSlash(baseUrl) + "/audio/transcriptions", timeout, key,
                model, supportsVerboseJson(model) ? "verbose_json" : "json", request);
    }

    private HttpClient client() {
        HttpClient c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    client = SttHttp.newClient(Duration.ofSeconds(15));
                }
                c = client;
            }
        }
        return c;
    }
}
