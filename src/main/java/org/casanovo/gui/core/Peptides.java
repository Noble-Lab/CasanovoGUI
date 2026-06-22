package org.casanovo.gui.core;

import java.util.Locale;

/** Small peptide-sequence helpers shared across the GUI. */
public final class Peptides {

    private Peptides() {
    }

    /**
     * Strip everything but letters and upper-case them — the bare amino-acid backbone, matching
     * how pepmap normalizes its input and how Casanovo's per-residue scores align to residues.
     */
    public static String bare(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= 'A' && ch <= 'Z') {
                sb.append(ch);
            } else if (ch >= 'a' && ch <= 'z') {
                sb.append((char) (ch - 32));
            }
        }
        return sb.toString();
    }

    /** Parse a comma-separated list of per-residue scores (e.g. Casanovo's aa_scores). */
    public static double[] parseScores(String csv) {
        if (csv == null || csv.isBlank()) {
            return new double[0];
        }
        String[] parts = csv.split(",");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException e) {
                out[i] = Double.NaN;
            }
        }
        return out;
    }
}
