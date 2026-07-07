package org.casanovo.gui.ui;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.stage.Window;
import org.casanovo.gui.core.CasanovoWeights;
import org.casanovo.gui.core.TimsTof;

import java.util.List;

/**
 * Options shared by the {@code sequence}, {@code db-search}, {@code eval} and
 * {@code train} sub-commands: model weights, YAML config, output directory,
 * output root, verbosity and force-overwrite.
 */
class CommonOptions {

    /**
     * Casanovo's default model selector ({@code _DEFAULT_MODEL_ID} in casanovo.py).
     * Passing it explicitly silences the "No model was specified. Using the default
     * model 'orbitrap'." warning. {@code --model} accepts this named selector, a
     * {@code .ckpt} file, or a URL.
     */
    static final String DEFAULT_MODEL = "orbitrap";

    final TextField modelField = FxUtils.wideField();
    final TextField configField = FxUtils.wideField();
    final TextField outputDirField = FxUtils.wideField();
    final TextField outputRootField = FxUtils.wideField();
    final ComboBox<String> verbosityCombo = new ComboBox<>();
    final CheckBox forceOverwrite =
            new CheckBox("Overwrite existing output files");
    /** The "Config file" row nodes, toggled together when GUI parameters are (de)selected. */
    private Node configLabel;
    private Button configBrowseButton;
    /** The input model id the placeholder currently reflects; lets refreshModelPrompt skip a redundant cache walk. */
    private String promptModelId;

    CommonOptions() {
        verbosityCombo.getItems().addAll("(default)", "debug", "info", "warning", "error");
        verbosityCombo.getSelectionModel().select("(default)");
        // Checked by default: re-runs into the same output folder just overwrite,
        // rather than failing with "file already exists" (Casanovo's own default is off).
        forceOverwrite.setSelected(true);
    }

    void addToForm(Window owner, FxUtils.FormGrid form) {
        form.addRow("Model weights:", modelField,
                FxUtils.fileButton(owner, modelField, "model", false, "Model weights (*.ckpt)", "*.ckpt"))
                .optional("Model .ckpt (blank = auto by input format)");
        form.tooltip("Optional. Blank auto-selects the model from the input format — timsTOF for .d "
                + "folders, otherwise orbitrap — and Casanovo downloads/caches its weights. The "
                + "placeholder shows the checkpoint that will be used; or pick a specific .ckpt. (--model)");
        refreshModelPrompt(List.of()); // initial: no input yet -> the default (orbitrap) model
        form.addRow("Output directory:", outputDirField,
                        FxUtils.dirButton(owner, outputDirField, "output"))
                .required("Folder for Casanovo output")
                .tooltip("Required. Folder where Casanovo writes its output files. (--output_dir)");
        form.addRow("Output root name:", outputRootField)
                .optional("Base name for output files")
                .tooltip("Optional. Base name for the generated output files. (--output_root)");
        form.addRow("Verbosity:", verbosityCombo)
                .optional("Logging detail")
                .tooltip("Optional. Logging detail for the run. '(default)' uses Casanovo's own setting. (--verbosity)");
        form.addRow("", forceOverwrite)
                .optional("Overwrite existing output")
                .tooltip("When on, re-running into the same output folder overwrites existing "
                        + "files instead of failing. On by default. (--force_overwrite)");
    }

    /**
     * Add the external "Config file" row. Call this LAST when building a pane's form (after any
     * notes): a GridPane drops a trailing all-hidden row cleanly, but keeps the vgap around a
     * hidden middle row — so making this the final row lets it collapse without leaving a gap. The
     * row is shown only when "Use GUI parameters" is off, where the config file is then required.
     */
    void addConfigRow(Window owner, FxUtils.FormGrid form) {
        Button configBrowse = FxUtils.fileButton(owner, configField, "config", false, "YAML config", "*.yaml", "*.yml");
        form.addRow("Config file:", configField, configBrowse)
                .required("YAML config file for Casanovo")
                .tooltip("Required when 'Use GUI parameters' is off. A YAML config file passed to "
                        + "Casanovo. (--config)");
        configLabel = form.lastLabel();
        configBrowseButton = configBrowse;
    }

    /**
     * Show or hide the "Config file" row. Hidden while the GUI generates the config from the
     * Parameters dialog; shown when the user unticks "Use GUI parameters" to supply a YAML file.
     * The field's value is preserved across toggles; {@link #appendArgs} ignores it while the row
     * is hidden, so a previously-typed path can't silently apply in GUI mode.
     */
    void setConfigFileVisible(boolean visible) {
        if (configLabel == null) {
            return; // addConfigRow was never called — no config row on this form
        }
        for (Node n : new Node[]{configLabel, configField, configBrowseButton}) {
            n.setVisible(visible);
            n.setManaged(visible);
        }
    }

    /** The config file is required when the user opts out of GUI parameters. */
    ValidationError validateConfigFile() {
        return PathFields.validateSingleFile(configField, "a YAML config file");
    }

