package org.casanovo.gui.ui;

import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.casanovo.gui.core.CasanovoConfig;
import org.casanovo.gui.core.ConfigChecks;
import org.casanovo.gui.core.ConfigField;
import org.casanovo.gui.core.ModList;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dialog exposing every Casanovo {@code config.yaml} parameter, one tab per
 * group. Edits are written back into the shared {@link CasanovoConfig} when the
 * user clicks OK. Editors are chosen by {@link ConfigField.Type}.
 *
 * <p>Returns {@code true} from {@link #showAndApply()} when the user clicked OK.</p>
 */
public class ConfigDialog {

    private final CasanovoConfig config;
    private final Window owner;
    private final Map<String, Control> editors = new LinkedHashMap<>();
    /**
     * The Parameters window itself, set once it is showing. Alerts raised from inside the dialog are
     * owned by it rather than by the window that owns it: two modals sharing an owner are not
     * guaranteed to stack in the order they were opened, and one that comes up behind the
     * Parameters window blocks input invisibly (see {@link #modListButton}).
     */
    private Window self;
    /** Tokens already unreferenced when the dialog opened; see {@link #newlyUnreferencedTokens}. */
    private List<String> unreferencedAtOpen = List.of();

    public ConfigDialog(Window owner, CasanovoConfig config) {
        this.owner = owner;
        this.config = config;
    }

    public boolean showAndApply() {
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        for (String group : CasanovoConfig.GROUP_ORDER) {
            tabPane.getTabs().add(new Tab(group, buildGroupContent(group)));
        }
        // What was already unreferenced before the user touched anything. Casanovo's shipped timsTOF
        // vocabulary defines tokens its own default mod lists don't name ("C[Cysteinyl]",
        // "[Carbamyl][Ammonia-loss]-"), so without this every timsTOF session would raise the
        // unreferenced-token notice on a config nobody edited. Only what this session made
        // unreferenced is worth saying.
        unreferencedAtOpen = unreferencedTokens();

        Button reset = new Button("Reset to defaults");
        reset.setOnAction(e -> onReset());
        Button saveFile = new Button("Save to file");
        saveFile.setOnAction(e -> onSaveToFile());
        HBox toolbar = new HBox(8, reset, saveFile);
        toolbar.setPadding(new Insets(0, 0, 8, 0));

        Label header = new Label("These values are written to a YAML config and passed to Casanovo "
                + "via --config. Blank = default/None.");
        header.setWrapText(true);
        header.setPadding(new Insets(0, 0, 8, 0));

        BorderPane content = new BorderPane();
        content.setTop(new VBox(header, toolbar));
        content.setCenter(tabPane);
        content.setPadding(new Insets(8));
        content.setPrefSize(720, 580);

        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle("Casanovo Parameters");
        dialog.setResizable(true);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(content);

        // Casanovo checks the types of these values and nothing else, so a value that is unreadable,
        // or readable but impossible, reaches the run and fails there with no hint of which setting
        // caused it. Checked here rather than only in the editors, because a field can also be typed
        // into directly, which their own guards never see.
        dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(ActionEvent.ACTION, e -> {
            if (!confirmValues()) {
                e.consume();
            }
        });

        dialog.setOnShown(e -> self = dialog.getDialogPane().getScene().getWindow());
        ButtonType result = dialog.showAndWait().orElse(ButtonType.CANCEL);
        if (result == ButtonType.OK) {
            readEditorsInto();
            return true;
        }
        return false;
    }

    private Node buildGroupContent(String group) {
        FxUtils.FormGrid form = new FxUtils.FormGrid();
        for (ConfigField f : config.getFieldsForGroup(group)) {
            Control editor = createEditor(f);
            editors.put(f.getKey(), editor);
            if (f.getType() == ConfigField.Type.TEXT_BLOCK) {
                form.addNote(f.getDescription());
                form.addRow(f.getLabel() + ":", editor);
            } else {
                Button editList = f.getType() == ConfigField.Type.MOD_LIST
                        ? modListButton(f, (TextField) editor) : null;
                form.addRow(f.getLabel() + ":", editor, editList);
                if (f.getDescription() != null && !f.getDescription().isEmpty()) {
                    form.addNote(f.getDescription());
                }
            }
        }
        ScrollPane scroll = new ScrollPane(form.getGrid());
        scroll.setFitToWidth(true);
        return scroll;
    }

    private Control createEditor(ConfigField f) {
        // The field's description is already shown as an always-visible note (see buildGroupContent),
        // so no hover tooltip is added here — it would just duplicate that text.
        switch (f.getType()) {
            case BOOL: {
                CheckBox cb = new CheckBox();
                cb.setSelected(f.getValue().trim().equalsIgnoreCase("true"));
                return cb;
            }
            case CHOICE: {
                ComboBox<String> combo = new ComboBox<>();
                if (f.getChoices() != null) {
                    combo.getItems().addAll(f.getChoices());
                }
                combo.getSelectionModel().select(f.getValue());
                return combo;
            }
            case TEXT_BLOCK: {
                TextArea area = new TextArea(f.getValue());
                area.setStyle("-fx-font-family: 'Consolas', 'Menlo', 'DejaVu Sans Mono', 'Courier New', monospace; -fx-font-size: 13px;");
                area.setPrefRowCount(14);
                return area;
            }
            default: {
                TextField tf = new TextField(f.getValue());
                return tf;
            }
        }
    }

    /**
     * "Edit list..." button for a {@link ConfigField.Type#MOD_LIST} field: opens {@link ModListEditor}
     * pre-populated from the current "Residues & modifications" editor (every tab is built before the
     * dialog is shown, so it reflects edits made earlier in this same dialog session, not just the
     * saved value).
     */
    private Button modListButton(ConfigField f, TextField target) {
        Button b = new Button("Edit list...");
        b.setOnAction(e -> {
            // Owned by this dialog's window, not by the window that owns this dialog: two modals
            // sharing an owner are not guaranteed to stack in the order they were opened, and a
            // mod-list dialog that opens behind the Parameters window blocks input invisibly.
            ModListEditor.edit(target.getScene().getWindow(), f.getLabel(), residuesText(), target.getText())
                    .ifPresent(target::setText);
        });
        return b;
    }

    /**
     * Current text of the "Residues & modifications" editor. Blank when that field has no editor —
     * only reachable if its group ever leaves {@link CasanovoConfig#GROUP_ORDER}, and a blank
     * vocabulary is the answer the mod-list checks already handle.
     */
    private String residuesText() {
        return editors.get("residues") instanceof TextArea area ? area.getText() : "";
    }

    /**
     * The label of the first {@code MOD_LIST} field the user left with no entries at all, or null.
     * A field with no editor is not "emptied by the user" and is skipped — reporting it would block
     * OK with nothing the user could do about it.
     */
    private String emptyModList() {
        for (ConfigField f : config.getFields()) {
            if (f.getType() == ConfigField.Type.MOD_LIST
                    && editors.get(f.getKey()) instanceof TextField tf
                    && ModList.splitEntries(tf.getText()).isEmpty()) {
                return f.getLabel();
            }
        }
        return null;
    }

    /**
     * Mod-list entries Casanovo could not resolve — not an 'aa:mod_residue' pair, or naming a token
     * the residues vocabulary doesn't define — one per line, or null when there are none. Reachable by
     * editing the vocabulary after the mod lists, or by typing an entry by hand, neither of which the
     * "Edit list..." picker covers.
     */
    private String badModEntries() {
        List<String> tokens = CasanovoConfig.residueTokens(residuesText());
        List<String> bad = new ArrayList<>();
        for (ConfigField f : config.getFields()) {
            if (f.getType() != ConfigField.Type.MOD_LIST) {
                continue;
            }
            for (String entry : ModList.unresolvable(modListText(f), tokens)) {
                // Quoted: a stray space is one of the things reported here, and unquoted it would be
                // invisible in the very message that exists to point it out.
                bad.add("    " + f.getLabel() + ": \"" + entry + "\"");
            }
        }
        return bad.isEmpty() ? null : String.join("\n", bad);
    }

    /**
     * Run every check on the current values and tell the caller whether to go ahead: false only when
     * a problem has no sensible interpretation. Shared by OK and "Save to file" — both hand the
     * values to Casanovo, and a check only OK performed would be one "Save to file" could walk
     * straight past.
     *
     * <p>Blocking checks first, so a value that cannot be read is reported before anything that
     * merely reads it as impossible.</p>
     */
    private boolean confirmValues() {
        Map<String, String> values = editorValues();
        return confirmTypes(values) && confirmModLists(self) && confirmSanity(values);
    }

    /**
     * Refuse values Casanovo cannot read as the field's type, or would read as something other than
     * what this dialog shows. Blocked rather than warned: saving one either fails the run with a
     * Python type error naming only the YAML key, or — for a fractional integer, which Casanovo
     * truncates — leaves the dialog describing a value the run never used.
     */
    private boolean confirmTypes(Map<String, String> values) {
        List<String> problems = ConfigChecks.typeErrors(config.getFields(), values);
        if (problems.isEmpty()) {
            return true;
        }
        Alert bad = new Alert(Alert.AlertType.ERROR,
                "These parameters cannot be used as they are:\n\n    "
                        + String.join("\n    ", problems)
                        + "\n\nCorrect them, or clear a field to leave Casanovo's own default in "
                        + "force.",
                ButtonType.OK);
        bad.setHeaderText(null);
        bad.initOwner(self);
        bad.showAndWait();
        return false;
    }

    /**
     * Report values Casanovo accepts and then cannot produce a result from — a minimum above its
     * maximum, a count below one. Warn, but let the user save: the checks encode what the settings
     * mean today, and the run is still theirs to start.
     */
    private boolean confirmSanity(Map<String, String> values) {
        List<String> problems = ConfigChecks.sanityWarnings(config.getFields(), values);
        if (problems.isEmpty()) {
            return true;
        }
        Alert warn = new Alert(Alert.AlertType.WARNING,
                "Casanovo will accept these parameters, but the run cannot return anything:\n\n    "
                        + String.join("\n    ", problems)
                        + "\n\nDepending on the setting the run either stops with a Python error or "
                        + "finishes successfully with an empty result — neither names the setting. "
                        + "Save anyway?",
                ButtonType.OK, ButtonType.CANCEL);
        warn.setHeaderText(null);
        warn.initOwner(self);
        return warn.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    /**
     * Current editor text by config key, for {@link ConfigChecks}. Switched on the field's type,
     * not on the widget class, so it reads each editor exactly where {@link #readEditorsInto}
     * writes it back: a field whose widget changes is then either updated in both or broken in
     * both, never silently dropped from the checks — a key missing here makes {@code typeErrors}
     * skip that field entirely, and the value reaches Casanovo unchecked with no symptom.
     */
    private Map<String, String> editorValues() {
        Map<String, String> values = new LinkedHashMap<>();
        for (ConfigField f : config.getFields()) {
            Control editor = editors.get(f.getKey());
            if (editor == null) {
                continue;
            }
            switch (f.getType()) {
                case BOOL:
                    // Nothing checks a boolean; recorded anyway so the map is every field that has
                    // an editor, rather than a subset each reader has to reason about.
                    values.put(f.getKey(), ((CheckBox) editor).isSelected() ? "true" : "false");
                    break;
                case CHOICE:
                    Object sel = ((ComboBox<?>) editor).getSelectionModel().getSelectedItem();
                    values.put(f.getKey(), sel == null ? "" : sel.toString());
                    break;
                case TEXT_BLOCK:
                    values.put(f.getKey(), ((TextArea) editor).getText());
                    break;
                default:
                    values.put(f.getKey(), ((TextField) editor).getText());
                    break;
            }
        }
        return values;
    }

    /**
     * Run the mod-list checks and tell the caller whether to go ahead: false only when a problem has
     * no sensible interpretation.
     */
    private boolean confirmModLists(Window self) {
        String emptied = emptyModList();
        if (emptied != null) {
            // An empty list serialises to a YAML null, and Casanovo's Config.validate_param skips a
            // null, so its own packaged default stays in force: the run does not search without
            // modifications, it searches with all of Casanovo's while this dialog shows none (verified
            // end to end against 5.2.1). Blocked rather than warned, because saving it is precisely
            // what makes the dialog stop describing the run.
            Alert empty = new Alert(Alert.AlertType.WARNING,
                    "\"" + emptied + "\" is empty, and Casanovo has no setting for \"no "
                            + "modifications\". A blank list does not turn them off — the search "
                            + "falls back to Casanovo's own default modifications, and this dialog "
                            + "would no longer show what the run uses. Restore an entry, or Cancel "
                            + "to leave the parameters unchanged.",
                    ButtonType.OK);
            empty.setHeaderText(null);
            empty.initOwner(self);
            empty.showAndWait();
            return false;
        }
        String bad = badModEntries();
        if (bad != null) {
            // Warn, but let the user save: the vocabulary this GUI knows about is not necessarily the
            // one the run will use.
            Alert warn = new Alert(Alert.AlertType.WARNING,
                    "A database search will fail on these modification entries:\n\n"
                            + bad + "\n\nEach must read 'aa:mod_residue' exactly — one colon, no "
                            + "spaces, no commas — and the mod_residue must be a token defined in "
                            + "\"Residues & modifications\". Save anyway?",
                    ButtonType.OK, ButtonType.CANCEL);
            warn.setHeaderText(null);
            warn.initOwner(self);
            if (warn.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return false;
            }
        }
        String unused = newlyUnreferencedTokens();
        if (unused != null) {
            // The other direction, and only ever an FYI: the run succeeds, it just can't match
            // peptides carrying these. Casanovo says the same thing, but not until the search has
            // already started — here it is still one field away from being fixed.
            Alert info = new Alert(Alert.AlertType.INFORMATION,
                    "These modifications are defined in \"Residues & modifications\" but no mod "
                            + "list uses them:\n\n" + unused + "\n\nCasanovo will skip peptides "
                            + "carrying them. Add them to \"Allowed fixed mods\" or \"Allowed "
                            + "variable mods\" to have them searched.",
                    ButtonType.OK);
            info.setHeaderText(null);
            info.initOwner(self);
            info.showAndWait();
        }
        return true;
    }

    /**
     * Modified tokens this dialog session left unreferenced, one per line, or null when there are
     * none. Only what changed here: a token the config already failed to reference on the way in is
     * not this edit's doing, and saying so on every OK press would train the user to dismiss the
     * notice unread. Informational either way — such a token doesn't fail a run, it just means
     * peptides carrying it can never be matched. See {@link ModList#unreferencedTokens}.
     */
    private String newlyUnreferencedTokens() {
        List<String> unused = new ArrayList<>(unreferencedTokens());
        unused.removeAll(unreferencedAtOpen);
        return unused.isEmpty() ? null : "    " + String.join("\n    ", unused);
    }

    /** Modified vocabulary tokens no {@code MOD_LIST} editor currently references. */
    private List<String> unreferencedTokens() {
        List<String> csvs = new ArrayList<>();
        for (ConfigField f : config.getFields()) {
            if (f.getType() == ConfigField.Type.MOD_LIST) {
                csvs.add(modListText(f));
            }
        }
        return ModList.unreferencedTokens(residuesText(), csvs.toArray(new String[0]));
    }

    /** The text of a {@code MOD_LIST} field's editor, or blank when the field has none. */
    private String modListText(ConfigField f) {
        return editors.get(f.getKey()) instanceof TextField tf ? tf.getText() : "";
    }

    private void readEditorsInto() {
        for (ConfigField f : config.getFields()) {
            Control editor = editors.get(f.getKey());
            if (editor == null) {
                continue;
            }
            switch (f.getType()) {
                case BOOL:
                    f.setValue(((CheckBox) editor).isSelected() ? "true" : "false");
                    break;
                case CHOICE:
                    Object sel = ((ComboBox<?>) editor).getSelectionModel().getSelectedItem();
                    f.setValue(sel == null ? "" : sel.toString());
                    break;
                case TEXT_BLOCK:
                    f.setValue(((TextArea) editor).getText());
                    break;
                default:
                    f.setValue(((TextField) editor).getText());
                    break;
            }
        }
    }

    private void refreshEditorsFromModel() {
        for (ConfigField f : config.getFields()) {
            Control editor = editors.get(f.getKey());
            if (editor == null) {
                continue;
            }
            switch (f.getType()) {
                case BOOL:
                    ((CheckBox) editor).setSelected(f.getValue().trim().equalsIgnoreCase("true"));
                    break;
                case CHOICE:
                    ((ComboBox<String>) editor).getSelectionModel().select(f.getValue());
                    break;
                case TEXT_BLOCK:
                    ((TextArea) editor).setText(f.getValue());
                    break;
                default:
                    ((TextField) editor).setText(f.getValue());
                    break;
            }
        }
    }

    private void onReset() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Reset all parameters to Casanovo defaults?", ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(null);
        confirm.initOwner(owner);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            config.resetAllToDefaults();
            refreshEditorsFromModel();
        }
    }

    private void onSaveToFile() {
        // Before readEditorsInto, not after: it writes the editors into the shared config, so an empty
        // list saved from here would also outlive a subsequent Cancel and then block OK on every
        // later visit to this dialog.
        if (!confirmValues()) {
            return;
        }
        readEditorsInto();
        FileChooser chooser = new FileChooser();
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("YAML config", "*.yaml", "*.yml"));
        chooser.setInitialFileName("casanovo_config.yaml");
        File target = chooser.showSaveDialog(owner);
        if (target != null) {
            try {
                config.writeTo(target);
                Alert ok = new Alert(Alert.AlertType.INFORMATION,
                        "Saved configuration to:\n" + target.getAbsolutePath(), ButtonType.OK);
                ok.setHeaderText(null);
                ok.initOwner(self);
                ok.showAndWait();
            } catch (IOException ex) {
                Alert err = new Alert(Alert.AlertType.ERROR, "Failed to save: " + ex.getMessage(),
                        ButtonType.OK);
                err.initOwner(self);
                err.showAndWait();
            }
        }
    }
}
