package org.casanovo.gui.ui;

import javafx.scene.control.TextField;

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

    static String firstMissing(TextField field) {
        for (String p : split(field)) {
            if (!new File(p).exists()) {
                return "File not found: " + p;
            }
        }
        return null;
    }

    static String validateSingleFile(TextField field, String label) {
        if (isEmpty(field)) {
            return "Please choose " + label + ".";
        }
        String path = field.getText().trim();
        if (!new File(path).exists()) {
            return "File not found: " + path;
        }
        return null;
    }
}
