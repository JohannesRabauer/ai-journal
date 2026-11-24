package dev.rabauer.ai.diary.gym;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.gym.GymCategory;
import dev.rabauer.ai.diary.coach.CoachRules;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The built-in library is what makes the gym usable without AI (acceptance 9). */
class GymTemplatesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void everyCategoryHasAtLeastThreeValidTemplatesInBothLanguages() {
        for (Language lang : List.of(Language.DE, Language.EN)) {
            List<GymTemplates.Template> all = GymTemplates.load(MAPPER, lang);
            for (GymCategory category : GymCategory.values()) {
                List<GymTemplates.Template> ofCategory = all.stream().filter(t -> t.category() == category).toList();
                assertThat(ofCategory).as(lang + " " + category).hasSizeGreaterThanOrEqualTo(3);
                for (GymTemplates.Template t : ofCategory) {
                    assertThat(t.title()).isNotBlank();
                    assertThat(t.prompt()).isNotBlank();
                    assertThat(t.steps()).hasSizeBetween(2, 5).allMatch(s -> !s.isBlank());
                    assertThat(t.estimatedMinutes()).isBetween(5, 10);
                }
            }
            assertThat(all.size()).isGreaterThanOrEqualTo(21);
        }
    }

    @Test
    void sameTemplateIdsExistInGermanAndEnglish() {
        Set<String> de = GymTemplates.load(MAPPER, Language.DE).stream().map(GymTemplates.Template::id).collect(Collectors.toSet());
        Set<String> en = GymTemplates.load(MAPPER, Language.EN).stream().map(GymTemplates.Template::id).collect(Collectors.toSet());
        assertThat(de).isEqualTo(en);
        assertThat(GymTemplates.load(MAPPER, Language.EN).stream().map(GymTemplates.Template::id).distinct().count())
                .isEqualTo(en.size());
    }

    @Test
    void templatesFollowTheCoachToneRules() {
        for (Language lang : List.of(Language.DE, Language.EN)) {
            for (GymTemplates.Template t : GymTemplates.load(MAPPER, lang)) {
                String all = t.title() + " " + t.prompt() + " " + String.join(" ", t.steps());
                assertThat(CoachRules.containsPraise(all)).as(t.id()).isFalse();
                assertThat(CoachRules.containsDiagnosisOrScore(all)).as(t.id()).isFalse();
            }
        }
    }
}
