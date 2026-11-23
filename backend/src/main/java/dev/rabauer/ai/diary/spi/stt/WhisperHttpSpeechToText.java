package dev.rabauer.ai.diary.spi.stt;

import dev.rabauer.ai.diary.spi.ProviderName;
import dev.rabauer.ai.diary.spi.SelfValidating;
import dev.rabauer.ai.diary.spi.SpeechToText;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/**
 * Local faster-whisper behind an OpenAI-compatible API (e.g. speaches): {@code POST {base-url}/v1/audio/transcriptions}.
 * Nothing is contacted at startup.
 */
@ApplicationScoped
@ProviderName("whisper")
@Unremovable
public class WhisperHttpSpeechToText implements SpeechToText, SelfValidating {

    static final String NAME = "whisper";

    @ConfigProperty(name = "journal.stt.whisper.base-url", defaultValue = "http://localhost:8000")
    String baseUrl = "http://localhost:8000";
    @ConfigProperty(name = "journal.stt.whisper.model", defaultValue = "Systran/faster-whisper-small")
    String model = "Systran/faster-whisper-small";
    @ConfigProperty(name = "journal.stt.whisper.timeout", defaultValue = "10m")
    Duration timeout = Duration.ofMinutes(10);

    private volatile HttpClient client;

    @Override
    public void validateConfiguration(List<String> errors) {
        String e = SttHttp.checkUrl("journal.stt.whisper.base-url", baseUrl);
        if (e != null) {
            errors.add(e);
        }
    }

    @Override
    public Result transcribe(Request request) {
        return SttHttp.post(client(), NAME, SttHttp.trimSlash(baseUrl) + "/v1/audio/transcriptions", timeout, null,
                model, "verbose_json", request);
    }

    private HttpClient client() {
        HttpClient c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    client = SttHttp.newClient(Duration.ofSeconds(10));
                }
                c = client;
            }
        }
        return c;
    }
}
