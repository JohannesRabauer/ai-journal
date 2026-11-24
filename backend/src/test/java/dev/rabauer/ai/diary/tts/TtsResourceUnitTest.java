package dev.rabauer.ai.diary.tts;

import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.tts.SpeakRequest;
import dev.rabauer.ai.diary.settings.SettingsService;
import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.spi.TextToSpeech;
import dev.rabauer.ai.diary.spi.tts.NoTextToSpeech;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Resource logic without Quarkus: provider none, and voice selection from settings. */
class TtsResourceUnitTest {

    @TempDir
    Path dir;

    private TtsResource resource(TextToSpeech tts, String provider) {
        TtsResource r = new TtsResource();
        r.tts = tts;
        r.cache = new TtsCache(dir, 10);
        r.providerName = provider;
        r.settings = new SettingsService() {
            @Override
            public dev.rabauer.ai.diary.api.settings.SettingsDto get() {
                var d = SettingsService.DEFAULTS;
                return new dev.rabauer.ai.diary.api.settings.SettingsDto(d.uiLanguage(), d.defaultEntryLanguage(), d.keepAudio(),
                        d.ttsAutoPlay(), "voice-de", "stale-other-provider-voice", d.reviewIntervalsDays());
            }
        };
        return r;
    }

    @Test
    void providerNoneAnswersNoContent() {
        var r = resource(new NoTextToSpeech(), "none");
        assertThat(r.speak(new SpeakRequest("Hallo?", Language.DE, null)).getStatus()).isEqualTo(204);
        assertThat(r.voices()).isEmpty();
    }

    @Test
    void settingsVoiceIsUsedOnlyWhenProviderListsIt() {
        var seen = new java.util.ArrayList<String>();
        TextToSpeech tts = new TextToSpeech() {
            @Override
            public Audio synthesize(Request request) {
                seen.add(request.voice());
                return new Audio(new byte[]{1}, "audio/wav");
            }

            @Override
            public List<Voice> voices() {
                return List.of(new Voice("voice-de", Language.DE, "x"), new Voice("voice-en", Language.EN, "x"));
            }
        };
        var r = resource(tts, "t");
        r.speak(new SpeakRequest("Was war heute wichtig?", Language.DE, null));
        r.speak(new SpeakRequest("What mattered?", Language.EN, null));
        r.speak(new SpeakRequest("Explicit", Language.EN, "custom"));
        assertThat(seen).containsExactly("voice-de", null, "custom");
    }

    @Test
    void unexpectedProviderKindsAllFallBackSilently() {
        for (var kind : ProviderException.Kind.values()) {
            TextToSpeech failing = new TextToSpeech() {
                @Override
                public Audio synthesize(Request request) {
                    throw new ProviderException("t", kind, "x");
                }

                @Override
                public List<Voice> voices() {
                    return List.of();
                }
            };
            assertThat(resource(failing, "t").speak(new SpeakRequest("Hi?", Language.EN, null)).getStatus()).isEqualTo(204);
        }
    }
}
