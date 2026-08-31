package org.casanovo.gui.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The length-normalized score is what a user filters on when long peptides matter, so it has to
 * be the geometric mean of exactly the per-residue scores Casanovo reports — not an approximation
 * recovered from the peptide score, which also carries the stop token's probability.
 */
class MzTabScoresTest {

    /** Two real Casanovo v5.2.0 PSMs: a confident 10-mer and a poor 19-mer. */
    private static final String MZTAB = String.join("\n",
            "MTD\tmzTab-version\t1.0.0",
            "PSH\tsequence\tPSM_ID\tsearch_engine_score[1]\tspectra_ref\topt_global_aa_scores",
            "PSM\tKGPGRPTGSK\t1\t0.9029047\tms_run[1]:scan=1882\t"
                    + "0.99139,0.99144,0.99202,0.98999,0.99405,0.98964,0.98897,0.99026,0.99054,0.99087",
            "PSM\tQSQPDEEDDDYFGDYDDDK\t2\t3.7628096e-09\tms_run[1]:scan=1891\t"
                    + "0.59800,0.68334,0.53114,0.37628,0.23653,0.25096,0.25719,0.30154,0.21720,0.20812,"
                    + "0.65462,0.13013,0.97844,0.93968,0.27323,0.40875,0.33287,0.37632,0.28541",
            "");

    private static Path writeMzTab() throws IOException {
        return write(MZTAB);
    }

    private static Path write(String content) throws IOException {
        Path f = Files.createTempFile("casanovogui_scores_", ".mztab");
        f.toFile().deleteOnExit();
        Files.writeString(f, content, StandardCharsets.UTF_8);
        return f;
    }

    /**
     * What Casanovo 5.2.1 writes when a parameter excludes every candidate: the PSM header, and
     * nothing under it. Captured from a run with min_peptide_len 60 above max_peptide_len 10, which
     * exits 0 and reports "Peptide Precision: 0.00%".
     */
    private static final String EMPTY_MZTAB = String.join("\n",
            "MTD\tmzTab-version\t1.0.0",
            "PSH\tsequence\tPSM_ID\tsearch_engine_score[1]\tspectra_ref\topt_global_aa_scores",
            "");

    @Test
    @DisplayName("A result with the PSM header but no row under it is reported as empty")
    void emptyResultIsDetected() throws IOException {
        assertTrue(MzTabScores.hasNoPsm(write(EMPTY_MZTAB).toFile()));
        assertFalse(MzTabScores.hasNoPsm(writeMzTab().toFile()),
                "a result with PSMs must never be called empty");
    }

    @Test
    @DisplayName("A file with no PSM section at all is not called empty")
    void nonResultFileIsNotEmpty() throws IOException {
        // findNewestMzTab takes whatever .mztab in the output folder is newest, so a half-flushed or
        // foreign file can reach this. Reporting it as "no peptide at all" would blame the user's
        // parameters for a file the run did not write; read() treats the same file as malformed.
        assertFalse(MzTabScores.hasNoPsm(write("MTD\tmzTab-version\t1.0.0\n").toFile()));
        assertFalse(MzTabScores.hasNoPsm(write("").toFile()), "a zero-byte file is not a result");
        assertFalse(MzTabScores.hasNoPsm(new java.io.File("no_such_file_9d3f.mztab")),
                "and neither is one that cannot be read");
    }

    @Test
    @DisplayName("The normalized score is the geometric mean of the per-residue scores")
    void geometricMean() {
        assertEquals(0.99091607298846,
                MzTabScores.normalizedScore(new double[]{0.99139, 0.99144, 0.99202, 0.98999, 0.99405,
                        0.98964, 0.98897, 0.99026, 0.99054, 0.99087}), 1e-12);
        // A residue Casanovo scored 0 must not send the whole peptide to zero: the score is clipped
        // to [eps, 1] exactly as Casanovo's own peptide score is (eps = np.finfo(np.float64).eps).
        double withZero = MzTabScores.normalizedScore(new double[]{0.9, 0.0, 0.9});
        assertEquals(5.644711473839881e-06, withZero, 1e-15);
    }

