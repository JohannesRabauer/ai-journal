package dev.rabauer.ai.diary.views.settings;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.html.UnorderedList;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.StreamResource;
import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.api.privacy.DeleteAllRequest;
import dev.rabauer.ai.diary.api.privacy.DeleteAllResultDto;
import dev.rabauer.ai.diary.api.settings.SettingsDto;
import dev.rabauer.ai.diary.api.system.SystemInfoDto;
import dev.rabauer.ai.diary.client.BackendException;
import dev.rabauer.ai.diary.client.PrivacyClient;
import dev.rabauer.ai.diary.client.SettingsClient;
import dev.rabauer.ai.diary.client.SystemClient;
import dev.rabauer.ai.diary.shell.MainLayout;
import dev.rabauer.ai.diary.shell.ThemeMode;
import dev.rabauer.ai.diary.shell.UiLocaleResolver;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * Settings (SPEC section 12): language, voice, audio retention, review intervals, appearance, privacy
 * (export, delete everything) and read-only profile info. Form logic lives in plain classes
 * ({@link IntervalsParser}, {@link SettingsDiff}, {@link DeleteConfirmation}).
 */
@Route(value = "settings", layout = MainLayout.class)
public class SettingsView extends Div implements HasDynamicTitle {

    private final SettingsClient settingsClient;
    private final SystemClient systemClient;
    private final PrivacyClient privacyClient;

    private final Select<Language> uiLanguage = new Select<>();
    private final Select<Language> entryLanguage = new Select<>();
    private final Checkbox keepAudio = new Checkbox();
    private final Checkbox ttsAutoPlay = new Checkbox();
    private final TextField intervals = new TextField();
    private final Select<ThemeMode> theme = new Select<>();
    private final Button save = new Button();
    private final Div saveStatus = new Div();

    private final Checkbox includeAudio = new Checkbox();
    private final Button export = new Button();
    private final Div exportStatus = new Div();
    private final Div exportHost = new Div();

    private final TextField confirmation = new TextField();
    private final Button deleteAll = new Button();
    private final Div deleteStatus = new Div();

    private final Div profile = new Div();

    private SettingsDto loaded;

    public SettingsView(SettingsClient settingsClient, SystemClient systemClient, PrivacyClient privacyClient) {
        this.settingsClient = settingsClient;
        this.systemClient = systemClient;
        this.privacyClient = privacyClient;

        add(new H1(getTranslation("settings.title")));
        add(buildForm());
        add(buildPrivacy());
        add(buildProfile());
    }

    @Override
    public String getPageTitle() {
        return getTranslation("settings.title");
    }

    // ---- build ---------------------------------------------------------------------------------

    private Div buildForm() {
        Div section = new Div();
        section.add(new H2(getTranslation("settings.section.general")));

        uiLanguage.setLabel(getTranslation("settings.uiLanguage"));
        uiLanguage.setItems(Language.AUTO, Language.DE, Language.EN);
        uiLanguage.setItemLabelGenerator(l -> getTranslation("settings.lang." + l.code()));
        uiLanguage.setHelperText(getTranslation("settings.uiLanguage.help"));

        entryLanguage.setLabel(getTranslation("settings.entryLanguage"));
        entryLanguage.setItems(Language.AUTO, Language.DE, Language.EN);
        entryLanguage.setItemLabelGenerator(l -> getTranslation("settings.entryLang." + l.code()));
        entryLanguage.setHelperText(getTranslation("settings.entryLanguage.help"));

        theme.setLabel(getTranslation("settings.theme"));
        theme.setItems(ThemeMode.values());
        theme.setItemLabelGenerator(m -> getTranslation("settings.theme." + m.code()));
        theme.setHelperText(getTranslation("settings.theme.help"));
        theme.addValueChangeListener(e -> {
            if (e.isFromClient() && e.getValue() != null) {
                UI.getCurrent().getPage().executeJs("window.diaryTheme && window.diaryTheme.set($0)", e.getValue().code());
            }
        });

        keepAudio.setLabel(getTranslation("settings.keepAudio"));
        keepAudio.setHelperText(getTranslation("settings.keepAudio.help"));
        ttsAutoPlay.setLabel(getTranslation("settings.ttsAutoPlay"));
        ttsAutoPlay.setHelperText(getTranslation("settings.ttsAutoPlay.help"));

        intervals.setLabel(getTranslation("settings.intervals"));
        intervals.setHelperText(getTranslation("settings.intervals.help"));
        intervals.setValueChangeMode(ValueChangeMode.ON_CHANGE);
        intervals.addValueChangeListener(e -> validateIntervals());

        save.setText(getTranslation("settings.save"));
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        save.addClickListener(e -> save());
        save.setEnabled(false);

        statusRegion(saveStatus);

        Div form = new Div(uiLanguage, entryLanguage, theme, keepAudio, ttsAutoPlay, intervals, save);
        form.addClassName("diary-form");
        section.add(form, saveStatus);
        return section;
    }

