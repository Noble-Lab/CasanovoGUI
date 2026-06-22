package org.casanovo.gui.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads the PSM section of a Casanovo mzTab file and computes, over a range of
 * score thresholds, how many PSMs and how many distinct peptide sequences have a
 * peptide score ({@code search_engine_score[1]}) at or above each threshold.
 *
 * <p>Columns are located by name from the {@code PSH} header, so differences in
 * column order between Casanovo versions are tolerated.</p>
 */
public final class MzTabScores {

    /** One PSM: its peptide {@code sequence} and its {@code search_engine_score[1]}. */
    public record Psm(String sequence, double score) {
    }

    /** Threshold sweep: {@code thresholds[i]} -> ({@code psmCounts[i]}, {@code peptideCounts[i]}). */
    public record Curve(double[] thresholds, int[] psmCounts, int[] peptideCounts) {
    }

    /** The highest-scoring PSM of a peptide: its score, {@code spectra_ref}, and per-residue {@code aa_scores}. */
    public record BestPsm(double score, String spectraRef, double[] aaScores) {
    }

    /** PSMs plus, per bare peptide, its best-scoring PSM. */
    public record Detailed(List<Psm> psms, Map<String, BestPsm> bestByPeptide) {
    }

    /** One PSM row: all its mzTab column values, plus the parsed score and per-residue aa_scores. */
    public record PsmRow(String[] values, double score, double[] aaScores) {
    }

    /** The PSM rows of one peptide, with the mzTab PSM column names (best-scoring row first). */
    public record PsmTable(List<String> columns, List<PsmRow> rows) {
    }

    private static final String SEQUENCE_COL = "sequence";
    private static final String SCORE_COL = "search_engine_score[1]";

    private MzTabScores() {
    }

