package org.casanovo.gui.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Pure input checks that mirror how <a href="https://github.com/Noble-Lab/glissade">glissade</a>
 * decides what it has been handed, so the GUI can refuse an input inline instead of letting the
 * tool exit quietly.
 *
 * <p>glissade sniffs formats by <em>substring of the path it was given</em>: a database file is
 * Percolator output iff {@code 'percolator' in path}, and a de novo file is mzTab / InstaNovo /
 * DeepNovo by {@code '.mztab'} / {@code '.csv'} / {@code '.tab'} appearing anywhere in the path.
 * Anything else prints "Unsupported &hellip; file format" and calls a bare {@code exit()} &mdash;
 * which is exit code <b>0</b>, so a caller that trusts the exit code sees a silent success with no
 * output file.</p>
 *
 * <p>The suffix checks here are deliberately <em>stricter</em> than glissade's: matching the honest
 * file extension stops a directory named {@code results.csv/} from routing an mzTab to the
 * InstaNovo reader.</p>
 */
public final class GlissadeChecks {

    /** How glissade will read a de novo result, decided from its file name. */
    public enum DenovoFormat {
        /** Casanovo mzTab. */
        MZTAB,
        /** InstaNovo CSV. */
        CSV,
        /** DeepNovo tab-separated. */
        TAB,
        /** Nothing glissade can read. */
        UNSUPPORTED
    }

    /** How much of a FASTA is scanned for CRLF line endings. */
    private static final int CRLF_SCAN_BYTES = 64 * 1024;

    private GlissadeChecks() {
    }

    /**
     * The reader glissade will use for {@code path}, by file extension. Case-insensitive: the
     * suffix is the user's spelling of the same file, and Casanovo itself writes {@code .mztab}
     * while other tools write {@code .mzTab}.
     */
    public static DenovoFormat denovoFormat(String path) {
        if (path == null) {
            return DenovoFormat.UNSUPPORTED;
        }
        String name = new File(path.trim()).getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".mztab")) {
            return DenovoFormat.MZTAB;
        }
        if (name.endsWith(".csv")) {
            return DenovoFormat.CSV;
        }
        if (name.endsWith(".tab")) {
            return DenovoFormat.TAB;
        }
        return DenovoFormat.UNSUPPORTED;
    }

    /**
     * Whether glissade will accept {@code path} as a database search result. It looks for the
     * literal, lower-case substring {@code percolator} anywhere in the path it is passed, so this
     * asks exactly the same question &mdash; a Percolator file renamed to something else is
     * rejected by glissade however valid its contents are.
     */
    public static boolean looksLikePercolator(String path) {
        return path != null && path.contains("percolator");
    }

    /**
     * Whether the first PSM row of an mzTab carries a {@code scan=} spectrum reference, which is
     * what glissade splits on to join de novo peptides to database PSMs. A run over mzML writes
     * {@code ms_run[1]:controllerType=0 controllerNumber=1 scan=5}; a run over MGF writes
     * {@code index=N} instead, on which glissade raises {@code IndexError}.
     *
     * <p>Reads at most the header plus the first PSM row. A file that cannot be read, or that has
     * no PSM rows at all, returns {@code true}: this check exists to catch a specific, known-bad
     * shape, not to second-guess every file.</p>
     */
    public static boolean mzTabHasScanRefs(File mzTab) {
        if (mzTab == null || !mzTab.isFile()) {
            return true;
        }
        try (BufferedReader r = Files.newBufferedReader(mzTab.toPath(), StandardCharsets.UTF_8)) {
            String line;
            int psmColumn = -1;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("PSH")) {
                    String[] header = line.split("\t", -1);
                    for (int i = 0; i < header.length; i++) {
                        if ("spectra_ref".equals(header[i].trim())) {
                            psmColumn = i;
                            break;
                        }
                    }
                } else if (line.startsWith("PSM")) {
                    if (psmColumn < 0) {
                        return true; // no spectra_ref column to judge
                    }
                    String[] cells = line.split("\t", -1);
                    return psmColumn < cells.length && cells[psmColumn].contains("scan=");
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Unreadable here is not a verdict; glissade will report its own error.
        }
        return true;
    }

    /**
     * Whether the start of {@code file} uses CRLF line endings. glissade builds its reference
     * sequence with {@code line[:-1]}, which strips only the {@code \n} &mdash; on a CRLF file the
     * {@code \r} stays inside the sequence, so a peptide spanning a line wrap is not found in the
     * reference and is misclassified as a de novo discovery. Worth a console warning; the GUI must
     * not rewrite the user's file.
     */
    public static boolean hasCrlf(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        try (InputStream in = Files.newInputStream(file.toPath())) {
            byte[] buf = new byte[CRLF_SCAN_BYTES];
            int n = in.readNBytes(buf, 0, buf.length);
            for (int i = 0; i + 1 < n; i++) {
                if (buf[i] == '\r' && buf[i + 1] == '\n') {
                    return true;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // best-effort warning only
        }
        return false;
    }
}
