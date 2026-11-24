package dev.rabauer.ai.diary.tts;

import dev.rabauer.ai.diary.api.common.Language;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageGuessTest {

    @Test
    void guessesGermanAndDefaultsToEnglish() {
        assertThat(LanguageGuess.of("Was war heute wichtig für dich?")).isEqualTo(Language.DE);
        assertThat(LanguageGuess.of("Wörter")).isEqualTo(Language.DE);
        assertThat(LanguageGuess.of("What mattered to you today?")).isEqualTo(Language.EN);
        assertThat(LanguageGuess.of("???")).isEqualTo(Language.EN);
    }
}
