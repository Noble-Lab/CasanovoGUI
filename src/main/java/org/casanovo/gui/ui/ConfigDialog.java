package org.casanovo.gui.ui;

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
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.casanovo.gui.core.CasanovoConfig;
import org.casanovo.gui.core.ConfigField;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
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
                form.addRow(f.getLabel() + ":", editor);
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
        Tooltip tip = (f.getDescription() == null || f.getDescription().isEmpty())
                ? null : new Tooltip(f.getDescription());
        switch (f.getType()) {
            case BOOL: {
                CheckBox cb = new CheckBox();
                cb.setSelected(f.getValue().trim().equalsIgnoreCase("true"));
                cb.setTooltip(tip);
                return cb;
            }
            case CHOICE: {
                ComboBox<String> combo = new ComboBox<>();
                if (f.getChoices() != null) {
                    combo.getItems().addAll(f.getChoices());
                }
                combo.getSelectionModel().select(f.getValue());
                combo.setTooltip(tip);
                return combo;
            }
            case TEXT_BLOCK: {
                TextArea area = new TextArea(f.getValue());
                area.setStyle("-fx-font-family: 'Consolas', 'Menlo', 'DejaVu Sans Mono', 'Courier New', monospace; -fx-font-size: 13px;");
                area.setPrefRowCount(14);
                area.setTooltip(tip);
                return area;
            }
            default: {
                TextField tf = new TextField(f.getValue());
                tf.setTooltip(tip);
                return tf;
            }
        }
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
                ok.initOwner(owner);
                ok.showAndWait();
            } catch (IOException ex) {
                Alert err = new Alert(Alert.AlertType.ERROR, "Failed to save: " + ex.getMessage(),
                        ButtonType.OK);
                err.initOwner(owner);
                err.showAndWait();
            }
        }
    }
}
