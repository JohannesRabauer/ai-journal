package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.api.entry.EntrySource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JournalPresenterSearchTest {

    @Test
    void filterParsingFallsBackToAll() {
        assertThat(JournalPresenter.Filter.parse(null)).isEqualTo(JournalPresenter.Filter.ALL);
        assertThat(JournalPresenter.Filter.parse("")).isEqualTo(JournalPresenter.Filter.ALL);
        assertThat(JournalPresenter.Filter.parse("nope")).isEqualTo(JournalPresenter.Filter.ALL);
        assertThat(JournalPresenter.Filter.parse(" VOICE ")).isEqualTo(JournalPresenter.Filter.VOICE);
        assertThat(JournalPresenter.Filter.parse("reflected")).isEqualTo(JournalPresenter.Filter.REFLECTED);
    }

    @Test
    void filterMapsToApiParameters() {
        assertThat(JournalPresenter.Filter.ALL.source()).isNull();
        assertThat(JournalPresenter.Filter.ALL.reflected()).isNull();
        assertThat(JournalPresenter.Filter.VOICE.source()).isEqualTo(EntrySource.VOICE);
        assertThat(JournalPresenter.Filter.VOICE.reflected()).isNull();
        assertThat(JournalPresenter.Filter.REFLECTED.source()).isNull();
        assertThat(JournalPresenter.Filter.REFLECTED.reflected()).isTrue();
    }

    @Test
    void queryIsTrimmedAndCapped() {
        assertThat(JournalPresenter.normalizeQuery(null)).isEmpty();
        assertThat(JournalPresenter.normalizeQuery("  hi \n")).isEqualTo("hi");
        assertThat(JournalPresenter.normalizeQuery("x".repeat(300))).hasSize(200);
    }

    @Test
    void hrefLeavesDefaultsOutAndEncodesText() {
        assertThat(JournalPresenter.href("", JournalPresenter.Filter.ALL, 0)).isEqualTo("/journal");
        assertThat(JournalPresenter.href(" ", JournalPresenter.Filter.ALL, 0)).isEqualTo("/journal");
        assertThat(JournalPresenter.href("a&b \"c\"", JournalPresenter.Filter.VOICE, 2))
                .isEqualTo("/journal?q=a%26b+%22c%22&filter=voice&page=2");
        assertThat(JournalPresenter.href(null, JournalPresenter.Filter.REFLECTED, 0)).isEqualTo("/journal?filter=reflected");
        assertThat(JournalPresenter.href(null, JournalPresenter.Filter.ALL, 3)).isEqualTo("/journal?page=3");
    }

    @Test
    void filterOptionsMarkTheActiveOneAndKeepTheText() {
        List<JournalPresenter.FilterOption> options = JournalPresenter.filterOptions("walk", JournalPresenter.Filter.VOICE);
        assertThat(options).extracting(JournalPresenter.FilterOption::param).containsExactly("all", "voice", "reflected");
        assertThat(options).extracting(JournalPresenter.FilterOption::current).containsExactly(false, true, false);
        assertThat(options.get(0).href()).isEqualTo("/journal?q=walk");
        assertThat(options.get(2).href()).isEqualTo("/journal?q=walk&filter=reflected");
        assertThat(options.get(1).labelKey()).isEqualTo("entries.filter.voice");
    }
}
