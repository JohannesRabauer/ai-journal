package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.api.gym.AttemptDto;
import dev.rabauer.ai.diary.api.gym.ExerciseDto;
import dev.rabauer.ai.diary.api.gym.GymCategory;
import dev.rabauer.ai.diary.gym.GymService;
import dev.rabauer.ai.diary.system.SystemInfoService;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;

import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Thinking Gym (SPEC section 7). The page renders the categories and the earlier answers (with the category filter
 * and "show earlier ones" as plain GET links). Preparing an exercise, saving an answer and asking for follow-up
 * questions are interactions of {@code js/gym.js} against {@code /api/gym}; without JavaScript a note says so.
 * No scores, counts or streaks anywhere.
 */
@Path("/gym")
@Produces(MediaType.TEXT_HTML)
public class GymPage {

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance page(Shell shell, Messages t, View v);
    }

    public record Category(String code, String attr, String name, String description) {
    }

    public record Option(String value, String label, boolean selected) {
    }

    public record HistoryItem(String when, String category, String duration, String snippet, String exerciseTitle,
                              String answer, List<String> questions) {
    }

    public record View(List<Category> categories, List<Option> filter, List<HistoryItem> history, boolean hasMore,
                       String moreHref, boolean llm, String lang, String selectedCategory) {
    }

    @Inject
    GymService gym;
    @Inject
    SystemInfoService system;
    @Inject
    ShellFactory shells;
    @Inject
    Clock clock;

    @GET
    public TemplateInstance page(@QueryParam("category") String categoryParam,
                                 @QueryParam("shown") @DefaultValue("20") int shownParam,
                                 @CookieParam(ClientZone.COOKIE) String zoneCookie, @Context HttpHeaders headers) {
        Messages t = Messages.forAcceptable(headers.getAcceptableLanguages());
        ZoneId zone = ClientZone.resolve(zoneCookie, clock.getZone());
        GymCategory filter = category(categoryParam);
        int shown = Math.max(GymPresenter.HISTORY_PAGE, Math.min(shownParam, 500));

        List<Category> categories = new ArrayList<>();
        for (GymCategory c : GymCategory.values()) {
            categories.add(new Category(c.name(), c.name().toLowerCase(Locale.ROOT), t.get(GymPresenter.categoryKey(c)), t.get(GymPresenter.descriptionKey(c))));
        }
        List<Option> options = new ArrayList<>();
        options.add(new Option("", t.get("gym.history.all"), filter == null));
        for (GymCategory c : GymCategory.values()) {
            options.add(new Option(c.name(), t.get(GymPresenter.categoryKey(c)), c == filter));
        }

        List<AttemptDto> history;
        try {
            history = gym.history(filter);
        } catch (RuntimeException e) {
            history = List.of();
        }
        Locale locale = t.locale();
        List<HistoryItem> items = new ArrayList<>();
        for (AttemptDto a : GymPresenter.visible(history, shown)) {
            int minutes = GymPresenter.minutes(a.durationSeconds());
            items.add(new HistoryItem(GymPresenter.time(a.createdAt(), zone, locale),
                    t.get(GymPresenter.categoryKey(a.category())),
                    minutes < 1 ? t.get("gym.history.under") : t.get("gym.history.minutes", minutes),
                    GymPresenter.snippet(a.answer(), 140), exerciseTitle(a.exerciseId()), a.answer(),
                    a.feedbackQuestions() == null ? List.of() : a.feedbackQuestions()));
        }
        boolean more = GymPresenter.hasMore(history, shown);
        String moreHref = more ? "/gym?" + (filter == null ? "" : "category=" + filter.name() + "&") + "shown="
                + (shown + GymPresenter.HISTORY_PAGE) + "#gym-history" : "";

        boolean llm = false;
        try {
            llm = GymPresenter.llmAvailable(system.info());
        } catch (RuntimeException e) {
            // not offered
        }
        View v = new View(categories, options, items, more, moreHref, llm, GymPresenter.languageTag(locale),
                filter == null ? "" : filter.name());
        return Templates.page(shells.create(t, "/gym"), t, v);
    }

    private String exerciseTitle(long id) {
        try {
            ExerciseDto exercise = gym.get(id);
            return exercise.title() == null ? "" : exercise.title();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static GymCategory category(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return GymCategory.valueOf(code);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
