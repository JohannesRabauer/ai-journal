package dev.rabauer.ai.diary.shell;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import dev.rabauer.ai.diary.api.system.SystemInfoDto;
import dev.rabauer.ai.diary.client.SystemClient;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Persistent, non-dismissible notice at the top of every route (it lives in {@link MainLayout}).
 * Cloud mode: states that entry text and audio leave the device (SPEC 11.3). Backend unreachable: a calm offline notice.
 * Nothing is shown in a local profile.
 */
public class CloudBanner extends Div {

    private final SystemClient system;
    private final Div inner = new Div();
    private volatile ShellStatus status = new ShellStatus(ShellStatus.Kind.LOCAL, "");
    private Consumer<ShellStatus> statusListener = s -> { };

    public CloudBanner(SystemClient system) {
        this.system = system;
        addClassName("diary-banner");
        inner.addClassName("diary-banner-inner");
        add(inner);
        setVisible(false);
    }

    public ShellStatus status() {
        return status;
    }

    /** Called on the UI thread whenever a new status is rendered (the shell mirrors it in the navigation). */
    public void setStatusListener(Consumer<ShellStatus> listener) {
        this.statusListener = listener == null ? s -> { } : listener;
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        refresh();
    }

    /** Loads system info off the UI thread and renders the result; never throws. */
    public void refresh() {
        UI ui = UI.getCurrent();
        if (ui == null) {
            return;
        }
        CompletableFuture.supplyAsync(this::load).thenAccept(s -> ui.access(() -> render(s)));
    }

    private ShellStatus load() {
        try {
            SystemInfoDto info = system.info();
            return info == null ? ShellStatus.offline() : ShellStatus.of(info);
        } catch (RuntimeException e) {
            return ShellStatus.offline();
        }
    }

    void render(ShellStatus s) {
        this.status = s;
        statusListener.accept(s);
        inner.removeAll();
        removeClassName("diary-banner-cloud");
        setVisible(s.visible());
        switch (s.kind()) {
            case CLOUD -> {
                addClassName("diary-banner-cloud");
                getElement().setAttribute("role", "region");
                getElement().setAttribute("aria-label", getTranslation("banner.cloud.label"));
                Span text = new Span(s.cloudNotice().isBlank() ? getTranslation("banner.cloud.default") : s.cloudNotice());
                inner.getElement().appendChild(new com.vaadin.flow.dom.Element("strong")
                        .setText(getTranslation("banner.cloud.title")));
                inner.add(text);
            }
            case OFFLINE -> {
                getElement().setAttribute("role", "status");
                getElement().removeAttribute("aria-label");
                Span text = new Span(getTranslation("banner.offline.text"));
                Button retry = new Button(getTranslation("banner.offline.retry"), e -> refresh());
                retry.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
                inner.add(text, retry);
            }
            case LOCAL -> {
                getElement().removeAttribute("role");
                getElement().removeAttribute("aria-label");
            }
        }
    }
}