    private Div buildPrivacy() {
        Div section = new Div();
        section.add(new H2(getTranslation("settings.section.privacy")));
        section.add(new Paragraph(getTranslation("settings.privacy.intro")));

        section.add(new H3(getTranslation("settings.export.title")));
        section.add(new Paragraph(getTranslation("settings.export.text")));
        includeAudio.setLabel(getTranslation("settings.export.includeAudio"));
        export.setText(getTranslation("settings.export.button"));
        export.addClickListener(e -> export());
        statusRegion(exportStatus);
        exportHost.getElement().getStyle().set("display", "none");
        Div exportForm = new Div(includeAudio, export);
        exportForm.addClassName("diary-form");
        section.add(exportForm, exportStatus, exportHost);

        Div danger = new Div();
        danger.addClassName("diary-danger");
        danger.add(new H3(getTranslation("settings.delete.title")));
        danger.add(new Paragraph(getTranslation("settings.delete.text")));
        Paragraph phrase = new Paragraph();
        phrase.add(new Span(getTranslation("settings.delete.phrasePrefix") + " "));
        com.vaadin.flow.component.html.Code code = new com.vaadin.flow.component.html.Code(DeleteAllRequest.CONFIRMATION_PHRASE);
        code.addClassName("diary-phrase");
        phrase.add(code);
        danger.add(phrase);

        confirmation.setLabel(getTranslation("settings.delete.confirmLabel"));
        confirmation.setHelperText(getTranslation("settings.delete.confirmHelp"));
        confirmation.setValueChangeMode(ValueChangeMode.EAGER);
        confirmation.setAutocomplete(com.vaadin.flow.component.textfield.Autocomplete.OFF);
        confirmation.addValueChangeListener(e -> deleteAll.setEnabled(DeleteConfirmation.isConfirmed(e.getValue())));
        deleteAll.setText(getTranslation("settings.delete.button"));
        deleteAll.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_PRIMARY);
        deleteAll.setEnabled(false);
        deleteAll.addClickListener(e -> deleteAll());
        statusRegion(deleteStatus);
        Div deleteForm = new Div(confirmation, deleteAll);
        deleteForm.addClassName("diary-form");
        danger.add(deleteForm, deleteStatus);
        section.add(danger);
        return section;
    }

    private Div buildProfile() {
        Div section = new Div();
        section.add(new H2(getTranslation("settings.section.profile")));
        section.add(new Paragraph(getTranslation("settings.profile.intro")));
        section.add(profile);
        return section;
    }

    /** Polite live region so assistive tech announces results. */
    private static void statusRegion(Div region) {
        region.getElement().setAttribute("role", "status");
        region.getElement().setAttribute("aria-live", "polite");
    }

    // ---- load ----------------------------------------------------------------------------------

    @Override
    protected void onAttach(AttachEvent event) {
        UI ui = event.getUI();
        ui.getPage().executeJs("return window.diaryTheme ? window.diaryTheme.get() : 'system'")
                .then(String.class, v -> theme.setValue(ThemeMode.fromCode(v)));
        CompletableFuture.supplyAsync(this::fetchSettings).thenAccept(r -> ui.access(() -> showSettings(r)));
        CompletableFuture.supplyAsync(this::fetchInfo).thenAccept(r -> ui.access(() -> showProfile(r)));
    }

    private Object fetchSettings() {
        try {
            return settingsClient.get();
        } catch (RuntimeException e) {
            return e;
        }
    }

    private Object fetchInfo() {
        try {
            return systemClient.info();
        } catch (RuntimeException e) {
            return e;
        }
    }

    private void showSettings(Object result) {
        if (result instanceof SettingsDto s) {
            apply(s);
            save.setEnabled(true);
        } else {
            showError(saveStatus, getTranslation("settings.error.load"));
        }
    }

    private void apply(SettingsDto s) {
        loaded = s;
        uiLanguage.setValue(s.uiLanguage() == null ? Language.AUTO : s.uiLanguage());
        entryLanguage.setValue(s.defaultEntryLanguage() == null ? Language.AUTO : s.defaultEntryLanguage());
        keepAudio.setValue(Boolean.TRUE.equals(s.keepAudio()));
        ttsAutoPlay.setValue(Boolean.TRUE.equals(s.ttsAutoPlay()));
        intervals.setValue(IntervalsParser.format(s.reviewIntervalsDays()));
        intervals.setInvalid(false);
    }

    private void showProfile(Object result) {
        profile.removeAll();
        if (!(result instanceof SystemInfoDto info)) {
            Paragraph p = new Paragraph(getTranslation("settings.profile.unavailable"));
            p.addClassName("diary-muted");
            profile.add(p);
            return;
        }
        UnorderedList list = new UnorderedList();
        list.add(new ListItem(getTranslation("settings.profile.profile", info.profile())));
        list.add(new ListItem(getTranslation(info.cloudMode() ? "settings.profile.cloud.yes" : "settings.profile.cloud.no")));
        Map<String, String> providers = info.providers() == null ? Map.of() : new TreeMap<>(info.providers());
        providers.forEach((capability, provider) ->
                list.add(new ListItem(getTranslation("settings.profile.provider", capability, provider))));
        profile.add(list);
    }

    // ---- save ----------------------------------------------------------------------------------

    private boolean validateIntervals() {
        IntervalsParser.Result r = IntervalsParser.parse(intervals.getValue());
        if (r.valid()) {
            intervals.setInvalid(false);
            intervals.setErrorMessage(null);
        } else {
            intervals.setErrorMessage(getTranslation(r.errorKey(), IntervalsParser.MIN_DAYS, IntervalsParser.MAX_DAYS,
                    IntervalsParser.MAX_COUNT));
            intervals.setInvalid(true);
        }
        return r.valid();
    }

    private void save() {
        clear(saveStatus);
        if (loaded == null || !validateIntervals()) {
            return;
        }
        SettingsDto edited = new SettingsDto(uiLanguage.getValue(), entryLanguage.getValue(), keepAudio.getValue(),
                ttsAutoPlay.getValue(), null, null, IntervalsParser.parse(intervals.getValue()).values());
        SettingsDto partial = SettingsDiff.partial(loaded, edited);
        if (SettingsDiff.isEmpty(partial)) {
            showInfo(saveStatus, getTranslation("settings.save.nothing"));
            return;
        }
        try {
            SettingsDto saved = settingsClient.update(partial);
            boolean languageChanged = partial.uiLanguage() != null;
            apply(saved == null ? edited : saved);
            showInfo(saveStatus, getTranslation("settings.save.done"));
            if (languageChanged) {
                UI ui = UI.getCurrent();
                ui.setLocale(UiLocaleResolver.resolve(loaded.uiLanguage(), ui.getLocale()));
                ui.getPage().reload();
            }
        } catch (BackendException e) {
            // 400 carries the backend's user-presentable message (e.g. invalid intervals)
            if (e.status() == 400 && partial.reviewIntervalsDays() != null) {
                intervals.setErrorMessage(e.error().message());
                intervals.setInvalid(true);
            }
            showError(saveStatus, e.error().message());
        } catch (RuntimeException e) {
            showError(saveStatus, getTranslation("settings.error.unreachable"));
        }
    }

    // ---- export --------------------------------------------------------------------------------

    private void export() {
        // The zip is streamed from the backend straight into the download: no temp file on this host.
        StreamResource resource = new StreamResource("ai-diary-export.zip",
                ExportDownload.writer(privacyClient, includeAudio.getValue()));
        resource.setContentType("application/zip");
        resource.setCacheTime(0);
        Anchor a = new Anchor(resource, "");
        a.getElement().setAttribute("download", "ai-diary-export.zip");
        a.getElement().setAttribute("tabindex", "-1");
        a.getElement().setAttribute("aria-hidden", "true");
        exportHost.removeAll();
        exportHost.add(a);
        a.getElement().executeJs("this.click()");
        showInfo(exportStatus, getTranslation("settings.export.started"));
    }

    // ---- delete everything ---------------------------------------------------------------------

    private void deleteAll() {
        clear(deleteStatus);
        if (!DeleteConfirmation.isConfirmed(confirmation.getValue())) {
            return;
        }
        try {
            DeleteAllResultDto result = privacyClient.deleteAll(DeleteConfirmation.request(confirmation.getValue()));
            confirmation.clear();
            showDeleteResult(result);
        } catch (RuntimeException e) {
            if (PrivacyClient.isNotImplemented(e)) {
                showError(deleteStatus, getTranslation("settings.notAvailable"));
            } else if (e instanceof BackendException be) {
                showError(deleteStatus, be.error().message());
            } else {
                showError(deleteStatus, getTranslation("settings.error.unreachable"));
            }
        }
    }

    private void showDeleteResult(DeleteAllResultDto result) {
        clear(deleteStatus);
        deleteStatus.addClassName("diary-status");
        Paragraph done = new Paragraph(getTranslation("settings.delete.done"));
        deleteStatus.add(done);
        Map<String, Long> deleted = result == null || result.deleted() == null ? Map.of() : new TreeMap<>(result.deleted());
        if (!deleted.isEmpty()) {
            UnorderedList list = new UnorderedList();
            list.addClassName("diary-result");
            deleted.forEach((store, n) -> list.add(new ListItem(getTranslation("settings.delete.result", store, n))));
            deleteStatus.add(list);
        }
    }

    // ---- status helpers ------------------------------------------------------------------------

    private static void clear(Div region) {
        region.removeAll();
        region.removeClassNames("diary-status", "diary-error");
        region.getElement().setAttribute("role", "status");
        region.getElement().setAttribute("aria-live", "polite");
    }

    private static void showInfo(Div region, String text) {
        clear(region);
        region.addClassName("diary-status");
        region.add(new Span(text));
    }

    /** Errors are announced assertively and carry a text prefix so colour is not the only signal. */
    private void showError(Div region, String text) {
        clear(region);
        region.addClassNames("diary-status", "diary-error");
        region.getElement().setAttribute("role", "alert");
        region.getElement().removeAttribute("aria-live");
        region.add(new Span(getTranslation("settings.error.prefix") + " " + text));
    }
}
