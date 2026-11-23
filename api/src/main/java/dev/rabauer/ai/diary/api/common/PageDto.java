package dev.rabauer.ai.diary.api.common;

import java.util.List;

/** One page of results; {@code page} is zero-based. */
public record PageDto<T>(List<T> items, int page, int size, long total) {
}
