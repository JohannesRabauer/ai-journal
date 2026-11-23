package dev.rabauer.ai.diary.views.weekly;

import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import dev.rabauer.ai.diary.shell.ComingSoonPage;
import dev.rabauer.ai.diary.shell.MainLayout;

/** Placeholder for "Week / Woche" until the weekly reflection lands. */
@Route(value = "weekly", layout = MainLayout.class)
public class WeeklyView extends ComingSoonPage implements HasDynamicTitle {

    private static final String PREFIX = "placeholder.weekly";

    public WeeklyView() {
        super(PREFIX);
    }

    @Override
    public String getPageTitle() {
        return title(PREFIX);
    }
}
