package dev.rabauer.ai.diary.api.system;

import java.util.Map;

/**
 * @param profile   {@code full} or {@code lite-cloud}
 * @param cloudMode true when any provider sends data to an external service; the UI must show a persistent banner
 * @param providers capability -> provider name, e.g. stt -> whisper
 */
public record SystemInfoDto(String profile, boolean cloudMode, String cloudNotice, Map<String, String> providers) {
}