    /**
     * Validate the shared required option (the output directory). Returns an inline
     * {@link ValidationError} pointing at the field, or {@code null} when it is set.
     */
    ValidationError validateOutputDir() {
        if (PathFields.isEmpty(outputDirField)) {
            return new ValidationError(
                    "Please set an \"Output directory\" before running, so the results are "
                            + "written where you expect.", outputDirField);
        }
        return null;
    }

    /**
     * Update the Model-weights placeholder to reflect the model that will be auto-selected for the
     * current input (timsTOF for a Bruker {@code .d} folder, otherwise orbitrap): the cached checkpoint
     * path when present, else a short "will download" hint. The placeholder shows only while the field
     * is blank, so a user-typed model is unaffected. Panes that allow {@code .d} call this when the
     * spectrum input changes, so switching between files and {@code .d} keeps the hint honest.
     */
    void refreshModelPrompt(List<String> peakPaths) {
        String modelId = TimsTof.anyDotD(peakPaths) ? "timstof" : DEFAULT_MODEL;
        if (modelId.equals(promptModelId)) {
            return; // input's .d-ness unchanged -> placeholder unchanged; skip the weights-cache walk
        }
        promptModelId = modelId;
        java.io.File cached = CasanovoWeights.findCachedFor(modelId);
        modelField.setPromptText(cached != null
                ? cached.getAbsolutePath()
                : modelId + " (blank = auto-select; Casanovo will download & cache the weights)");
    }

    /**
     * Keep the Model-weights placeholder following {@code peakField}'s input type ({@code .d} -> timsTOF,
     * else orbitrap). Shared by the panes that accept spectrum input so the wiring lives in one place.
     */
    void trackModelInput(MultiFileField peakField) {
        peakField.field().textProperty().addListener((o, a, b) ->
                refreshModelPrompt(PathFields.split(peakField.field())));
        refreshModelPrompt(PathFields.split(peakField.field()));
    }

    /**
     * The model id this pane passes to {@code --model} for {@code peakPaths}: the user's typed model if
     * set, otherwise auto-selected from the input format ({@code timstof} for a Bruker {@code .d} folder,
     * else {@code orbitrap}). Returns {@code ""} when the field is blank and {@code defaultModel} is false
     * (training from scratch). Single source of truth for the model, used by {@link #appendArgs}; the run
     * and Parameters dialog read the resulting {@code --model} so their timsTOF decisions can't diverge.
     */
    String resolveModelId(List<String> peakPaths, boolean defaultModel) {
        String model = modelField.getText() == null ? "" : modelField.getText().trim();
        if (!model.isEmpty()) {
            return model;
        }
        return defaultModel ? (TimsTof.anyDotD(peakPaths) ? "timstof" : DEFAULT_MODEL) : "";
    }

    /**
     * Append the shared options to {@code args}.
     *
     * @param defaultModel when {@code true} and the model field is blank, a model is auto-selected
     *                     from the input format ({@code timstof} for a Bruker {@code .d} folder, else
     *                     {@code orbitrap}) and added explicitly so Casanovo doesn't emit its "No model
     *                     was specified" warning. Training passes {@code false}: a blank model there
     *                     means "train from scratch" and must not be pinned to a default model.
     * @param peakPaths    the run's spectrum paths, used to detect timsTOF {@code .d} input
     */
    void appendArgs(List<String> args, boolean defaultModel, List<String> peakPaths) {
        String model = resolveModelId(peakPaths, defaultModel);
        if (!model.isEmpty()) {
            args.add("--model");
            args.add(model);
        }
        // Only in config-file mode (the row is shown); in GUI-parameters mode the field is hidden,
        // its value preserved but ignored — the GUI generates the config instead.
        if (configField.isVisible()) {
            addFileArg(args, "--config", configField.getText());
        }
        // Pass an ABSOLUTE output dir. The runner sets the process working directory to this
        // folder, so a relative --output_dir would resolve against it and nest (e.g. ok\ok) —
        // which also breaks "Open in PDV" (the GUI looks for the mzTab in the un-nested folder).
        String outDir = outputDirField.getText() == null ? "" : outputDirField.getText().trim();
        if (!outDir.isEmpty()) {
            args.add("--output_dir");
            args.add(new java.io.File(outDir).getAbsolutePath());
        }
        String root = outputRootField.getText() == null ? "" : outputRootField.getText().trim();
        if (!root.isEmpty()) {
            args.add("--output_root");
            args.add(root);
        }
        String v = verbosityCombo.getSelectionModel().getSelectedItem();
        if (v != null && !"(default)".equals(v)) {
            args.add("--verbosity");
            args.add(v);
        }
        if (forceOverwrite.isSelected()) {
            args.add("--force_overwrite");
        }
    }

    private static void addFileArg(List<String> args, String flag, String value) {
        if (value != null && !value.trim().isEmpty()) {
            args.add(flag);
            args.add(value.trim());
        }
    }
}
