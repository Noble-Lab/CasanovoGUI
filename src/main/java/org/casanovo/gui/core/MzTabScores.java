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

    /**
     * One PSM: its peptide {@code sequence}, its {@code search_engine_score[1]} (Casanovo's
     * peptide score) and its length-normalized score (see {@link #normalizedScore}). The
     * normalized score is {@code NaN} when the mzTab carries no per-residue scores.
     */
    public record Psm(String sequence, double score, double normScore) {

        /** This PSM's value under {@code type}. */
        public double score(ScoreType type) {
            return type == ScoreType.NORMALIZED ? normScore : score;
        }
    }

    /**
     * Which confidence score a peptide is judged by.
     *
     * <p>Casanovo's peptide score is essentially the <em>product</em> of the per-residue scores
     * (it carries one further factor that the mzTab does not write out), so it falls
     * geometrically with peptide length: a single cutoff therefore demands far more per-residue
     * confidence from a long peptide than from a short one, and long peptides are filtered out
     * almost regardless of how well they were sequenced. The normalized score is the geometric
     * mean of the same per-residue scores, which is on a per-residue scale and so comparable
     * across lengths.</p>
     */
    public enum ScoreType {
        /** {@code search_engine_score[1]} as Casanovo reports it: the product of per-residue scores. */
        PEPTIDE("Casanovo peptide score"),
        /** Geometric mean of the per-residue scores — the same quantity, per residue. */
        NORMALIZED("Geometric mean of AA scores");

        private final String label;

        ScoreType(String label) {
            this.label = label;
        }

        /** The name shown in the interface. */
        public String label() {
            return label;
        }
    }

    /**
     * The geometric mean of the per-residue scores, {@code exp(mean(log(aa)))} — Casanovo's peptide
     * score taken per residue rather than over the whole peptide, so that peptides of different
     * lengths can be compared on one scale. Scores are clipped to {@code [eps, 1]} exactly as
     * Casanovo's own peptide score does, so a single zero-probability residue does not send the
     * whole peptide to zero. Returns {@code NaN} for an empty or unparseable score list.
     */
    public static double normalizedScore(double[] aaScores) {
        if (aaScores == null || aaScores.length == 0) {
            return Double.NaN;
        }
        double eps = Math.ulp(1.0); // np.finfo(np.float64).eps, matching Casanovo's clip
        double sumLog = 0.0;
        for (double v : aaScores) {
            if (Double.isNaN(v)) {
                return Double.NaN;
            }
            sumLog += Math.log(Math.min(1.0, Math.max(eps, v)));
        }
        return Math.exp(sumLog / aaScores.length);
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

    /** One PSM row: all its mzTab column values, plus the parsed score, normalized score, and
     *  per-residue aa_scores. */
    public record PsmRow(String[] values, double score, double normScore, double[] aaScores) {

        /** This PSM row's value under {@code type}. */
        public double score(ScoreType type) {
            return type == ScoreType.NORMALIZED ? normScore : score;
        }
    }

    /** The PSM rows of one peptide, with the mzTab PSM column names (best-scoring row first). */
    public record PsmTable(List<String> columns, List<PsmRow> rows) {
    }

    private static final String SEQUENCE_COL = "sequence";
    private static final String SCORE_COL = "search_engine_score[1]";

    /** Index of the per-residue score column ({@code opt_global_aa_scores}), or -1 if absent. */
    private static int aaScoresIndex(Map<String, Integer> header) {
        for (Map.Entry<String, Integer> en : header.entrySet()) {
            if (en.getKey().contains("aa_scores")) {
                return en.getValue();
            }
        }
        return -1;
    }

    /** {@link #normalizedScore} of the aa_scores cell at {@code aaIdx}, or NaN when there is none. */
    private static double normScoreOf(String[] cells, int aaIdx) {
        if (aaIdx < 0 || aaIdx >= cells.length) {
            return Double.NaN;
        }
        return normalizedScore(Peptides.parseScores(cells[aaIdx]));
    }

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
                    Integer sc = idx.get(SCORE_COL);
                    if (s == null || sc == null) {
                        throw new IOException("mzTab PSM header is missing '" + SEQUENCE_COL
                                + "' or '" + SCORE_COL + "'.");
                    }
                    seqIdx = s;
                    scoreIdx = sc;
                    aaIdx = aaScoresIndex(idx);
                    sawHeader = true;
                } else if (sawHeader && line.startsWith("PSM\t")) {
                    String[] c = line.split("\t", -1);
                    if (seqIdx >= c.length || scoreIdx >= c.length) {
                        continue;
                    }
                    double score = parse(c[scoreIdx]);
                    if (!Double.isNaN(score)) {
                        psms.add(new Psm(c[seqIdx], score, normScoreOf(c, aaIdx)));
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
     * Like {@link #read} but, in the same single pass, also keeps — per bare peptide — the PSM
     * that scores highest under {@code type}: its score, {@code spectra_ref}, and per-residue
     * {@code aa_scores} (column {@code opt_global_aa_scores}, or any column whose name contains
     * {@code aa_scores}). A peptide whose PSMs all lack a score of that kind (e.g. NORMALIZED with
     * no aa_scores column) has no entry in the returned map. Only the best record per distinct
     * peptide is retained, so memory stays proportional to distinct peptides, not to total PSMs.
     *
     * <p><b>Caveat:</b> to skip the per-PSM normalized-score computation when it will never be
     * consulted, the returned {@link Detailed#psms}' {@code normScore} is left {@code NaN} whenever
     * {@code type} is {@link ScoreType#PEPTIDE} — even for a PSM whose mzTab row does carry
     * {@code aa_scores}. Only {@link #read}, and this method called with {@code NORMALIZED}, honor
     * {@link Psm}'s general contract that {@code normScore} being {@code NaN} means the mzTab has no
     * per-residue scores at all.</p>
     */
    public static Detailed readWithAaScores(File mzTab, ScoreType type) throws IOException {
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
                    aaIdx = aaScoresIndex(idx);
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
                    // Skip the split + log work when the normalized score isn't the one in use.
                    double normScore = type == ScoreType.NORMALIZED ? normScoreOf(c, aaIdx) : Double.NaN;
                    psms.add(new Psm(c[seqIdx], score, normScore));
                    String bare = Peptides.bare(c[seqIdx]);
                    double sc = type == ScoreType.NORMALIZED ? normScore : score;
                    if (!bare.isEmpty() && !Double.isNaN(sc)
                            && sc > bestScore.getOrDefault(bare, Double.NEGATIVE_INFINITY)) {
                        bestScore.put(bare, sc);
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
     * match), with the PSM column names, sorted by its score under {@code type} descending. Each
     * row keeps all its raw column values plus the parsed score, normalized score, and per-residue
     * {@code aa_scores}.
     */
    public static PsmTable readPsmRowsForPeptide(File mzTab, String barePeptide, ScoreType type)
            throws IOException {
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
                    aaIdx = aaScoresIndex(idx);
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
                    rows.add(new PsmRow(java.util.Arrays.copyOfRange(c, 1, c.length), score,
                            normalizedScore(aa), aa));
                }
            }
        }
        if (!sawHeader) {
            throw new IOException("No PSM section (PSH header) found in " + mzTab.getName() + ".");
        }
        // Best score first; a PSM with no score of the selected type sorts last, not first
        // (plain Double.compare treats NaN as the largest value).
        rows.sort((a, b) -> {
            double sa = a.score(type);
            double sb = b.score(type);
            if (Double.isNaN(sa)) {
                return Double.isNaN(sb) ? 0 : 1;
            }
            return Double.isNaN(sb) ? -1 : Double.compare(sb, sa);
        });
        return new PsmTable(columns, rows);
    }

    /**
     * Names of PSM columns that are empty ({@code null}, blank, or the literal {@code "null"} mzTab
     * missing-value marker) for <em>every</em> PSM in {@code mzTab}. Used to hide always-empty columns
     * from the per-residue PSM table.
     */
    public static List<String> detectEmptyPsmColumns(File mzTab) throws IOException {
        List<String> columns = new ArrayList<>();
        boolean[] hasValue = null;
        boolean sawHeader = false;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(Files.newInputStream(mzTab.toPath()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("PSH\t")) {
                    String[] h = line.split("\t", -1);
                    columns.clear();
                    for (int i = 1; i < h.length; i++) { // drop the leading "PSH" row-type cell
                        columns.add(h[i].trim());
                    }
                    hasValue = new boolean[columns.size()];
                    sawHeader = true;
                } else if (sawHeader && hasValue != null && line.startsWith("PSM\t")) {
                    String[] c = line.split("\t", -1);
                    for (int i = 0; i < hasValue.length; i++) {
                        int ci = i + 1; // +1 for the leading "PSM" row-type cell
                        if (ci < c.length && !isEmptyCell(c[ci])) {
                            hasValue[i] = true;
                        }
                    }
                }
            }
        }
        List<String> empty = new ArrayList<>();
        if (hasValue != null) {
            for (int i = 0; i < hasValue.length; i++) {
                if (!hasValue[i]) {
                    empty.add(columns.get(i));
                }
            }
        }
        return empty;
    }

    /** An mzTab missing-value cell: actual null, blank, or the literal {@code "null"} marker. */
    private static boolean isEmptyCell(String s) {
        return s == null || s.isBlank() || s.equalsIgnoreCase("null");
    }

    /**
     * For thresholds from {@code min} to {@code max} (inclusive) in steps of
     * {@code step}, count the PSMs and the distinct peptide sequences whose score
     * under {@code type} is {@code >=} the threshold. PSMs with no score of that
     * kind (NaN) are counted at no threshold.
     */
    public static Curve cumulativeCounts(List<Psm> psms, ScoreType type,
                                         double min, double max, double step) {
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
        sorted.removeIf(p -> Double.isNaN(p.score(type)));
        sorted.sort((a, b) -> Double.compare(b.score(type), a.score(type)));
        Set<String> sequences = new HashSet<>();
        int j = 0;
        int psmCount = 0;
        for (int i = n - 1; i >= 0; i--) {
            double t = thresholds[i];
            while (j < sorted.size() && sorted.get(j).score(type) >= t) {
                sequences.add(sorted.get(j).sequence());
                psmCount++;
                j++;
            }
            psmCounts[i] = psmCount;
            peptideCounts[i] = sequences.size();
        }
        return new Curve(thresholds, psmCounts, peptideCounts);
    }

    /**
     * Read the spectrum-file locations from the mzTab metadata ({@code MTD ms_run[k]-location
     * file:///...}), resolved to local {@link File}s — used to auto-detect the spectra for PDV. Only
     * the metadata section is scanned (parsing stops at the PSM/PRT/PEP section). {@code file:} URIs
     * are decoded (handling spaces / percent-encoding); a non-URI value is treated as a plain path.
     *
     * @throws IOException if the file cannot be read
     */
    public static List<File> readMsRunLocations(File mzTab) throws IOException {
        List<File> files = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(Files.newInputStream(mzTab.toPath()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("PSH") || line.startsWith("PSM")
                        || line.startsWith("PRT") || line.startsWith("PEP")) {
                    break; // past the metadata section
                }
                if (line.startsWith("MTD\t")) {
                    String[] c = line.split("\t", -1);
                    if (c.length >= 3 && c[1].matches("ms_run\\[\\d+\\]-location")) {
                        File f = locationToFile(c[2].trim());
                        if (f != null) {
                            files.add(f);
                        }
                    }
                }
            }
        }
        return files;
    }

    /** Convert an mzTab {@code ms_run-location} value (a {@code file:} URI or a plain path) to a File. */
    private static File locationToFile(String location) {
        if (location.isEmpty()) {
            return null;
        }
        if (location.startsWith("file:")) {
            try {
                // Encode spaces so URI parsing doesn't choke on raw (unencoded) paths.
                return java.nio.file.Paths.get(java.net.URI.create(location.replace(" ", "%20"))).toFile();
            } catch (RuntimeException e) {
                return new File(location.replaceFirst("^file:/{0,3}", ""));
            }
        }
        return new File(location);
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
