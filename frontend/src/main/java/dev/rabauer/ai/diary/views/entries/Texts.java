package dev.rabauer.ai.diary.views.entries;

/** Translation lookup handed to view helpers (typically {@code component::getTranslation}). */
@FunctionalInterface
public interface Texts {

    String get(String key, Object... params);
}
