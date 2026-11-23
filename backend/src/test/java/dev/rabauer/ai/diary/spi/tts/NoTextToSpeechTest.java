package dev.rabauer.ai.diary.spi.tts;

import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.spi.TextToSpeech;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NoTextToSpeechTest {

    @Test
    void neverProducesAudioAndIsNotRetryable() {
        var tts = new NoTextToSpeech();
        assertThat(tts.voices()).isEmpty();
        assertThatThrownBy(() -> tts.synthesize(new TextToSpeech.Request("Hi?", Language.EN, null)))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.retryable()).isFalse());
    }
}
