package dev.rabauer.ai.diary.entry;

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
}
