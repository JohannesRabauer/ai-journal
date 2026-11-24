package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.api.common.PageDto;
import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.entry.EntryService;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Journal page, server-rendered. {@code ?page=N} shows pages 0..N cumulatively (what the Vaadin "Load older entries"
 * button builds up), so the no-JavaScript link is a plain GET. {@code &fragment=1} returns only page N's entries
 * for the progressive-enhancement script.
 */
@Path("/journal")
@Produces(MediaType.TEXT_HTML)
public class JournalResource {

    static final int PAGE_SIZE = 20;
    /** Keeps the cumulative no-JS view bounded. */
    static final int MAX_PAGE = 200;

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance journal(Shell shell, Messages t, Journal j);

        static native TemplateInstance journal$chunk(Shell shell, Messages t, Journal j);
    }

    /** View model; progress and eyebrow are formatted here to keep the template free of logic. */
    public record Journal(boolean empty, boolean noMatch, String eyebrow, List<JournalPresenter.DaySection> days,
                          String progress, String nextHref, String q, String filter,
                          List<JournalPresenter.FilterOption> filters, String clearHref) {
    }

    @Inject
    EntryService entries;
    @Inject
    ShellFactory shells;
    @Inject
    Clock clock;
    @Inject
    NotMovedPages notMoved;

    /** Entry detail (phase 1 replaces this): /journal/{id}. */
    @GET
    @Path("{id: [0-9]+}")
    public TemplateInstance entry(@PathParam("id") long id, @Context HttpHeaders headers, @Context UriInfo uri) {
        return notMoved.render(headers.getAcceptableLanguages(), uri.getRequestUri(), "/journal", "nav.journal", "/entries/" + id);
    }

    /** Coach for one entry (phase 3 replaces this): /journal/{id}/reflect. */
    @GET
    @Path("{id: [0-9]+}/reflect")
    public TemplateInstance reflect(@PathParam("id") long id, @Context HttpHeaders headers, @Context UriInfo uri) {
        return notMoved.render(headers.getAcceptableLanguages(), uri.getRequestUri(), "/journal", "nav.journal", "/entries/" + id + "/coach");
    }

    @GET
    public TemplateInstance journal(@QueryParam("page") @DefaultValue("0") int page,
                                    @QueryParam("fragment") String fragment,
                                    @QueryParam("q") String qParam, @QueryParam("filter") String filterParam,
                                    @CookieParam(ClientZone.COOKIE) String zoneCookie, @Context HttpHeaders headers) {
        Messages t = Messages.forAcceptable(headers.getAcceptableLanguages());
        int last = Math.max(0, Math.min(page, MAX_PAGE));
        boolean fragmentOnly = fragment != null && !fragment.isEmpty() && !"0".equals(fragment);

        String q = JournalPresenter.normalizeQuery(qParam);
        JournalPresenter.Filter filter = JournalPresenter.Filter.parse(filterParam);
        boolean filtered = !q.isEmpty() || filter != JournalPresenter.Filter.ALL;

        List<EntryDto> items = new ArrayList<>();
        PageDto<EntryDto> result = null;
        for (int p = fragmentOnly ? last : 0; p <= last; p++) {
            result = entries.list(p, PAGE_SIZE, q, filter.source() == null ? null : filter.source().name(),
                    filter.reflected());
            items.addAll(result.items());
            if (result.items().isEmpty()) {
                break;
            }
        }
        long total = result.total();
        // Entries shown on this page and all before it.
        long shown = Math.min(total, fragmentOnly ? (long) last * PAGE_SIZE + items.size() : items.size());
        boolean more = !result.items().isEmpty() && (long) (last + 1) * PAGE_SIZE < total;

        // "Today" follows the browser's time zone (diary-tz cookie), not the server clock.
        LocalDate today = LocalDate.now(clock.withZone(ClientZone.resolve(zoneCookie, clock.getZone())));
        String eyebrow = total == 0 ? ""
                : filtered ? t.get(total == 1 ? "entries.search.matches.one" : "entries.search.matches", total)
                : t.get(total == 1 ? "entries.journal.count.one" : "entries.journal.count", total);
        Journal j = new Journal(
                total == 0 && items.isEmpty() && !filtered,
                total == 0 && items.isEmpty() && filtered,
                eyebrow,
                JournalPresenter.sections(items, today, t),
                total == 0 ? "" : t.get("entries.journal.loaded", shown, total),
                more ? JournalPresenter.href(q, filter, last + 1) : null,
                q, filter.param(), JournalPresenter.filterOptions(q, filter),
                JournalPresenter.href("", JournalPresenter.Filter.ALL, 0));
        Shell shell = shells.create(t, "/journal");
        return fragmentOnly ? Templates.journal$chunk(shell, t, j) : Templates.journal(shell, t, j);
    }
}
