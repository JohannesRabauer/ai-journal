package dev.rabauer.ai.diary.web.ui;

import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.privacy.DeleteAllRequest;
import dev.rabauer.ai.diary.api.settings.SettingsDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsLogicTest {

    @Test
    void acceptsAscendingIntervalsInRange() {
        var r = IntervalsParser.parse("1, 3, 7, 14, 30");
        assertThat(r.valid()).isTrue();
        assertThat(r.values()).containsExactly(1, 3, 7, 14, 30);
        assertThat(IntervalsParser.parse("1 2 365").values()).containsExactly(1, 2, 365);
        assertThat(IntervalsParser.format(List.of(1, 3, 7))).isEqualTo("1, 3, 7");
    }

    @Test
    void rejectsInvalidIntervalsWithSpecificErrors() {
        assertThat(IntervalsParser.parse("").errorKey()).isEqualTo("settings.intervals.error.empty");
        assertThat(IntervalsParser.parse(null).errorKey()).isEqualTo("settings.intervals.error.empty");
        assertThat(IntervalsParser.parse("1, x").errorKey()).isEqualTo("settings.intervals.error.format");
        assertThat(IntervalsParser.parse("1.5").errorKey()).isEqualTo("settings.intervals.error.format");
        assertThat(IntervalsParser.parse("0, 3").errorKey()).isEqualTo("settings.intervals.error.range");
        assertThat(IntervalsParser.parse("1, 366").errorKey()).isEqualTo("settings.intervals.error.range");
        assertThat(IntervalsParser.parse("3, 3").errorKey()).isEqualTo("settings.intervals.error.order");
        assertThat(IntervalsParser.parse("7, 3").errorKey()).isEqualTo("settings.intervals.error.order");
        assertThat(IntervalsParser.parse("1,2,3,4,5,6,7,8,9,10,11").errorKey()).isEqualTo("settings.intervals.error.count");
    }

    @Test
    void deleteIsEnabledOnlyForTheExactPhrase() {
        assertThat(DeleteConfirmation.isConfirmed(DeleteAllRequest.CONFIRMATION_PHRASE)).isTrue();
        assertThat(DeleteConfirmation.isConfirmed("delete everything")).isFalse();
        assertThat(DeleteConfirmation.isConfirmed(DeleteAllRequest.CONFIRMATION_PHRASE + " ")).isFalse();
        assertThat(DeleteConfirmation.isConfirmed("")).isFalse();
        assertThat(DeleteConfirmation.isConfirmed(null)).isFalse();
        assertThat(DeleteConfirmation.request(DeleteAllRequest.CONFIRMATION_PHRASE).confirmation())
                .isEqualTo(DeleteAllRequest.CONFIRMATION_PHRASE);
        assertThatThrownBy(() -> DeleteConfirmation.request("nope")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void diffSendsOnlyChangedFields() {
        SettingsDto current = new SettingsDto(Language.AUTO, Language.DE, true, false, "v1", "v2", List.of(1, 3, 7));
        SettingsDto same = new SettingsDto(Language.AUTO, Language.DE, true, false, null, null, List.of(1, 3, 7));
        assertThat(SettingsDiff.isEmpty(SettingsDiff.partial(current, same))).isTrue();

        SettingsDto edited = new SettingsDto(Language.EN, Language.DE, false, false, null, null, List.of(1, 3, 7, 14));
        SettingsDto partial = SettingsDiff.partial(current, edited);
        assertThat(partial.uiLanguage()).isEqualTo(Language.EN);
        assertThat(partial.defaultEntryLanguage()).isNull();
        assertThat(partial.keepAudio()).isFalse();
        assertThat(partial.ttsAutoPlay()).isNull();
        assertThat(partial.reviewIntervalsDays()).containsExactly(1, 3, 7, 14);
        assertThat(partial.ttsVoiceDe()).isNull();
        assertThat(SettingsDiff.isEmpty(partial)).isFalse();
    }
}
