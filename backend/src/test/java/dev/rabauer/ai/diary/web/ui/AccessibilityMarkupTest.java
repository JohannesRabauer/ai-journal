package dev.rabauer.ai.diary.web.ui;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural accessibility rules every page of the Qute UI keeps (docs/A11Y.md, "Qute UI pass"): one h1, language,
 * landmarks, skip link first, current page marked once, no duplicate ids, no inline script/style, form fields
 * labelled, plus two source rules for the mobile tab bar and single-key shortcuts.
 */
@QuarkusTest
class AccessibilityMarkupTest {

    private static final String[] PAGES = {"/", "/journal", "/memory", "/gym", "/weekly", "/settings"};
    private static final Pattern ID = Pattern.compile("\\sid=\"([^\"]+)\"");
    private static final Pattern FIELD = Pattern.compile("<(input|select|textarea)\\b([^>]*)>");

    @Test
    void everyPageHasOneH1TheLanguageAndTheLandmarks() {
        for (String lang : new String[]{"en", "de"}) {
            for (String page : PAGES) {
                String body = PageTestSupport.get(page, lang).asString();
                String where = page + " (" + lang + ")";
                assertThat(PageTestSupport.count(body, "<h1")).as("h1 on " + where).isEqualTo(1);
                assertThat(body).as("lang on " + where).contains("<html lang=\"" + lang + "\"");
                assertThat(PageTestSupport.count(body, "<nav ")).as("nav on " + where).isEqualTo(1);
                assertThat(body).contains("<nav class=\"diary-nav\" aria-label=\"");
                assertThat(PageTestSupport.count(body, "<main ")).as("main on " + where).isEqualTo(1);
                assertThat(PageTestSupport.count(body, "aria-current=\"page\"")).as("current page on " + where)
                        .isBetween(1, 2); // the nav link and, on phones, the settings icon in the top bar
            }
        }
    }

    @Test
    void theSkipLinkIsTheFirstFocusableElementAndTargetsMain() {
        for (String page : PAGES) {
            String body = PageTestSupport.html(page);
            int skip = body.indexOf("class=\"skip-link\" href=\"#main-content\"");
            assertThat(skip).as("skip link on " + page).isPositive();
            assertThat(body.indexOf("<a ")).as("first link on " + page).isEqualTo(body.lastIndexOf("<a ", skip));
            assertThat(body.indexOf("<button")).isGreaterThan(skip);
            assertThat(body).contains("<main id=\"main-content\" class=\"diary-main\" tabindex=\"-1\">");
        }
    }

    @Test
    void idsAreUniqueAndAriaReferencesResolve() {
        for (String page : PAGES) {
            String body = withoutTemplates(PageTestSupport.html(page));
            Set<String> seen = new HashSet<>();
            Matcher m = ID.matcher(body);
            while (m.find()) {
                assertThat(seen.add(m.group(1))).as("duplicate id " + m.group(1) + " on " + page).isTrue();
            }
            Matcher refs = Pattern.compile("aria-(?:describedby|labelledby|controls)=\"([^\"]+)\"").matcher(body);
            while (refs.find()) {
                for (String id : refs.group(1).trim().split("\\s+")) {
                    assertThat(seen).as("aria reference " + id + " on " + page).contains(id);
                }
            }
        }
    }

    @Test
    void noInlineScriptStyleOrHandlers() {
        for (String page : PAGES) {
            String body = PageTestSupport.html(page);
            assertThat(body).doesNotContain("<script>").doesNotContain(" style=\"").doesNotContainPattern(" on[a-z]+=\"");
        }
    }

    @Test
    void everyFormFieldHasALabel() {
        for (String page : PAGES) {
            String body = withoutTemplates(PageTestSupport.html(page));
            Set<String> labelled = new HashSet<>();
            Matcher l = Pattern.compile("<label[^>]*\\sfor=\"([^\"]+)\"").matcher(body);
            while (l.find()) {
                labelled.add(l.group(1));
            }
            List<String> bad = new ArrayList<>();
            Matcher f = FIELD.matcher(body);
            while (f.find()) {
                String attrs = f.group(2);
                if (attrs.contains("type=\"hidden\"") || attrs.contains("aria-label=") || attrs.contains("aria-labelledby=")) {
                    continue;
                }
                Matcher id = ID.matcher(attrs);
                if (!id.find() || !labelled.contains(id.group(1))) {
                    bad.add(f.group());
                }
            }
            assertThat(bad).as("unlabelled fields on " + page).isEmpty();
        }
    }

    @Test
    void fieldErrorsAreAnnouncedWhenScriptsFillThem() {
        // the error text is injected by settings.js; an alert role makes it spoken although focus stays on the field
        assertThat(PageTestSupport.html("/settings")).contains("id=\"settings-intervals-error\" role=\"alert\"");
    }

    @Test
    void theMobileTabBarIsInTheFlowAndWrapsInsteadOfOverflowing() throws IOException {
        String css = Files.readString(resource("css/styles.css"), StandardCharsets.UTF_8);
        int mobile = css.indexOf("@media (max-width: 767.98px)");
        assertThat(mobile).isPositive();
        String block = css.substring(mobile, css.indexOf("@media (prefers-reduced-motion", mobile));
        assertThat(block).doesNotContain("position: fixed");
        assertThat(block).contains("flex-wrap: wrap").contains("position: sticky");
    }

    @Test
    void singleKeyGradeShortcutsOnlyWorkWhileTheCardHasFocus() throws IOException {
        // WCAG 2.1.4 Character Key Shortcuts: active only with focus inside the revealed card.
        String js = Files.readString(resource("js/memory.js"), StandardCharsets.UTF_8);
        assertThat(js).contains("target.closest('.memory-reveal')");
    }

    private static String withoutTemplates(String html) {
        return html.replaceAll("(?s)<template\\b.*?</template>", "");
    }

    private static Path resource(String path) {
        return Path.of("src/main/resources/META-INF/resources", path);
    }
}
