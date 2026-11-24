package dev.rabauer.ai.diary.coach;

import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.transcription.TranscribeResultDto;
import dev.rabauer.ai.diary.rest.TranscribeApi;
import dev.rabauer.ai.diary.spi.AudioSource;
import dev.rabauer.ai.diary.spi.SpeechToText;
import dev.rabauer.ai.diary.web.ApiException;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Transcribe-only endpoint for spoken coach answers. Reads at most {@code journal.coach.transcribe.max-bytes}
 * (default 10 MB) into memory, calls the configured {@link SpeechToText} synchronously, persists nothing and logs
 * nothing about the content. {@code ProviderException} propagates to the 503 mapper.
 */
public class TranscribeResource implements TranscribeApi {

    @Inject
    SpeechToText speechToText;

    @ConfigProperty(name = "journal.coach.transcribe.max-bytes", defaultValue = "10485760")
    long maxBytes;

    @Override
    public TranscribeResultDto transcribe(Language language, String contentType, InputStream audio) {
        byte[] data;
        try {
            data = audio.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
        } catch (IOException e) {
            throw ApiException.badRequest("AUDIO_UNREADABLE", "The audio could not be read.");
        }
        if (data.length == 0) {
            throw ApiException.badRequest("AUDIO_EMPTY", "No audio received.");
        }
        if (data.length > maxBytes) {
            throw new ApiException(Response.Status.REQUEST_ENTITY_TOO_LARGE, "AUDIO_TOO_LARGE",
                    "The audio is too large. Please keep spoken answers short or type instead.");
        }
        String mime = mimeOf(contentType);
        AudioSource source = new AudioSource(() -> new ByteArrayInputStream(data), data.length, mime, "answer." + extensionFor(mime));
        SpeechToText.Result result = speechToText.transcribe(new SpeechToText.Request(source, language == null ? Language.AUTO : language));
        return new TranscribeResultDto(result.text() == null ? "" : result.text(),
                result.detectedLanguage() == null ? Language.AUTO : result.detectedLanguage());
    }

    static String mimeOf(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "application/octet-stream";
        }
        int semi = contentType.indexOf(';');
        return (semi >= 0 ? contentType.substring(0, semi) : contentType).strip().toLowerCase(Locale.ROOT);
    }

    static String extensionFor(String mime) {
        return switch (mime) {
            case "audio/ogg" -> "ogg";
            case "audio/mp4" -> "mp4";
            case "audio/wav", "audio/x-wav" -> "wav";
            default -> "webm";
        };
    }
}
