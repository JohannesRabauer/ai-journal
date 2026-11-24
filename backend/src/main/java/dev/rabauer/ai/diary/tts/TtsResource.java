package dev.rabauer.ai.diary.tts;

import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.tts.SpeakRequest;
import dev.rabauer.ai.diary.api.tts.VoiceDto;
import dev.rabauer.ai.diary.rest.TtsApi;
import dev.rabauer.ai.diary.settings.SettingsService;
import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.spi.TextToSpeech;
import dev.rabauer.ai.diary.web.ApiException;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Speech output (SPEC section 9). Any provider failure (and provider {@code none}) answers 204 so the client silently
 * shows text only. Logs carry the provider and the failure kind, never the text.
 */
public class TtsResource implements TtsApi {

    static final int MAX_TEXT_CHARS = 1000;
    private static final Logger LOG = Logger.getLogger(TtsResource.class);

    @Inject
    TextToSpeech tts;
    @Inject
    TtsCache cache;
    @Inject
    SettingsService settings;
    @ConfigProperty(name = "journal.tts.provider", defaultValue = "none")
    String providerName;

    @Override
    public Response speak(SpeakRequest request) {
        if (request == null || request.text() == null || request.text().isBlank()) {
            throw ApiException.badRequest("TEXT_REQUIRED", "Provide the text to speak.");
        }
        if (request.text().length() > MAX_TEXT_CHARS) {
            throw ApiException.badRequest("TEXT_TOO_LONG", "The text is too long to speak (maximum " + MAX_TEXT_CHARS + " characters).");
        }
        String text = request.text().strip();
        Language language = request.language() == null || request.language() == Language.AUTO
                ? LanguageGuess.of(text) : request.language();
        try {
            String voice = resolveVoice(request.voice(), language);
            String key = TtsCache.key(providerName, voice, language.code(), text);
            TextToSpeech.Audio audio = cache.get(key).orElse(null);
            if (audio == null) {
                audio = tts.synthesize(new TextToSpeech.Request(text, language, voice));
                cache.put(key, audio);
            }
            return Response.ok(audio.data(), audio.contentType()).header("X-Content-Type-Options", "nosniff").build();
        } catch (ProviderException e) {
            LOG.infof("speech output unavailable: provider=%s kind=%s", providerName, e.kind());
            return Response.noContent().build();
        }
    }

    @Override
    public List<VoiceDto> voices() {
        try {
            return tts.voices().stream().map(v -> new VoiceDto(v.id(), v.language(), v.label())).toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Explicit request voice wins. Otherwise the voice chosen in the settings for this language, but only if the active
     * provider lists it (settings may still hold a voice of another provider); else null = provider default.
     */
    private String resolveVoice(String requested, Language language) {
        if (requested != null && !requested.isBlank()) {
            return requested.strip();
        }
        var s = settings.get();
        String configured = language == Language.DE ? s.ttsVoiceDe() : s.ttsVoiceEn();
        if (configured == null || configured.isBlank()) {
            return null;
        }
        boolean known = tts.voices().stream().anyMatch(v -> v.id().equals(configured) && v.language() == language);
        return known ? configured : null;
    }
}
