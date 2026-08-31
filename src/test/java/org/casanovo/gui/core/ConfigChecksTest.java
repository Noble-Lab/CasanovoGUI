package org.casanovo.gui.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigChecksTest {

    private final List<ConfigField> fields = new CasanovoConfig().getFields();

    private List<String> typeErrors(String... keyValues) {
        return ConfigChecks.typeErrors(fields, values(keyValues));
    }

    private List<String> warnings(String... keyValues) {
        return ConfigChecks.sanityWarnings(fields, values(keyValues));
    }

    private static Map<String, String> values(String... keyValues) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("Casanovo's defaults raise nothing, so an untouched dialog never nags")
    void defaultsAreClean() {
        Map<String, String> defaults = new HashMap<>();
        for (ConfigField f : fields) {
            defaults.put(f.getKey(), f.getValue());
        }
        assertEquals(List.of(), ConfigChecks.typeErrors(fields, defaults));
        assertEquals(List.of(), ConfigChecks.sanityWarnings(fields, defaults));
    }

    @Test
    @DisplayName("A value Casanovo cannot read as the field's type is reported by label")
    void unreadableValuesAreReported() {
        // Casanovo 5.2.1: "Incorrect type for configuration value precursor_mass_tol: could not
        // convert string to float: 'abc'" — a failed run, and only the YAML key to go on.
        List<String> problems = typeErrors("precursor_mass_tol", "abc");
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).startsWith("Precursor m/z tolerance (ppm): \"abc\""), problems.get(0));
    }

    @Test
    @DisplayName("A fraction in an integer field is refused, because Casanovo would silently truncate it")
    void fractionalIntegerIsRefused() {
        // Verified against 5.2.1: min_peptide_len: 1.5 loads as 1. Accepting it would leave the
        // dialog showing a value the run never used.
        assertEquals(1, typeErrors("min_peptide_len", "1.5").size());
        assertEquals(1, typeErrors("min_peptide_len", "1e3").size(), "int('1e3') fails in Casanovo");
    }

    @Test
    @DisplayName("A leading zero is refused, because PyYAML reads it as octal")
    void leadingZeroIsRefused() {
        // Confirmed with PyYAML 6.0.3: "k: 010" loads as the int 8 (YAML 1.1 octal), so the run
        // would use 8 while the dialog kept showing 010 — the same silent disagreement as 1.5.
        assertEquals(1, typeErrors("min_peptide_len", "010").size());
        assertEquals(1, typeErrors("isotope_error_range", "010, 1").size());
        assertEquals(List.of(), typeErrors("min_peptide_len", "0"), "a plain zero is still a value");
    }

    @Test
    @DisplayName("An underscore is judged where PyYAML puts it, not stripped before the check")
    void underscoresAreJudgedWherePyYamlPutsThem() {
        // Confirmed against PyYAML 6.0.3: "1_024", "1__0" and "5_" load as integers, "_5" stays the
        // string "_5" and Casanovo's int() then raises the type error this check exists to precede.
        assertEquals(List.of(), typeErrors("min_peptide_len", "1_024"));
        assertEquals(List.of(), typeErrors("min_peptide_len", "1__0"));
        assertEquals(List.of(), typeErrors("min_peptide_len", "5_"));
        assertEquals(1, typeErrors("min_peptide_len", "_5").size());
    }

    @Test
    @DisplayName("The forms Casanovo does accept are left alone")
    void acceptedFormsPass() {
        assertEquals(List.of(), typeErrors(
                "predict_batch_size", "1_024",       // PyYAML reads the underscore as a separator
                "devices", "-1",
                "precursor_mass_tol", "inf",         // written quoted, read by float()
                "learning_rate", "5e-4",
                "weight_decay", ".5",
                "isotope_error_range", "[0, 1]",
                "enzyme", "trypsin",                 // free text is Casanovo's to judge
                "lance_dir", "C:\\some\\path"));
    }

    @Test
    @DisplayName("A blank is not an error: it leaves Casanovo's own default in force")
    void blanksAreNotErrors() {
        assertEquals(List.of(), typeErrors("min_peptide_len", "", "precursor_mass_tol", "   "));
    }

    @Test
    @DisplayName("Every entry of an integer list is checked, not just the first")
    void everyListEntryIsChecked() {
        List<String> problems = typeErrors("isotope_error_range", "0, x, y");
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("\"x\""), problems.get(0));
    }

    @Test
    @DisplayName("A minimum above its maximum is reported with both values")
    void invertedPairIsReported() {
        // Verified against 5.2.1: this run exits 0, writes an mzTab with no PSM row, and prints
        // "Peptide Precision: 0.00%" — a misconfiguration that reads like a result.
        List<String> problems = warnings("min_peptide_len", "60", "max_peptide_len", "10");
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("Min peptide length (60)"), problems.get(0));
        assertTrue(problems.get(0).contains("Max peptide length (10)"), problems.get(0));
    }

    @Test
    @DisplayName("A blank side of a pair is judged by Casanovo's default, not skipped")
    void blankIsJudgedByItsDefault() {
        // Clearing "Min peptide length" leaves Casanovo's 6 in force, so a max of 3 is still broken.
        List<String> problems = warnings("min_peptide_len", "", "max_peptide_len", "3");
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("Min peptide length (6)"), problems.get(0));
    }

    @Test
    @DisplayName("A count below one is reported for each setting that cannot survive it")
    void floorsAreReported() {
        // All verified end to end against 5.2.1 on 50 annotated spectra: max_charge/max_peaks die
        // inside depthcharge with a bare StopIteration, top_match/n_beams exit 0 with no PSM at all.
        assertEquals(List.of("Max charge is -1: every spectrum is skipped as over-charged."),
                warnings("max_charge", "-1"));
        assertEquals(1, warnings("top_match", "0").size());
        assertEquals(1, warnings("n_beams", "0").size());
        assertEquals(1, warnings("predict_batch_size", "0").size());
        // A max below its own minimum satisfies the pair rule and the floor by the same route, so
        // it is reported once: two sentences would read as two mistakes where the user made one.
        assertEquals(1, warnings("max_peaks", "0").size(), warnings("max_peaks", "0").toString());
        assertTrue(warnings("max_peaks", "0").get(0).startsWith("Min peaks (20) is above Max peaks (0)"),
                warnings("max_peaks", "0").toString());
    }

    @Test
    @DisplayName("A value the type check refuses is never also reported as impossible")
    void unreadableValuesAreNotWarnedAboutTwice() {
        // Only ConfigDialog's ordering keeps these apart today; the checks have to agree on their
        // own, or another caller gets two contradictory reports on one field.
        for (String bad : List.of("abc", "1.5", "010", "inf")) {
            assertEquals(1, typeErrors("max_charge", bad).size(), bad);
            assertEquals(List.of(), warnings("max_charge", bad), bad);
        }
    }

    @Test
    @DisplayName("A number too large for a long is reported as the user wrote it")
    void hugeValuesArePrintedAsTyped() {
        // A saturating (long) cast would print 9223372036854775807 — a number nowhere in the dialog,
        // in the one message whose job is to point at the value that is wrong.
        for (String huge : List.of("99999999999999999999", "9223372036854775808")) {
            // The second is Long.MAX_VALUE + 1, which widens to exactly (double) Long.MAX_VALUE — a
            // "<= Long.MAX_VALUE" guard lets it through and the cast saturates.
            List<String> problems = warnings("min_peaks", huge);
            assertEquals(1, problems.size(), problems.toString());
            assertFalse(problems.get(0).contains("9223372036854775807"), problems.get(0));
        }
    }

    @Test
    @DisplayName("The effective value is the user's, or Casanovo's default when the field is blank")
    void effectiveFallsBackToTheDefault() {
        assertEquals(5, ConfigChecks.effectiveInt(fields, values("top_match", "5"), "top_match", 1));
        assertEquals(1, ConfigChecks.effectiveInt(fields, values("top_match", ""), "top_match", 9),
                "blank means Casanovo's own default of 1, not the caller's fallback");
        assertEquals(9, ConfigChecks.effectiveInt(fields, values("top_match", "abc"), "top_match", 9),
                "an unreadable value is left to the type check, which has already refused it");
        assertEquals(9, ConfigChecks.effectiveInt(fields, Map.of(), "no_such_key", 9));
        assertEquals("1", ConfigChecks.effective(fields, Map.of(), "top_match"),
                "with no editor values, the field's own current value is read");
    }

    @Test
    @DisplayName("Casanovo's type error is traced back to the key it names")
    void typeErrorKeyIsExtracted() {
        assertEquals("precursor_mass_tol", ConfigChecks.typeErrorKey(
                "ERROR: Incorrect type for configuration value precursor_mass_tol: could not "
                        + "convert string to float: 'abc'"));
        assertNull(ConfigChecks.typeErrorKey("INFO: Casanovo version 5.2.1"));
        assertNull(ConfigChecks.typeErrorKey("StopIteration"));
    }
}