    @Test
    @DisplayName("No per-residue scores means no normalized score, rather than a wrong one")
    void missingScoresAreNaN() {
        assertTrue(Double.isNaN(MzTabScores.normalizedScore(new double[0])));
        assertTrue(Double.isNaN(MzTabScores.normalizedScore(null)));
        assertTrue(Double.isNaN(MzTabScores.normalizedScore(Peptides.parseScores("0.9,oops"))));
    }

    @Test
    @DisplayName("Both scores are read from the mzTab, and they rank long peptides differently")
    void bothScoresAreParsed() throws IOException {
        List<MzTabScores.Psm> psms = MzTabScores.read(writeMzTab().toFile());
        assertEquals(2, psms.size());

        MzTabScores.Psm shortPeptide = psms.get(0);
        MzTabScores.Psm longPeptide = psms.get(1);
        assertEquals(0.9029047, shortPeptide.score(MzTabScores.ScoreType.PEPTIDE), 1e-9);
        assertEquals(0.99091607298846, shortPeptide.score(MzTabScores.ScoreType.NORMALIZED), 1e-12);
        assertEquals(3.7628096e-09, longPeptide.score(MzTabScores.ScoreType.PEPTIDE), 1e-16);
        assertEquals(0.3668592377525631, longPeptide.score(MzTabScores.ScoreType.NORMALIZED), 1e-12);

        // The point of the option: a cutoff of 0.5 on the raw score keeps only the 10-mer, and the
        // 19-mer is nine orders of magnitude below it purely because it is long.
        assertTrue(shortPeptide.score(MzTabScores.ScoreType.PEPTIDE) >= 0.5);
        assertTrue(longPeptide.score(MzTabScores.ScoreType.PEPTIDE) < 1e-8);
    }

    @Test
    @DisplayName("The threshold sweep follows whichever score was selected")
    void cumulativeCountsFollowTheSelectedScore() throws IOException {
        List<MzTabScores.Psm> psms = MzTabScores.read(writeMzTab().toFile());

        MzTabScores.Curve raw = MzTabScores.cumulativeCounts(psms, MzTabScores.ScoreType.PEPTIDE, 0.0, 1.0, 0.05);
        MzTabScores.Curve norm = MzTabScores.cumulativeCounts(psms, MzTabScores.ScoreType.NORMALIZED, 0.0, 1.0, 0.05);
        int at035 = 7; // thresholds[7] == 0.35

        assertEquals(0.35, raw.thresholds()[at035], 1e-12);
        assertEquals(1, raw.psmCounts()[at035], "only the 10-mer clears 0.35 on the raw score");
        assertEquals(2, norm.psmCounts()[at035], "both clear 0.35 per residue");
    }

    @Test
    @DisplayName("A PSM with no per-residue scores is left out of the normalized sweep, not counted as zero")
    void psmsWithoutAaScoresAreExcluded() throws IOException {
        Path f = Files.createTempFile("casanovogui_noaa_", ".mztab");
        f.toFile().deleteOnExit();
        Files.writeString(f, String.join("\n",
                "PSH\tsequence\tPSM_ID\tsearch_engine_score[1]",
                "PSM\tPEPTIDEK\t1\t0.9",
                ""), StandardCharsets.UTF_8);

        List<MzTabScores.Psm> psms = MzTabScores.read(f.toFile());
        assertEquals(1, psms.size());
        assertTrue(Double.isNaN(psms.get(0).normScore()));

        MzTabScores.Curve norm = MzTabScores.cumulativeCounts(psms, MzTabScores.ScoreType.NORMALIZED, 0.0, 1.0, 0.05);
        assertEquals(0, norm.psmCounts()[0], "a PSM that cannot be scored is counted at no threshold");
        MzTabScores.Curve raw = MzTabScores.cumulativeCounts(psms, MzTabScores.ScoreType.PEPTIDE, 0.0, 1.0, 0.05);
        assertEquals(1, raw.psmCounts()[0]);
    }

