package org.casanovo.gui.core;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.casanovo.gui.core.ConfigField.Type;

/**
 * The complete set of Casanovo configuration parameters (mirroring Casanovo's
 * {@code config.yaml}), each with its default, type, group and description.
 *
 * <p>Holds the user's current values and can serialise them to a full YAML
 * config file, which the GUI passes to Casanovo via {@code --config}. A
 * complete file is written (every key, defaults included) so behaviour does not
 * depend on how Casanovo merges partial configs.</p>
 */
public class CasanovoConfig {

    // Group display order for the Parameters dialog tabs.
    public static final String G_PRECURSOR = "Precursor & Peptides";
    public static final String G_DENOVO = "De novo";
    public static final String G_DB = "Database Search";
    public static final String G_SPECTRUM = "Spectrum Processing";
    public static final String G_MODEL = "Model Architecture";
    public static final String G_TRAINING = "Training / Inference";
    public static final String G_OUTPUT = "Output & Logging";
    public static final String G_VOCAB = "Vocabulary";

    public static final List<String> GROUP_ORDER = Arrays.asList(
            G_PRECURSOR, G_DENOVO, G_DB, G_SPECTRUM, G_MODEL, G_TRAINING, G_OUTPUT, G_VOCAB);

    private static final String DEFAULT_RESIDUES =
            "\"G\": 57.021464\n"
            + "\"A\": 71.037114\n"
            + "\"S\": 87.032028\n"
            + "\"P\": 97.052764\n"
            + "\"V\": 99.068414\n"
            + "\"T\": 101.047670\n"
            + "\"C[Carbamidomethyl]\": 160.030649\n"
            + "\"L\": 113.084064\n"
            + "\"I\": 113.084064\n"
            + "\"N\": 114.042927\n"
            + "\"D\": 115.026943\n"
            + "\"Q\": 128.058578\n"
            + "\"K\": 128.094963\n"
            + "\"E\": 129.042593\n"
            + "\"M\": 131.040485\n"
            + "\"H\": 137.058912\n"
            + "\"F\": 147.068414\n"
            + "\"R\": 156.101111\n"
            + "\"Y\": 163.063329\n"
            + "\"W\": 186.079313\n"
            + "\"M[Oxidation]\": 147.035400\n"
            + "\"N[Deamidated]\": 115.026943\n"
            + "\"Q[Deamidated]\": 129.042594\n"
            + "\"[Acetyl]-\": 42.010565\n"
            + "\"[Carbamyl]-\": 43.005814\n"
            + "\"[Ammonia-loss]-\": -17.026549\n"
            + "\"[+25.980265]-\": 25.980265\n";

    private final List<ConfigField> fields = new ArrayList<>();
    private final Map<String, ConfigField> byKey = new LinkedHashMap<>();

