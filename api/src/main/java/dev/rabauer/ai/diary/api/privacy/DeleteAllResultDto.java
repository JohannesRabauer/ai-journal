package dev.rabauer.ai.diary.api.privacy;

import java.util.Map;

/** @param deleted table or store -> number of rows/files removed */
public record DeleteAllResultDto(Map<String, Long> deleted) {
}
