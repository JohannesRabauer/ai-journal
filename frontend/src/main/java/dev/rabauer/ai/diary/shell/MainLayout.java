package dev.rabauer.ai.diary.shell;

import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;

/**
 * Wave 0 skeleton of the app shell (SPEC section 12) so feature views can declare
 * {@code @Route(value = "...", layout = MainLayout.class)}. Owned and redesigned by W1c
 * (theme, i18n, cloud banner, mobile bottom bar).
 */
public class MainLayout extends AppLayout {

    public MainLayout() {
        SideNav nav = new SideNav();
        nav.addItem(new SideNavItem("Today", ""));
        nav.addItem(new SideNavItem("Journal", "entries"));
        nav.addItem(new SideNavItem("Remember", "memory"));
        nav.addItem(new SideNavItem("Thinking Gym", "gym"));
        nav.addItem(new SideNavItem("Week", "weekly"));
        nav.addItem(new SideNavItem("Settings", "settings"));
        addToDrawer(nav);
        addToNavbar(new DrawerToggle(), new Span("AI Diary"));
    }
}
