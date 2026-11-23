package dev.rabauer.ai.diary.config;

import java.util.Set;

/**
 * @param name      configured provider name
 * @param available names of all implementations present in this build
 * @param external  true if the selected implementation sends data off-device
 */
public record ProviderSelection(Capability capability, String name, Set<String> available, boolean external) {
}
