package dev.rabauer.ai.diary.spi;

/**
 * Marker for providers that send data off this device (e.g. OpenAI). Only allowed in the {@code lite-cloud}
 * profile; startup validation rejects them elsewhere, and the UI shows a persistent cloud notice while one is active.
 */
public interface ExternalProvider {
}
