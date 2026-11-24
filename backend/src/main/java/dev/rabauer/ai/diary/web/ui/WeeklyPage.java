package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.insights.GenerateWeeklyReportRequest;
import dev.rabauer.ai.diary.api.insights.WeeklyReportDto;
import dev.rabauer.ai.diary.api.insights.WeeklyReportSummaryDto;
import dev.rabauer.ai.diary.entry.EntryService;
import dev.rabauer.ai.diary.insights.WeeklyReportService;
import dev.rabauer.ai.diary.security.Csrf;
import dev.rabauer.ai.diary.web.ui.WeeklyPresenter.EditDraft;
import dev.rabauer.ai.diary.web.ui.WeeklyPresenter.Week;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Week page (SPEC section 8). {@code /weekly?week=2026-W12} shows the stored reflection of an ISO week (default: the
 * last completed week), {@code &edit=1} the edit form. Everything is reachable without JavaScript: week navigation
 * are links, create / regenerate / delete / save are form posts with the form token that redirect back with a short
 * message code. {@code js/weekly.js} adds the confirmation dialogs, a status while the reflection is being made, and
 * rows in the edit form.
 */
@Path("/weekly")
@Produces(MediaType.TEXT_HTML)
public class WeeklyPage {

    private static final Set<String> ANNOUNCE = Set.of("created", "saved", "deleted");
    private static final Set<String> ERRORS = Set.of("unreachable", "provider", "invalid", "noEntries", "badWeek",
            "invalidEdit", "notFound", "unavailable");

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance page(Shell shell, Messages t, View v);
    }

    public record WeekOption(String code, String label, boolean selected) {
    }

    public record Link(String href, String text) {
    }

    public record ThemeView(String title, String description, List<Link> links) {
    }

    public record QuoteView(String text, Link source) {
    }

    public record ReportView(long id, String meta, List<ThemeView> themes, List<QuoteView> quotes, List<String> threads,
                             boolean hasLearned, String learnedSummary, List<String> learnedPrompts,
                             List<String> patterns, String question) {
    }

    public record ThemeRow(String title, String description, String entries) {
    }

    public record QuoteRow(String text, long entryId) {
    }

    public record EditView(List<ThemeRow> themes, List<QuoteRow> quotes, List<String> threads, List<String> patterns,
                           int itemsCreated, int reviewsDone, String question) {
    }

    public record HistoryItem(String href, String label, String meta, boolean current) {
    }

    /** {@code report} and {@code edit} are both null for a week without a reflection; {@code edit} is set when editing. */
    public record View(String weekCode, int isoYear, int isoWeek, String title, String range, String prevHref,
                       String nextHref, List<WeekOption> weeks, Flash flash, ReportView report, EditView edit,
                       boolean hasReport, boolean editing, List<HistoryItem> history, String viewHref) {
    }

    @Inject
    WeeklyReportService weekly;
    @Inject
    EntryService entries;
    @Inject
    ShellFactory shells;
    @Inject
    Clock clock;
    @Inject
    Csrf csrf;

    @GET
    public TemplateInstance page(@QueryParam("week") String weekParam, @QueryParam("edit") String edit,
                                 @QueryParam("msg") String msg, @QueryParam("error") String error,
                                 @CookieParam(ClientZone.COOKIE) String zoneCookie, @Context HttpHeaders headers) {
        Messages t = Messages.forAcceptable(headers.getAcceptableLanguages());
        ZoneId zone = ClientZone.resolve(zoneCookie, clock.getZone());
        LocalDate today = LocalDate.now(clock.withZone(zone));

        Flash flash = Flash.NONE;
        List<WeeklyReportSummaryDto> summaries = List.of();
        try {
            summaries = weekly.list();
        } catch (RuntimeException e) {
            flash = new Flash(t.get(WeeklyPresenter.errorFor(e).messageKey()), true);
        }
        List<Week> weeks = WeeklyPresenter.pickerWeeks(today, summaries.stream().map(WeeklyPresenter::weekOf).toList());
        Week last = WeeklyPresenter.lastCompleted(today);
        Week selected = Week.parse(weekParam).filter(weeks::contains).orElse(last);

        if (msg != null && ANNOUNCE.contains(msg)) {
            flash = new Flash(t.get("weekly.announce." + msg), false);
        } else if (error != null && ERRORS.contains(error)) {
            flash = new Flash(t.get("weekly.error." + error), true);
        }

        Locale locale = t.locale();
        int index = weeks.indexOf(selected);
        String prev = index < weeks.size() - 1 ? href(weeks.get(index + 1)) : "";
        String next = index > 0 ? href(weeks.get(index - 1)) : "";
        Week thisWeek = WeeklyPresenter.thisWeek(today);
        List<WeekOption> options = weeks.stream().map(w -> new WeekOption(w.code(), t.get(
                w.equals(thisWeek) ? "weekly.week.thisSoFar" : w.equals(last) ? "weekly.week.last" : "weekly.week.option",
                String.valueOf(w.isoWeek()), WeeklyPresenter.range(w, locale)), w.equals(selected))).toList();

        ReportView report = null;
        EditView editView = null;
        Optional<WeeklyReportSummaryDto> summary = WeeklyPresenter.summaryOf(selected, summaries);
        if (summary.isPresent()) {
            try {
                WeeklyReportDto dto = weekly.get(summary.get().id());
                if ("1".equals(edit)) {
                    editView = editView(new EditDraft(dto));
                } else {
                    report = reportView(t, dto, zone);
                }
            } catch (RuntimeException e) {
                flash = new Flash(t.get(WeeklyPresenter.errorFor(e).messageKey()), true);
            }
        }

        List<HistoryItem> history = summaries.stream().map(s -> {
            Week w = WeeklyPresenter.weekOf(s);
            return new HistoryItem(href(w), t.get("weekly.history.item", String.valueOf(s.isoWeek()), String.valueOf(s.isoYear())),
                    WeeklyPresenter.range(w, locale) + " · "
                            + t.get("weekly.history.created", WeeklyPresenter.dateTime(s.generatedAt(), zone, locale)),
                    w.equals(selected));
        }).toList();

        View v = new View(selected.code(), selected.isoYear(), selected.isoWeek(),
                t.get("weekly.week.heading", String.valueOf(selected.isoWeek())), WeeklyPresenter.range(selected, locale),
                prev, next, options, flash, report, editView, report != null || editView != null, editView != null, history,
                href(selected));
        return Templates.page(shells.create(t, "/weekly"), t, v);
    }

    // ------------------------------------------------------------------ no-JavaScript writes

    @POST
    @Path("create")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response create(@FormParam("week") String weekCode, @FormParam("_csrf") String token) {
        csrf.require(token);
        Optional<Week> week = Week.parse(weekCode);
        if (week.isEmpty()) {
            return redirect("/weekly?error=badWeek");
        }
        try {
            weekly.generate(new GenerateWeeklyReportRequest(week.get().isoYear(), week.get().isoWeek()));
            return redirect(href(week.get()) + "&msg=created");
        } catch (RuntimeException e) {
            return redirect(href(week.get()) + "&error=" + errorCode(e));
        }
    }

    @POST
    @Path("delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response delete(@FormParam("week") String weekCode, @FormParam("_csrf") String token) {
        csrf.require(token);
        Optional<Week> week = Week.parse(weekCode);
        if (week.isEmpty()) {
            return redirect("/weekly?error=badWeek");
        }
        try {
            Optional<WeeklyReportSummaryDto> summary = WeeklyPresenter.summaryOf(week.get(), weekly.list());
            if (summary.isEmpty()) {
                return redirect(href(week.get()) + "&error=notFound");
            }
            weekly.delete(summary.get().id());
            return redirect(href(week.get()) + "&msg=deleted");
        } catch (RuntimeException e) {
            return redirect(href(week.get()) + "&error=" + errorCode(e));
        }
    }

    @POST
    @Path("save")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response save(@FormParam("week") String weekCode, @FormParam("_csrf") String token,
                         @FormParam("themeTitle") List<String> themeTitles,
                         @FormParam("themeDescription") List<String> themeDescriptions,
                         @FormParam("themeEntries") List<String> themeEntries,
                         @FormParam("quoteText") List<String> quoteTexts, @FormParam("quoteEntry") List<String> quoteEntries,
                         @FormParam("thread") List<String> threads, @FormParam("pattern") List<String> patterns,
                         @FormParam("itemsCreated") String itemsCreated, @FormParam("reviewsDone") String reviewsDone,
                         @FormParam("question") String question) {
        csrf.require(token);
        Optional<Week> week = Week.parse(weekCode);
        if (week.isEmpty()) {
            return redirect("/weekly?error=badWeek");
        }
        try {
            Optional<WeeklyReportSummaryDto> summary = WeeklyPresenter.summaryOf(week.get(), weekly.list());
            if (summary.isEmpty()) {
                return redirect(href(week.get()) + "&error=notFound");
            }
            EditDraft draft = new EditDraft(weekly.get(summary.get().id()));
            draft.applyForm(themeTitles, themeDescriptions, themeEntries, quoteTexts, quoteEntries, threads, patterns,
                    count(itemsCreated), count(reviewsDone), question);
            weekly.update(summary.get().id(), draft.toReport());
            return redirect(href(week.get()) + "&msg=saved");
        } catch (RuntimeException e) {
            return redirect(href(week.get()) + "&error=" + errorCode(e));
        }
    }

    // ------------------------------------------------------------------ view models

    private ReportView reportView(Messages t, WeeklyReportDto r, ZoneId zone) {
        Locale locale = t.locale();
        Map<Long, String> dates = new HashMap<>();
        for (Long id : WeeklyPresenter.referencedEntries(r)) {
            try {
                EntryDto entry = entries.get(id);
                String date = WeeklyPresenter.entryDate(entry.entryTimestamp(), locale);
                if (date != null) {
                    dates.put(id, date);
                }
            } catch (RuntimeException e) {
                // the entry may be gone: its link shows the generic text
            }
        }
        String meta = t.get("weekly.generated", WeeklyPresenter.dateTime(r.generatedAt(), zone, locale));
        if (r.editedAt() != null) {
            meta += " · " + t.get("weekly.edited", WeeklyPresenter.dateTime(r.editedAt(), zone, locale));
        }
        List<ThemeView> themes = new ArrayList<>();
        for (WeeklyReportDto.Theme theme : nz(r.recurringThemes())) {
            List<Link> links = nz(theme.entryIds()).stream().map(id -> link(t, dates, id)).toList();
            themes.add(new ThemeView(theme.title(), theme.description(), links));
        }
        List<QuoteView> quotes = nz(r.inYourOwnWords()).stream()
                .map(q -> new QuoteView(q.text(), link(t, dates, q.entryId()))).toList();
        WeeklyReportDto.Learned l = r.whatYouLearned();
        boolean hasLearned = WeeklyPresenter.hasLearned(l);
        return new ReportView(r.id(), meta, themes, quotes, nz(r.openThreads()), hasLearned,
                hasLearned ? t.get("weekly.learned.summary", l.itemsCreated(), l.reviewsDone()) : "",
                hasLearned ? nz(l.itemPrompts()) : List.of(), nz(r.noticedPatterns()),
                r.questionForNextWeek() == null ? "" : r.questionForNextWeek());
    }

    private static Link link(Messages t, Map<Long, String> dates, long entryId) {
        String date = dates.get(entryId);
        return new Link("/journal/" + entryId,
                date == null ? t.get("weekly.entry.linkFallback") : t.get("weekly.entry.link", date));
    }

    /** The edit form: the stored rows plus one empty row of each kind a user may add without JavaScript. */
    private static EditView editView(EditDraft draft) {
        List<ThemeRow> themes = new ArrayList<>(draft.themes.stream().map(r -> new ThemeRow(nz(r.title), nz(r.description),
                String.join(",", r.entryIds.stream().map(String::valueOf).toList()))).toList());
        themes.add(new ThemeRow("", "", ""));
        List<QuoteRow> quotes = draft.quotes.stream().map(q -> new QuoteRow(nz(q.text), q.entryId)).toList();
        List<String> threads = new ArrayList<>(draft.openThreads);
        threads.add("");
        List<String> patterns = new ArrayList<>(draft.patterns);
        patterns.add("");
        return new EditView(themes, quotes, threads, patterns, draft.itemsCreated, draft.reviewsDone, nz(draft.question));
    }

    private static <T> List<T> nz(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String href(Week week) {
        return "/weekly?week=" + week.code();
    }

    /** {@code weekly.error.<code>} for a failure. */
    static String errorCode(RuntimeException e) {
        String key = WeeklyPresenter.errorFor(e).messageKey();
        return key.substring("weekly.error.".length());
    }

    private static Integer count(String value) {
        try {
            return value == null || value.isBlank() ? null : Math.max(0, Integer.parseInt(value.strip()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Response redirect(String path) {
        return Response.seeOther(URI.create(path)).build();
    }
}
