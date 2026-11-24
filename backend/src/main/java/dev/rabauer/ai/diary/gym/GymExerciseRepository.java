package dev.rabauer.ai.diary.gym;

import dev.rabauer.ai.diary.api.gym.GymCategory;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class GymExerciseRepository implements PanacheRepository<GymExerciseEntity> {

    /** Latest time an exercise was issued, per category (categories never issued are absent). */
    public Map<GymCategory, Instant> lastIssuedPerCategory() {
        Map<GymCategory, Instant> out = new EnumMap<>(GymCategory.class);
        for (Object[] row : getEntityManager()
                .createQuery("select e.category, max(e.createdAt) from GymExerciseEntity e group by e.category", Object[].class)
                .getResultList()) {
            out.put((GymCategory) row[0], (Instant) row[1]);
        }
        return out;
    }

    /** Latest use per template id for a category and language. */
    public Map<String, Instant> lastUsePerTemplate(GymCategory category, String language) {
        Map<String, Instant> out = new HashMap<>();
        for (Object[] row : getEntityManager().createQuery(
                        "select e.templateId, max(e.createdAt) from GymExerciseEntity e "
                                + "where e.category = ?1 and e.language = ?2 and e.templateId is not null group by e.templateId",
                        Object[].class).setParameter(1, category).setParameter(2, language).getResultList()) {
            out.put((String) row[0], (Instant) row[1]);
        }
        return out;
    }

    /** Entry ids used as source by the most recent exercises. */
    public Set<Long> recentSourceEntries(int lastExercises) {
        Set<Long> out = new LinkedHashSet<>();
        List<GymExerciseEntity> recent = find("sourceEntryId is not null",
                Sort.by("createdAt", Sort.Direction.Descending).and("id", Sort.Direction.Descending))
                .page(Page.ofSize(lastExercises)).list();
        recent.forEach(e -> out.add(e.sourceEntryId));
        return out;
    }
}
