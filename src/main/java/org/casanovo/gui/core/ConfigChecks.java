package org.casanovo.gui.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks on a set of {@code config.yaml} values, run before they are handed to Casanovo.
 *
 * <p>Casanovo validates types and nothing else. Verified against 5.2.1 on 50 annotated spectra:
 * {@code precursor_mass_tol: abc} is refused, but every value below loads, and the run then either
 * dies inside depthcharge with a bare {@code StopIteration} naming neither setting nor file
 * ({@code max_charge: -1}, {@code max_peaks: 0}, {@code min_peaks} above {@code max_peaks}; {@code
 * predict_batch_size: 0} crashes too, but as a pyarrow error rather than that traceback) or exits 0
 * having reported nothing at all, with 0% precision and
 * only a generic "No predictions were logged" ({@code min_peptide_len} above {@code
 * max_peptide_len}, {@code max_peptide_len: 0}, {@code top_match: 0}, {@code n_beams: 0}). The
 * checks here exist to name the setting while it is still one field away from being fixed.</p>
 *
 * <p>Kept out of the dialog so the parsing is testable without a JavaFX toolkit.</p>
 */
public final class ConfigChecks {

    /**
     * PyYAML's own base-10 integer form, which is what decides whether the value in the config
     * arrives as a number at all. Confirmed against PyYAML 6.0.3: {@code 1_024}, {@code 1__0} and
     * {@code 5_} all load as integers, while {@code _5} stays a string that Casanovo's {@code int()}
     * then rejects, and a leading zero takes the YAML 1.1 octal branch — {@code 010} arrives as 8
     * while the dialog goes on showing 010. Matched against the value as typed, so none of that is
     * normalised away before it is judged.
     */
    private static final Pattern INTEGER = Pattern.compile("[+-]?(0|[1-9][0-9_]*)");
    /**
     * A plain decimal, with an optional exponent. Deliberately narrower than {@code
     * Double.parseDouble}, which also takes {@code 5d}, {@code 0x1p3} and {@code NaN} — forms
     * Python's {@code float()} rejects on the far side of the YAML file.
     */
    private static final Pattern DECIMAL =
            Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");
    /**
     * How Casanovo reports the one thing it does check, e.g. {@code Incorrect type for configuration
     * value precursor_mass_tol: could not convert string to float: 'abc'}. It names the YAML key,
     * which the GUI can turn back into the field the user typed into.
     */
    private static final Pattern TYPE_ERROR =
            Pattern.compile("Incorrect type for configuration value ([A-Za-z_][A-Za-z0-9_]*)");

    private ConfigChecks() {
    }

    /**
     * A pair of settings that has to stay in order: past its partner the run can match nothing at
     * all, and Casanovo reports that only by failing with no result.
     */
    private record Pair(String minKey, String maxKey, String consequence) {
    }

    /** A setting with a floor: under it Casanovo either crashes or can never match a spectrum. */
    private record Floor(String key, double least, String consequence) {
    }

    private static final List<Pair> PAIRS = List.of(
            new Pair("min_peptide_len", "max_peptide_len",
                    "no peptide can be both, so the run reports nothing"),
            new Pair("min_peaks", "max_peaks",
                    "every spectrum is discarded before the model sees it"));

    private static final List<Floor> FLOORS = List.of(
            new Floor("max_charge", 1, "every spectrum is skipped as over-charged"),
            new Floor("max_peptide_len", 1, "no peptide is short enough to report"),
            new Floor("max_peaks", 1, "every spectrum is discarded before the model sees it"),
            new Floor("top_match", 1, "no PSM is reported for any spectrum"),
            new Floor("n_beams", 1, "beam search has no beam to search with"),
            // No train_batch_size counterpart: it was never run end to end, and sanityWarnings has
            // no notion of the sub-command, so it would warn a de novo run about a value that run
            // never reads.
            new Floor("predict_batch_size", 1, "there is no batch to run inference on"));

    /**
     * Values Casanovo cannot read as the field's type, or would read as something other than what
     * the dialog shows — one message per problem, empty when there are none.
     *
     * <p>A blank is not a problem: it serialises to a YAML null, which Casanovo's {@code
     * validate_param} skips in favour of its own default. A non-integer in an integer field is one
     * even though Casanovo accepts it: {@code min_peptide_len: 1.5} loads as 1, so the dialog would
     * be showing a value the run never used.</p>
     *
     * @param fields the parameter definitions, for each field's type and label
     * @param values current editor text by config key; a key with no entry is skipped
     */
    public static List<String> typeErrors(List<ConfigField> fields, Map<String, String> values) {
        List<String> out = new ArrayList<>();
        for (ConfigField f : fields) {
            String raw = values.get(f.getKey());
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            String v = raw.trim();
            switch (f.getType()) {
                case INT:
                    if (!isInteger(v)) {
                        out.add(f.getLabel() + ": \"" + v + "\" is not a whole number.");
                    }
                    break;
                case FLOAT:
                    if (!isDecimal(v)) {
                        out.add(f.getLabel() + ": \"" + v + "\" is not a number.");
                    }
                    break;
                case INT_LIST:
                    for (String part : CasanovoConfig.intListParts(v)) {
                        if (!isInteger(part)) {
                            out.add(f.getLabel() + ": \"" + part + "\" is not a whole number.");
                        }
                    }
                    break;
                default:
                    break; // BOOL/CHOICE come from widgets that can't hold a bad value; the rest is free text
            }
        }
        return out;
    }