    public CasanovoConfig() {
        // --- Precursor & Peptides (inference / fine-tune / db) ---
        add("precursor_mass_tol", "Precursor m/z tolerance (ppm)", G_PRECURSOR, Type.FLOAT, "50", null,
                "Max absolute difference vs. observed precursor m/z (ppm). Use 'inf' to disable.");
        add("isotope_error_range", "Isotope error range", G_PRECURSOR, Type.INT_LIST, "0, 1", null,
                "Isotopes to consider when comparing predicted and observed precursor m/z.");
        add("min_peptide_len", "Min peptide length", G_PRECURSOR, Type.INT, "6", null,
                "Minimum length of considered peptides.");
        add("max_peptide_len", "Max peptide length", G_PRECURSOR, Type.INT, "100", null,
                "Maximum length of considered peptides.");
        add("predict_batch_size", "Predict batch size", G_PRECURSOR, Type.INT, "1024", null,
                "Number of spectra in one inference batch.");
        add("top_match", "Top matches per spectrum", G_PRECURSOR, Type.INT, "1", null,
                "Number of PSMs reported per spectrum. Must be 1 when using --evaluate.");
        add("accelerator", "Accelerator", G_PRECURSOR, Type.CHOICE, "auto",
                new String[]{"auto", "cpu", "gpu", "tpu", "ipu", "hpu", "mps"},
                "Hardware accelerator to use.");
        add("devices", "Devices", G_PRECURSOR, Type.INT, "", null,
                "Number of devices, or -1 for all. Blank = auto-select.");

        // --- De novo ---
        add("n_beams", "Number of beams", G_DENOVO, Type.INT, "1", null,
                "Number of beams used in beam search.");

        // --- Database search ---
        add("enzyme", "Enzyme", G_DB, Type.STRING, "trypsin", null,
                "Enzyme for in silico digestion (pyteomics expasy_rules name, or a regex).");
        add("digestion", "Digestion", G_DB, Type.CHOICE, "full",
                new String[]{"full", "semi", "non-specific"},
                "Digestion type for candidate peptide generation.");
        add("missed_cleavages", "Missed cleavages", G_DB, Type.INT, "0", null,
                "Number of allowed missed cleavages when digesting proteins.");
        add("max_mods", "Max modifications", G_DB, Type.INT, "1", null,
                "Max variable modifications per peptide. Blank = all isoforms.");
        add("allowed_fixed_mods", "Allowed fixed mods", G_DB, Type.STRING, "C:C[Carbamidomethyl]", null,
                "Comma-separated 'aa:mod_residue' fixed modifications.");
        add("allowed_var_mods", "Allowed variable mods", G_DB, Type.STRING,
                "M:M[Oxidation],N:N[Deamidated],Q:Q[Deamidated],nterm:[Acetyl]-,nterm:[Carbamyl]-,"
                        + "nterm:[Ammonia-loss]-,nterm:[+25.980265]-", null,
                "Comma-separated 'aa:mod_residue' variable modifications.");

        // --- Spectrum processing ---
        add("min_peaks", "Min peaks", G_SPECTRUM, Type.INT, "20", null,
                "Minimum number of peaks for a spectrum to be considered valid.");
        add("max_peaks", "Max peaks", G_SPECTRUM, Type.INT, "150", null,
                "Maximum number of most-intense peaks to retain.");
        add("min_mz", "Min m/z", G_SPECTRUM, Type.FLOAT, "50.0", null,
                "Minimum peak m/z; smaller peaks discarded.");
        add("max_mz", "Max m/z", G_SPECTRUM, Type.FLOAT, "2500.0", null,
                "Maximum peak m/z; larger peaks discarded.");
        add("min_intensity", "Min intensity", G_SPECTRUM, Type.FLOAT, "0.01", null,
                "Minimum peak intensity; less intense peaks discarded.");
        add("remove_precursor_tol", "Remove precursor tol (Da)", G_SPECTRUM, Type.FLOAT, "2.0", null,
                "Max absolute m/z difference when removing the precursor peak.");
        add("max_charge", "Max charge", G_SPECTRUM, Type.INT, "4", null,
                "Max precursor charge; spectra with larger charge are skipped.");

        // --- Model architecture (training from scratch) ---
        add("dim_model", "Model dimension", G_MODEL, Type.INT, "512", null,
                "Dimensionality of latent representations (peak embeddings).");
        add("n_head", "Attention heads", G_MODEL, Type.INT, "8", null,
                "Number of attention heads.");
        add("dim_feedforward", "Feedforward dimension", G_MODEL, Type.INT, "1024", null,
                "Dimensionality of fully connected layers.");
        add("n_layers", "Transformer layers", G_MODEL, Type.INT, "9", null,
                "Number of transformer layers in encoder and decoder.");
        add("dropout", "Dropout", G_MODEL, Type.FLOAT, "0.0", null,
                "Dropout rate for model weights.");
        add("dim_intensity", "Intensity dimension", G_MODEL, Type.INT, "", null,
                "Dimensions for encoding peak intensity. Blank = None.");
        add("warmup_iters", "Warm-up iterations", G_MODEL, Type.INT, "100000", null,
                "Iterations for linear warm-up of the learning rate.");
        add("cosine_schedule_period_iters", "Cosine schedule period", G_MODEL, Type.INT, "600000", null,
                "Iterations for the cosine half-period of the learning rate.");
        add("learning_rate", "Learning rate", G_MODEL, Type.FLOAT, "5e-4", null,
                "Learning rate for weight updates during training.");
        add("weight_decay", "Weight decay", G_MODEL, Type.FLOAT, "1e-5", null,
                "Regularization term for weight updates.");
        add("train_label_smoothing", "Label smoothing", G_MODEL, Type.FLOAT, "0.01", null,
                "Amount of label smoothing when computing the training loss.");

        // --- Training / inference ---
        add("random_seed", "Random seed", G_TRAINING, Type.INT, "454", null,
                "Random seed to ensure reproducible results.");
        add("train_batch_size", "Train batch size", G_TRAINING, Type.INT, "32", null,
                "Number of spectra in one training batch.");
        add("max_epochs", "Max epochs", G_TRAINING, Type.INT, "30", null,
                "Maximum number of training epochs.");
        add("shuffle", "Shuffle dataset", G_TRAINING, Type.BOOL, "true", null,
                "Shuffle dataset during training.");
        add("shuffle_buffer_size", "Shuffle buffer size", G_TRAINING, Type.INT, "10000", null,
                "Samples to buffer while randomly shuffling the training data.");
        add("num_sanity_val_steps", "Sanity val steps", G_TRAINING, Type.INT, "0", null,
                "Validation steps to run before training begins.");
        add("calculate_precision", "Calculate precision", G_TRAINING, Type.BOOL, "false", null,
                "Calculate peptide/AA precision during training (expensive).");
        add("accumulate_grad_batches", "Accumulate grad batches", G_TRAINING, Type.INT, "1", null,
                "Accumulate gradients over k batches before stepping the optimizer.");
        add("gradient_clip_val", "Gradient clip value", G_TRAINING, Type.FLOAT, "", null,
                "Value at which to clip gradients. Blank = disabled.");
        add("gradient_clip_algorithm", "Gradient clip algorithm", G_TRAINING, Type.CHOICE, "",
                new String[]{"", "value", "norm"},
                "Gradient clipping algorithm. Blank = None.");
        add("precision", "Precision", G_TRAINING, Type.CHOICE, "32-true",
                new String[]{"16-true", "16-mixed", "bf16-true", "bf16-mixed", "32-true",
                        "64-true", "64", "32", "16", "bf16"},
                "Floating point precision.");
        add("replace_isoleucine_with_leucine", "Replace I with L", G_TRAINING, Type.BOOL, "true", null,
                "Replace Isoleucine (I) by Leucine (L) in peptide sequences.");
        add("massivekb_tokenizer", "MassIVE-KB tokenizer", G_TRAINING, Type.BOOL, "false", null,
                "Use MassIVE-KB style tokenizer; otherwise ProForma syntax.");
        add("val_check_interval", "Validation check interval", G_TRAINING, Type.INT, "50000", null,
                "Model validation and checkpointing frequency in training steps.");

        // --- Output & logging ---
        add("n_log", "Log frequency", G_OUTPUT, Type.INT, "1", null,
                "Logging frequency in training steps.");
        add("tb_summarywriter", "TensorBoard summary", G_OUTPUT, Type.BOOL, "false", null,
                "Whether to create a TensorBoard directory.");
        add("log_metrics", "Log metrics (CSV)", G_OUTPUT, Type.BOOL, "false", null,
                "Whether to create a csv_logs directory.");
        add("log_every_n_steps", "Log optimizer every N steps", G_OUTPUT, Type.INT, "50", null,
                "How often to log optimizer parameters, in steps.");
        add("lance_dir", "Lance cache directory", G_OUTPUT, Type.STRING, "", null,
                "Path to save Lance instances. Blank = temporary.");

        // --- Vocabulary ---
        add("residues", "Residues & modifications", G_VOCAB, Type.TEXT_BLOCK, DEFAULT_RESIDUES, null,
                "Amino-acid and modification masses. One 'token': mass per line.");
    }