    /**
     * {@link MzTabScores#readWithAaScores} is the reader production code actually calls (via
     * ViewPane); it must pick each peptide's best PSM under the requested {@link MzTabScores.ScoreType},
     * not always by the raw peptide score.
     */
    @Test
    @DisplayName("readWithAaScores picks the best PSM under the requested score type")
    void readWithAaScoresFollowsTheSelectedScore() throws IOException {
        Path f = Files.createTempFile("casanovogui_bestpsm_", ".mztab");
        f.toFile().deleteOnExit();
        Files.writeString(f, String.join("\n",
                "PSH\tsequence\tPSM_ID\tsearch_engine_score[1]\tspectra_ref\topt_global_aa_scores",
                // Same peptide, two PSMs: A has the higher raw score, B the higher per-residue mean.
                "PSM\tABCDEFGH\t1\t0.40\tms_run[1]:scan=10\t0.9,0.9,0.9,0.9,0.9,0.9,0.9,0.9",
                "PSM\tABCDEFGH\t2\t0.30\tms_run[1]:scan=20\t0.95,0.95,0.95,0.95,0.95,0.95,0.95,0.95",
                ""), StandardCharsets.UTF_8);

        MzTabScores.BestPsm underPeptide = MzTabScores.readWithAaScores(f.toFile(), MzTabScores.ScoreType.PEPTIDE)
                .bestByPeptide().get("ABCDEFGH");
        assertEquals(0.40, underPeptide.score(), 1e-9, "PSM A has the higher raw score");
        assertEquals("ms_run[1]:scan=10", underPeptide.spectraRef());

        MzTabScores.BestPsm underNormalized =
                MzTabScores.readWithAaScores(f.toFile(), MzTabScores.ScoreType.NORMALIZED)
                        .bestByPeptide().get("ABCDEFGH");
        assertEquals(0.95, underNormalized.score(), 1e-9, "PSM B has the higher per-residue mean");
        assertEquals("ms_run[1]:scan=20", underNormalized.spectraRef());
    }

    @Test
    @DisplayName("readWithAaScores omits a peptide from bestByPeptide when none of its PSMs have the "
            + "requested score, rather than falling back to the raw score")
    void readWithAaScoresOmitsPeptidesWithoutTheSelectedScore() throws IOException {
        Path f = Files.createTempFile("casanovogui_bestpsm_noaa_", ".mztab");
        f.toFile().deleteOnExit();
        Files.writeString(f, String.join("\n",
                "PSH\tsequence\tPSM_ID\tsearch_engine_score[1]",
                "PSM\tPEPTIDEK\t1\t0.9",
                ""), StandardCharsets.UTF_8);

        MzTabScores.Detailed underPeptide = MzTabScores.readWithAaScores(f.toFile(), MzTabScores.ScoreType.PEPTIDE);
        assertEquals(0.9, underPeptide.bestByPeptide().get("PEPTIDEK").score(), 1e-9);

        MzTabScores.Detailed underNormalized =
                MzTabScores.readWithAaScores(f.toFile(), MzTabScores.ScoreType.NORMALIZED);
        assertTrue(underNormalized.bestByPeptide().isEmpty(),
                "no PSM has a normalized score, so the peptide has no best-PSM entry");
    }

    /**
     * Plain {@code Double.compare} treats {@code NaN} as the largest value, so a naive descending
     * sort by score puts a PSM with no score of the selected type first, not last. AaScorePopup
     * treats row 0 as "the best PSM", so that PSM must never be a NaN-scored one.
     */
    @Test
    @DisplayName("readPsmRowsForPeptide sorts a PSM with no score of the selected type last, not first")
    void readPsmRowsForPeptideSortsNaNLast() throws IOException {
        Path f = Files.createTempFile("casanovogui_psmrows_nan_", ".mztab");
        f.toFile().deleteOnExit();
        Files.writeString(f, String.join("\n",
                "PSH\tsequence\tPSM_ID\tsearch_engine_score[1]\topt_global_aa_scores",
                // PSM 1 has no aa_scores (normScore NaN) but the higher raw score; PSM 2 has aa_scores.
                "PSM\tABCDEFGH\t1\t0.90\t",
                "PSM\tABCDEFGH\t2\t0.10\t0.8,0.8,0.8,0.8,0.8,0.8,0.8,0.8",
                ""), StandardCharsets.UTF_8);

        List<MzTabScores.PsmRow> rows = MzTabScores
                .readPsmRowsForPeptide(f.toFile(), "ABCDEFGH", MzTabScores.ScoreType.NORMALIZED).rows();
        assertEquals(2, rows.size());
        assertEquals("2", rows.get(0).values()[1], "the PSM with a normalized score (PSM_ID 2) sorts first");
        assertTrue(Double.isNaN(rows.get(1).normScore()));
    }
}
