package dev.rabauer.ai.diary.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pure validation of the provider/profile configuration. Returns human-readable problems, one per line,
 * each naming the property or environment variable to fix.
 */
public final class ConfigValidator {

    public record Input(String profile,
                        List<ProviderSelection> selections,
                        Optional<String> cloudConsent,
                        boolean openAiKeyPresent,
                        String dbKind,
                        boolean dbPasswordPresent,
                        boolean dbPasswordIsPlaceholder) {

        public Input(String profile, List<ProviderSelection> selections, Optional<String> cloudConsent,
                     boolean openAiKeyPresent, String dbKind, boolean dbPasswordPresent) {
            this(profile, selections, cloudConsent, openAiKeyPresent, dbKind, dbPasswordPresent, false);
        }
    }

    /** The example value of .env.example: a database with this password is not protected. */
    public static final String PLACEHOLDER_DB_PASSWORD = "change-me-to-a-long-random-value";

    private ConfigValidator() {
    }

    public static List<String> validate(Input in) {
        List<String> errors = new ArrayList<>();
        boolean lite = JournalConfig.PROFILE_LITE_CLOUD.equals(in.profile());
        if (!lite && !JournalConfig.PROFILE_FULL.equals(in.profile())) {
            errors.add("journal.profile=" + in.profile() + " is unknown. Use 'full' or 'lite-cloud' (env JOURNAL_PROFILE).");
        }
        for (ProviderSelection s : in.selections()) {
            if (s.name() == null || s.name().isBlank()) {
                errors.add(s.capability().property() + " is not set. Available: " + s.available());
                continue;
            }
            if (!s.available().contains(s.name())) {
                errors.add(s.capability().property() + "=" + s.name() + " is not available in this build. Available: "
                        + s.available().stream().sorted().toList());
                continue;
            }
            if (s.external() && !lite) {
                errors.add(s.capability().property() + "=" + s.name()
                        + " sends journal data to an external service. This is only allowed with journal.profile=lite-cloud.");
            }
        }
        if (lite) {
            if (in.cloudConsent().filter(JournalConfig.CLOUD_CONSENT_PHRASE::equals).isEmpty()) {
                errors.add("lite-cloud sends entry text and audio to OpenAI. To accept this, set JOURNAL_CLOUD_CONSENT="
                        + JournalConfig.CLOUD_CONSENT_PHRASE + " (property journal.cloud.consent).");
            }
            if (!in.openAiKeyPresent()) {
                errors.add("lite-cloud requires an OpenAI API key: set OPENAI_API_KEY (property journal.openai.api-key).");
            }
            if (!"h2".equals(in.dbKind())) {
                errors.add("lite-cloud requires the embedded H2 database, but this build uses '" + in.dbKind()
                        + "'. Build with -Dquarkus.profile=lite-cloud (see scripts/build-lite.*).");
            }
        }
        boolean pgvector = in.selections().stream()
                .anyMatch(s -> s.capability() == Capability.VECTOR && "pgvector".equals(s.name()));
        if (pgvector && !"postgresql".equals(in.dbKind())) {
            errors.add("journal.vector.provider=pgvector requires PostgreSQL; use journal.vector.provider=jdbc with " + in.dbKind() + ".");
        }
        if ("postgresql".equals(in.dbKind()) && !in.dbPasswordPresent()) {
            errors.add("No database password configured: set JOURNAL_DB_PASSWORD (property quarkus.datasource.password).");
        }
        if ("postgresql".equals(in.dbKind()) && in.dbPasswordIsPlaceholder()) {
            errors.add("JOURNAL_DB_PASSWORD still has the example value from .env.example. Choose your own long random password "
                    + "(for a database that already exists see scripts/secure-existing-db.*).");
        }
        return errors;
    }
}
