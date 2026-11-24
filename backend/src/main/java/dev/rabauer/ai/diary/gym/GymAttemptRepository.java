package dev.rabauer.ai.diary.gym;

import dev.rabauer.ai.diary.api.gym.GymCategory;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class GymAttemptRepository implements PanacheRepository<GymAttemptEntity> {

    static final int HISTORY_LIMIT = 500;
    private static final Sort NEWEST_FIRST = Sort.by("createdAt", Sort.Direction.Descending).and("id", Sort.Direction.Descending);

    /** Newest first, optionally for one category. */
    public List<GymAttemptEntity> history(GymCategory category) {
        var query = category == null ? findAll(NEWEST_FIRST) : find("category", NEWEST_FIRST, category);
        return query.page(Page.ofSize(HISTORY_LIMIT)).list();
    }

    /** Latest attempt time per category (categories never practiced are absent). */
    public Map<GymCategory, Instant> lastAttemptPerCategory() {
        Map<GymCategory, Instant> out = new EnumMap<>(GymCategory.class);
        for (Object[] row : getEntityManager()
                .createQuery("select a.category, max(a.createdAt) from GymAttemptEntity a group by a.category", Object[].class)
                .getResultList()) {
            out.put((GymCategory) row[0], (Instant) row[1]);
        }
        return out;
    }

    public List<GymAttemptEntity> forExercise(long exerciseId) {
        return list("exerciseId", NEWEST_FIRST, exerciseId);
    }
}
