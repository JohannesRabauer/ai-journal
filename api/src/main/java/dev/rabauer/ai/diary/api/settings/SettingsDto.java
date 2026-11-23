package dev.rabauer.ai.diary.api.settings;

import dev.rabauer.ai.diary.api.common.Language;

import java.util.List;

/**
 * User settings (single user). Null fields in a PUT are left unchanged.
 *
 * @param keepAudio           false = delete audio once the entry is finished (transcript confirmed)
 * @param reviewIntervalsDays SM-2 initial intervals, default [1,3,7,14,30]
 */
public record SettingsDto(
        Language uiLanguage,
        Language defaultEntryLanguage,
        Boolean keepAudio,
        Boolean ttsAutoPlay,
        String ttsVoiceDe,
        String ttsVoiceEn,
        List<Integer> reviewIntervalsDays
) {
}
