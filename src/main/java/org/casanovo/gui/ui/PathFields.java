package org.casanovo.gui.ui;

import javafx.scene.control.TextField;

import org.casanovo.gui.core.TimsTof;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Helpers for validating and splitting path-list text fields. Multiple paths
 * are held in a single field separated by the platform path separator.
 */
final class PathFields {

    private PathFields() {
    }

    static boolean isEmpty(TextField field) {
        return field.getText() == null || field.getText().trim().isEmpty();
    }

    static List<String> split(TextField field) {
        List<String> out = new ArrayList<>();
        if (field.getText() == null) {
            return out;
        }
        for (String p : field.getText().split(Pattern.quote(File.pathSeparator))) {
            String t = p.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    static ValidationError firstMissing(TextField field) {
        for (String p : split(field)) {
            if (!new File(p).exists()) {
                return new ValidationError("File not found: " + p, field);
            }
        }
        return null;
    }

    static ValidationError validateSingleFile(TextField field, String label) {
        if (isEmpty(field)) {
            return new ValidationError("Please choose " + label + ".", field);
        }
        String path = field.getText().trim();
        if (!new File(path).exists()) {
            return new ValidationError("File not found: " + path, field);
        }
        return null;
    }

    /**
     * A run's spectrum inputs must all be one type (all mzML, all mzXML, all MGF, all raw, or all
     * {@code .d}) — a mix confuses the model choice and downstream PDV visualization. Returns an
     * inline error pointing at {@code field}, or {@code null} when the inputs are a single type.
     */
    static ValidationError validateSingleSpectrumType(TextField field) {
        if (TimsTof.hasMixedSpectrumTypes(split(field))) {
            return new ValidationError(
                    "All spectrum inputs must be the same type — all mzML, all mzXML, all MGF, all raw, "
                            + "or all .d — not a mix.", field);
        }
        return null;
    }
}
