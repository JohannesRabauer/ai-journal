package dev.rabauer.ai.diary.views.entries;

import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;

import java.time.Clock;

/** Vaadin glue: learns the browser time zone once per UI and hands out a {@link ClientTime}. */
public final class BrowserZone {

    private static final Class<String> KEY = String.class;

    private BrowserZone() {
    }

    /** Ask the browser for its zone (async). Call when a view that creates entries is built. */
    public static void init(UI ui) {
        if (ui == null || ComponentUtil.getData(ui, KEY) != null) {
            return;
        }
        ui.getPage().retrieveExtendedClientDetails(details -> {
            String id = details.getTimeZoneId();
            if (id != null) {
                ComponentUtil.setData(ui, KEY, id);
            }
        });
    }

    /** Current time in the browser zone; falls back to the server zone until the browser answered. */
    public static ClientTime current() {
        UI ui = UI.getCurrent();
        String id = ui == null ? null : ComponentUtil.getData(ui, KEY);
        return new ClientTime(Clock.systemDefaultZone(), id);
    }
}
