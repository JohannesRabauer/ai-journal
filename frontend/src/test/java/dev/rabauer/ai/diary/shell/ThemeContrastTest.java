package dev.rabauer.ai.diary.shell;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Parses the --diary-* colour tokens from the theme and checks WCAG 2.1 AA contrast (docs/DESIGN.md). */
class ThemeContrastTest {

    private static final Path CSS = Path.of("src/main/frontend/themes/diary/styles.css");

    @Test
    void lightAndDarkTokensMeetAA() throws IOException {
        String css = Files.readString(CSS, StandardCharsets.UTF_8);
        Map<String, String> light = tokens(block(css, ":root {"));
        Map<String, String> dark = new HashMap<>(light);
        dark.putAll(tokens(block(css, "html[theme~='dark'] {")));
        for (Map<String, String> t : java.util.List.of(light, dark)) {
            // body and secondary text: 4.5:1 on page and surface
            for (String bg : new String[]{"--diary-bg", "--diary-surface"}) {
                assertThat(ratio(t.get("--diary-text"), t.get(bg))).as("text on " + bg).isGreaterThanOrEqualTo(4.5);
                assertThat(ratio(t.get("--diary-muted"), t.get(bg))).as("muted on " + bg).isGreaterThanOrEqualTo(4.5);
                assertThat(ratio(t.get("--diary-accent"), t.get(bg))).as("accent text on " + bg).isGreaterThanOrEqualTo(4.5);
                assertThat(ratio(t.get("--diary-error"), t.get(bg))).as("error text on " + bg).isGreaterThanOrEqualTo(4.5);
                // control borders / focus ring: 3:1
                assertThat(ratio(t.get("--diary-border"), t.get(bg))).as("border on " + bg).isGreaterThanOrEqualTo(3.0);
            }
            assertThat(ratio(t.get("--diary-on-accent"), t.get("--diary-accent"))).as("text on accent").isGreaterThanOrEqualTo(4.5);
            // destructive primary buttons: Lumo error contrast colour on the error fill
            assertThat(ratio(t.get("--lumo-error-contrast-color"), t.get("--diary-error"))).as("text on error fill")
                    .isGreaterThanOrEqualTo(4.5);
        }
    }

    /** Lumo draws focus rings with --lumo-primary-color-50pct; a translucent ring would drop below 3:1 (WCAG 1.4.11). */
    @Test
    void focusRingTokenIsTheSolidAccent() throws IOException {
        String css = Files.readString(CSS, StandardCharsets.UTF_8);
        assertThat(css).containsPattern("--lumo-primary-color-50pct:\\s*var\\(--diary-accent\\);");
    }

    private static String block(String css, String start) {
        int i = css.indexOf(start);
        assertThat(i).as("block " + start).isGreaterThanOrEqualTo(0);
        return css.substring(i, css.indexOf("}", i));
    }

    private static Map<String, String> tokens(String block) {
        Map<String, String> out = new HashMap<>();
        Matcher m = Pattern.compile("(--diary-[a-z-]+|--lumo-error-contrast-color):\\s*(#[0-9a-fA-F]{6});").matcher(block);
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        return out;
    }

    static double ratio(String a, String b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(String hex) {
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = Integer.parseInt(hex.substring(1 + 2 * i, 3 + 2 * i), 16) / 255.0;
            c[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }
}
