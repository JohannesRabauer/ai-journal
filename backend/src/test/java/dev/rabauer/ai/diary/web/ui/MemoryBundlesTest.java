package dev.rabauer.ai.diary.web.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** The memory bundles stay complete in both languages and free of score/streak vocabulary. */
class MemoryBundlesTest {

    private static Properties load(String lang) throws IOException {
        Properties p = new Properties();
        try (var in = MemoryBundlesTest.class.getResourceAsStream("/i18n/memory_" + lang + ".properties")) {
            assertThat(in).isNotNull();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    void bothLanguagesHaveTheSameKeys() throws IOException {
        assertThat(load("de").stringPropertyNames()).isEqualTo(load("en").stringPropertyNames());
    }

    @Test
    void everyGradeAndTypeUsedByTheViewHasAText() throws IOException {
        for (String lang : new String[] {"de", "en"}) {
            Properties p = load(lang);
            for (MemoryPresenter.Grade g : MemoryPresenter.Grade.values()) {
                assertThat(p.getProperty(g.key())).as(lang + " " + g.key()).isNotBlank();
            }
            for (var t : dev.rabauer.ai.diary.api.memory.MemoryItemType.values()) {
                assertThat(p.getProperty(MemoryPresenter.typeKey(t))).as(lang + " " + t).isNotBlank();
            }
        }
    }

    @Test
    void noStreaksScoresOrPercentages() throws IOException {
        Pattern banned = Pattern.compile("streak|score|punkte|serie|level|%|prozent|percent", Pattern.CASE_INSENSITIVE);
        for (String lang : new String[] {"de", "en"}) {
            load(lang).forEach((k, v) -> assertThat(banned.matcher(v.toString().toLowerCase(Locale.ROOT)).find())
                    .as(lang + " " + k).isFalse());
        }
    }
}
