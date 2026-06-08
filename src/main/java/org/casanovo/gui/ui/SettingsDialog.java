package org.casanovo.gui.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextField;
import javafx.stage.Window;
import org.casanovo.gui.core.Settings;

/**
 * Dialog for configuring how the GUI locates and launches Casanovo: the
 * executable path, and an optional Conda environment to run inside.
 *
 * <p>Returns {@code true} from {@link #showAndApply()} when the user saved.</p>
 */
public class SettingsDialog {

    private final Settings settings;
    private final Window owner;

    private final TextField executableField = FxUtils.wideField();
    private final CheckBox useCondaCheck = new CheckBox("Run inside a Conda environment");
    private final TextField condaExecField = FxUtils.wideField();
    private final TextField condaEnvField = FxUtils.wideField();
    private final TextField pdvField = FxUtils.wideField();

    public SettingsDialog(Window owner, Settings settings) {
        this.owner = owner;
        this.settings = settings;
    }

    /** Show modally; if the user clicks Save, write into the Settings and return true. */
    public boolean showAndApply() {
        executableField.setText(settings.getCasanovoExecutable());
        useCondaCheck.setSelected(settings.isUseConda());
        condaExecField.setText(settings.getCondaExecutable());
        condaEnvField.setText(settings.getCondaEnv());
        pdvField.setText(settings.getPdvJar());
        updateEnabled();
        useCondaCheck.setOnAction(e -> updateEnabled());

        FxUtils.FormGrid form = new FxUtils.FormGrid();
        form.addRow("Casanovo executable:", executableField,
                        FxUtils.fileButton(owner, executableField, false, null))
                .addNote("Path to the 'casanovo' program, or just 'casanovo' if it is on your PATH.");
        form.addRow("", useCondaCheck)
                .addNote("When enabled, runs: conda run --no-capture-output -n <env> casanovo …");
        form.addRow("Conda executable:", condaExecField,
                        FxUtils.fileButton(owner, condaExecField, false, null))
                .addNote("Path to 'conda' (or 'mamba'), or just 'conda' if on your PATH.");
        form.addRow("Conda environment name:", condaEnvField);
        form.addRow("PDV jar (optional):", pdvField,
                        FxUtils.fileButton(owner, pdvField, false, "PDV jar (*.jar)", "*.jar"))
                .addNote("Path to a PDV jar for \"Open in PDV\". Leave blank to auto-download the latest PDV.");

        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle("Casanovo Settings");
        dialog.setHeaderText("How should the GUI run Casanovo?");
        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(form.getGrid());

        dialog.getDialogPane().lookupButton(saveType).addEventFilter(
                javafx.event.ActionEvent.ACTION, evt -> {
                    String err = validate();
                    if (err != null) {
                        new Alert(Alert.AlertType.WARNING, err).showAndWait();
                        evt.consume();
                    }
                });

        ButtonType result = dialog.showAndWait().orElse(ButtonType.CANCEL);
        if (result == saveType) {
            settings.setCasanovoExecutable(executableField.getText().trim());
            settings.setUseConda(useCondaCheck.isSelected());
            settings.setCondaExecutable(condaExecField.getText().trim());
            settings.setCondaEnv(condaEnvField.getText().trim());
            settings.setPdvJar(pdvField.getText().trim());
            settings.save();
            return true;
        }
        return false;
    }

    private void updateEnabled() {
        boolean on = useCondaCheck.isSelected();
        condaExecField.setDisable(!on);
        condaEnvField.setDisable(!on);
    }

    private String validate() {
        if (executableField.getText().trim().isEmpty()) {
            return "Please provide the Casanovo executable (or 'casanovo').";
        }
        if (useCondaCheck.isSelected() && condaEnvField.getText().trim().isEmpty()) {
            return "Please provide a Conda environment name, or disable Conda execution.";
        }
        return null;
    }
}
