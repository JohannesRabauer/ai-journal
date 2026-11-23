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
import com.vaadin.flow.component.icon.Icon;
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

    public MainLayout(SystemClient system) {
        addClassName("diary-shell");

        Anchor skip = new Anchor("#main-content", getTranslation("nav.skip"));
        skip.addClassName("skip-link");
        skip.getElement().executeJs("this.addEventListener('click', function (e) {"
                + " e.preventDefault(); var m = document.getElementById('main-content'); if (m) { m.focus(); } });");

        Nav nav = new Nav();
        nav.addClassName("diary-nav");
        nav.getElement().setAttribute("aria-label", getTranslation("nav.label"));
        Span brand = new Span(getTranslation("app.name"));
        brand.addClassName("diary-brand");
        brand.getElement().setAttribute("aria-hidden", "true");
        UnorderedList list = new UnorderedList();
        for (NavDestination d : NavDestination.ALL) {
            Anchor a = new Anchor(d.route(), "");
            a.getElement().setAttribute("router-link", true);
            Icon icon = d.icon().create();
            icon.getElement().setAttribute("aria-hidden", "true");
            a.add(icon, new Span(getTranslation(d.labelKey())));
            links.put(d, a);
            list.add(new ListItem(a));
        }
        nav.add(brand, list);

        main.setId("main-content");
        main.addClassName("diary-main");
        main.getElement().setAttribute("tabindex", "-1");

        Div body = new Div(new CloudBanner(system), main);
        body.addClassName("diary-body");

        add(skip, nav, body);
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
        links.forEach((dest, anchor) -> {
            Element e = anchor.getElement();
            if (dest.isActive(path)) {
                e.setAttribute("aria-current", "page");
            } else {
                e.removeAttribute("aria-current");
            }
        });
    }
}
