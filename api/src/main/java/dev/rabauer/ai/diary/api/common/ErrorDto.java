package dev.rabauer.ai.diary.api.common;

/**
 * Error body for all non-2xx responses. {@code message} is user-presentable and must never contain journal content.
 *
 * @param code    stable machine-readable code, e.g. {@code TRANSCRIPTION_FAILED}, {@code LLM_OUTPUT_INVALID}
 * @param message short, calm, user-presentable message (no journal content)
 */
public record ErrorDto(String code, String message) {
}
