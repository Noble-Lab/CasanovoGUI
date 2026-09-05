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
 * <p>{@link #denovoFormat} answers by the honest file extension, which is what the user means by
 * "this is an mzTab". {@link #glissadeDenovoFormat} answers the way glissade actually routes: it
 * tests {@code .mztab}, then {@code .csv}, then {@code .tab} against the <em>whole path</em>, so
 * the first marker to appear anywhere wins &mdash; {@code /data/results.mztab/preds.csv} is an
 * InstaNovo CSV that glissade hands to the mzTab reader. Where the two disagree glissade wins and
 * the run produces nothing, so {@link #formatIsUnambiguous} catches it before the run rather than
 * leaving the user with a silent empty result.</p>
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
     * The reader glissade will actually use for {@code path}: it matches {@code .mztab},
     * {@code .csv} and {@code .tab} anywhere in the path it was given, in that order, so a folder
     * name decides the format just as readily as the file name does.
     */
    public static DenovoFormat glissadeDenovoFormat(String path) {
        if (path == null) {
            return DenovoFormat.UNSUPPORTED;
        }
        String whole = path.trim().toLowerCase(Locale.ROOT);
        if (whole.contains(".mztab")) {
            return DenovoFormat.MZTAB;
        }
        if (whole.contains(".csv")) {
            return DenovoFormat.CSV;
        }
        if (whole.contains(".tab")) {
            return DenovoFormat.TAB;
        }
        return DenovoFormat.UNSUPPORTED;
    }

    /**
     * Whether the file's own extension and glissade's substring routing agree about {@code path}.
     * When they do not -- {@code C:\data\results.csv\run.mztab} is an mzTab that glissade hands to
     * the InstaNovo reader -- glissade prints "Unsupported ... file format", exits <em>zero</em>
     * and writes nothing. The GUI cannot change that routing, so the only useful moment is before
     * the run.
     */
    public static boolean formatIsUnambiguous(String path) {
        return denovoFormat(path) == glissadeDenovoFormat(path);
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
