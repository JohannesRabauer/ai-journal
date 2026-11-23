package dev.rabauer.ai.diary.system;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import java.time.Clock;

/** Injectable clock; tests may replace it with a fixed clock via a higher-priority alternative. */
@ApplicationScoped
public class ClockProducer {

    @Produces
    @DefaultBean
    @Singleton
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}
