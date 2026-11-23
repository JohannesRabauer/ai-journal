package dev.rabauer.ai.diary.shell;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;

/** Calm placeholder for features that arrive in a later wave. No fake content, no counters. */
public class ComingSoonPage extends Div {

    /** @param keyPrefix i18n prefix with {@code .title} and {@code .text}, e.g. "placeholder.memory" */
    public ComingSoonPage(String keyPrefix) {
        H1 title = new H1(getTranslation(keyPrefix + ".title"));
        Paragraph text = new Paragraph(getTranslation(keyPrefix + ".text"));
        text.addClassName("diary-muted");
        add(title, text);
    }

    public String title(String keyPrefix) {
        return getTranslation(keyPrefix + ".title");
    }
}
