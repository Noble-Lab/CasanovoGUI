package org.casanovo.gui.core;

import java.util.Locale;

/**
 * Tiny operating-system helper: platform predicates and the Windows-only native
 * process safeguards, in one place so cross-platform behaviour is consistent.
 */
public final class Os {

    private static final String NAME = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private static final String ARCH = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

    private Os() {
    }

    public static boolean isWindows() {
        return NAME.contains("win");
    }

    public static boolean isMac() {
        return NAME.contains("mac") || NAME.contains("darwin");
    }

    public static boolean isLinux() {
        return NAME.contains("linux");
    }

    /** True on Apple Silicon / ARM64 (e.g. macOS aarch64). */
    public static boolean isAarch64() {
        return ARCH.contains("aarch64") || ARCH.contains("arm64");
    }

    /**
     * Apply the environment Casanovo's subprocess needs.
     *
     * <ul>
     *   <li><b>All platforms:</b> {@code PYTHONIOENCODING=utf-8} so Casanovo's Rich/click
     *       output — box-drawing characters, {@code ≥}, emoji, progress bars — reaches the
     *       GUI as UTF-8 instead of literal {@code \\uXXXX} escapes. When Python's stdout is
     *       a pipe it otherwise defaults to the OS code page (e.g. Windows cp1252) and
     *       backslash-escapes anything it can't encode.</li>
     *   <li><b>All platforms:</b> {@code FORCE_COLOR=1} so PyTorch Lightning's Rich progress
     *       bar (the default on Lightning ≥2.x when {@code rich} is installed) treats our
     *       captured pipe as a terminal and streams progress refreshes <em>live</em>. Without
     *       it Rich detects a non-TTY and only flushes the bar once, at the very end of the
     *       run — so the GUI sees no progress until it is already done. The colour escape
     *       codes this enables are stripped before display (see {@code MainApp.onOutput}).</li>
     *   <li><b>Windows only:</b> the Intel OpenMP / MKL workaround that otherwise lets
     *       Casanovo crash with a hard access violation (exit {@code 0xC0000005}). Not
     *       applied elsewhere: there is no such DLL clash, and forcing
     *       {@code MKL_THREADING_LAYER=SEQUENTIAL} would needlessly serialise MKL.</li>
     * </ul>
     */
    public static void applyNativeEnv(ProcessBuilder pb) {
        pb.environment().putIfAbsent("PYTHONIOENCODING", "utf-8");
        pb.environment().putIfAbsent("FORCE_COLOR", "1");
        if (isWindows()) {
            pb.environment().putIfAbsent("KMP_DUPLICATE_LIB_OK", "TRUE");
            pb.environment().putIfAbsent("MKL_THREADING_LAYER", "SEQUENTIAL");
        }
    }
}
