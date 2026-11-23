package dev.rabauer.ai.diary.views.memory;

import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import dev.rabauer.ai.diary.shell.ComingSoonPage;
import dev.rabauer.ai.diary.shell.MainLayout;

/** Placeholder for "Remember / Erinnern" until the memory feature lands. */
@Route(value = "memory", layout = MainLayout.class)
public class MemoryView extends ComingSoonPage implements HasDynamicTitle {

    private static final String PREFIX = "placeholder.memory";

    public MemoryView() {
        super(PREFIX);
    }

    @Override
    public String getPageTitle() {
        return title(PREFIX);
    }
}
