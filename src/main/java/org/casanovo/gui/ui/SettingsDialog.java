package org.casanovo.gui.ui;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Window;
import org.casanovo.gui.core.PdvLauncher;
import org.casanovo.gui.core.PepMapLauncher;
import org.casanovo.gui.core.Settings;
import org.casanovo.gui.core.UpdateChecker;

import java.util.Optional;

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
    private final TextField pepmapField = FxUtils.wideField();

    private final Label pdvStatusLabel = new Label();
    private final Button pdvUpgradeButton = new Button();
    private final ProgressBar pdvProgressBar = new ProgressBar();
    private String pdvTargetVersion;

    private final Label pepmapStatusLabel = new Label();
    private final Button pepmapUpgradeButton = new Button();
    private final ProgressBar pepmapProgressBar = new ProgressBar();
    private String pepmapTargetVersion;

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
        pepmapField.setText(settings.getPepmapJar());
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
                .addNote("Path to a PDV jar for \"Open in PDV\". Leave blank to auto-download the latest PDV.")
                .addFullWidth(buildPdvStatusRow());
        form.addRow("pepmap jar (optional):", pepmapField,
                        FxUtils.fileButton(owner, pepmapField, false, "pepmap jar (*.jar)", "*.jar"))
                .addNote("Path to a pepmap jar for the Mapping tab. Leave blank to auto-download the latest pepmap.")
                .addFullWidth(buildPepmapStatusRow());

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

        checkPdvVersionAsync();
        checkPepmapVersionAsync();
        ButtonType result = dialog.showAndWait().orElse(ButtonType.CANCEL);
        if (result == saveType) {
            settings.setCasanovoExecutable(executableField.getText().trim());
            settings.setUseConda(useCondaCheck.isSelected());
            settings.setCondaExecutable(condaExecField.getText().trim());
            settings.setCondaEnv(condaEnvField.getText().trim());
            settings.setPdvJar(pdvField.getText().trim());
            settings.setPepmapJar(pepmapField.getText().trim());
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

    // ---- PDV version status + one-click upgrade ----------------------------

    private HBox buildPdvStatusRow() {
        pdvStatusLabel.getStyleClass().add("text-muted");
        pdvStatusLabel.setStyle("-fx-font-style: italic;");
        pdvStatusLabel.setWrapText(true);
        pdvStatusLabel.setMaxWidth(Double.MAX_VALUE);
        pdvProgressBar.setPrefWidth(140);
        pdvProgressBar.setVisible(false);
        pdvProgressBar.setManaged(false);
        pdvUpgradeButton.setOnAction(e -> upgradePdv());
        hideUpgrade();
        HBox row = new HBox(8, pdvStatusLabel, pdvProgressBar, pdvUpgradeButton);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(pdvStatusLabel, Priority.ALWAYS);
        return row;
    }

    /** Look up the latest PDV (off the FX thread) and reflect it in the status row. */
    private void checkPdvVersionAsync() {
        // The auto-download only applies when no explicit jar is configured.
        if (!pdvField.getText().trim().isEmpty()) {
            pdvStatusLabel.setText("Using the configured jar above (auto-download disabled).");
            hideUpgrade();
            return;
        }
        pdvStatusLabel.setText("Checking for the latest PDV…");
        Thread t = new Thread(() -> {
            Optional<String> latest = PdvLauncher.latestUsableVersion();
            Optional<String> installed = PdvLauncher.installedVersion();
            Platform.runLater(() -> applyPdvStatus(latest, installed));
        }, "pdv-version-check");
        t.setDaemon(true);
        t.start();
    }

    private void applyPdvStatus(Optional<String> latest, Optional<String> installed) {
        if (latest.isEmpty()) {
            pdvStatusLabel.setText("Could not check for the latest PDV (offline?).");
            hideUpgrade();
            return;
        }
        String latestV = latest.get();
        pdvTargetVersion = latestV;
        if (installed.isEmpty()) {
            pdvStatusLabel.setText("Latest PDV: v" + latestV + " — downloaded on first \"Open in PDV\".");
            showUpgrade("Download v" + latestV);
        } else if (UpdateChecker.isNewer(latestV, installed.get())) {
            pdvStatusLabel.setText("New PDV available: v" + latestV + " (you have v" + installed.get() + ").");
            showUpgrade("Upgrade to v" + latestV);
        } else {
            pdvStatusLabel.setText("PDV v" + installed.get() + " is up to date.");
            hideUpgrade();
        }
    }

    private void upgradePdv() {
        hideUpgrade();
        pdvProgressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        pdvProgressBar.setVisible(true);
        pdvProgressBar.setManaged(true);
        pdvStatusLabel.setText("Downloading PDV"
                + (pdvTargetVersion == null ? "" : " v" + pdvTargetVersion) + "…");
        Thread t = new Thread(() -> {
            try {
                java.nio.file.Path jar = PdvLauncher.downloadPdv(
                        msg -> {
                            if (msg != null && msg.startsWith("Extracting")) {
                                Platform.runLater(() -> {
                                    pdvStatusLabel.setText("Extracting PDV…");
                                    pdvProgressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                                });
                            }
                        },
                        frac -> Platform.runLater(() -> pdvProgressBar.setProgress(
                                frac < 0 ? ProgressBar.INDETERMINATE_PROGRESS : frac)));
                Optional<String> now = PdvLauncher.installedVersion();
                Platform.runLater(() -> {
                    pdvProgressBar.setVisible(false);
                    pdvProgressBar.setManaged(false);
                    // Show where it landed WITHOUT pinning the field, so auto-download stays on.
                    pdvStatusLabel.setText("PDV v" + now.orElse(pdvTargetVersion) + " installed: " + jar);
                    hideUpgrade();
                });
            } catch (Exception ex) {
                String m = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                Platform.runLater(() -> {
                    pdvProgressBar.setVisible(false);
                    pdvProgressBar.setManaged(false);
                    pdvStatusLabel.setText("Download failed: " + m);
                    showUpgrade(pdvTargetVersion == null ? "Retry download" : "Retry v" + pdvTargetVersion);
                });
            }
        }, "pdv-upgrade");
        t.setDaemon(true);
        t.start();
    }

    private void showUpgrade(String text) {
        pdvUpgradeButton.setText(text);
        pdvUpgradeButton.setDisable(false);
        pdvUpgradeButton.setVisible(true);
        pdvUpgradeButton.setManaged(true);
    }

    private void hideUpgrade() {
        pdvUpgradeButton.setVisible(false);
        pdvUpgradeButton.setManaged(false);
    }

    // ---- pepmap version status + one-click download ------------------------

    private HBox buildPepmapStatusRow() {
        pepmapStatusLabel.getStyleClass().add("text-muted");
        pepmapStatusLabel.setStyle("-fx-font-style: italic;");
        pepmapStatusLabel.setWrapText(true);
        pepmapStatusLabel.setMaxWidth(Double.MAX_VALUE);
        pepmapProgressBar.setPrefWidth(140);
        pepmapProgressBar.setVisible(false);
        pepmapProgressBar.setManaged(false);
        pepmapUpgradeButton.setOnAction(e -> upgradePepmap());
        hidePepmapUpgrade();
        HBox row = new HBox(8, pepmapStatusLabel, pepmapProgressBar, pepmapUpgradeButton);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(pepmapStatusLabel, Priority.ALWAYS);
        return row;
    }

    /** Look up the latest pepmap (off the FX thread) and reflect it in the status row. */
    private void checkPepmapVersionAsync() {
        // The auto-download only applies when no explicit jar is configured.
        if (!pepmapField.getText().trim().isEmpty()) {
            pepmapStatusLabel.setText("Using the configured jar above (auto-download disabled).");
            hidePepmapUpgrade();
            return;
        }
        pepmapStatusLabel.setText("Checking for the latest pepmap…");
        Thread t = new Thread(() -> {
            Optional<String> latest = PepMapLauncher.latestUsableVersion();
            Optional<String> installed = PepMapLauncher.installedVersion();
            Platform.runLater(() -> applyPepmapStatus(latest, installed));
        }, "pepmap-version-check");
        t.setDaemon(true);
        t.start();
    }

    private void applyPepmapStatus(Optional<String> latest, Optional<String> installed) {
        if (latest.isEmpty()) {
            // No pepmap release published yet (404) or offline: auto-download can't work, so point
            // the user at the jar field above instead of offering a download that would fail.
            if (installed.isPresent()) {
                pepmapStatusLabel.setText("Using cached pepmap v" + installed.get()
                        + " (could not check for a newer release).");
            } else {
                pepmapStatusLabel.setText("No pepmap release available to download yet — "
                        + "set a local pepmap jar above for the Mapping tab.");
            }
            hidePepmapUpgrade();
            return;
        }
        String latestV = latest.get();
        pepmapTargetVersion = latestV;
        if (installed.isEmpty()) {
            pepmapStatusLabel.setText("Latest pepmap: v" + latestV + " — downloaded on first mapping run.");
            showPepmapUpgrade("Download v" + latestV);
        } else if (UpdateChecker.isNewer(latestV, installed.get())) {
            pepmapStatusLabel.setText("New pepmap available: v" + latestV + " (you have v" + installed.get() + ").");
            showPepmapUpgrade("Upgrade to v" + latestV);
        } else {
            pepmapStatusLabel.setText("pepmap v" + installed.get() + " is up to date.");
            hidePepmapUpgrade();
        }
    }

    private void upgradePepmap() {
        hidePepmapUpgrade();
        pepmapProgressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        pepmapProgressBar.setVisible(true);
        pepmapProgressBar.setManaged(true);
        pepmapStatusLabel.setText("Downloading pepmap"
                + (pepmapTargetVersion == null ? "" : " v" + pepmapTargetVersion) + "…");
        Thread t = new Thread(() -> {
            try {
                java.nio.file.Path jar = PepMapLauncher.downloadPepmap(
                        msg -> { /* status is driven by the progress bar below */ },
                        frac -> Platform.runLater(() -> pepmapProgressBar.setProgress(
                                frac < 0 ? ProgressBar.INDETERMINATE_PROGRESS : frac)));
                Optional<String> now = PepMapLauncher.installedVersion();
                Platform.runLater(() -> {
                    pepmapProgressBar.setVisible(false);
                    pepmapProgressBar.setManaged(false);
                    // Show where it landed WITHOUT pinning the field, so auto-download stays on.
                    pepmapStatusLabel.setText("pepmap v" + now.orElse(pepmapTargetVersion) + " installed: " + jar);
                    hidePepmapUpgrade();
                });
            } catch (Exception ex) {
                String m = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                Platform.runLater(() -> {
                    pepmapProgressBar.setVisible(false);
                    pepmapProgressBar.setManaged(false);
                    pepmapStatusLabel.setText("Download failed: " + m);
                    showPepmapUpgrade(pepmapTargetVersion == null ? "Retry download" : "Retry v" + pepmapTargetVersion);
                });
            }
        }, "pepmap-upgrade");
        t.setDaemon(true);
        t.start();
    }

    private void showPepmapUpgrade(String text) {
        pepmapUpgradeButton.setText(text);
        pepmapUpgradeButton.setDisable(false);
        pepmapUpgradeButton.setVisible(true);
        pepmapUpgradeButton.setManaged(true);
    }

    private void hidePepmapUpgrade() {
        pepmapUpgradeButton.setVisible(false);
        pepmapUpgradeButton.setManaged(false);
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
