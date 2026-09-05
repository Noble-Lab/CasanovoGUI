package org.casanovo.gui.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlissadeDiscoveriesTest {

    @Test
    @DisplayName("Rows are read by header name, not column position")
    void readsByHeaderName(@TempDir Path dir) throws IOException {
        Path tsv = write(dir, "q-value\tPeptide\tScore\n"
                + "0.001\tPEPTIDER\t-1.5\n"
                + "0.020\tSEQVENCE\t-3.25\n");
        List<GlissadeDiscoveries.Row> rows = GlissadeDiscoveries.read(tsv.toFile());
        assertEquals(2, rows.size());
        assertEquals("PEPTIDER", rows.get(0).peptide());
        assertEquals(-1.5, rows.get(0).score());
        assertEquals(0.001, rows.get(0).q());
    }

    @Test
    @DisplayName("An unparseable row is skipped, not fatal")
    void skipsBadRow(@TempDir Path dir) throws IOException {
        Path tsv = write(dir, "Peptide\tScore\tq-value\n"
                + "GOOD\t-1.0\t0.01\n"
                + "BAD\tnot-a-number\t0.02\n"
                + "\n"
                + "ALSOGOOD\t-2.0\t0.03\n");
        List<GlissadeDiscoveries.Row> rows = GlissadeDiscoveries.read(tsv.toFile());
        assertEquals(List.of("GOOD", "ALSOGOOD"), rows.stream().map(GlissadeDiscoveries.Row::peptide).toList());
    }

    @Test
    @DisplayName("An empty file is empty results; a foreign file is an error")
    void emptyVersusForeign(@TempDir Path dir) throws IOException {
        assertTrue(GlissadeDiscoveries.read(write(dir, "").toFile()).isEmpty());
        Path foreign = Files.writeString(dir.resolve("other.tsv"), "a\tb\n1\t2\n", StandardCharsets.UTF_8);
        IOException e = assertThrows(IOException.class,
                () -> GlissadeDiscoveries.read(foreign.toFile()));
        assertTrue(e.getMessage().contains("Not a glissade result"));
    }

    @Test
    @DisplayName("Filtering at a cutoff is inclusive of the cutoff itself")
    void filterAtCutoff() {
        List<GlissadeDiscoveries.Row> rows = List.of(
                new GlissadeDiscoveries.Row("A", -1, 0.005),
                new GlissadeDiscoveries.Row("B", -2, 0.010),
                new GlissadeDiscoveries.Row("C", -3, 0.011));
        assertEquals(List.of("A", "B"),
                GlissadeDiscoveries.atOrBelow(rows, 0.01).stream().map(GlissadeDiscoveries.Row::peptide).toList());
        assertEquals(3, GlissadeDiscoveries.atOrBelow(rows, 1.0).size());
        assertTrue(GlissadeDiscoveries.atOrBelow(rows, 0.001).isEmpty());
    }

    @Test
    @DisplayName("pi0 is read off glissade's console line and nowhere else")
    void parsesPi0() {
        assertEquals(0.6321, GlissadeDiscoveries.parsePi0("Inferred pi0: 0.6321").getAsDouble());
        assertTrue(GlissadeDiscoveries.parsePi0("Total matched scores: 1840").isEmpty());
        assertTrue(GlissadeDiscoveries.parsePi0("Inferred pi0: nonsense").isEmpty());
        assertTrue(GlissadeDiscoveries.parsePi0(null).isEmpty());
    }

    @Test
    @DisplayName("The output folder keeps the extension, so two tools on one experiment cannot collide")
    void outputFolderKeepsExtension() {
        // The real case: Casanovo and DeepNovo results for one run differ only by extension, and
        // folding it away sent both to LFQ_Orbitrap_DDA_Human_01_glissade -- the second overwrote
        // the first, silently, because glissade has no output-directory flag.
        assertEquals("LFQ_Orbitrap_DDA_Human_01_mztab_glissade",
                GlissadeDiscoveries.outputFolderName("LFQ_Orbitrap_DDA_Human_01.mztab"));
        assertEquals("LFQ_Orbitrap_DDA_Human_01_tab_glissade",
                GlissadeDiscoveries.outputFolderName("LFQ_Orbitrap_DDA_Human_01.tab"));
        assertNotEquals(GlissadeDiscoveries.outputFolderName("x.mztab"),
                GlissadeDiscoveries.outputFolderName("x.tab"));
    }

    @Test
    @DisplayName("Odd names still produce one usable folder name")
    void outputFolderEdgeCases() {
        assertEquals("preds_csv_glissade", GlissadeDiscoveries.outputFolderName("preds.csv"));
        assertEquals("noext_glissade", GlissadeDiscoveries.outputFolderName("noext"));
        // A dotted stem keeps every dot but the last, which is the one naming the format.
        assertEquals("run.2026-09-04_mztab_glissade",
                GlissadeDiscoveries.outputFolderName("run.2026-09-04.mztab"));
        // A leading dot is the whole name, not an extension: do not leave the folder starting "_".
        assertEquals(".hidden_glissade", GlissadeDiscoveries.outputFolderName(".hidden"));
        assertEquals("_glissade", GlissadeDiscoveries.outputFolderName(null));
    }

    private static Path write(Path dir, String text) throws IOException {
        return Files.writeString(dir.resolve(GlissadeDiscoveries.OUTPUT_FILE), text,
                StandardCharsets.UTF_8);
    }
}