    private void add(String key, String label, String group, Type type,
                     String def, String[] choices, String desc) {
        ConfigField f = new ConfigField(key, label, group, type, def, choices, desc);
        fields.add(f);
        byKey.put(key, f);
    }

    public List<ConfigField> getFields() {
        return fields;
    }

    public List<ConfigField> getFieldsForGroup(String group) {
        List<ConfigField> out = new ArrayList<>();
        for (ConfigField f : fields) {
            if (f.getGroup().equals(group)) {
                out.add(f);
            }
        }
        return out;
    }

    public ConfigField get(String key) {
        return byKey.get(key);
    }

    public void resetAllToDefaults() {
        for (ConfigField f : fields) {
            f.resetToDefault();
        }
    }

    /** Serialise the current values to a complete Casanovo YAML configuration. */
    public String toYaml() {
        StringBuilder sb = new StringBuilder();
        sb.append("###\n# Casanovo configuration generated by Casanovo GUI.\n"
                + "# Blank entries are interpreted as `None`.\n###\n\n");
        for (ConfigField f : fields) {
            if (f.getType() == Type.TEXT_BLOCK) {
                appendBlock(sb, f);
            } else {
                appendScalar(sb, f);
            }
        }
        // new_token_init was added in Casanovo 5.2.0: a fine-tuning-only option (map new
        // vocabulary tokens to existing ones for weight initialization) that the GUI does
        // not expose. Emit its empty-map default so this file passes Casanovo's "all
        // expected options present" validation. This path is the FALLBACK used when the
        // version-correct base from `casanovo configure` isn't available (see
        // overlayOnto); the primary path inherits every key from Casanovo itself.
        if (byKey.get("new_token_init") == null) {
            sb.append("new_token_init: {}\n");
        }
        return sb.toString();
    }

    /**
     * Overlay the user's customized parameters onto {@code baseYaml} — the output of
     * {@code casanovo configure} for the installed Casanovo, which already contains every
     * option that version expects. Only fields the user changed from their default are
     * applied; every other key (including options this GUI doesn't know about) keeps
     * Casanovo's own default. This keeps the generated config valid across Casanovo
     * releases that add new options, without the GUI tracking the schema.
     */
    public String overlayOnto(String baseYaml) {
        String result = baseYaml;
        for (ConfigField f : fields) {
            if (!f.isModifiedFromDefault()) {
                continue;
            }
            result = (f.getType() == Type.TEXT_BLOCK)
                    ? replaceBlock(result, f)
                    : replaceScalar(result, f);
        }
        return result;
    }

