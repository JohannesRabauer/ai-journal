package dev.rabauer.ai.diary.spi.stt;

import dev.rabauer.ai.diary.api.common.Language;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SttLanguageMappingTest {

    @Test
    void germanVariants() {
        for (String v : List.of("de", "DE", "german", "German", " german ", "de-DE", "de_AT", "deu")) {
            assertThat(SttHttp.mapLanguage(v)).as(v).isEqualTo(Language.DE);
        }
    }

    @Test
    void englishVariants() {
        for (String v : List.of("en", "english", "English", "en-US", "eng")) {
            assertThat(SttHttp.mapLanguage(v)).as(v).isEqualTo(Language.EN);
        }
    }

    @Test
    void everythingElseIsAuto() {
        assertThat(SttHttp.mapLanguage(null)).isEqualTo(Language.AUTO);
        for (String v : List.of("", "  ", "french", "fr", "denglish")) {
            assertThat(SttHttp.mapLanguage(v)).as(v).isEqualTo(Language.AUTO);
        }
    }

    @Test
    void urlCheck() {
        assertThat(SttHttp.checkUrl("p", "http://localhost:8000")).isNull();
        assertThat(SttHttp.checkUrl("p", "https://api.openai.com/v1")).isNull();
        assertThat(SttHttp.checkUrl("p", "localhost:8000")).isNotNull();
        assertThat(SttHttp.checkUrl("p", "ftp://x")).isNotNull();
        assertThat(SttHttp.checkUrl("p", "")).isNotNull();
        assertThat(SttHttp.checkUrl("p", null)).isNotNull();
    }
}
