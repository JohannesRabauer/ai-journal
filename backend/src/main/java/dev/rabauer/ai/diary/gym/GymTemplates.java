package dev.rabauer.ai.diary.gym;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.gym.GymCategory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * The built-in exercise library ({@code gym/templates_de.json}, {@code gym/templates_en.json}). It makes the gym
 * fully usable without an LLM. Every entry is checked on load: 2 to 5 steps, 5 to 10 minutes, non-blank texts.
 */
@ApplicationScoped
public class GymTemplates {

    /** One library exercise. The same id exists in both languages. */
    public record Template(String id, GymCategory category, int estimatedMinutes, String title, String prompt, List<String> steps) {
    }

    private static final TypeReference<List<Template>> LIST = new TypeReference<>() {
    };

    @Inject
    ObjectMapper mapper;

    private volatile Map<Language, List<Template>> cache;

    public List<Template> all(Language language) {
        Map<Language, List<Template>> c = cache;
        if (c == null) {
            c = Map.of(Language.DE, load(mapper, Language.DE), Language.EN, load(mapper, Language.EN));
            cache = c;
        }
        return c.get(language == Language.DE ? Language.DE : Language.EN);
    }

    public List<Template> forCategory(Language language, GymCategory category) {
        return all(language).stream().filter(t -> t.category() == category).toList();
    }

    /** Reads and validates one language file. */
    public static List<Template> load(ObjectMapper mapper, Language language) {
        String name = "gym/templates_" + language.code() + ".json";
        try (InputStream in = GymTemplates.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + name);
            }
            List<Template> list = mapper.readValue(in, LIST);
            for (Template t : list) {
                validate(name, t);
            }
            return List.copyOf(list);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + name, e);
        }
    }

    private static void validate(String file, Template t) {
        boolean ok = t.id() != null && !t.id().isBlank() && t.category() != null
                && t.title() != null && !t.title().isBlank() && t.prompt() != null && !t.prompt().isBlank()
                && t.steps() != null && t.steps().size() >= 2 && t.steps().size() <= 5
                && t.steps().stream().allMatch(s -> s != null && !s.isBlank())
                && t.estimatedMinutes() >= 5 && t.estimatedMinutes() <= 10;
        if (!ok) {
            throw new IllegalStateException("Invalid template '" + t.id() + "' in " + file);
        }
    }
}
