package dev.rabauer.ai.diary.config;

import dev.rabauer.ai.diary.spi.SelfValidating;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Fails startup with one clear message listing every configuration problem. */
@ApplicationScoped
public class StartupValidation {

    private static final Logger LOG = Logger.getLogger(StartupValidation.class);

    @Inject
    JournalConfig config;
    @Inject
    ProviderProducers providers;

    @Inject
    jakarta.enterprise.inject.Instance<Flyway> flyway;
    @ConfigProperty(name = "quarkus.flyway.migrate-at-start")
    boolean migratedByQuarkus;

    @ConfigProperty(name = "quarkus.datasource.db-kind")
    String dbKind;
    @ConfigProperty(name = "quarkus.datasource.password")
    Optional<String> dbPassword;

    void onStart(@Observes @Priority(1) StartupEvent event) {
        var selections = providers.selections();
        List<String> errors = new ArrayList<>(ConfigValidator.validate(new ConfigValidator.Input(
                config.profile(),
                List.copyOf(selections.values()),
                config.cloud().consent(),
                config.openai().apiKey().filter(k -> !k.isBlank()).isPresent(),
                dbKind,
                dbPassword.filter(p -> !p.isBlank()).isPresent())));
        if (errors.isEmpty()) {
            for (Capability c : Capability.values()) {
                providers.selectedInstance(c)
                        .filter(SelfValidating.class::isInstance)
                        .ifPresent(p -> ((SelfValidating) p).validateConfiguration(errors));
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Invalid AI Diary configuration:\n  - " + String.join("\n  - ", errors));
        }
        if (!migratedByQuarkus) {
            flyway.get().migrate();
        }
        StringBuilder sb = new StringBuilder("AI Diary profile=").append(config.profile()).append(", db=").append(dbKind);
        selections.values().forEach(s -> sb.append(", ").append(s.capability().key()).append('=').append(s.name()));
        LOG.info(sb);
        selections.values().stream()
                .filter(s -> s.name().equals("fake"))
                .forEach(s -> LOG.warnf("%s=fake: no real provider, for development and tests only.", s.capability().property()));
        if (selections.values().stream().anyMatch(ProviderSelection::external)) {
            LOG.warn("CLOUD MODE: entry text and audio are sent to OpenAI (lite-cloud profile, consent given).");
        }
    }
}
