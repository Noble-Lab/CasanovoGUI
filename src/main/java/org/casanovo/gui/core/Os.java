package org.casanovo.gui.core;

import java.util.Locale;

/**
 * Tiny operating-system helper: platform predicates and the per-platform native
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
     * Whether two paths denote the same file, comparing where they resolve rather than how they
     * are spelled. A path the user typed can reach the same file by another route (a "." segment,
     * a symlinked home), and on Windows case does not distinguish them either.
     *
     * <p>{@code isSameFile} needs both to exist; normalising is the fallback, which is what
     * matters for a managed install that has not been created yet.</p>
     */
    public static boolean samePath(java.nio.file.Path a, java.nio.file.Path b) {
        try {
            if (java.nio.file.Files.exists(a) && java.nio.file.Files.exists(b)) {
                return java.nio.file.Files.isSameFile(a, b);
            }
        } catch (java.io.IOException | RuntimeException ignored) {
            // Fall through to the lexical comparison below.
        }
        return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }

    /** {@code CUDA_VISIBLE_DEVICES} value that makes no GPU visible. See
     * {@link #applyNativeEnv(ProcessBuilder, String)}. */
    public static final String NO_CUDA_DEVICES = "-1";

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
     *   <li><b>macOS / Apple Silicon only:</b> {@code PYTORCH_ENABLE_MPS_FALLBACK=1}, so the handful
     *       of operators Casanovo needs that PyTorch's MPS backend lacks (notably
     *       {@code aten::_nested_tensor_from_mask_left_aligned}) run on the CPU instead of aborting
     *       the run with a {@code NotImplementedError}. This is what makes the {@code mps} choice in
     *       the Parameters dialog usable; it is inert unless MPS is actually selected, since it only
     *       changes what happens once an operator is dispatched there. Measured on an M4 Mac mini
     *       over 2,000 spectra: 72.5 s on CPU vs 45.0 s on MPS, with identical output.</li>
     *   <li><b>Windows only:</b> the Intel OpenMP / MKL workaround that otherwise lets
     *       Casanovo crash with a hard access violation (exit {@code 0xC0000005}). Not
     *       applied elsewhere: there is no such DLL clash, and forcing
     *       {@code MKL_THREADING_LAYER=SEQUENTIAL} would needlessly serialise MKL.</li>
     * </ul>
     */
    public static void applyNativeEnv(ProcessBuilder pb) {
        applyNativeEnv(pb, null);
    }

    /**
     * As {@link #applyNativeEnv(ProcessBuilder)}, and additionally make "CPU" mean CPU.
     *
     * <p>Selecting {@code accelerator: cpu} tells Lightning which device to place the model on,
     * but nothing stops another layer of the stack from enumerating and binding a CUDA device
     * anyway. Setting {@code CUDA_VISIBLE_DEVICES} to {@value #NO_CUDA_DEVICES} removes every
     * CUDA device from the subprocess's view, so a CPU run cannot bind one whatever any layer
     * above it asks for. This deliberately overrides rather than defaults: hiding the GPUs is
     * precisely what selecting CPU asks for, so an inherited value must not defeat it. It is a
     * CUDA-only guard: Metal has no equivalent switch, so on Apple Silicon a CPU run relies on
     * Lightning's own device placement.</p>
     *
     * <p>The value is {@code -1} rather than the empty string because Windows cannot carry an
     * empty variable into a child process: {@code CreateProcess} drops it from the environment
     * block, so the child sees the variable <em>unset</em> — and an inherited
     * {@code CUDA_VISIBLE_DEVICES=0} would then be widened to "every GPU" instead of narrowed to
     * none. CUDA treats {@code -1} as an invalid index and makes no device visible, on every
     * platform.</p>
     *
     * <p>Note this cannot itself provoke PyTorch's
     * {@code "Cannot access accelerator device when none is available."}: that check asks
     * whether a backend was <em>compiled in</em>, which hiding devices does not change.</p>
     *
     * @param accelerator the selected Casanovo {@code accelerator}, or {@code null} when unknown
     */
    public static void applyNativeEnv(ProcessBuilder pb, String accelerator) {
        if (accelerator != null && accelerator.trim().equalsIgnoreCase("cpu")) {
            pb.environment().put("CUDA_VISIBLE_DEVICES", NO_CUDA_DEVICES);
        }
        pb.environment().putIfAbsent("PYTHONIOENCODING", "utf-8");
        pb.environment().putIfAbsent("FORCE_COLOR", "1");
        if (isMac() && isAarch64()) {
            pb.environment().putIfAbsent("PYTORCH_ENABLE_MPS_FALLBACK", "1");
        }
        if (isWindows()) {
            pb.environment().putIfAbsent("KMP_DUPLICATE_LIB_OK", "TRUE");
            pb.environment().putIfAbsent("MKL_THREADING_LAYER", "SEQUENTIAL");
        }
    }
}
