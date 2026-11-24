package dev.rabauer.ai.diary.views.settings;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Section;
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
import dev.rabauer.ai.diary.shell.DiaryIcons;
import dev.rabauer.ai.diary.shell.MainLayout;
import dev.rabauer.ai.diary.shell.ThemeMode;
import dev.rabauer.ai.diary.shell.UiLocaleResolver;

import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * Settings (SPEC section 12): language, voice, audio retention, review intervals, appearance, privacy
 * (export, delete everything) and read-only profile info. Form logic lives in plain classes
 * ({@link IntervalsParser}, {@link SettingsDiff}, {@link DeleteConfirmation}).
 */
@Route(value = "settings", layout = MainLayout.class)
@CssImport("./settings/settings.css")
public class SettingsView extends Div implements HasDynamicTitle {

    private final SettingsClient settingsClient;
    private final SystemClient systemClient;
    private final PrivacyClient privacyClient;

    private final Select<Language> uiLanguage = new Select<>();
    private final Select<Language> entryLanguage = new Select<>();
    private final Checkbox keepAudio = new Checkbox();
    private final Checkbox ttsAutoPlay = new Checkbox();
    private final TextField intervals = new TextField();
    private final Map<ThemeMode, NativeButton> themeButtons = new EnumMap<>(ThemeMode.class);
    private ThemeMode themeMode = ThemeMode.SYSTEM;
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

        addClassName("settings-view");
        add(new H1(getTranslation("settings.title")));
        Paragraph intro = new Paragraph(getTranslation("settings.intro"));
        intro.addClassName("diary-muted");
        add(intro);
        buildFields();
        add(card("settings.section.appearance", buildAppearance()));
        add(card("settings.section.language", new Div(uiLanguage, entryLanguage)));
        add(card("settings.section.voice", new Div(ttsAutoPlay, keepAudio)));
        add(card("settings.section.reminders", new Div(intervals)));
        add(buildSave());
        add(card("settings.section.privacy", buildPrivacy()));
        add(card("settings.section.profile", buildProfile()));
    }

    @Override
    public String getPageTitle() {
        return getTranslation("settings.title");
    }

    // ---- build ---------------------------------------------------------------------------------

    /** A grouped settings card: h2 heading plus its content, in the shared card look. */
    private Section card(String headingKey, Div content) {
        Section card = new Section();
        card.addClassNames("settings-card", "diary-card");
        String id = headingKey.replace('.', '-');
        H2 heading = new H2(getTranslation(headingKey));
        heading.setId(id);
        card.getElement().setAttribute("aria-labelledby", id);
        content.addClassName("settings-fields");
        card.add(heading, content);
        return card;
    }

    private void buildFields() {
        uiLanguage.setLabel(getTranslation("settings.uiLanguage"));
        uiLanguage.setItems(Language.AUTO, Language.DE, Language.EN);
        uiLanguage.setItemLabelGenerator(l -> getTranslation("settings.lang." + l.code()));
        uiLanguage.setHelperText(getTranslation("settings.uiLanguage.help"));

        entryLanguage.setLabel(getTranslation("settings.entryLanguage"));
        entryLanguage.setItems(Language.AUTO, Language.DE, Language.EN);
        entryLanguage.setItemLabelGenerator(l -> getTranslation("settings.entryLang." + l.code()));
        entryLanguage.setHelperText(getTranslation("settings.entryLanguage.help"));

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
    }

    /** Theme as a segmented control of real buttons (aria-pressed); applied immediately, stored in this browser only. */
    private Div buildAppearance() {
        Span label = new Span(getTranslation("settings.theme"));
        label.setId("settings-theme-label");
        label.addClassName("settings-label");
        Div group = new Div();
        group.addClassName("settings-seg");
        group.getElement().setAttribute("role", "group");
        group.getElement().setAttribute("aria-labelledby", "settings-theme-label");
        for (ThemeMode mode : ThemeMode.values()) {
            NativeButton button = new NativeButton();
            button.getElement().setAttribute("type", "button");
            button.add(DiaryIcons.create(switch (mode) {
                case LIGHT -> "sun";
                case DARK -> "moon";
                default -> "cog";
            }), new Span(getTranslation("settings.theme." + mode.code())));
            button.addClickListener(e -> {
                setThemeMode(mode);
                UI.getCurrent().getPage().executeJs("window.diaryTheme && window.diaryTheme.set($0)", mode.code());
            });
            themeButtons.put(mode, button);
            group.add(button);
        }
        setThemeMode(themeMode);
        Span help = new Span(getTranslation("settings.theme.help"));
        help.addClassName("diary-muted");
        return new Div(label, group, help);
    }

    private void setThemeMode(ThemeMode mode) {
        themeMode = mode;
        themeButtons.forEach((m, b) -> b.getElement().setAttribute("aria-pressed", String.valueOf(m == mode)));
    }

    private Div buildSave() {
        Div row = new Div(save, saveStatus);
        row.addClassName("settings-save");
        return row;
    }

    private Div buildPrivacy() {
        Div section = new Div();
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
        export.addThemeVariants(ButtonVariant.LUMO_CONTRAST);
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
                .then(String.class, v -> setThemeMode(ThemeMode.fromCode(v)));
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
