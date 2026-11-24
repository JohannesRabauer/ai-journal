package dev.rabauer.ai.diary.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigValidatorTest {

    private static List<ProviderSelection> local() {
        List<ProviderSelection> s = new ArrayList<>();
        for (Capability c : Capability.values()) {
            s.add(new ProviderSelection(c, "fake", Set.of("fake", "local"), false));
        }
        return s;
    }

    private static ConfigValidator.Input full(List<ProviderSelection> sel) {
        return new ConfigValidator.Input("full", sel, Optional.empty(), false);
    }

    @Test
    void validLocalConfigurationHasNoErrors() {
        assertThat(ConfigValidator.validate(full(local()))).isEmpty();
    }

    @Test
    void unknownProviderIsReportedWithAlternatives() {
        var sel = local();
        sel.set(0, new ProviderSelection(Capability.STT, "whisperr", Set.of("fake", "whisper"), false));
        assertThat(ConfigValidator.validate(full(sel)))
                .singleElement().asString()
                .contains("journal.stt.provider=whisperr").contains("[fake, whisper]");
    }

    @Test
    void blankProviderIsReported() {
        var sel = local();
        sel.set(2, new ProviderSelection(Capability.LLM, " ", Set.of("fake"), false));
        assertThat(ConfigValidator.validate(full(sel))).singleElement().asString().contains("journal.llm.provider is not set");
    }

    @Test
    void externalProviderIsRejectedOutsideLiteCloud() {
        var sel = local();
        sel.set(2, new ProviderSelection(Capability.LLM, "openai", Set.of("fake", "openai"), true));
        assertThat(ConfigValidator.validate(full(sel)))
                .singleElement().asString().contains("only allowed with journal.profile=lite-cloud");
    }

    @Test
    void liteCloudRequiresConsentAndKey() {
        var in = new ConfigValidator.Input("lite-cloud", local(), Optional.of("yes"), false);
        List<String> errors = ConfigValidator.validate(in);
        assertThat(errors).hasSize(2);
        assertThat(String.join("\n", errors))
                .contains("JOURNAL_CLOUD_CONSENT=I_UNDERSTAND_DATA_LEAVES_THIS_DEVICE")
                .contains("OPENAI_API_KEY");
    }

    @Test
    void liteCloudWithConsentAndKeyIsValidAndAllowsExternalProviders() {
        var sel = local();
        sel.set(2, new ProviderSelection(Capability.LLM, "openai", Set.of("openai"), true));
        var in = new ConfigValidator.Input("lite-cloud", sel, Optional.of(JournalConfig.CLOUD_CONSENT_PHRASE), true);
        assertThat(ConfigValidator.validate(in)).isEmpty();
    }

    @Test
    void unknownProfileIsReported() {
        var in = new ConfigValidator.Input("cloud", local(), Optional.empty(), false);
        assertThat(ConfigValidator.validate(in)).singleElement().asString().contains("journal.profile=cloud");
    }
}
