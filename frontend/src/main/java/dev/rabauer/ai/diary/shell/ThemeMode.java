package dev.rabauer.ai.diary.shell;

/** Manual colour scheme override; SYSTEM follows prefers-color-scheme. Stored in the browser (localStorage). */
public enum ThemeMode {
    SYSTEM, LIGHT, DARK;

    public String code() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static ThemeMode fromCode(String code) {
        if (code != null) {
            for (ThemeMode m : values()) {
                if (m.code().equals(code)) {
                    return m;
                }
            }
        }
        return SYSTEM;
    }
}
