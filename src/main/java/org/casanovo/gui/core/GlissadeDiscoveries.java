package org.casanovo.gui.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;

/**
 * Reads the single file glissade writes: {@code glissade_discoveries.tsv}, columns
 * {@code Peptide}, {@code Score} (natural-log de novo score) and {@code q-value}, sorted by score
 * descending.
 *
 * <p>glissade writes it into its <em>process working directory</em> and offers no output-directory
 * flag, so the caller controls where it lands by choosing the working directory.</p>
 */
public final class GlissadeDiscoveries {

    /** The file glissade writes, in whatever directory it was run from. */
    public static final String OUTPUT_FILE = "glissade_discoveries.tsv";

    /**
     * The folder a run on {@code denovoFileName} writes into, beside that file.
     *
     * <p>The extension is part of the name, not stripped off, because the three de novo tools are
     * routinely run on one experiment and named after it: {@code X.mztab} (Casanovo) and
     * {@code X.tab} (DeepNovo) differ only by extension, so folding it away gave both the same
     * folder and the second run silently overwrote the first. glissade offers no output-directory
     * flag, so this name is the only thing keeping two results apart.</p>
     *
     * @param denovoFileName the de novo file's name, without its directory
     * @return e.g. {@code X_mztab_glissade} for {@code X.mztab}, {@code X_glissade} for {@code X}
     */
    public static String outputFolderName(String denovoFileName) {
        String base = denovoFileName == null ? "" : denovoFileName.trim();
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            // "X.mztab" -> "X_mztab": the dot would read as an extension on the folder itself.
            base = base.substring(0, dot) + "_" + base.substring(dot + 1);
        }
        return base + "_glissade";
    }

    /** One accepted peptide: the sequence as glissade reports it, its log score and its q-value. */
    public record Row(String peptide, double score, double q) {
    }

    private GlissadeDiscoveries() {
    }

    /**
     * Parse {@code tsv}. Columns are located by header name rather than position, and a row whose
     * numbers do not parse is skipped rather than failing the whole file &mdash; a partially
     * written result should still show what it has.
     *
     * @throws IOException if the file cannot be read or carries no recognisable header
     */
    public static List<Row> read(File tsv) throws IOException {
        List<Row> rows = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(tsv.toPath(), StandardCharsets.UTF_8)) {
            String header = r.readLine();
            if (header == null) {
                return rows; // an empty file is empty results, not a malformed one
            }
            String[] cols = header.split("\t", -1);
            int pep = indexOf(cols, "peptide");
            int score = indexOf(cols, "score");
            int q = indexOf(cols, "q-value");
            if (pep < 0 || score < 0 || q < 0) {
                throw new IOException("Not a glissade result: expected Peptide/Score/q-value "
                        + "columns in " + tsv.getName());
            }
            int widest = Math.max(pep, Math.max(score, q));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] cells = line.split("\t", -1);
                if (cells.length <= widest) {
                    continue;
                }
                try {
                    rows.add(new Row(cells[pep].trim(),
                            Double.parseDouble(cells[score].trim()),
                            Double.parseDouble(cells[q].trim())));
                } catch (NumberFormatException ignored) {
                    // Skip an unparseable row; the rest of the file is still useful.
                }
            }
        }
        return rows;
    }

    /** The rows accepted at a q-value cutoff, inclusive of the cutoff itself. */
    public static List<Row> atOrBelow(List<Row> rows, double cutoff) {
        return rows.stream().filter(row -> row.q() <= cutoff).toList();
    }

    /**
     * The &pi;<sub>0</sub> glissade reports on its {@code Inferred pi0: 0.63} console line, or
     * empty when {@code line} is not that line. glissade prints it once, at the end of the fit,
     * and writes it nowhere else &mdash; the console is the only place to read it from.
     */
    public static OptionalDouble parsePi0(String line) {
        if (line == null) {
            return OptionalDouble.empty();
        }
        String marker = "Inferred pi0:";
        int at = line.indexOf(marker);
        if (at < 0) {
            return OptionalDouble.empty();
        }
        try {
            return OptionalDouble.of(Double.parseDouble(line.substring(at + marker.length()).trim()));
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }

    private static int indexOf(String[] header, String name) {
        for (int i = 0; i < header.length; i++) {
            if (header[i].trim().toLowerCase(Locale.ROOT).equals(name)) {
                return i;
            }
        }
        return -1;
    }
}
