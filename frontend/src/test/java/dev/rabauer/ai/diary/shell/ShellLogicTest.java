package dev.rabauer.ai.diary.shell;

import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.system.SystemInfoDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ShellLogicTest {

    @Test
    void uiLocaleFollowsSettingOrBrowser() {
        assertThat(UiLocaleResolver.resolve(Language.DE, Locale.ENGLISH)).isEqualTo(Locale.GERMAN);
        assertThat(UiLocaleResolver.resolve(Language.EN, Locale.GERMAN)).isEqualTo(Locale.ENGLISH);
        assertThat(UiLocaleResolver.resolve(Language.AUTO, Locale.GERMANY)).isEqualTo(Locale.GERMAN);
        assertThat(UiLocaleResolver.resolve(Language.AUTO, Locale.FRANCE)).isEqualTo(Locale.ENGLISH);
        assertThat(UiLocaleResolver.resolve(null, null)).isEqualTo(Locale.ENGLISH);
    }

    @Test
    void navigationFollowsSpecTable() {
        assertThat(NavDestination.ALL).extracting(NavDestination::route)
                .containsExactly("", "entries", "memory", "gym", "weekly", "settings");
    }

    @Test
    void activeItemMatchesRouteAndSubRoutes() {
        NavDestination today = NavDestination.ALL.get(0);
        NavDestination journal = NavDestination.ALL.get(1);
        assertThat(today.isActive("")).isTrue();
        assertThat(today.isActive("/")).isTrue();
        assertThat(today.isActive("entries")).isFalse();
        assertThat(journal.isActive("entries")).isTrue();
        assertThat(journal.isActive("entries/5/coach")).isTrue();
        assertThat(journal.isActive("entriesx")).isFalse();
        assertThat(NavDestination.ALL.stream().filter(d -> d.isActive("settings")).count()).isEqualTo(1);
    }

    @Test
    void bannerShowsOnlyForCloudOrOffline() {
        assertThat(ShellStatus.of(new SystemInfoDto("full", false, null, Map.of())).visible()).isFalse();
        ShellStatus cloud = ShellStatus.of(new SystemInfoDto("lite-cloud", true, "Text goes to OpenAI.", Map.of()));
        assertThat(cloud.kind()).isEqualTo(ShellStatus.Kind.CLOUD);
        assertThat(cloud.cloudNotice()).isEqualTo("Text goes to OpenAI.");
        assertThat(cloud.visible()).isTrue();
        assertThat(ShellStatus.of(new SystemInfoDto("lite-cloud", true, null, Map.of())).cloudNotice()).isEmpty();
        ShellStatus off = ShellStatus.of(null);
        assertThat(off.kind()).isEqualTo(ShellStatus.Kind.OFFLINE);
        assertThat(off.visible()).isTrue();
    }

    @Test
    void themeModeParsesStoredCodesAndDefaultsToSystem() {
        assertThat(ThemeMode.fromCode("dark")).isEqualTo(ThemeMode.DARK);
        assertThat(ThemeMode.fromCode("light")).isEqualTo(ThemeMode.LIGHT);
        assertThat(ThemeMode.fromCode("system")).isEqualTo(ThemeMode.SYSTEM);
        assertThat(ThemeMode.fromCode("nonsense")).isEqualTo(ThemeMode.SYSTEM);
        assertThat(ThemeMode.fromCode(null)).isEqualTo(ThemeMode.SYSTEM);
        assertThat(List.of(ThemeMode.values())).extracting(ThemeMode::code).containsExactly("system", "light", "dark");
    }
}
