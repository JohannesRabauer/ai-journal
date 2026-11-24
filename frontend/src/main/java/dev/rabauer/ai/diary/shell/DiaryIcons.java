package dev.rabauer.ai.diary.shell;

import com.vaadin.flow.component.icon.SvgIcon;

/**
 * Line icons from the local sprite {@code META-INF/resources/icons/diary-icons.svg} (no CDN, SPEC 10).
 * Icons are decorative: every use sits next to a visible text label, so they are hidden from assistive technology.
 */
public final class DiaryIcons {

    /** Relative to the app base URL, so it resolves on nested routes too. */
    static final String SPRITE = "icons/diary-icons.svg";

    private DiaryIcons() {
    }

    public static SvgIcon create(String symbol) {
        SvgIcon icon = new SvgIcon(SPRITE, symbol);
        icon.addClassName("diary-icon");
        icon.getElement().setAttribute("aria-hidden", "true");
        return icon;
    }
}
