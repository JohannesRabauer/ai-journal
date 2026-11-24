package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.entry.CreateEntryRequest;
import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.EntrySource;
import dev.rabauer.ai.diary.entry.EntryService;
import dev.rabauer.ai.diary.security.Csrf;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Today (SPEC 4): record or write a new entry, review the transcript, finish. Hard rule (SPEC 4.4): there is no AI
 * output anywhere on this page; the only background work is the transcription of the user's own recording.
 * {@code ?fragment=timeline} returns only the "Earlier today" list for the in-place refresh by {@code js/today.js}.
 */
@Path("/")
@Produces(MediaType.TEXT_HTML)
public class TodayResource {

    /** Entries fetched to find today's: the page shows only today's, so a long day never needs more. */
    static final int LOOKBACK = 50;

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance today(Shell shell, Messages t, Today v);

        static native TemplateInstance today$timeline(Shell shell, Messages t, Today v);
    }

    /**
     * View model. {@code zone} is the zone the server used for the date line, so the script can tell when the
     * browser is somewhere else (first visit without the cookie) and reload once.
     */
    public record Today(String zone, String dateLine, String greeting, List<TodayPresenter.Item> items,
                        boolean loadFailed, boolean empty, boolean saved, String texts, String recorderLabels) {
    }

    @Inject
    EntryService entries;
    @Inject
    ShellFactory shells;
    @Inject
    Clock clock;
    @Inject
    Csrf csrf;

    @GET
    public TemplateInstance today(@QueryParam("fragment") String fragment, @QueryParam("saved") String saved,
                                  @CookieParam(ClientZone.COOKIE) String zoneCookie, @Context HttpHeaders headers) {
        Messages t = Messages.forAcceptable(headers.getAcceptableLanguages());
        ZoneId zone = ClientZone.resolve(zoneCookie, clock.getZone());
        LocalDateTime now = LocalDateTime.now(clock.withZone(zone));

        List<TodayPresenter.Item> items = List.of();
        boolean failed = false;
        try {
            items = TodayPresenter.timeline(
                    TodayPresenter.todayEntries(entries.list(0, LOOKBACK).items(), now.toLocalDate()), t);
        } catch (RuntimeException e) {
            failed = true;
        }
        Today v = new Today(zone.getId(), TodayPresenter.dateLine(now, t.locale()),
                t.get(TodayPresenter.greetingKey(now)), items, failed, items.isEmpty(),
                saved != null && !saved.isEmpty() && !"0".equals(saved),
                PageTexts.json(t, PageTexts.PANEL_KEYS), PageTexts.recorderLabels(t));
        Shell shell = shells.create(t, "/");
        boolean timelineOnly = fragment != null && !fragment.isEmpty() && !"0".equals(fragment);
        return timelineOnly ? Templates.today$timeline(shell, t, v) : Templates.today(shell, t, v);
    }

    /**
     * No-JavaScript write path: the plain form of the Write panel. Creates the entry in the browser's time zone,
     * finishes it and answers 303 to the page (flash flag {@code saved}, never journal text).
     */
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response write(@FormParam("text") String text, @FormParam("language") String language,
                          @FormParam("_csrf") String token, @CookieParam(ClientZone.COOKIE) String zoneCookie) {
        csrf.require(token);
        if (text == null || text.isBlank()) {
            return Response.seeOther(URI.create("/")).build();
        }
        LocalDateTime now = LocalDateTime.now(clock.withZone(ClientZone.resolve(zoneCookie, clock.getZone())));
        EntryDto created = entries.create(new CreateEntryRequest(text, now, languageOrNull(language), EntrySource.TEXT));
        entries.finish(created.id());
        return Response.seeOther(URI.create("/?saved=1")).build();
    }

    /** "DE" or "EN" (any case) select a language; anything else, including "AUTO", lets the provider detect it. */
    static Language languageOrNull(String code) {
        if (code == null) {
            return null;
        }
        try {
            Language l = Language.fromCode(code);
            return l == Language.AUTO ? null : l;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
