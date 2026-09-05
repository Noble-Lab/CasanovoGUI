package org.casanovo.gui.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The GUI's pre-flight checks exist because glissade reports a bad input by printing a line and
 * exiting <em>zero</em>: without these, an unusable file looks like a successful run that produced
 * nothing.
 */
class GlissadeChecksTest {

    @Test
    @DisplayName("De novo format comes from the real extension, case-insensitively")
    void denovoFormatBySuffix() {
        assertEquals(GlissadeChecks.DenovoFormat.MZTAB, GlissadeChecks.denovoFormat("run.mzTab"));
        assertEquals(GlissadeChecks.DenovoFormat.MZTAB, GlissadeChecks.denovoFormat("RUN.MZTAB"));
        assertEquals(GlissadeChecks.DenovoFormat.CSV, GlissadeChecks.denovoFormat("preds.csv"));
        assertEquals(GlissadeChecks.DenovoFormat.TAB, GlissadeChecks.denovoFormat("out.tab"));
        assertEquals(GlissadeChecks.DenovoFormat.UNSUPPORTED, GlissadeChecks.denovoFormat("out.txt"));
        assertEquals(GlissadeChecks.DenovoFormat.UNSUPPORTED, GlissadeChecks.denovoFormat(null));
    }

    @Test
    @DisplayName("A folder named like another format cannot hijack the suffix")
    void folderNameDoesNotDecideFormat() {
        // glissade would call this InstaNovo CSV, because it matches '.csv' anywhere in the path.
        assertEquals(GlissadeChecks.DenovoFormat.MZTAB,
                GlissadeChecks.denovoFormat("C:\\data\\results.csv\\run.mztab"));
    }

    @Test
    @DisplayName("Where the extension and glissade's path matching disagree, the run is refused")
    void ambiguousPathIsCaught() {
        // glissade tests '.mztab' before '.csv' before '.tab', on the WHOLE path, so an earlier
        // marker anywhere wins: this InstaNovo CSV would be handed to the mzTab reader.
        String hijacked = "/data/results.mztab/preds.csv";
        assertEquals(GlissadeChecks.DenovoFormat.CSV, GlissadeChecks.denovoFormat(hijacked));
        assertEquals(GlissadeChecks.DenovoFormat.MZTAB, GlissadeChecks.glissadeDenovoFormat(hijacked));
        assertFalse(GlissadeChecks.formatIsUnambiguous(hijacked));
        // A double extension does it too, with no folder involved.
        assertFalse(GlissadeChecks.formatIsUnambiguous("/data/run.mztab.csv"));
        // The reverse order is harmless: '.mztab' is tested first, so it still wins and both agree.
        assertTrue(GlissadeChecks.formatIsUnambiguous("/data/results.csv/run.mztab"));
        assertTrue(GlissadeChecks.formatIsUnambiguous("/data/run 1/out.mzTab"));
        assertTrue(GlissadeChecks.formatIsUnambiguous("/data/preds.csv"));
        assertEquals(GlissadeChecks.DenovoFormat.UNSUPPORTED, GlissadeChecks.glissadeDenovoFormat(null));
    }

    @Test
    @DisplayName("Percolator inputs are recognised exactly as glissade recognises them")
    void percolatorSubstring() {
        assertTrue(GlissadeChecks.looksLikePercolator("/data/percolator.target.psms.txt"));
        assertTrue(GlissadeChecks.looksLikePercolator("/percolator/psms.txt"), "folder counts too");
        assertFalse(GlissadeChecks.looksLikePercolator("/data/psms.txt"));
        // glissade's check is case-sensitive; ours must not be more permissive than the tool.
        assertFalse(GlissadeChecks.looksLikePercolator("/data/Percolator.target.psms.txt"));
        assertFalse(GlissadeChecks.looksLikePercolator(null));
    }

    @Test
    @DisplayName("An mzML-run mzTab has scan= refs; an MGF-run one does not")
    void mzTabScanRefs(@TempDir Path dir) throws IOException {
        Path good = write(dir.resolve("mzml.mztab"),
                "MTD\tmzTab-version\t1.0.0\n"
                        + "PSH\tsequence\tPSM_ID\tspectra_ref\n"
                        + "PSM\tPEPTIDE\t1\tms_run[1]:controllerType=0 controllerNumber=1 scan=5\n");
        assertTrue(GlissadeChecks.mzTabHasScanRefs(good.toFile()));

        Path bad = write(dir.resolve("mgf.mztab"),
                "PSH\tsequence\tPSM_ID\tspectra_ref\n"
                        + "PSM\tPEPTIDE\t1\tms_run[1]:index=5\n");
        assertFalse(GlissadeChecks.mzTabHasScanRefs(bad.toFile()));
    }

    @Test
    @DisplayName("A file we cannot judge is not condemned")
    void unjudgeableMzTabPasses(@TempDir Path dir) throws IOException {
        assertTrue(GlissadeChecks.mzTabHasScanRefs(dir.resolve("missing.mztab").toFile()));
        Path headerOnly = write(dir.resolve("empty.mztab"), "PSH\tsequence\tspectra_ref\n");
        assertTrue(GlissadeChecks.mzTabHasScanRefs(headerOnly.toFile()), "no PSM rows to judge");
    }

    @Test
    @DisplayName("CRLF in a FASTA is detected, LF is not")
    void crlfDetection(@TempDir Path dir) throws IOException {
        assertTrue(GlissadeChecks.hasCrlf(write(dir.resolve("crlf.fasta"),
                ">sp|P1\r\nPEPTIDER\r\n").toFile()));
        assertFalse(GlissadeChecks.hasCrlf(write(dir.resolve("lf.fasta"),
                ">sp|P1\nPEPTIDER\n").toFile()));
        assertFalse(GlissadeChecks.hasCrlf(dir.resolve("nope.fasta").toFile()));
    }

    private static Path write(Path path, String text) throws IOException {
        return Files.writeString(path, text, StandardCharsets.UTF_8);
    }
}