    /**
     * Value combinations Casanovo loads without complaint and then cannot produce a result from —
     * one message per problem, empty when there are none. Advisory: these encode what the settings
     * mean today, and a value they call impossible is still the user's to send.
     *
     * <p>A field left blank is judged by Casanovo's own default for it, which is what a YAML null
     * leaves in force — otherwise clearing "Min peptide length" would hide a "Max peptide length"
     * set below it. A value {@link #typeErrors} already rejected is skipped: it has been reported
     * once, and reading it as a number would only say the same thing again.</p>
     */
    public static List<String> sanityWarnings(List<ConfigField> fields, Map<String, String> values) {
        Map<String, ConfigField> byKey = byKey(fields);
        List<String> out = new ArrayList<>();
        // Keys a pair rule has already spoken for. A max below 1 is usually also below its own
        // minimum, and the two rules reach the same consequence by the same route — saying it twice
        // in one dialog reads as two problems where the user made one mistake.
        Set<String> reported = new HashSet<>();
        for (Pair p : PAIRS) {
            Double min = number(byKey, values, p.minKey());
            Double max = number(byKey, values, p.maxKey());
            if (min != null && max != null && min > max) {
                out.add(label(byKey, p.minKey()) + " (" + trim(min) + ") is above "
                        + label(byKey, p.maxKey()) + " (" + trim(max) + "): "
                        + p.consequence() + ".");
                reported.add(p.minKey());
                reported.add(p.maxKey());
            }
        }
        for (Floor fl : FLOORS) {
            if (reported.contains(fl.key())) {
                continue;
            }
            Double v = number(byKey, values, fl.key());
            if (v != null && v < fl.least()) {
                out.add(label(byKey, fl.key()) + " is " + trim(v) + ": " + fl.consequence() + ".");
            }
        }
        return out;
    }

    /**
     * The value a run will actually use for {@code key}: what the user typed, or — when that is
     * blank — Casanovo's own default, which a YAML null leaves in force. Blank when the field is
     * unknown. {@code values} may omit a key, in which case the field's current value is read.
     */
    public static String effective(List<ConfigField> fields, Map<String, String> values, String key) {
        return effective(byKey(fields), values, key);
    }

    /**
     * {@link #effective} read as a whole number, or {@code fallback} when it isn't one. Callers use
     * the fallback for a value {@link #typeErrors} would already have refused, so a run is never
     * stopped twice for the same thing.
     */
    public static int effectiveInt(List<ConfigField> fields, Map<String, String> values, String key,
                                   int fallback) {
        String v = effective(fields, values, key).replace("_", "");
        try {
            return INTEGER.matcher(v).matches() ? Integer.parseInt(v) : fallback;
        } catch (NumberFormatException e) {
            return fallback; // digits, but more of them than an int holds
        }
    }

    /**
     * The config key Casanovo named in a type error on {@code line}, or null when the line carries
     * none. Lets a failed run point at the field that was typed into rather than at an exit code.
     */
    public static String typeErrorKey(String line) {
        var m = TYPE_ERROR.matcher(line);
        return m.find() ? m.group(1) : null;
    }

    private static String effective(Map<String, ConfigField> byKey, Map<String, String> values,
                                    String key) {
        ConfigField f = byKey.get(key);
        if (f == null) {
            return "";
        }
        String raw = values.containsKey(key) ? values.get(key) : f.getValue();
        String v = raw == null ? "" : raw.trim();
        return v.isEmpty() ? f.getDefaultValue().trim() : v;
    }

    private static Map<String, ConfigField> byKey(List<ConfigField> fields) {
        Map<String, ConfigField> byKey = new LinkedHashMap<>();
        for (ConfigField f : fields) {
            byKey.put(f.getKey(), f);
        }
        return byKey;
    }

    private static String label(Map<String, ConfigField> byKey, String key) {
        ConfigField f = byKey.get(key);
        return f == null ? key : f.getLabel();
    }

    /**
     * The effective value of {@code key} as a number, or null when it isn't one this can judge —
     * which includes anything the field's own type check rejects, so a value already reported as
     * unreadable is never also reported as impossible.
     */
    private static Double number(Map<String, ConfigField> byKey, Map<String, String> values,
                                 String key) {
        ConfigField f = byKey.get(key);
        String v = effective(byKey, values, key);
        if (f == null || v.isEmpty() || !readsAs(f.getType(), v)) {
            return null;
        }
        if (v.equalsIgnoreCase("inf") || v.equalsIgnoreCase("+inf")) {
            return Double.POSITIVE_INFINITY;
        }
        if (v.equalsIgnoreCase("-inf")) {
            return Double.NEGATIVE_INFINITY;
        }
        return Double.parseDouble(v.replace("_", ""));
    }

    /** Whether {@code v} is a value Casanovo can read as {@code type} — the {@link #typeErrors} rule. */
    private static boolean readsAs(ConfigField.Type type, String v) {
        return switch (type) {
            case INT -> isInteger(v);
            case FLOAT -> isDecimal(v);
            default -> false; // nothing else carries a number the comparisons can use
        };
    }

    /**
     * Render a comparison value the way it was written: 6 rather than 6.0, but 0.5 intact. A
     * magnitude no {@code long} can hold is left in its decimal form — a saturated cast would print
     * a number the user never typed, in the one message whose job is to point at theirs.
     */
    private static String trim(double d) {
        return !Double.isInfinite(d) && d == Math.rint(d) && Math.abs(d) < 0x1p63
                ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static boolean isInteger(String v) {
        return INTEGER.matcher(v).matches();
    }

    private static boolean isDecimal(String v) {
        // "inf" is how Casanovo's own config disables precursor_mass_tol, and CasanovoConfig quotes
        // it on the way out so YAML keeps it a string for float() to read.
        return v.equalsIgnoreCase("inf") || v.equalsIgnoreCase("+inf") || v.equalsIgnoreCase("-inf")
                || DECIMAL.matcher(v).matches();
    }

}
