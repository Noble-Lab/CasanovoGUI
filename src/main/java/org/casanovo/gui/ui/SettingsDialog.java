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
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Window;
import org.casanovo.gui.core.PdvLauncher;
import org.casanovo.gui.core.PepMapLauncher;
import org.casanovo.gui.core.RawFileParserLauncher;
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
    private final Runnable onInstall; // download + install Python/Casanovo (was the File → Install menu item)

    private final TextField executableField = FxUtils.wideField();
    private final CheckBox useCondaCheck = new CheckBox("Run inside a Conda environment");
    private final TextField condaExecField = FxUtils.wideField();
    private final TextField condaEnvField = FxUtils.wideField();
    private final TextField pdvField = FxUtils.wideField();
    private final TextField pepmapField = FxUtils.wideField();
    private final TextField rawParserField = FxUtils.wideField();
    private HBox execRow; // Casanovo executable + Browse + Install; grayed out under Conda

    private final Label pdvStatusLabel = new Label();
    private final Button pdvUpgradeButton = new Button();
    private final ProgressBar pdvProgressBar = new ProgressBar();
    private String pdvTargetVersion;

    private final Label pepmapStatusLabel = new Label();
    private final Button pepmapUpgradeButton = new Button();
    private final ProgressBar pepmapProgressBar = new ProgressBar();
    private String pepmapTargetVersion;

    private final Label rawParserStatusLabel = new Label();
    private final Button rawParserUpgradeButton = new Button();
    private final ProgressBar rawParserProgressBar = new ProgressBar();
    private String rawParserTargetVersion;

    public SettingsDialog(Window owner, Settings settings, Runnable onInstall) {
        this.owner = owner;
        this.settings = settings;
        this.onInstall = onInstall;
    }

    /** Show modally; if the user clicks Save, write into the Settings and return true. */
    public boolean showAndApply() {
        executableField.setText(settings.getCasanovoExecutable());
        useCondaCheck.setSelected(settings.isUseConda());
        condaExecField.setText(settings.getCondaExecutable());
        condaEnvField.setText(settings.getCondaEnv());
        pdvField.setText(settings.getPdvJar());
        pepmapField.setText(settings.getPepmapJar());
        rawParserField.setText(settings.getRawParserPath());
        useCondaCheck.setOnAction(e -> updateEnabled());
        useCondaCheck.setMinWidth(Region.USE_PREF_SIZE); // never truncate the checkbox label

        Button installButton = new Button("Install Casanovo");
        installButton.setTooltip(new Tooltip(
                "Download a private Python runtime and install Casanovo into ~/.casanovo-gui "
                        + "(needs internet; takes a few minutes)."));
        // Each path field shares its row with a right-aligned Browse button (the executable row also
        // carries Install). The executable row is grayed out under Conda, where the configured path is
        // bypassed (conda run resolves "casanovo" inside the env).
        execRow = browseRow(executableField, FxUtils.fileButton(owner, executableField, false, null), installButton);

        FxUtils.FormGrid form = new FxUtils.FormGrid();
        form.addRow("Casanovo executable:", execRow)
                .addNote("Path to the 'casanovo' program, or just 'casanovo' if it is on your PATH. "
                        + "No Casanovo yet? Click \"Install Casanovo\".");
        form.addRow("", useCondaCheck)
                .addNote("When enabled, runs: conda run --no-capture-output -n <env> casanovo …");
        form.addRow("Conda executable:",
                        browseRow(condaExecField, FxUtils.fileButton(owner, condaExecField, false, null)))
                .addNote("Path to 'conda' (or 'mamba'), or just 'conda' if on your PATH.");
        form.addRow("Conda environment name:", condaEnvField);
        form.addRow("PDV jar (optional):",
                        browseRow(pdvField, FxUtils.fileButton(owner, pdvField, false, "PDV jar (*.jar)", "*.jar")))
                .addNote("Path to a PDV jar for \"Open in PDV\". Leave blank to auto-download the latest PDV.")
                .addFullWidth(buildPdvStatusRow());
        form.addRow("pepmap jar (optional):",
                        browseRow(pepmapField, FxUtils.fileButton(owner, pepmapField, false, "pepmap jar (*.jar)", "*.jar")))
                .addNote("Path to a pepmap jar for the View tab. Leave blank to auto-download the latest pepmap.")
                .addFullWidth(buildPepmapStatusRow());
        form.addRow("ThermoRawFileParser (optional):",
                        browseRow(rawParserField, FxUtils.fileButton(owner, rawParserField, false, null)))
                .addNote("Path to a ThermoRawFileParser executable, used to convert Thermo .raw files to "
                        + "mzML before Sequence/DB Search runs. Leave blank to auto-download the latest release.")
                .addFullWidth(buildRawParserStatusRow());
        updateEnabled();

        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle("Casanovo Settings");
        dialog.setHeaderText("How should the GUI run Casanovo?");
        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(form.getGrid());
        dialog.getDialogPane().setMinWidth(700); // a bit wider, with room for the executable + Install row

        dialog.getDialogPane().lookupButton(saveType).addEventFilter(
                javafx.event.ActionEvent.ACTION, evt -> {
                    String err = validate();
                    if (err != null) {
                        new Alert(Alert.AlertType.WARNING, err).showAndWait();
                        evt.consume();
                    }
                });

        // Install Casanovo: close this dialog, then run the install so its progress shows in the main
        // window's console/status (the dialog is modal, so it would otherwise hide that progress).
        installButton.setDisable(onInstall == null);
        installButton.setOnAction(e -> {
            dialog.close();
            if (onInstall != null) {
                Platform.runLater(onInstall);
            }
        });

        checkPdvVersionAsync();
        checkPepmapVersionAsync();
        checkRawParserVersionAsync();
        ButtonType result = dialog.showAndWait().orElse(ButtonType.CANCEL);
        if (result == saveType) {
            settings.setCasanovoExecutable(executableField.getText().trim());
            settings.setUseConda(useCondaCheck.isSelected());
            settings.setCondaExecutable(condaExecField.getText().trim());
            settings.setCondaEnv(condaEnvField.getText().trim());
            settings.setPdvJar(pdvField.getText().trim());
            settings.setPepmapJar(pepmapField.getText().trim());
            settings.setRawParserPath(rawParserField.getText().trim());
            settings.save();
            return true;
        }
        return false;
    }

    private void updateEnabled() {
        boolean on = useCondaCheck.isSelected();
        condaExecField.setDisable(!on);
        condaEnvField.setDisable(!on);
        if (execRow != null) {
            execRow.setDisable(on); // executable/Browse/Install are unused when running inside a Conda env
        }
    }

    /** A field that fills the row, with its trailing button(s) right-aligned at the row's end. */
    private static HBox browseRow(TextField field, Button... trailing) {
        HBox.setHgrow(field, Priority.ALWAYS);
        for (Button b : trailing) {
            b.setMinWidth(Region.USE_PREF_SIZE); // the growing field must not squeeze the button text
        }
        HBox box = new HBox(6, field);
        box.getChildren().addAll(trailing);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
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
        String configuredJar = pdvField.getText().trim();
        boolean configured = !configuredJar.isEmpty();
        pdvStatusLabel.setText("Checking for the latest PDV…");
        Thread t = new Thread(() -> {
            Optional<String> latest = PdvLauncher.latestUsableVersion();
            // "Current" PDV = the configured jar's version when one is set, else the cached download.
            Optional<String> current = configured
                    ? PdvLauncher.versionOfJarPath(configuredJar)
                    : PdvLauncher.installedVersion();
            Platform.runLater(() -> applyPdvStatus(latest, current, configured));
        }, "pdv-version-check");
        t.setDaemon(true);
        t.start();
    }

    private void applyPdvStatus(Optional<String> latest, Optional<String> current, boolean configured) {
        if (latest.isEmpty()) {
            pdvStatusLabel.setText(configured
                    ? "Using the configured jar above (couldn't check for a newer PDV — offline?)."
                    : "Could not check for the latest PDV (offline?).");
            hideUpgrade();
            return;
        }
        String latestV = latest.get();
        pdvTargetVersion = latestV;
        if (configured) {
            // A configured jar overrides any download, so report status but never offer an auto-download.
            hideUpgrade();
            if (current.isPresent() && UpdateChecker.isNewer(latestV, current.get())) {
                pdvStatusLabel.setText("A newer PDV is available: v" + latestV + " (configured jar is v"
                        + current.get() + "). Clear the jar field above to auto-download it.");
            } else if (current.isPresent()) {
                pdvStatusLabel.setText("Configured jar (v" + current.get()
                        + ") is up to date with the latest public PDV (v" + latestV + ").");
            } else {
                pdvStatusLabel.setText("Using the configured jar above (auto-download disabled). "
                        + "Latest public PDV: v" + latestV + ".");
            }
            return;
        }
        if (current.isEmpty()) {
            pdvStatusLabel.setText("Latest PDV: v" + latestV + " — downloaded on first \"Open in PDV\".");
            showUpgrade("Download v" + latestV);
        } else if (UpdateChecker.isNewer(latestV, current.get())) {
            pdvStatusLabel.setText("New PDV available: v" + latestV + " (you have v" + current.get() + ").");
            showUpgrade("Upgrade to v" + latestV);
        } else {
            pdvStatusLabel.setText("PDV v" + current.get() + " is up to date.");
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
        String configuredJar = pepmapField.getText().trim();
        boolean configured = !configuredJar.isEmpty();
        pepmapStatusLabel.setText("Checking for the latest pepmap…");
        Thread t = new Thread(() -> {
            Optional<String> latest = PepMapLauncher.latestUsableVersion();
            // "Current" pepmap = the configured jar's version when one is set, else the cached download.
            Optional<String> current = configured
                    ? PepMapLauncher.versionOfJarPath(configuredJar)
                    : PepMapLauncher.installedVersion();
            Platform.runLater(() -> applyPepmapStatus(latest, current, configured));
        }, "pepmap-version-check");
        t.setDaemon(true);
        t.start();
    }

    private void applyPepmapStatus(Optional<String> latest, Optional<String> current, boolean configured) {
        if (latest.isEmpty()) {
            // No pepmap release published yet (404) or offline: auto-download can't work.
            if (configured) {
                pepmapStatusLabel.setText("Using the configured jar above "
                        + "(couldn't check for a newer pepmap — offline or no release yet).");
            } else if (current.isPresent()) {
                pepmapStatusLabel.setText("Using cached pepmap v" + current.get()
                        + " (could not check for a newer release).");
            } else {
                pepmapStatusLabel.setText("No pepmap release available to download yet — "
                        + "set a local pepmap jar above for the View tab.");
            }
            hidePepmapUpgrade();
            return;
        }
        String latestV = latest.get();
        pepmapTargetVersion = latestV;
        if (configured) {
            // A configured jar overrides any download, so report status but never offer an auto-download.
            hidePepmapUpgrade();
            if (current.isPresent() && UpdateChecker.isNewer(latestV, current.get())) {
                pepmapStatusLabel.setText("A newer pepmap is available: v" + latestV + " (configured jar is v"
                        + current.get() + "). Clear the jar field above to auto-download it.");
            } else if (current.isPresent()) {
                pepmapStatusLabel.setText("Configured jar (v" + current.get()
                        + ") is up to date with the latest public pepmap (v" + latestV + ").");
            } else {
                pepmapStatusLabel.setText("Using the configured jar above (auto-download disabled). "
                        + "Latest public pepmap: v" + latestV + ".");
            }
            return;
        }
        if (current.isEmpty()) {
            pepmapStatusLabel.setText("Latest pepmap: v" + latestV + " — downloaded on first mapping run.");
            showPepmapUpgrade("Download v" + latestV);
        } else if (UpdateChecker.isNewer(latestV, current.get())) {
            pepmapStatusLabel.setText("New pepmap available: v" + latestV + " (you have v" + current.get() + ").");
            showPepmapUpgrade("Upgrade to v" + latestV);
        } else {
            pepmapStatusLabel.setText("pepmap v" + current.get() + " is up to date.");
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

    // ---- ThermoRawFileParser version status + one-click download -----------

    private HBox buildRawParserStatusRow() {
        rawParserStatusLabel.getStyleClass().add("text-muted");
        rawParserStatusLabel.setStyle("-fx-font-style: italic;");
        rawParserStatusLabel.setWrapText(true);
        rawParserStatusLabel.setMaxWidth(Double.MAX_VALUE);
        rawParserProgressBar.setPrefWidth(140);
        rawParserProgressBar.setVisible(false);
        rawParserProgressBar.setManaged(false);
        rawParserUpgradeButton.setOnAction(e -> upgradeRawParser());
        hideRawParserUpgrade();
        HBox row = new HBox(8, rawParserStatusLabel, rawParserProgressBar, rawParserUpgradeButton);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(rawParserStatusLabel, Priority.ALWAYS);
        return row;
    }

    /** Look up the latest ThermoRawFileParser (off the FX thread) and reflect it in the status row. */
    private void checkRawParserVersionAsync() {
        String configuredExe = rawParserField.getText().trim();
        boolean configured = !configuredExe.isEmpty();
        rawParserStatusLabel.setText("Checking for the latest ThermoRawFileParser…");
        Thread t = new Thread(() -> {
            Optional<String> latest = RawFileParserLauncher.latestUsableVersion();
            // "Current" = the configured executable's version when one is set, else the cached download.
            Optional<String> current = configured
                    ? RawFileParserLauncher.versionOfExePath(configuredExe)
                    : RawFileParserLauncher.installedVersion();
            Platform.runLater(() -> applyRawParserStatus(latest, current, configured));
        }, "rawparser-version-check");
        t.setDaemon(true);
        t.start();
    }

    private void applyRawParserStatus(Optional<String> latest, Optional<String> current, boolean configured) {
        if (latest.isEmpty()) {
            if (configured) {
                rawParserStatusLabel.setText("Using the configured executable above "
                        + "(couldn't check for a newer ThermoRawFileParser — offline?).");
            } else if (current.isPresent()) {
                rawParserStatusLabel.setText("Using cached ThermoRawFileParser v" + current.get()
                        + " (could not check for a newer release).");
            } else {
                rawParserStatusLabel.setText("Could not check for the latest ThermoRawFileParser (offline?).");
            }
            hideRawParserUpgrade();
            return;
        }
        String latestV = latest.get();
        rawParserTargetVersion = latestV;
        if (configured) {
            // A configured executable overrides any download, so report status but never offer auto-download.
            hideRawParserUpgrade();
            rawParserStatusLabel.setText("Using the configured executable above (auto-download disabled). "
                    + "Latest public ThermoRawFileParser: v" + latestV + ".");
            return;
        }
        if (current.isEmpty()) {
            rawParserStatusLabel.setText("Latest ThermoRawFileParser: v" + latestV
                    + " — downloaded on first .raw conversion.");
            showRawParserUpgrade("Download v" + latestV);
        } else if (UpdateChecker.isNewer(latestV, current.get())) {
            rawParserStatusLabel.setText("New ThermoRawFileParser available: v" + latestV
                    + " (you have v" + current.get() + ").");
            showRawParserUpgrade("Upgrade to v" + latestV);
        } else {
            rawParserStatusLabel.setText("ThermoRawFileParser v" + current.get() + " is up to date.");
            hideRawParserUpgrade();
        }
    }

    private void upgradeRawParser() {
        hideRawParserUpgrade();
        rawParserProgressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        rawParserProgressBar.setVisible(true);
        rawParserProgressBar.setManaged(true);
        rawParserStatusLabel.setText("Downloading ThermoRawFileParser"
                + (rawParserTargetVersion == null ? "" : " v" + rawParserTargetVersion) + "…");
        Thread t = new Thread(() -> {
            try {
                java.nio.file.Path exe = RawFileParserLauncher.downloadAndExtract(
                        msg -> {
                            if (msg != null && msg.startsWith("Extracting")) {
                                Platform.runLater(() -> {
                                    rawParserStatusLabel.setText("Extracting ThermoRawFileParser…");
                                    rawParserProgressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                                });
                            }
                        },
                        frac -> Platform.runLater(() -> rawParserProgressBar.setProgress(
                                frac < 0 ? ProgressBar.INDETERMINATE_PROGRESS : frac)));
                Optional<String> now = RawFileParserLauncher.installedVersion();
                Platform.runLater(() -> {
                    rawParserProgressBar.setVisible(false);
                    rawParserProgressBar.setManaged(false);
                    // Show where it landed WITHOUT pinning the field, so auto-download stays on.
                    rawParserStatusLabel.setText("ThermoRawFileParser v" + now.orElse(rawParserTargetVersion)
                            + " installed: " + exe);
                    hideRawParserUpgrade();
                });
            } catch (Exception ex) {
                String m = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                Platform.runLater(() -> {
                    rawParserProgressBar.setVisible(false);
                    rawParserProgressBar.setManaged(false);
                    rawParserStatusLabel.setText("Download failed: " + m);
                    showRawParserUpgrade(rawParserTargetVersion == null
                            ? "Retry download" : "Retry v" + rawParserTargetVersion);
                });
            }
        }, "rawparser-upgrade");
        t.setDaemon(true);
        t.start();
    }

    private void showRawParserUpgrade(String text) {
        rawParserUpgradeButton.setText(text);
        rawParserUpgradeButton.setDisable(false);
        rawParserUpgradeButton.setVisible(true);
        rawParserUpgradeButton.setManaged(true);
    }

    private void hideRawParserUpgrade() {
        rawParserUpgradeButton.setVisible(false);
        rawParserUpgradeButton.setManaged(false);
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
