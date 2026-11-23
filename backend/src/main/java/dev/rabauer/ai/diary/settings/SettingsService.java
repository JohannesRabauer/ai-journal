package dev.rabauer.ai.diary.settings;

import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.settings.SettingsDto;
import dev.rabauer.ai.diary.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Single-user settings stored as key/value rows; defaults apply when a key is absent. */
@ApplicationScoped
public class SettingsService {

    public static final List<Integer> DEFAULT_INTERVALS = List.of(1, 3, 7, 14, 30);
    public static final SettingsDto DEFAULTS = new SettingsDto(Language.AUTO, Language.AUTO, true, true, null, null, DEFAULT_INTERVALS);

    @Transactional
    public SettingsDto get() {
        Map<String, String> m = AppSettingEntity.<AppSettingEntity>listAll().stream()
                .collect(Collectors.toMap(e -> e.key, e -> e.value));
        return new SettingsDto(
                read(m, "uiLanguage", Language::fromCode, DEFAULTS.uiLanguage()),
                read(m, "defaultEntryLanguage", Language::fromCode, DEFAULTS.defaultEntryLanguage()),
                read(m, "keepAudio", Boolean::valueOf, DEFAULTS.keepAudio()),
                read(m, "ttsAutoPlay", Boolean::valueOf, DEFAULTS.ttsAutoPlay()),
                m.get("ttsVoiceDe"),
                m.get("ttsVoiceEn"),
                read(m, "reviewIntervalsDays", SettingsService::parseIntervals, DEFAULT_INTERVALS));
    }

    @Transactional
    public SettingsDto update(SettingsDto s) {
        if (s.reviewIntervalsDays() != null) {
            validateIntervals(s.reviewIntervalsDays());
            put("reviewIntervalsDays", s.reviewIntervalsDays().stream().map(String::valueOf).collect(Collectors.joining(",")));
        }
        if (s.uiLanguage() != null) put("uiLanguage", s.uiLanguage().code());
        if (s.defaultEntryLanguage() != null) put("defaultEntryLanguage", s.defaultEntryLanguage().code());
        if (s.keepAudio() != null) put("keepAudio", s.keepAudio().toString());
        if (s.ttsAutoPlay() != null) put("ttsAutoPlay", s.ttsAutoPlay().toString());
        if (s.ttsVoiceDe() != null) put("ttsVoiceDe", s.ttsVoiceDe());
        if (s.ttsVoiceEn() != null) put("ttsVoiceEn", s.ttsVoiceEn());
        return get();
    }

    @Transactional
    public long deleteAll() {
        return AppSettingEntity.deleteAll();
    }

    static void validateIntervals(List<Integer> intervals) {
        if (intervals.isEmpty() || intervals.size() > 10) {
            throw ApiException.badRequest("INVALID_INTERVALS", "Provide between 1 and 10 review intervals.");
        }
        int prev = 0;
        for (Integer i : intervals) {
            if (i == null || i < 1 || i > 365 || i < prev) {
                throw ApiException.badRequest("INVALID_INTERVALS", "Intervals must be ascending whole days between 1 and 365.");
            }
            prev = i;
        }
    }

    private static List<Integer> parseIntervals(String s) {
        return Arrays.stream(s.split(",")).map(String::trim).map(Integer::valueOf).toList();
    }

    private static <T> T read(Map<String, String> m, String key, Function<String, T> parse, T dflt) {
        String v = m.get(key);
        if (v == null) {
            return dflt;
        }
        try {
            return parse.apply(v);
        } catch (RuntimeException e) {
            return dflt;
        }
    }

    private static void put(String key, String value) {
        AppSettingEntity e = AppSettingEntity.findById(key);
        if (e == null) {
            e = new AppSettingEntity();
            e.key = key;
            e.value = value;
            e.persist();
        } else {
            e.value = value;
        }
    }
}
