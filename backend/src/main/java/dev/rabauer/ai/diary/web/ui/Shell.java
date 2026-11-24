package dev.rabauer.ai.diary.web.ui;

import java.util.List;

/**
 * Everything the layout template needs; mirrors the Vaadin MainLayout (nav, settings shortcut, privacy line).
 * {@code lang} is the page language, {@code statusKind} is "cloud" or "local" (offline is applied by js/status.js).
 */
public record Shell(String lang, boolean cloud, String statusKind, String statusIcon, String statusKey,
                    List<NavItem> nav, NavItem settings) {

    public record NavItem(String href, String labelKey, String icon, boolean active, boolean secondary) {
    }
}
