package dev.rabauer.ai.diary.shell;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.Main;
import com.vaadin.flow.component.html.Nav;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.html.UnorderedList;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.AfterNavigationObserver;
import com.vaadin.flow.router.RouterLayout;
import dev.rabauer.ai.diary.client.SystemClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * App shell (SPEC section 12): one navigation landmark that is a side bar on desktop and a bottom bar below
 * 768px (pure CSS, see themes/diary/styles.css), skip link, persistent cloud/offline banner, main landmark.
 * No streaks, counters or scores here.
 */
public class MainLayout extends Div implements RouterLayout, AfterNavigationObserver {

    private final Main main = new Main();
    private final Map<NavDestination, Anchor> links = new LinkedHashMap<>();
    private final Map<NavDestination, Anchor> topbarLinks = new LinkedHashMap<>();
    private final Div statusLine = new Div();

    public MainLayout(SystemClient system) {
        addClassName("diary-shell");

        Anchor skip = new Anchor("#main-content", getTranslation("nav.skip"));
        skip.addClassName("skip-link");
        skip.getElement().executeJs("this.addEventListener('click', function (e) {"
                + " e.preventDefault(); var m = document.getElementById('main-content'); if (m) { m.focus(); } });");

        Nav nav = new Nav();
        nav.addClassName("diary-nav");
        nav.getElement().setAttribute("aria-label", getTranslation("nav.label"));
        UnorderedList list = new UnorderedList();
        for (NavDestination d : NavDestination.ALL) {
            Anchor a = new Anchor(d.route(), "");
            a.getElement().setAttribute("router-link", true);
            a.add(DiaryIcons.create(d.icon()), new Span(getTranslation(d.labelKey())));
            links.put(d, a);
            ListItem item = new ListItem(a);
            if (d.secondary()) {
                item.addClassName("diary-nav-secondary");
            }
            list.add(item);
        }
        statusLine.addClassName("diary-nav-status");
        nav.add(brand(), list, statusLine);

        // Mobile only (CSS): brand plus a settings shortcut, since settings is not in the bottom bar.
        NavDestination settings = NavDestination.ALL.stream().filter(NavDestination::secondary).findFirst().orElseThrow();
        Anchor settingsShortcut = new Anchor(settings.route(), "");
        settingsShortcut.getElement().setAttribute("router-link", true);
        settingsShortcut.getElement().setAttribute("aria-label", getTranslation(settings.labelKey()));
        settingsShortcut.addClassName("diary-topbar-action");
        settingsShortcut.add(DiaryIcons.create(settings.icon()));
        topbarLinks.put(settings, settingsShortcut);
        Div topbar = new Div(brand(), settingsShortcut);
        topbar.addClassName("diary-topbar");

        main.setId("main-content");
        main.addClassName("diary-main");
        main.getElement().setAttribute("tabindex", "-1");

        CloudBanner banner = new CloudBanner(system);
        banner.setStatusListener(this::showStatus);
        Div body = new Div(topbar, banner, main);
        body.addClassName("diary-body");

        showStatus(banner.status());
        add(skip, nav, body);
    }

    /** Brand mark plus app name; decorative, the page title already names the app. */
    private Div brand() {
        Span mark = new Span(DiaryIcons.create("feather"));
        mark.addClassName("diary-brand-mark");
        Div brand = new Div(mark, new Span(getTranslation("app.name")));
        brand.addClassName("diary-brand");
        brand.getElement().setAttribute("aria-hidden", "true");
        return brand;
    }

    /** Quiet privacy line under the navigation; the banner stays the prominent notice for cloud and offline. */
    private void showStatus(ShellStatus status) {
        statusLine.removeAll();
        String key = switch (status.kind()) {
            case LOCAL -> "status.local";
            case CLOUD -> "status.cloud";
            case OFFLINE -> "status.offline";
        };
        String icon = switch (status.kind()) {
            case LOCAL -> "lock";
            case CLOUD -> "cloud";
            case OFFLINE -> "offline";
        };
        statusLine.getElement().setAttribute("data-kind", status.kind().name().toLowerCase(java.util.Locale.ROOT));
        statusLine.add(DiaryIcons.create(icon), new Span(getTranslation(key)));
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        UI ui = attachEvent.getUI();
        ui.getPage().executeJs("document.documentElement.lang = $0", ui.getLocale().getLanguage());
    }

    @Override
    public void showRouterLayoutContent(HasElement content) {
        main.removeAll();
        if (content != null) {
            main.getElement().appendChild(content.getElement());
        }
    }

    @Override
    public void afterNavigation(AfterNavigationEvent event) {
        String path = event.getLocation().getPath();
        java.util.stream.Stream.concat(links.entrySet().stream(), topbarLinks.entrySet().stream()).forEach(entry -> {
            NavDestination dest = entry.getKey();
            Anchor anchor = entry.getValue();
            Element e = anchor.getElement();
            if (dest.isActive(path)) {
                e.setAttribute("aria-current", "page");
            } else {
                e.removeAttribute("aria-current");
            }
        });
    }
}
