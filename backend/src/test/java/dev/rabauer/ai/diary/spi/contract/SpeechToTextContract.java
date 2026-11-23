package dev.rabauer.ai.diary.spi.contract;

import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.spi.AudioSource;
import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.spi.SpeechToText;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every SpeechToText must pass this. HTTP implementations arrange responses with WireMock in the hooks;
 * no real network is ever used.
 */
public abstract class SpeechToTextContract {

    protected abstract SpeechToText create();

    /** Make the next transcription succeed with this text and detected language. */
    protected abstract void arrangeSuccess(String text, Language detected);

    /** Make the provider unreachable (connection refused / timeout). */
    protected abstract void arrangeUnreachable();

    /** Make the provider answer with a server error. */
    protected abstract void arrangeServerError();

    protected static AudioSource audio() {
        byte[] data = new byte[]{1, 2, 3, 4};
        return new AudioSource(() -> new ByteArrayInputStream(data), data.length, "audio/webm", "entry.webm");
    }

    @Test
    void transcribesAndReportsLanguage() {
        arrangeSuccess("Hallo Welt", Language.DE);
        var result = create().transcribe(new SpeechToText.Request(audio(), Language.AUTO));
        assertThat(result.text()).isEqualTo("Hallo Welt");
        assertThat(result.detectedLanguage()).isEqualTo(Language.DE);
    }

    @Test
    void unreachableProviderRaisesRetryableException() {
        arrangeUnreachable();
        assertThatThrownBy(() -> create().transcribe(new SpeechToText.Request(audio(), Language.EN)))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.kind()).isEqualTo(ProviderException.Kind.UNREACHABLE);
                    assertThat(e.retryable()).isTrue();
                });
    }

    @Test
    void serverErrorRaisesProviderException() {
        arrangeServerError();
        assertThatThrownBy(() -> create().transcribe(new SpeechToText.Request(audio(), Language.EN)))
                .isInstanceOf(ProviderException.class);
    }
}
