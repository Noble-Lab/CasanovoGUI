package org.casanovo.gui.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the two settings the GUI needs out of an externally supplied {@code --config} YAML: the
 * accelerator, which decides how the run's subprocess environment is shaped, and the prediction
 * batch size, which drives the progress bar.
 *
 * <p>This is a scanner, not a YAML parser, because it needs an answer for a file it may not be
 * able to decode and must never fail a run over one. It does follow the rules of the parser
 * Casanovo actually uses (PyYAML) for the parts that matter: the last of a repeated key wins,
 * whitespace may precede the colon, and "top level" means the file's least-indented mapping
 * rather than column 0.</p>
 */
public final class ConfigFile {

    private ConfigFile() {
    }

    /** Any line terminator, the way {@code String.split} spells it. */
    private static final String LINE_BREAK = "\\R";

    /**
     * The contents of the {@code --config} file {@code command} supplies, or {@code null} when
     * there is no such argument or the path is not a file.
     *
     * <p>Decoded from bytes rather than readAllLines: a strict UTF-8 decode throws on one stray
     * byte in a comment, and the readers above would then report the run as unconfigured — which
     * costs a CPU run its CUDA_VISIBLE_DEVICES guard. The keys and values here are ASCII, so
     * replacing the undecodable bytes loses nothing that matters.</p>
     */
    private static String configText(CasanovoCommand command) throws IOException {
        List<String> args = command.getArguments();
        int i = args.indexOf("--config");
        if (i < 0 || i + 1 >= args.size()) {
            return null;
        }
        File file = new File(args.get(i + 1));
        if (!file.isFile()) {
            return null;
        }
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /**
     * The file split into lines with any UTF-8 BOM removed &mdash; the form {@link #scalarIn}
     * scans. Separate from the scan so a reader after two keys pays for it once.
     */
    private static String[] normalizedLines(String text) {
        String[] lines = text.split(LINE_BREAK);
        for (int n = 0; n < lines.length; n++) {
            // A UTF-8 BOM arrives as U+FEFF on the first line — Notepad and PowerShell both
            // write one — and would otherwise hide a key declared on line 1.
            if (lines[n].startsWith("﻿")) {
                lines[n] = lines[n].substring(1);
            }
        }
        return lines;
    }

    /**
     * The value of a top-level {@code key} in a config already split by
     * {@link #normalizedLines} and measured by {@link #rootIndent}, or {@code null}.
     */
    private static String scalarIn(String[] lines, int rootIndent, String key) {
        // "key:" and "key :" are the same mapping to PyYAML; requiring the colon to be glued to
        // the key would report the run as unconfigured.
        Pattern declaration = Pattern.compile("^(\\s*)" + Pattern.quote(key) + "\\s*:");
        String found = null;
        for (String line : lines) {
            Matcher declared = declaration.matcher(line);
            if (!declared.find() || declared.group(1).length() != rootIndent) {
                continue; // a key of some nested block, not the run's own setting
            }
            String value = line.substring(declared.end());
            int hash = value.indexOf('#');
            if (hash >= 0) {
                value = value.substring(0, hash);
            }
            value = value.trim().replace("\"", "").replace("'", "").trim();
            found = value.isEmpty() ? null : value; // keep scanning: the last key is the one
        }
        return found;
    }

    /**
     * The indentation of the file's root mapping: the least-indented content line. Usually zero,
     * but a uniformly indented file is valid YAML and PyYAML loads it as an ordinary top-level
     * mapping, so anchoring at column 0 would report such a run as unconfigured and cost a CPU
     * run its GPU guard.
     */
    private static int rootIndent(String[] lines) {
        int root = Integer.MAX_VALUE;
        for (String line : lines) {
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")
                    || isYamlDocumentMarker(stripped)) {
                continue;
            }
            root = Math.min(root, line.length() - line.stripLeading().length());
        }
        return root == Integer.MAX_VALUE ? 0 : root;
    }

    /** YAML stream markers are not mapping content and therefore do not establish indentation. */
    private static boolean isYamlDocumentMarker(String stripped) {
        int comment = stripped.indexOf('#');
        String token = (comment < 0 ? stripped : stripped.substring(0, comment)).trim();
        return token.equals("---") || token.equals("...");
    }

    /** Casanovo's own {@code predict_batch_size} default, used when the file does not set one. */
    private static final int DEFAULT_BATCH_SIZE = 1024;

    /** A scanned {@code predict_batch_size} as a number, or Casanovo's own default. */
    private static int batchSize(String scanned) {
        try {
            return scanned == null ? DEFAULT_BATCH_SIZE : Integer.parseInt(scanned);
        } catch (NumberFormatException ignored) {
            return DEFAULT_BATCH_SIZE;
        }
    }

    /**
     * Everything a run needs from an external config, read in ONE pass over the file. The two
     * values used to be fetched separately, which read and decoded the same file twice — on the
     * network share this reader was moved off the UI thread to survive, that doubles the wait.
     *
     * @param accelerator      accelerator tag used to shape the run environment
     * @param predictBatchSize batch size used to estimate prediction progress
     */
    public record RunValues(String accelerator, int predictBatchSize) {
    }

    /**
     * What the run will use, from whichever side owns the configuration.
     *
     * <p>When the GUI owns it, both values come directly from the controls that will generate
     * the config. Otherwise both come from the user's file, in one pass.</p>
     *
     * <p>The accelerator is what the launcher reads (as a tag on the command) to hide every GPU
     * from a CPU run, so a run that loses it silently loses that guard.</p>
     */
    public static RunValues forRun(CasanovoCommand base, boolean guiOwnsConfig,
                                   String guiAccelerator, String guiPredictBatchSize) {
        return guiOwnsConfig ? fromGui(guiAccelerator, guiPredictBatchSize) : read(base);
    }

    /**
     * Values that need no file access, used when starting the background resolver itself fails.
     * An external config is unknown in that case; GUI-owned values are already in memory.
     */
    public static RunValues fallback(boolean guiOwnsConfig, String guiAccelerator,
                                     String guiPredictBatchSize) {
        return guiOwnsConfig ? fromGui(guiAccelerator, guiPredictBatchSize)
                : new RunValues(DeviceProbe.UNKNOWN, DEFAULT_BATCH_SIZE);
    }

    private static RunValues fromGui(String accelerator, String predictBatchSize) {
        int batchSize;
        try {
            batchSize = predictBatchSize == null
                    ? DEFAULT_BATCH_SIZE : Integer.parseInt(predictBatchSize.trim());
        } catch (NumberFormatException ignored) {
            batchSize = DEFAULT_BATCH_SIZE;
        }
        return new RunValues(accelerator, batchSize);
    }

    private static RunValues read(CasanovoCommand command) {
        String text;
        try {
            text = configText(command);
        } catch (IOException ignored) {
            // Unreadable config: the accelerator is genuinely unknown, and the batch size falls
            // back to the default the progress bar assumes.
            return new RunValues(DeviceProbe.UNKNOWN, DEFAULT_BATCH_SIZE);
        }
        if (text == null) {
            return new RunValues(null, DEFAULT_BATCH_SIZE); // no --config: nothing to read
        }
        // One split, one BOM strip and one indentation scan for both keys: reading the file once
        // and then parsing it twice gives back most of what reading it once saved.
        String[] lines = normalizedLines(text);
        int rootIndent = rootIndent(lines);
        return new RunValues(scalarIn(lines, rootIndent, "accelerator"),
                batchSize(scalarIn(lines, rootIndent, "predict_batch_size")));
    }
}
