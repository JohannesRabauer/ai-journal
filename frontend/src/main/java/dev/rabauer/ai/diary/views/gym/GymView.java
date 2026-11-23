package dev.rabauer.ai.diary.views.gym;

import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import dev.rabauer.ai.diary.shell.ComingSoonPage;
import dev.rabauer.ai.diary.shell.MainLayout;

/** Placeholder for "Thinking Gym / Denktraining" until the gym feature lands. */
@Route(value = "gym", layout = MainLayout.class)
public class GymView extends ComingSoonPage implements HasDynamicTitle {

    private static final String PREFIX = "placeholder.gym";

    public GymView() {
        super(PREFIX);
    }

    @Override
    public String getPageTitle() {
        return title(PREFIX);
    }
}
