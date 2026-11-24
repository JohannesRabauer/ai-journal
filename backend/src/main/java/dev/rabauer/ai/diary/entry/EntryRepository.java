package dev.rabauer.ai.diary.entry;

import dev.rabauer.ai.diary.api.entry.EntrySource;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class EntryRepository implements PanacheRepository<DiaryEntryEntity> {

    /** Newest first: by entry timestamp, then by id (stable for equal timestamps). */
    public static final Sort NEWEST_FIRST = Sort.by("timestamp", Sort.Direction.Descending, Sort.NullPrecedence.NULLS_LAST)
            .and("id", Sort.Direction.Descending);

    public List<DiaryEntryEntity> newestFirst(int page, int size) {
        return findAll(NEWEST_FIRST).page(Page.of(page, size)).list();
    }

    /** Escape character of the LIKE pattern built by {@link #likePattern} (not a backslash: no quoting ambiguity). */
    static final char LIKE_ESCAPE = '!';

    /** {@code %text%}, lower-cased, with {@code %}, {@code _} and the escape character taken literally. */
    static String likePattern(String text) {
        String escaped = text.toLowerCase(java.util.Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }

    private static final String HAS_CONVERSATION =
            "exists (select 1 from CoachConversationEntity c where c.entryId = e.id)";

    private record Filter(String where, java.util.Map<String, Object> params) {
    }

    /** The where clause is assembled from fixed fragments only; every value is a bound parameter. */
    private static Filter filter(String text, EntrySource source, Boolean reflected) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        if (text != null && !text.isEmpty()) {
            parts.add("lower(e.entryText) like :pattern escape '" + LIKE_ESCAPE + "'");
            params.put("pattern", likePattern(text));
        }
        if (source != null) {
            parts.add("e.source = :source");
            params.put("source", source);
        }
        if (reflected != null) {
            parts.add(reflected ? HAS_CONVERSATION : "not " + HAS_CONVERSATION);
        }
        return new Filter(parts.isEmpty() ? "" : " where " + String.join(" and ", parts), params);
    }

    /** Newest first, filtered (every filter optional). */
    public List<DiaryEntryEntity> newestFirst(int page, int size, String text, EntrySource source, Boolean reflected) {
        Filter f = filter(text, source, reflected);
        var q = getEntityManager().createQuery("select e from DiaryEntryEntity e" + f.where()
                + " order by e.timestamp desc nulls last, e.id desc", DiaryEntryEntity.class);
        f.params().forEach(q::setParameter);
        return q.setFirstResult(page * size).setMaxResults(size).getResultList();
    }

    public long countFiltered(String text, EntrySource source, Boolean reflected) {
        Filter f = filter(text, source, reflected);
        var q = getEntityManager().createQuery("select count(e) from DiaryEntryEntity e" + f.where(), Long.class);
        f.params().forEach(q::setParameter);
        return q.getSingleResult();
    }
}
