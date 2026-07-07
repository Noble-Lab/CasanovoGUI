package org.casanovo.gui.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Bruker timsTOF ({@code .d}) support. A {@code .d} is a <em>directory</em> (unlike mzML/MGF
 * files), read natively by Casanovo 5.2+ (no conversion) — so the GUI just passes the folder path.
 *
 * <p>timsTOF data needs the {@code timstof} model, whose checkpoint was trained with a different
 * residue vocabulary than the default; Casanovo's {@code _validate_vocab_compatibility} rejects a
 * config whose {@code residues} don't match. Those timsTOF residues (and {@code max_peaks: 500})
 * live only in Casanovo's shipped {@code config_timstof.yaml}; this class reads them from the
 * installed package (version-matched) so the GUI can apply them when a {@code .d} run is set up.</p>
 */
public final class TimsTof {

    private TimsTof() {
    }

    /** A timsTOF folder: a directory named {@code *.d} that contains a {@code .tdf} file. */
    public static boolean isDotD(File f) {
        return f != null && f.isDirectory()
                && f.getName().toLowerCase(Locale.ROOT).endsWith(".d")
                && hasTdf(f);
    }

    /** True if {@code dir} directly holds a Bruker TDF database file (e.g. {@code analysis.tdf}). */
    private static boolean hasTdf(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return false;
        }
        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".tdf")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The canonical spectrum-input <em>type</em> of a path: {@code ".d"} for a Bruker folder (by name),
     * otherwise the lowercased file extension of the final path segment (e.g. {@code "mzml"},
     * {@code "mgf"}). Empty for a blank path or one with no extension.
     */
    public static String spectrumType(String path) {
        if (path == null) {
            return "";
        }
        String p = path.trim();
        if (p.isEmpty()) {
            return "";
        }
        if (looksLikeDotD(p)) {
            return ".d";
        }
        int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        String name = slash >= 0 ? p.substring(slash + 1) : p;
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    /**
     * True when {@code paths} contains more than one distinct spectrum {@link #spectrumType type} —
     * e.g. mixing mzML with MGF, or files with a {@code .d} folder. A run's inputs must all be one type
     * so a single model applies and downstream tools (e.g. PDV) can open them consistently.
     */
    public static boolean hasMixedSpectrumTypes(List<String> paths) {
        if (paths == null) {
            return false;
        }
        String seen = null;
        for (String p : paths) {
            String t = spectrumType(p);
            if (t.isEmpty()) {
                continue;
            }
            if (seen == null) {
                seen = t;
            } else if (!seen.equals(t)) {
                return true;
            }
        }
        return false;
    }

    /** True if any of {@code paths} is a timsTOF {@code .d} folder. */
    public static boolean anyDotD(List<String> paths) {
        if (paths == null) {
            return false;
        }
        for (String p : paths) {
            if (p != null && !p.isBlank() && isDotD(new File(p.trim()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A path that <em>names</em> a timsTOF {@code .d} folder (its final segment ends in {@code .d}),
     * regardless of whether it currently exists or still has its {@code .tdf}. Used to classify an
     * already-selected entry so a momentarily-unavailable {@code .d} (e.g. an unmounted share) is not
     * mistaken for a spectrum file; use {@link #isDotD(File)} for a real add-time validity check.
     */
    public static boolean looksLikeDotD(String path) {
        if (path == null) {
            return false;
        }
        String p = path.trim();
        while (p.endsWith("/") || p.endsWith("\\")) {
            p = p.substring(0, p.length() - 1);
        }
        return p.toLowerCase(Locale.ROOT).endsWith(".d");
    }

    /** The timsTOF {@code residues} block + {@code max_peaks} value, as the GUI config stores them. */
    public record Profile(String residues, String maxPeaks) {
    }

    /**
     * Read the timsTOF {@code residues} + {@code max_peaks} from the installed Casanovo's
     * {@code config_timstof.yaml}, or empty if it can't be located/parsed (e.g. a bare-PATH/Conda
     * Casanovo whose venv layout can't be walked). The {@code residues} value is returned in the
     * GUI's text-block format — one {@code "KEY": value} line each, unindented (see
     * {@link CasanovoConfig} {@code appendBlock}).
     */
    public static Optional<Profile> profile(Settings settings) {
        Optional<Path> file = configFile(settings);
        if (file.isEmpty()) {
            return Optional.empty();
        }
        try {
            List<String> lines = Files.readAllLines(file.get(), StandardCharsets.UTF_8);
            String maxPeaks = null;
            StringBuilder residues = new StringBuilder();
            boolean inResidues = false;
            for (String line : lines) {
                // A max_peaks line is always a top-level key (column 0); residues entries are indented,
                // so this never matches inside the residues block regardless of the two keys' order.
                if (line.matches("^max_peaks:\\s*\\S.*")) {
                    maxPeaks = stripInlineComment(line.substring(line.indexOf(':') + 1).trim());
                }
                if (line.matches("^residues:\\s*")) {
                    inResidues = true;
                    continue;
                }
                if (inResidues) {
                    if (line.isBlank()) {
                        continue;
                    }
                    if (!Character.isWhitespace(line.charAt(0))) {
                        inResidues = false; // reached the next top-level key
                        continue;
                    }
                    String entry = stripInlineComment(line.trim()); // de-indent + drop trailing comment
                    if (!entry.isEmpty() && !entry.startsWith("#")) { // skip full-line comments
                        residues.append(entry).append('\n');
                    }
                }
            }
            if (residues.length() == 0) {
                return Optional.empty();
            }
            return Optional.of(new Profile(residues.toString().stripTrailing(),
                    maxPeaks == null ? "500" : maxPeaks));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Drop a trailing {@code # ...} YAML comment (a {@code #} preceded by whitespace), keeping the value. */
    private static String stripInlineComment(String s) {
        for (int i = 1; i < s.length(); i++) {
            if (s.charAt(i) == '#' && Character.isWhitespace(s.charAt(i - 1))) {
                return s.substring(0, i).trim();
            }
        }
        return s;
    }

    /** Locate {@code <site-packages>/casanovo/config_timstof.yaml} for the configured executable. */
    private static Optional<Path> configFile(Settings settings) {
        Optional<Path> root = PyVenv.venvRootForExecutable(settings.getCasanovoExecutable());
        if (root.isEmpty()) {
            return Optional.empty();
        }
        for (Path sp : PyVenv.sitePackages(root.get())) {
            Path cfg = sp.resolve("casanovo").resolve("config_timstof.yaml");
            if (Files.isRegularFile(cfg)) {
                return Optional.of(cfg);
            }
        }
        return Optional.empty();
    }
}