    /** The YAML this field serialises to (a scalar line, or a multi-line block), no trailing newline. */
    private String fieldYaml(ConfigField f) {
        StringBuilder sb = new StringBuilder();
        if (f.getType() == Type.TEXT_BLOCK) {
            appendBlock(sb, f);
        } else {
            appendScalar(sb, f);
        }
        return sb.toString().stripTrailing();
    }

    /** Replace a single {@code key: ...} line; append the key if the base lacks it. */
    private String replaceScalar(String yaml, ConfigField f) {
        String snippet = fieldYaml(f);
        String regex = "(?m)^" + Pattern.quote(f.getKey()) + ":.*$";
        if (Pattern.compile(regex).matcher(yaml).find()) {
            return yaml.replaceFirst(regex, Matcher.quoteReplacement(snippet));
        }
        return appendKey(yaml, snippet);
    }

    /** Replace a {@code key:} block (key line + its indented body); append if absent. */
    private String replaceBlock(String yaml, ConfigField f) {
        String snippet = fieldYaml(f);
        // Key line plus the following indented lines that make up the mapping block.
        String regex = "(?m)^" + Pattern.quote(f.getKey()) + ":[^\\n]*(?:\\n[ \\t]+[^\\n]*)*";
        if (Pattern.compile(regex).matcher(yaml).find()) {
            return yaml.replaceFirst(regex, Matcher.quoteReplacement(snippet));
        }
        return appendKey(yaml, snippet);
    }

    private static String appendKey(String yaml, String snippet) {
        return yaml + (yaml.endsWith("\n") ? "" : "\n") + snippet + "\n";
    }

    private void appendScalar(StringBuilder sb, ConfigField f) {
        String key = f.getKey();
        String v = f.getValue().trim();
        switch (f.getType()) {
            case BOOL:
                sb.append(key).append(": ").append(
                        v.equalsIgnoreCase("true") ? "true" : "false").append('\n');
                break;
            case INT:
                if (v.isEmpty()) {
                    sb.append(key).append(":\n");
                } else {
                    sb.append(key).append(": ").append(v.replace("_", "")).append('\n');
                }
                break;
            case FLOAT:
                if (v.isEmpty()) {
                    sb.append(key).append(":\n");
                } else if (v.equalsIgnoreCase("inf") || v.equalsIgnoreCase("-inf")) {
                    sb.append(key).append(": \"").append(v.toLowerCase()).append("\"\n");
                } else {
                    sb.append(key).append(": ").append(v).append('\n');
                }
                break;
            case INT_LIST:
                if (v.isEmpty()) {
                    sb.append(key).append(":\n");
                } else {
                    List<String> parts = new ArrayList<>();
                    for (String p : v.replace("[", "").replace("]", "").split(",")) {
                        String t = p.trim();
                        if (!t.isEmpty()) {
                            parts.add(t);
                        }
                    }
                    sb.append(key).append(": [").append(String.join(", ", parts)).append("]\n");
                }
                break;
            case CHOICE:
            case STRING:
            default:
                if (v.isEmpty()) {
                    sb.append(key).append(":\n");
                } else {
                    // Leave "accelerator: auto" untouched: Casanovo maps it to CPU on arm64 Macs, which is
                    // what we want. We used to force "mps" here for speed, but MPS lacks ops Casanovo needs
                    // (e.g. aten::_nested_tensor_from_mask_left_aligned) and crashes the run.
                    sb.append(key).append(": \"").append(v).append("\"\n");
                }
                break;
        }
    }

    private void appendBlock(StringBuilder sb, ConfigField f) {
        sb.append(f.getKey()).append(":\n");
        for (String line : f.getValue().split("\\R")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                sb.append("  ").append(t).append('\n');
            }
        }
    }

    /** Write the GUI's self-generated YAML to a temp file (deleted on JVM exit). */
    public File writeTempFile() throws IOException {
        return writeTempConfig(toYaml());
    }

    /** Write an arbitrary YAML config string to a temp file (deleted on JVM exit). */
    public static File writeTempConfig(String yaml) throws IOException {
        File f = File.createTempFile("casanovo-gui-config-", ".yaml");
        f.deleteOnExit();
        writeConfigTo(yaml, f);
        return f;
    }

    /** Write an arbitrary YAML config string to a specific file (kept on disk). */
    public static void writeConfigTo(String yaml, File dest) throws IOException {
        try (Writer w = Files.newBufferedWriter(dest.toPath(), StandardCharsets.UTF_8)) {
            w.write(yaml);
        }
    }

    /** Write the YAML to a specific file. */
    public void writeTo(File file) throws IOException {
        try (Writer w = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            w.write(toYaml());
        }
    }
}
