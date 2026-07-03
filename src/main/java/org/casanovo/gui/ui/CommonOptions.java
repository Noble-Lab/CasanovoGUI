package org.casanovo.gui.ui;

import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.stage.Window;
import org.casanovo.gui.core.CasanovoWeights;

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

    CommonOptions() {
        verbosityCombo.getItems().addAll("(default)", "debug", "info", "warning", "error");
        verbosityCombo.getSelectionModel().select("(default)");
        // Checked by default: re-runs into the same output folder just overwrite,
        // rather than failing with "file already exists" (Casanovo's own default is off).
        forceOverwrite.setSelected(true);
    }

    void addToForm(Window owner, FxUtils.FormGrid form) {
        form.addRow("Model weights:", modelField,
                FxUtils.fileButton(owner, modelField, false, "Model weights (*.ckpt)", "*.ckpt"))
                .optional("Model .ckpt (blank = default)");
        java.io.File cachedWeights = CasanovoWeights.findCachedDefault();
        if (cachedWeights != null) {
            modelField.setPromptText(cachedWeights.getAbsolutePath());
            form.tooltip("Optional. Cached default weights found: " + cachedWeights.getName()
                    + " — leave blank to use them, or pick a different .ckpt. (--model)");
        } else {
            form.tooltip("Optional. Leave blank to let Casanovo download/cache default weights. (--model)");
        }
        form.addRow("Config file:", configField,
                        FxUtils.fileButton(owner, configField, false, "YAML config", "*.yaml", "*.yml"))
                .optional("YAML config (overrides GUI parameters)")
                .tooltip("Optional. Overrides the GUI parameters when set. (--config)");
        form.addRow("Output directory:", outputDirField,
                        FxUtils.dirButton(owner, outputDirField))
                .required("Folder for Casanovo output")
                .tooltip("Required. Folder where Casanovo writes its output files. (--output_dir)");
        form.addRow("Output root name:", outputRootField)
                .optional("Base name for output files")
                .tooltip("Optional. Base name for the generated output files. (--output_root)");
        form.addRow("Verbosity:", verbosityCombo)
                .optional("Logging detail")
                .tooltip("Logging detail for the run. '(default)' uses Casanovo's own setting. (--verbosity)");
        form.addRow("", forceOverwrite)
                .optional("Overwrite existing output")
                .tooltip("When on, re-running into the same output folder overwrites existing "
                        + "files instead of failing. On by default. (--force_overwrite)");
    }

    /**
     * Append the shared options to {@code args}.
     *
     * @param defaultModel when {@code true} and the model field is blank, an explicit
     *                     {@code --model orbitrap} is added so Casanovo does not emit its
     *                     "No model was specified" warning. Training passes {@code false}:
     *                     a blank model there means "train from scratch" and must not be
     *                     pinned to the default model.
     */
    void appendArgs(List<String> args, boolean defaultModel) {
        String model = modelField.getText() == null ? "" : modelField.getText().trim();
        if (model.isEmpty() && defaultModel) {
            model = DEFAULT_MODEL;
        }
        if (!model.isEmpty()) {
            args.add("--model");
            args.add(model);
        }
        addFileArg(args, "--config", configField.getText());
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
