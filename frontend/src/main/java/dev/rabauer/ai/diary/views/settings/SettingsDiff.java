package dev.rabauer.ai.diary.views.settings;

import dev.rabauer.ai.diary.api.settings.SettingsDto;

import java.util.Objects;

/** Builds the partial PUT body: only fields that differ from the loaded settings, everything else null. */
public final class SettingsDiff {

    private SettingsDiff() {
    }

    public static SettingsDto partial(SettingsDto current, SettingsDto edited) {
        return new SettingsDto(
                changed(current.uiLanguage(), edited.uiLanguage()),
                changed(current.defaultEntryLanguage(), edited.defaultEntryLanguage()),
                changed(current.keepAudio(), edited.keepAudio()),
                changed(current.ttsAutoPlay(), edited.ttsAutoPlay()),
                null,
                null,
                changed(current.reviewIntervalsDays(), edited.reviewIntervalsDays()));
    }

    public static boolean isEmpty(SettingsDto partial) {
        return partial.uiLanguage() == null && partial.defaultEntryLanguage() == null
                && partial.keepAudio() == null && partial.ttsAutoPlay() == null
                && partial.ttsVoiceDe() == null && partial.ttsVoiceEn() == null
                && partial.reviewIntervalsDays() == null;
    }

    private static <T> T changed(T current, T edited) {
        return Objects.equals(current, edited) ? null : edited;
    }
}
