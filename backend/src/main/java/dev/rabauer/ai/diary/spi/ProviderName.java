package dev.rabauer.ai.diary.spi;

import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Qualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Qualifier naming a provider implementation. The value is what users put in configuration,
 * e.g. {@code journal.stt.provider=whisper}.
 *
 * <p>Every implementation of an SPI interface MUST be annotated with
 * {@code @ApplicationScoped @ProviderName("<name>") @io.quarkus.arc.Unremovable}. Business code never injects an
 * implementation directly; it injects the interface (unqualified), which {@code ProviderProducers} resolves from
 * configuration.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
public @interface ProviderName {
    String value();

    final class Literal extends AnnotationLiteral<ProviderName> implements ProviderName {
        private final String value;

        public Literal(String value) {
            this.value = value;
        }

        @Override
        public String value() {
            return value;
        }
    }
}
