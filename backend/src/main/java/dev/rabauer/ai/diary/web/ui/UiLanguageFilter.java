package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.settings.SettingsService;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

/**
 * The "Interface language" setting for the server-rendered pages: when it is German or English it replaces the
 * {@code Accept-Language} header of page requests, so every page's {@link Messages#forAcceptable} picks it up on the
 * next render (the Vaadin frontend did the same with its UiLocaleResolver). "Browser language" leaves the header alone.
 * The JSON API under {@code /api} is not touched: it answers in the language the caller asks for.
 */
public class UiLanguageFilter {

    @Inject
    SettingsService settings;

    @ServerRequestFilter
    public void apply(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        if (path.startsWith("/api/") || path.startsWith("api/")) {
            return;
        }
        Language language;
        try {
            language = settings.get().uiLanguage();
        } catch (RuntimeException e) {
            return; // settings unreadable: keep the browser language rather than failing the page
        }
        if (language == Language.DE || language == Language.EN) {
            ctx.getHeaders().putSingle(HttpHeaders.ACCEPT_LANGUAGE, language.code());
        }
    }
}
