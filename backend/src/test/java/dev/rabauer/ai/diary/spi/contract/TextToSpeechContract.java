package dev.rabauer.ai.diary.spi.contract;

import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.spi.TextToSpeech;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public abstract class TextToSpeechContract {

    protected abstract TextToSpeech create();

    protected abstract void arrangeAudio(byte[] audio, String contentType);

    protected abstract void arrangeUnreachable();

    @Test
    void synthesizesAudio() {
        arrangeAudio(new byte[]{82, 73, 70, 70}, "audio/wav");
        var audio = create().synthesize(new TextToSpeech.Request("Was war heute wichtig?", Language.DE, null));
        assertThat(audio.data()).isNotEmpty();
        assertThat(audio.contentType()).startsWith("audio/");
    }

    @Test
    void listsVoicesWithLanguage() {
        assertThat(create().voices()).allSatisfy(v -> assertThat(v.language()).isIn(Language.DE, Language.EN));
    }

    @Test
    void unreachableProviderRaisesProviderException() {
        arrangeUnreachable();
        assertThatThrownBy(() -> create().synthesize(new TextToSpeech.Request("Hi?", Language.EN, null)))
                .isInstanceOf(ProviderException.class);
    }
}