    /**
     * Parse the PSM rows of an mzTab file into (sequence, score) pairs. Rows whose
     * score cell is missing, {@code null}, or unparseable are skipped.
     *
     * @throws IOException if the file cannot be read or has no PSM section / required columns
     */
    public static List<Psm> read(File mzTab) throws IOException {
        List<Psm> psms = new ArrayList<>();
        int seqIdx = -1;
        int scoreIdx = -1;
        boolean sawHeader = false;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(Files.newInputStream(mzTab.toPath()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("PSH\t")) {
                    String[] h = line.split("\t", -1);
                    Map<String, Integer> idx = new HashMap<>();
                    for (int i = 0; i < h.length; i++) {
                        idx.put(h[i].trim().toLowerCase(Locale.ROOT), i);
                    }
                    Integer s = idx.get(SEQUENCE_COL);
                    Integer sc = idx.get(SCORE_COL);
                    if (s == null || sc == null) {
                        throw new IOException("mzTab PSM header is missing '" + SEQUENCE_COL
                                + "' or '" + SCORE_COL + "'.");
                    }
                    seqIdx = s;
                    scoreIdx = sc;
                    sawHeader = true;
                } else if (sawHeader && line.startsWith("PSM\t")) {
                    String[] c = line.split("\t", -1);
                    if (seqIdx >= c.length || scoreIdx >= c.length) {
                        continue;
                    }
                    double score = parse(c[scoreIdx]);
                    if (!Double.isNaN(score)) {
                        psms.add(new Psm(c[seqIdx], score));
                    }
                }
            }
        }
        if (!sawHeader) {
            throw new IOException("No PSM section (PSH header) found in " + mzTab.getName()
                    + ". Is this a Casanovo mzTab result file?");
        }
        return psms;
    }

    /**
     * Like {@link #read} but, in the same single pass, also keeps — per bare peptide — the
     * highest-scoring PSM's score, {@code spectra_ref}, and per-residue {@code aa_scores} (column
     * {@code opt_global_aa_scores}, or any column whose name contains {@code aa_scores}). Only the
     * best record per distinct peptide is retained, so memory stays proportional to distinct
     * peptides, not to total PSMs.
     */
    public static Detailed readWithAaScores(File mzTab) throws IOException {
        List<Psm> psms = new ArrayList<>();
        Map<String, Double> bestScore = new HashMap<>();
        Map<String, String> bestAa = new HashMap<>();
        Map<String, String> bestRef = new HashMap<>();
        int seqIdx = -1;
        int scoreIdx = -1;
        int aaIdx = -1;
        int refIdx = -1;
        boolean sawHeader = false;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(Files.newInputStream(mzTab.toPath()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("PSH\t")) {
                    String[] h = line.split("\t", -1);
                    Map<String, Integer> idx = new HashMap<>();
                    for (int i = 0; i < h.length; i++) {
                        idx.put(h[i].trim().toLowerCase(Locale.ROOT), i);
                    }
                    Integer s = idx.get(SEQUENCE_COL);
                    Integer sc = idx.get(SCORE_COL);
                    if (s == null || sc == null) {
                        throw new IOException("mzTab PSM header is missing '" + SEQUENCE_COL
                                + "' or '" + SCORE_COL + "'.");
                    }
                    seqIdx = s;
                    scoreIdx = sc;
                    Integer ref = idx.get("spectra_ref");
                    refIdx = (ref == null) ? -1 : ref;
                    for (Map.Entry<String, Integer> en : idx.entrySet()) {
                        if (en.getKey().contains("aa_scores")) {
                            aaIdx = en.getValue();
                            break;
                        }
                    }
                    sawHeader = true;
                } else if (sawHeader && line.startsWith("PSM\t")) {
                    String[] c = line.split("\t", -1);
                    if (seqIdx >= c.length || scoreIdx >= c.length) {
                        continue;
                    }
                    double score = parse(c[scoreIdx]);
                    if (Double.isNaN(score)) {
                        continue;
                    }
                    psms.add(new Psm(c[seqIdx], score));
                    String bare = Peptides.bare(c[seqIdx]);
                    if (!bare.isEmpty() && score > bestScore.getOrDefault(bare, Double.NEGATIVE_INFINITY)) {
                        bestScore.put(bare, score);
                        bestAa.put(bare, aaIdx >= 0 && aaIdx < c.length ? c[aaIdx] : "");
                        bestRef.put(bare, refIdx >= 0 && refIdx < c.length ? c[refIdx] : "");
                    }
                }
            }
        }
        if (!sawHeader) {
            throw new IOException("No PSM section (PSH header) found in " + mzTab.getName()
                    + ". Is this a Casanovo mzTab result file?");
        }
        Map<String, BestPsm> best = new HashMap<>();
        for (Map.Entry<String, Double> en : bestScore.entrySet()) {
            String bare = en.getKey();
            best.put(bare, new BestPsm(en.getValue(), bestRef.getOrDefault(bare, ""),
                    Peptides.parseScores(bestAa.getOrDefault(bare, ""))));
        }
        return new Detailed(psms, best);
    }

    /**
     * Scan the PSM section and return every PSM row of {@code barePeptide} (mods stripped for the
     * match), with the PSM column names, sorted by {@code search_engine_score[1]} descending. Each
     * row keeps all its raw column values plus the parsed score and per-residue {@code aa_scores}.
     */
    public static PsmTable readPsmRowsForPeptide(File mzTab, String barePeptide) throws IOException {
        List<String> columns = new ArrayList<>();
        List<PsmRow> rows = new ArrayList<>();
        int seqIdx = -1;
        int scoreIdx = -1;
        int aaIdx = -1;
        boolean sawHeader = false;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(Files.newInputStream(mzTab.toPath()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("PSH\t")) {
                    String[] h = line.split("\t", -1);
                    Map<String, Integer> idx = new HashMap<>();
                    for (int i = 0; i < h.length; i++) {
                        idx.put(h[i].trim().toLowerCase(Locale.ROOT), i);
                    }
                    Integer s = idx.get(SEQUENCE_COL);
                    if (s == null) {
                        throw new IOException("mzTab PSM header is missing '" + SEQUENCE_COL + "'.");
                    }
                    seqIdx = s;
                    Integer sc = idx.get(SCORE_COL);
                    scoreIdx = (sc == null) ? -1 : sc;
                    aaIdx = -1;
                    for (Map.Entry<String, Integer> en : idx.entrySet()) {
                        if (en.getKey().contains("aa_scores")) {
                            aaIdx = en.getValue();
                            break;
                        }
                    }
                    columns.clear();
                    for (int i = 1; i < h.length; i++) { // drop the leading "PSH" row-type cell
                        columns.add(h[i].trim());
                    }
                    sawHeader = true;
                } else if (sawHeader && line.startsWith("PSM\t")) {
                    String[] c = line.split("\t", -1);
                    if (seqIdx >= c.length || !Peptides.bare(c[seqIdx]).equals(barePeptide)) {
                        continue;
                    }
                    double score = (scoreIdx >= 0 && scoreIdx < c.length) ? parse(c[scoreIdx]) : Double.NaN;
                    double[] aa = (aaIdx >= 0 && aaIdx < c.length)
                            ? Peptides.parseScores(c[aaIdx]) : new double[0];
                    rows.add(new PsmRow(java.util.Arrays.copyOfRange(c, 1, c.length), score, aa));
                }
            }
        }
        if (!sawHeader) {
            throw new IOException("No PSM section (PSH header) found in " + mzTab.getName() + ".");
        }
        rows.sort((a, b) -> Double.compare(b.score(), a.score())); // best score first
        return new PsmTable(columns, rows);
    }

    /**
     * For thresholds from {@code min} to {@code max} (inclusive) in steps of
     * {@code step}, count the PSMs and the distinct peptide sequences whose score
     * is {@code >=} the threshold.
     */
    public static Curve cumulativeCounts(List<Psm> psms, double min, double max, double step) {
        if (step <= 0 || max < min) {
            throw new IllegalArgumentException(
                    "Invalid score range: min=" + min + " max=" + max + " step=" + step);
        }
        int n = (int) Math.round((max - min) / step) + 1;
        double[] thresholds = new double[n];
        for (int i = 0; i < n; i++) {
            thresholds[i] = min + i * step;
        }
        int[] psmCounts = new int[n];
        int[] peptideCounts = new int[n];

        // Sort by score descending and sweep thresholds high -> low, so each PSM
        // is folded in exactly once as the threshold drops past its score.
        List<Psm> sorted = new ArrayList<>(psms);
        sorted.sort((a, b) -> Double.compare(b.score(), a.score()));
        Set<String> sequences = new HashSet<>();
        int j = 0;
        int psmCount = 0;
        for (int i = n - 1; i >= 0; i--) {
            double t = thresholds[i];
            while (j < sorted.size() && sorted.get(j).score() >= t) {
                sequences.add(sorted.get(j).sequence());
                psmCount++;
                j++;
            }
            psmCounts[i] = psmCount;
            peptideCounts[i] = sequences.size();
        }
        return new Curve(thresholds, psmCounts, peptideCounts);
    }

    private static double parse(String s) {
        if (s == null) {
            return Double.NaN;
        }
        String t = s.trim();
        if (t.isEmpty() || t.equalsIgnoreCase("null") || t.equalsIgnoreCase("nan")) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
