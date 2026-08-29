package org.casanovo.gui.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Self-contained installer for Casanovo and its Python runtime.
 *
 * <p>It downloads <a href="https://github.com/astral-sh/uv">uv</a> (a fast,
 * standalone Python package manager), uses it to create a private virtual
 * environment with a pinned Python interpreter (uv downloads the interpreter
 * itself), detects the NVIDIA driver to choose CUDA vs. CPU PyTorch wheels, and
 * finally {@code uv pip install casanovo}. Everything lives under a single root
 * (default {@code ~/.casanovo-gui}); nothing is installed system-wide.</p>
 */
public final class CasanovoInstaller {

    /** Python version uv will fetch for the venv (Casanovo supports 3.10-3.13). */
    public static final String PYTHON_VERSION = "3.11";

    /**
     * PyArrow range compatible with the {@code pylance} version Casanovo pins. pylance's
     * native extension links an older PyArrow C++ ABI, so a too-new PyArrow crashes
     * Casanovo with a hard access violation (exit {@code 0xC0000005}). Single source of
     * truth for the pin used at install, update and repair time (local and remote).
     */
    public static final String PYARROW_PIN = "pyarrow>=14,<17";

    /** How long to wait for a helper command that only prints text (e.g. {@code uv --help}). */
    private static final long HELP_TIMEOUT_SECONDS = 30;

    /** Ask before stopping an installer command that has been silent this long. */
    private static final long INSTALL_STALL_WARNING_SECONDS = 10 * 60;

    /** A healthy {@code nvidia-smi} returns almost immediately and never downloads anything. */
    private static final long NVIDIA_TOTAL_TIMEOUT_SECONDS = 15;

    /** PyArrow major version at/above which the ABI clash occurs (the pin's {@code <17} ceiling). */
    private static final int PYARROW_BAD_MAJOR = 17;

    private CasanovoInstaller() {
    }

    /**
     * Called from an installer worker when a command has stopped reporting progress. Returning
     * {@code true} continues waiting and resets the warning timer; {@code false} stops it.
     */
    @FunctionalInterface
    public interface StallHandler {
        /**
         * @param command        command that stopped reporting output
         * @param silentSeconds  seconds since its last output
         * @param elapsedSeconds seconds since the command started
         * @return {@code true} to keep waiting, {@code false} to stop the command
         */
        boolean continueWaiting(List<String> command, long silentSeconds, long elapsedSeconds);
    }

    /** Default installation root: {@code ~/.casanovo-gui}. */
    public static Path defaultInstallRoot() {
        return Paths.get(System.getProperty("user.home"), ".casanovo-gui");
    }

    /**
     * The {@code casanovo} executable inside the default managed venv (it may not exist
     * yet). Mirrors the {@code .venv/Scripts|bin} layout {@link #installAll} creates, so
     * the GUI can detect a prior managed install.
     */
    public static Path managedExecutable() {
        Path venv = defaultInstallRoot().resolve(".venv");
        return Os.isWindows()
                ? venv.resolve("Scripts").resolve("casanovo.exe")
                : venv.resolve("bin").resolve("casanovo");
    }

    /**
     * Resolve the configured executable to the GUI-managed venv. The returned path is always the
     * known managed root, never a root inferred from a symlink or hardlink alias supplied in
     * Settings. This method may touch the filesystem and must therefore be called off the FX
     * application thread.
     *
     * @param configuredExecutable executable selected in Settings
     * @param useConda whether the selected environment is Conda-managed
     * @return the fixed GUI-managed venv root, or empty for an external installation
     */
    public static Optional<Path> managedVenvRoot(String configuredExecutable, boolean useConda) {
        return managedVenvRoot(configuredExecutable, useConda, defaultInstallRoot());
    }

    /** Package-visible overload whose install root can be isolated by tests. */
    static Optional<Path> managedVenvRoot(String configuredExecutable, boolean useConda,
                                          Path installRoot) {
        if (useConda || configuredExecutable == null || configuredExecutable.isBlank()) {
            return Optional.empty();
        }
        try {
            Path configured = Path.of(configuredExecutable);
            Path venvRoot = installRoot.resolve(".venv").toAbsolutePath().normalize();
            Path expected = Os.isWindows()
                    ? venvRoot.resolve("Scripts").resolve("casanovo.exe")
                    : venvRoot.resolve("bin").resolve("casanovo");
            Path lexical = configured.toAbsolutePath().normalize();
            // installAll's fallback can discover a differently placed launcher inside this same
            // venv. It is still ours even when it is not the conventional Scripts/bin path.
            if (lexical.startsWith(venvRoot) || Os.samePath(configured, expected)) {
                return Optional.of(venvRoot);
            }
        } catch (RuntimeException ignored) {
            // Invalid/unresolvable user input is simply not a managed installation.
        }
        return Optional.empty();
    }

    /**
     * Install Python + Casanovo under {@code installRoot}.
     *
     * @param installRoot directory to install into (created if needed)
     * @param logSink     receives human-readable progress lines
     * @return absolute path to the {@code casanovo} executable in the new venv
     * @throws Exception if a required download or install step fails
     */
    public static String installAll(Path installRoot, Consumer<String> logSink) throws Exception {
        return installAll(installRoot, logSink, null);
    }

    /**
     * As {@link #installAll(Path, Consumer)}, with a decision callback for stalled commands.
     *
     * @param installRoot directory to install into
     * @param logSink receives human-readable progress lines
     * @param stallHandler decides whether a quiet command should keep waiting; {@code null}
     *                     retains the non-interactive timeout behavior
     * @return absolute path to the installed Casanovo executable
     * @throws Exception if installation fails or a stalled command is stopped
     */
    public static String installAll(Path installRoot, Consumer<String> logSink,
                                    StallHandler stallHandler) throws Exception {
        try {
            return doInstallAll(installRoot, logSink, stallHandler);
        } finally {
            // Even a failed install has usually replaced the venv (it starts with `uv venv
            // --clear`), so a cached device report describes an environment that is gone.
            DeviceProbe.invalidate();
        }
    }

    private static String doInstallAll(Path installRoot, Consumer<String> logSink,
                                       StallHandler stallHandler) throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean isWindows = os.contains("win");
        boolean isMac = os.contains("mac") || os.contains("darwin");
        boolean isLinux = os.contains("linux");
        if (!isWindows && !isLinux && !isMac) {
            throw new IllegalStateException("Unsupported OS for auto-install: " + os);
        }

        Files.createDirectories(installRoot);
        Path uvDir = installRoot.resolve("uv");
        Path logsDir = installRoot.resolve("logs");
        Files.createDirectories(uvDir);
        Files.createDirectories(logsDir);
        Path logFile = logsDir.resolve("install.log");

        Logger log = new Logger(logFile, logSink);
        Runner cmd = new Runner(log, INSTALL_STALL_WARNING_SECONDS, 0, stallHandler);

        log.info("=== Casanovo installation started ===");
        log.info("Install root: " + installRoot.toAbsolutePath());

        // ---- 1. Download uv ----
        String uvUrl;
        if (isWindows) {
            uvUrl = "https://github.com/astral-sh/uv/releases/latest/download/uv-x86_64-pc-windows-msvc.zip";
        } else if (isMac) {
            String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            uvUrl = (arch.contains("aarch64") || arch.contains("arm"))
                    ? "https://github.com/astral-sh/uv/releases/latest/download/uv-aarch64-apple-darwin.tar.gz"
                    : "https://github.com/astral-sh/uv/releases/latest/download/uv-x86_64-apple-darwin.tar.gz";
        } else {
            uvUrl = "https://github.com/astral-sh/uv/releases/latest/download/uv-x86_64-unknown-linux-gnu.tar.gz";
        }
        Path uvArchive = uvDir.resolve(isWindows ? "uv.zip" : "uv.tar.gz");
        log.info("Downloading uv from: " + uvUrl);
        download(uvUrl, uvArchive);

        // ---- 2. Unpack uv ----
        if (isWindows) {
            log.info("Extracting uv...");
            unzip(uvArchive, uvDir);
        } else {
            log.info("Extracting uv...");
            cmd.run(List.of("tar", "-xzf", uvArchive.toString(), "-C", uvDir.toString()), installRoot);
        }

        Path uvExe = findExecutable(uvDir, isWindows ? "uv.exe" : "uv");
        try {
            uvExe.toFile().setExecutable(true);
        } catch (SecurityException ignored) {
        }
        log.info("uv ready: " + uvExe.toAbsolutePath());

        // ---- 3. Create venv with a pinned Python (uv downloads the interpreter) ----
        log.info("Creating virtual environment with Python " + PYTHON_VERSION + " ...");
        cmd.run(List.of(uvExe.toString(), "venv", "--clear", "--python", PYTHON_VERSION, ".venv"), installRoot);

        // ---- 4-5. Install Casanovo together with a PyTorch matched to this machine ----
        // Casanovo AND the PyArrow pin in ONE resolution, so the result is atomic: the launcher
        // only appears if the pin applied too. Casanovo pins pylance==0.15.0, whose native
        // extension links an older PyArrow C++ ABI (NOT stable across majors); pylance declares
        // only pyarrow>=12, so a separate resolution first downloads the newest PyArrow (24.x)
        // and then replaces it, and in between the venv is in the state that crashes the
        // interpreter with a hard access violation (exit 0xC0000005 / -1073741819) the moment
        // Casanovo's import chain touches lance — even `casanovo version` dies.
        log.info("Installing Casanovo (this can take several minutes)...");
        List<String> casanovoAndPin = List.of("casanovo", PYARROW_PIN);
        if (!pipInstallMatchingTorch(uvExe, installRoot, cmd, log, casanovoAndPin)) {
            // uv too old for --torch-backend: fall back to our own driver -> CUDA wheel mapping.
            // torch goes in FIRST so Casanovo's own resolution keeps the GPU build; installing
            // Casanovo first would download a PyPI torch and then download the matched one again.
            installCudaTorchIfGpu(uvExe, installRoot, isWindows, cmd, log);
            cmd.run(pipInstall(uvExe, casanovoAndPin), installRoot);
        }

        // ---- 7. Locate the installed casanovo launcher ----
        Path casanovoExe = isWindows
                ? installRoot.resolve(".venv").resolve("Scripts").resolve("casanovo.exe")
                : installRoot.resolve(".venv").resolve("bin").resolve("casanovo");
        if (!Files.exists(casanovoExe)) {
            casanovoExe = findExecutable(installRoot.resolve(".venv"), isWindows ? "casanovo.exe" : "casanovo");
        }
        String result = casanovoExe.toAbsolutePath().toString();

        // ---- 8. Best-effort sanity check (non-fatal) ----
        // Use the `version` subcommand -- Casanovo 5.x is a Click command group with no
        // top-level `--version` flag, so `casanovo --version` exits non-zero ("No such
        // option") even on a healthy install. A failure here does not mean the install
        // failed (the package is already installed), so we only warn.
        log.info("Verifying installation...");
        try {
            cmd.run(List.of(casanovoExe.toString(), "version"), installRoot);
        } catch (Exception ve) {
            log.info("[warn] Version check did not complete cleanly: " + ve.getMessage());
            log.info("[warn] Casanovo is installed at " + result
                    + ". If it crashes with exit 0xC0000005 (-1073741819), it is usually a"
                    + " PyArrow/pylance ABI clash; this installer pins PyArrow <17, and the GUI"
                    + " also launches Casanovo with KMP_DUPLICATE_LIB_OK=TRUE as a safeguard.");
        }

        // ---- 8b. Best-effort MPS (Apple Silicon GPU) check (non-fatal) ----
        verifyMpsIfAppleSilicon(installRoot, cmd, log);

        log.info("=== Done. Casanovo executable: " + result + " ===");
        return result;
    }

    /**
     * Upgrade an existing GUI-managed Casanovo install in place. Only valid when
     * Casanovo was installed through {@link #installAll} (i.e. {@code uv} lives
     * under {@code installRoot}); for Conda / PATH installs the user must update
     * with their own tooling.
     *
     * <p>Uses {@code uv pip install --upgrade-package casanovo casanovo}, NOT a
     * blanket {@code -U}. A blanket upgrade re-resolves the whole dependency
     * closure: it pulls {@code torch} from PyPI (discarding the CUDA build for a
     * CPU one) even when the installed build already satisfies what Casanovo asks for.
     * {@code --upgrade-package casanovo} upgrades only Casanovo and the deps its
     * new version strictly requires, leaving the carefully-installed CUDA
     * {@code torch} in place (the installed version still satisfies Casanovo's
     * {@code torch>=2.2}).</p>
     *
     * <p><b>Self-heal.</b> Should a future Casanovo raise its torch floor past the
     * installed CUDA build, {@code --upgrade-package} would still be forced to move
     * {@code torch} (and pull a CPU wheel from PyPI). To cover that, the GPU-sensitive
     * trio is snapshotted before and after the upgrade; if any of the three moved, the
     * same GPU-detection + matched-CUDA-trio install that {@link #installAll} uses is
     * re-run, restoring a consistent, GPU-enabled, new-enough stack.</p>
     *
     * <p>It then re-applies the {@code pyarrow>=14,<17} pin: Casanovo pins
     * {@code pylance==0.15.0}, whose native extension links an older PyArrow C++
     * ABI. A dependency bump can otherwise pull the newest PyArrow and reintroduce
     * the hard access-violation crash (exit {@code 0xC0000005}) the moment
     * Casanovo touches lance/pyarrow.</p>
     *
     * @param installRoot the GUI install root (default {@code ~/.casanovo-gui})
     * @param logSink     receives human-readable progress lines
     * @throws Exception if {@code uv} can't be found or a step fails
     */
    public static void updateCasanovo(Path installRoot, Consumer<String> logSink) throws Exception {
        updateCasanovo(installRoot, logSink, null);
    }

    /**
     * As {@link #updateCasanovo(Path, Consumer)}, with a decision callback for stalled commands.
     *
     * @param installRoot GUI-managed installation root
     * @param logSink receives human-readable progress lines
     * @param stallHandler decides whether a quiet command should keep waiting; {@code null}
     *                     retains the non-interactive timeout behavior
     * @throws Exception if the update fails or a stalled command is stopped
     */
    public static void updateCasanovo(Path installRoot, Consumer<String> logSink,
                                      StallHandler stallHandler) throws Exception {
        try {
            doUpdateCasanovo(installRoot, logSink, stallHandler);
        } finally {
            DeviceProbe.invalidate(); // as above: a partial update still moved the venv
        }
    }

    private static void doUpdateCasanovo(Path installRoot, Consumer<String> logSink,
                                         StallHandler stallHandler) throws Exception {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

        Path logsDir = installRoot.resolve("logs");
        Files.createDirectories(logsDir);
        Logger log = new Logger(logsDir.resolve("install.log"), logSink);
        Runner cmd = new Runner(log, INSTALL_STALL_WARNING_SECONDS, 0, stallHandler);

        Path uvExe = installRoot.resolve("uv").resolve(isWindows ? "uv.exe" : "uv");
        if (!Files.exists(uvExe)) {
            uvExe = findExecutable(installRoot.resolve("uv"), isWindows ? "uv.exe" : "uv");
        }
        Path venvRoot = installRoot.resolve(".venv");

        log.info("=== Casanovo update started ===");

        // Snapshot the GPU-sensitive trio so we can tell whether the upgrade disturbed it.
        String torchBefore = torchStackSignature(venvRoot);

        log.info("Updating Casanovo (preserving the existing PyTorch / GPU stack)...");
        // --upgrade-package casanovo (not -U): upgrade Casanovo only, leaving the installed
        // torch untouched where its constraints allow. --torch-backend=auto additionally
        // guarantees that any torch uv *is* forced to move stays matched to this driver.
        // The pin travels with the upgrade for the same reason it does on a fresh install: a
        // separate second resolution leaves a window where the launcher exists but PyArrow is
        // the version that hard-faults on import.
        List<String> upgradeArgs = List.of("--upgrade-package", "casanovo", "casanovo", PYARROW_PIN);
        boolean matchedTorch = pipInstallMatchingTorch(uvExe, installRoot, cmd, log, upgradeArgs);
        if (!matchedTorch) {
            cmd.run(pipInstall(uvExe, upgradeArgs), installRoot);
        }

        // Self-heal: if the upgrade moved torch (e.g. a raised torch floor dragged in a CPU
        // build from PyPI), re-establish a CUDA build matched to this machine.
        String torchAfter = torchStackSignature(venvRoot);
        if (!torchAfter.equals(torchBefore)) {
            log.info("PyTorch stack changed during the upgrade:");
            log.info("  before: " + torchBefore);
            log.info("  after:  " + torchAfter);
            boolean cudaImpossible = Os.isMac(); // no macOS CUDA wheels exist, for any Mac
            if (matchedTorch && (cudaImpossible || hasGpuTorchBuild(torchAfter))) {
                log.info("uv selected it for this machine's driver -> no repair needed.");
            } else if (matchedTorch) {
                // uv chose the backend itself and landed on a CPU build.
                // That is correct on a machine with no NVIDIA GPU, and wrong only where uv could
                // not see the driver — but this fallback mapping tops out at cu121, whose newest
                // wheel is torch 2.5.1, so "repairing" it here would downgrade what uv resolved
                // and can leave a modern card with no kernels at all. Report it instead: the
                // pre-run device check describes what is actually installed.
                log.info("[warn] uv resolved PyTorch " + torchAfter + ", which has no GPU backend.");
                log.info("[warn] That is expected without a supported GPU. If this machine has an NVIDIA GPU,"
                        + " check that its driver is visible (nvidia-smi) and reinstall from"
                        + " Settings — this updater will not replace what uv chose, because its"
                        + " own fallback index is older than that build.");
            } else {
                log.info("Re-establishing a matched GPU PyTorch stack...");
                installCudaTorchIfGpu(uvExe, installRoot, isWindows, cmd, log);
            }
        } else {
            log.info("PyTorch stack unchanged (" + torchAfter + ") -> no GPU repair needed.");
        }

        log.info("=== Casanovo update complete ===");
    }

    /**
     * Re-pin PyArrow to a {@code pylance}-compatible version in an existing
     * GUI-managed install ({@code uv pip install pyarrow>=14,<17}). Used by the
     * startup self-check to repair a venv whose PyArrow drifted too new — the
     * combination that crashes Casanovo with exit {@code 0xC0000005}. Only valid
     * when Casanovo was installed via {@link #installAll} (uv lives under
     * {@code installRoot}).
     *
     * @param installRoot the GUI install root (default {@code ~/.casanovo-gui})
     * @param logSink     receives human-readable progress lines
     * @throws Exception if {@code uv} can't be found or the step fails
     */
    public static void repairPyArrow(Path installRoot, Consumer<String> logSink) throws Exception {
        repairPyArrow(installRoot, logSink, null);
    }

    /**
     * As {@link #repairPyArrow(Path, Consumer)}, with a decision callback for stalled commands.
     *
     * @param installRoot GUI-managed installation root
     * @param logSink receives human-readable progress lines
     * @param stallHandler decides whether a quiet command should keep waiting; {@code null}
     *                     retains the non-interactive timeout behavior
     * @throws Exception if repair fails or a stalled command is stopped
     */
    public static void repairPyArrow(Path installRoot, Consumer<String> logSink,
                                     StallHandler stallHandler) throws Exception {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path logsDir = installRoot.resolve("logs");
        Files.createDirectories(logsDir);
        Logger log = new Logger(logsDir.resolve("install.log"), logSink);
        Runner cmd = new Runner(log, INSTALL_STALL_WARNING_SECONDS, 0, stallHandler);
        Path uvExe = installRoot.resolve("uv").resolve(isWindows ? "uv.exe" : "uv");
        if (!Files.exists(uvExe)) {
            uvExe = findExecutable(installRoot.resolve("uv"), isWindows ? "uv.exe" : "uv");
        }
        log.info("=== PyArrow repair started ===");
        pinPyArrow(uvExe, installRoot, cmd, log);
        log.info("=== PyArrow repair complete ===");
    }

    /**
     * Whether the venv at {@code venvRoot} has a PyArrow too new for the
     * {@code pylance} version Casanovo pins — the combination that crashes
     * Casanovo with exit {@code 0xC0000005}. True only when both packages are
     * installed and PyArrow's major version is at/above {@link #PYARROW_BAD_MAJOR}.
     * Reads {@code dist-info} metadata; launches no Python.
     */
    public static boolean hasPyArrowMismatch(Path venvRoot) {
        Optional<String> pyarrow = PyVenv.packageVersion(venvRoot, "pyarrow");
        Optional<String> pylance = PyVenv.packageVersion(venvRoot, "pylance");
        if (pyarrow.isEmpty() || pylance.isEmpty()) {
            return false; // not the lance/pyarrow stack — nothing we know to repair
        }
        return majorVersion(pyarrow.get()) >= PYARROW_BAD_MAJOR;
    }

    /** Install PyArrow constrained to {@link #PYARROW_PIN}. */
    private static void pinPyArrow(Path uvExe, Path workDir, Runner cmd, Logger log)
            throws IOException, InterruptedException {
        log.info("Pinning PyArrow to a pylance-compatible version (avoids 0xC0000005 crash)...");
        cmd.run(pipInstall(uvExe, List.of(PYARROW_PIN)), workDir);
    }

    /** Leading numeric component of a version string ({@code "24.0.0"} -> 24), or -1. */
    private static int majorVersion(String version) {
        String head = version.trim();
        int dot = head.indexOf('.');
        if (dot >= 0) {
            head = head.substring(0, dot);
        }
        head = head.replaceAll("\\D.*$", "");
        try {
            return head.isEmpty() ? -1 : Integer.parseInt(head);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Run {@code uv pip install} with a PyTorch matched to this machine, and report whether
     * that was possible.
     *
     * <p>uv's {@code --torch-backend=auto} reads the installed NVIDIA driver and resolves the
     * PyTorch ecosystem against the matching CUDA wheel index (or the CPU index when there is
     * no driver), in the <em>same</em> resolution as everything else being installed. That is
     * strictly better than choosing an index ourselves: it covers the full range of CUDA
     * versions rather than the two {@link #cudaTorchIndexUrl} knows about, it tracks new
     * releases without us editing a table, and because torch is resolved together with
     * Casanovo it cannot be silently replaced by a second, independent resolution.</p>
     *
     * <p>The previous approach pinned every machine with a driver newer than 531.14 to the
     * {@code cu121} index, whose newest wheel is torch 2.5.1 &mdash; a build carrying no
     * kernels for GPU architectures released since, which fails at run time with "no kernel
     * image is available for execution on the device" even though detection appeared to
     * succeed.</p>
     *
     * @param pipArgs what to install (e.g. {@code casanovo}, or upgrade flags plus the package)
     * @return {@code true} if uv chose the backend; {@code false} if this uv is too old for the
     *         flag, in which case the caller should apply the legacy driver mapping itself
     */
    private static boolean pipInstallMatchingTorch(Path uvExe, Path workDir, Runner cmd, Logger log,
                                                   List<String> pipArgs)
            throws IOException, InterruptedException {
        return pipInstallMatchingTorch(uvExe, workDir, cmd, log, pipArgs,
                uvSupportsTorchBackend(uvExe, workDir, log));
    }

    /**
     * As above, with the "does this uv take the flag" answer supplied. Split out so the part that
     * decides what to do about a failure can be tested without a uv binary: whether it installs,
     * whether a failure is the flag's fault, and whether the caller's fallback is invoked.
     */
    static boolean pipInstallMatchingTorch(Path uvExe, Path workDir, Runner cmd, Logger log,
                                           List<String> pipArgs, boolean flagSupported)
            throws IOException, InterruptedException {
        if (!flagSupported) {
            log.info("[warn] This uv build has no --torch-backend; falling back to driver detection.");
            return false;
        }
        log.info("Letting uv match PyTorch to this machine's GPU driver (--torch-backend=auto).");
        List<String> command = new ArrayList<>(List.of("--torch-backend=auto"));
        command.addAll(pipArgs);
        try {
            cmd.run(pipInstall(uvExe, command), workDir);
            return true;
        } catch (CommandFailed e) {
            // Some uv builds list the flag in --help but still gate it behind --preview, so the
            // help text alone cannot decide this. A rejected flag is treated the way an absent one
            // is — the caller's fallback installs a driver-matched stack itself — but only when uv
            // actually complained about the flag: every other failure (a proxy, a resolution
            // conflict, a full disk) must surface instead of triggering a second full install.
            if (!rejectedTheFlag(e.output())) {
                throw e;
            }
            log.info("[warn] uv rejected --torch-backend; falling back to driver detection.");
            return false;
        }
    }

    /**
     * Whether a failed {@code uv pip install} failed <em>because of</em> {@code --torch-backend}.
     * Judged only on what uv printed: the exception message repeats the command line, which
     * contains the flag whatever went wrong, so matching that would classify every failure —
     * a proxy error, a resolution conflict, a full disk — as a rejected flag.
     */
    static boolean rejectedTheFlag(String output) {
        if (output == null) {
            return false;
        }
        // Judged line by line: the complaint has to name the flag *and* be about parsing it, in
        // the same sentence. uv's parser prints "error: unexpected argument '--torch-backend'
        // found" and its preview gate prints "the `--torch-backend` option is only available in
        // preview mode" — both single lines. Matching across a whole install log would let an
        // unrelated failure elsewhere in it pair up with a note that merely mentions the flag,
        // which is how a proxy error would send the install down the legacy index.
        for (String line : output.toLowerCase(Locale.ROOT).split("\\R")) {
            if (!line.contains("torch-backend")) {
                continue;
            }
            // Deliberately not "error": uv prints that on every failure.
            if (line.contains("unexpected argument")
                    || line.contains("unrecognized")
                    || line.contains("unknown option")
                    || line.contains("preview")) {
                return true;
            }
        }
        return false;
    }

    /** {@code uv pip install <args...>}. */
    private static List<String> pipInstall(Path uvExe, List<String> pipArgs) {
        List<String> command = new ArrayList<>(List.of(uvExe.toString(), "pip", "install"));
        command.addAll(pipArgs);
        return command;
    }

    /**
     * Whether this uv advertises {@code --torch-backend} (added in uv 0.6.6). Probed from the
     * binary's own help rather than by parsing its version, so it stays correct however uv
     * numbers its releases. Advertising it is not proof it is usable &mdash; some builds gate it
     * behind {@code --preview} &mdash; which is why {@link #pipInstallMatchingTorch} also treats
     * a rejected flag as "unsupported". Run quietly: the help text is long and would swamp the
     * install log.
     */
    private static boolean uvSupportsTorchBackend(Path uvExe, Path workDir, Logger log) {
        Process p = null;
        Path helpFile = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(uvExe.toString(), "pip", "install", "--help");
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            // Help goes to a file rather than a pipe: reading a pipe to EOF blocks until the
            // process exits, so the bound below could never fire on a uv that wedges without
            // closing stdout — the same trap DeviceProbe.runProbe avoids the same way.
            helpFile = Files.createTempFile("casanovo-gui-uv-help", ".txt");
            helpFile.toFile().deleteOnExit();
            pb.redirectOutput(helpFile.toFile());
            p = pb.start();
            if (!p.waitFor(HELP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.info("[warn] uv did not answer --help within " + HELP_TIMEOUT_SECONDS
                        + " s; assuming no --torch-backend.");
                return false;
            }
            // Decoded from bytes, not Files.readString: native libraries write locale-encoded
            // bytes into this file, and a strict UTF-8 decode would throw and be read as "the
            // flag is absent" — a claim about uv that was never established.
            String help = new String(Files.readAllBytes(helpFile), StandardCharsets.UTF_8);
            return help.contains("--torch-backend");
        } catch (IOException e) {
            log.info("[warn] Could not ask uv whether it supports --torch-backend (" + e + ").");
            return false;
        } catch (InterruptedException e) {
            if (p != null) {
                p.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (helpFile != null) {
                try {
                    Files.deleteIfExists(helpFile);
                } catch (IOException ignored) {
                    // temp file; deleteOnExit will catch it
                }
            }
        }
    }

    /**
     * Detect an NVIDIA driver and, when one capable of a CUDA build is present,
     * install {@code torch} from the corresponding PyTorch CUDA wheel index.
     * A no-op (keeps the CPU stack) when no capable driver is found. Used by both
     * {@link #installAll} and {@link #updateCasanovo}.
     *
     * <p>Only {@code torch}: Casanovo requires {@code torch>=2.2} and nothing else in its
     * dependency closure imports {@code torchvision} or {@code torchaudio} (lightning and
     * torchmetrics declare them under optional extras that are not installed). Installing the
     * usual three-package line from pytorch.org added a few hundred megabytes per install and
     * a stack to keep in lockstep, for packages no run ever loads.</p>
     */
    private static void installCudaTorchIfGpu(Path uvExe, Path workDir, boolean isWindows,
                                              Runner cmd, Logger log)
            throws IOException, InterruptedException {
        String driverVersion = detectNvidiaDriver(cmd, workDir, isWindows, log);
        String torchIndexUrl = cudaTorchIndexUrl(driverVersion);
        if (torchIndexUrl != null) {
            log.info("NVIDIA driver " + driverVersion + " detected -> installing "
                    + (torchIndexUrl.endsWith("cu121") ? "CUDA 12.1" : "CUDA 11.8") + " PyTorch.");
            log.info("Installing the CUDA PyTorch wheel...");
            // --reinstall-package: uv resolves against what is installed, and a CPU torch of the
            // same version already satisfies a bare `torch` requirement whatever --index-url
            // says. Without this the update path's self-heal logs success and changes nothing —
            // the venv keeps the PyPI CPU wheel the upgrade dragged in.
            cmd.run(pipInstall(uvExe, List.of("--reinstall-package", "torch", "torch",
                    "--index-url", torchIndexUrl)), workDir);
        } else if (Os.isMac() && Os.isAarch64()) {
            log.info("Apple Silicon detected -> keeping the default macOS arm64 PyTorch"
                    + " wheel, which includes MPS (Metal) GPU support.");
        } else {
            log.info("No CUDA-capable NVIDIA driver detected -> using CPU PyTorch.");
        }
    }

    /**
     * The PyTorch CUDA wheel index matching an NVIDIA driver version, or {@code null} for a CPU install
     * (no CUDA-capable driver). <strong>Single source of truth</strong> for the driver&rarr;CUDA mapping,
     * shared by the local installer and the remote (SSH) {@code RemoteInstaller} so both pick the same wheel
     * for a given driver. Update the thresholds / index URLs here and both installers follow.
     *
     * <p><b>Fallback only.</b> {@link #pipInstallMatchingTorch} normally lets uv pick the
     * wheel from the driver, which covers far more CUDA versions than the two below. This
     * mapping is used only when the available uv predates {@code --torch-backend}; note that
     * {@code cu121} is a retired index whose newest wheel is torch 2.5.1.</p>
     *
     * @param driverVersion the {@code nvidia-smi} {@code driver_version}, or {@code null} when there is no
     *                      NVIDIA GPU
     */
    public static String cudaTorchIndexUrl(String driverVersion) {
        if (driverVersion != null && versionAtLeast(driverVersion, "531.14")) {
            return "https://download.pytorch.org/whl/cu121";
        }
        if (driverVersion != null && versionAtLeast(driverVersion, "522.06")) {
            return "https://download.pytorch.org/whl/cu118";
        }
        return null;
    }

    /**
     * A compact, comparable signature of the GPU-sensitive trio's installed
     * versions (read from {@code dist-info} metadata via {@link PyVenv} — no
     * Python is launched). Includes the local build segment, so a CUDA-to-CPU
     * swap (e.g. {@code 2.5.1+cu121} -> {@code 2.12.0}) registers as a change.
     */
    private static String torchStackSignature(Path venvRoot) {
        // torch alone: it is the only one of the three this application installs, and testing the
        // combined string for a CUDA build was satisfied by any package's "+cu" suffix.
        return "torch=" + PyVenv.packageVersion(venvRoot, "torch").orElse("?");
    }

    /**
     * On Apple Silicon, confirm the installed PyTorch actually exposes the MPS
     * (Metal) GPU backend, which is what the GUI's {@code accelerator="mps"} default
     * runs on. Best-effort and non-fatal: a healthy install does not depend on this
     * passing — it only surfaces a clear warning when (e.g. a Rosetta x86 interpreter)
     * an MPS-less wheel was pulled and Casanovo would silently fall back to CPU.
     * A no-op on every other platform.
     */
    private static void verifyMpsIfAppleSilicon(Path installRoot, Runner cmd, Logger log) {
        if (!Os.isMac() || !Os.isAarch64()) {
            return;
        }
        Path python = installRoot.resolve(".venv").resolve("bin").resolve("python");
        log.info("Checking PyTorch MPS (Apple Silicon GPU) availability...");
        try {
            String out = cmd.run(List.of(python.toString(), "-c",
                    "import torch; print('MPS_AVAILABLE=' + str(torch.backends.mps.is_available()))"),
                    installRoot);
            if (out.contains("MPS_AVAILABLE=True")) {
                log.info("PyTorch MPS is available. Casanovo can run on it: the operators it "
                        + "needs that MPS lacks (e.g. _nested_tensor_from_mask_left_aligned) fall "
                        + "back to the CPU via PYTORCH_ENABLE_MPS_FALLBACK, which the GUI sets.");
            } else {
                log.info("[warn] MPS is NOT available in the installed PyTorch; Casanovo will run on"
                        + " CPU. This usually means an x86 (Rosetta) Python/torch was installed"
                        + " instead of the native arm64 build.");
            }
        } catch (Exception e) {
            log.info("[warn] Could not run the MPS availability check: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void download(String url, Path target) throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();
        HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(target,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("Download failed (HTTP " + resp.statusCode() + "): " + url);
        }
    }

    private static void unzip(Path zip, Path destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                Path out = destDir.resolve(entry.getName()).normalize();
                if (!out.startsWith(destDir)) {
                    throw new IOException("Unsafe zip entry: " + entry.getName());
                }
                Files.createDirectories(out.getParent());
                try (OutputStream os = Files.newOutputStream(out,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    zis.transferTo(os);
                }
                zis.closeEntry();
            }
        }
    }

    private static Path findExecutable(Path root, String name) throws IOException {
        Path direct = root.resolve(name);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase(name))
                    .findFirst()
                    .orElseThrow(() -> new FileNotFoundException(name + " not found under " + root));
        }
    }

    /**
     * The NVIDIA driver version {@code nvidia-smi} reports, or empty when there is none to find.
     * Runs a subprocess, so callers must stay off the UI thread.
     *
     * <p>Offered publicly because a device verdict alone cannot tell "this install picked the
     * wrong PyTorch" from "this machine has no NVIDIA GPU", and only the first is worth a
     * multi-gigabyte reinstall.</p>
     */
    public static Optional<String> nvidiaDriverVersion() {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Logger quiet = new Logger(null, null);
        return Optional.ofNullable(detectNvidiaDriver(
                new Runner(quiet, 0, NVIDIA_TOTAL_TIMEOUT_SECONDS, null),
                Path.of("."), isWindows, quiet));
    }

    /** True for a PyTorch signature carrying a CUDA or ROCm local-version tag. */
    static boolean hasGpuTorchBuild(String signature) {
        if (signature == null) {
            return false;
        }
        String lower = signature.toLowerCase(Locale.ROOT);
        return lower.contains("+cu") || lower.contains("+rocm");
    }

    private static String detectNvidiaDriver(Runner cmd, Path workDir, boolean isWindows, Logger log) {
        try {
            List<String> driverCmd = isWindows
                    ? List.of("cmd.exe", "/c", "nvidia-smi --query-gpu=driver_version --format=csv,noheader")
                    : List.of("nvidia-smi", "--query-gpu=driver_version", "--format=csv,noheader");
            String out = cmd.run(driverCmd, workDir);
            for (String line : out.split("\\R")) {
                if (!line.trim().isEmpty()) {
                    return line.trim();
                }
            }
        } catch (Exception e) {
            log.info("nvidia-smi not available; assuming no GPU.");
        }
        return null;
    }

    private static boolean versionAtLeast(String a, String b) {
        if (a == null) {
            return false;
        }
        String[] as = a.trim().split("\\.");
        String[] bs = b.trim().split("\\.");
        int n = Math.max(as.length, bs.length);
        for (int i = 0; i < n; i++) {
            int ai = i < as.length ? parseIntSafe(as[i]) : 0;
            int bi = i < bs.length ? parseIntSafe(bs[i]) : 0;
            if (ai != bi) {
                return ai > bi;
            }
        }
        return true;
    }

    private static int parseIntSafe(String s) {
        String digits = s.replaceAll("\\D+", "");
        return digits.isEmpty() ? 0 : Integer.parseInt(digits);
    }

    /** Writes log lines to a file and forwards them live to a sink. */
    static final class Logger {
        private final Path logFile;
        private final Consumer<String> sink;

        Logger(Path logFile, Consumer<String> sink) {
            this.logFile = logFile;
            this.sink = sink;
        }

        synchronized void info(String msg) {
            if (sink != null) {
                sink.accept(msg);
            }
            // Some short-lived probes intentionally use a sink-only/quiet logger. In that
            // mode there is no install log to write, but Runner still reports its command and
            // output through this method.
            if (logFile == null) {
                return;
            }
            try {
                Files.writeString(logFile, msg + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * A command that exited non-zero, carrying what it printed. The output is a field rather
     * than only part of the message because callers classify failures by it &mdash; and the
     * message necessarily echoes the command line, so matching on the message would match the
     * arguments we passed rather than uv's complaint about them.
     */
    static final class CommandFailed extends IOException {
        private static final long serialVersionUID = 1L;

        private final String output;

        CommandFailed(List<String> command, int exitCode, String output) {
            super("Command failed (exit " + exitCode + "): " + String.join(" ", command)
                    + Text.tail(output, 400));
            this.output = output == null ? "" : output;
        }

        /** Everything the command printed (stdout and stderr merged), never {@code null}. */
        String output() {
            return output;
        }
    }

    /** Runs a command, streaming its merged output live to the logger. Not final: a test
     * substitutes a runner that records commands and returns scripted results instead of
     * launching uv. */
    static class Runner {
        private final Logger log;
        private final long outputIdleTimeoutSeconds;
        private final long totalTimeoutSeconds;
        private final StallHandler stallHandler;

        Runner(Logger log) {
            this(log, 60);
        }

        Runner(Logger log, long outputIdleTimeoutSeconds) {
            this(log, outputIdleTimeoutSeconds, 0, null);
        }

        Runner(Logger log, long outputIdleTimeoutSeconds, long totalTimeoutSeconds,
               StallHandler stallHandler) {
            this.log = log;
            this.outputIdleTimeoutSeconds = Math.max(0, outputIdleTimeoutSeconds);
            this.totalTimeoutSeconds = Math.max(0, totalTimeoutSeconds);
            this.stallHandler = stallHandler;
        }

        String run(List<String> command, Path workDir) throws IOException, InterruptedException {
            log.info("$ " + String.join(" ", command));
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            Os.applyNativeEnv(pb); // per-platform subprocess env (see Os.applyNativeEnv)
            // applyNativeEnv also sets FORCE_COLOR (for the run console's live Rich progress),
            // but the install console does not strip ANSI — so opt out here. NO_COLOR overrides
            // FORCE_COLOR, giving uv/Casanovo plain text instead of raw colour escapes.
            pb.environment().putIfAbsent("NO_COLOR", "1");
            // No stdin: a GUI has no console to prompt at, so a uv that asks for a keyring
            // password or an index credential would otherwise block on a read that can never be
            // answered, with `installing` stuck true and the window dead for the session. Reading
            // EOF makes it fail with a message the log can show instead.
            pb.redirectInput(ProcessBuilder.Redirect.from(new File(
                    System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                            ? "NUL" : "/dev/null")));
            Process p = pb.start();

            // Read on a separate thread so waiting for readLine()/EOF can never hide a hung
            // child from the checks below. Install commands use a user-confirmed idle warning,
            // while short probes can opt into a hard wall-clock deadline.
            StringBuilder captured = new StringBuilder();
            long started = System.nanoTime();
            AtomicLong lastOutput = new AtomicLong(started);
            AtomicReference<IOException> readFailure = new AtomicReference<>();
            AtomicBoolean closingReader = new AtomicBoolean();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        log.info(line);
                        synchronized (captured) {
                            captured.append(line).append('\n');
                        }
                        lastOutput.set(System.nanoTime());
                    }
                } catch (IOException e) {
                    // Once the child has exited, Runner may close an inherited stdout handle to
                    // release a reader that cannot otherwise observe EOF. That deliberate close
                    // is teardown, not evidence that a successful command failed.
                    if (!closingReader.get()) {
                        readFailure.compareAndSet(null, e);
                    }
                }
            }, "casanovo-installer-output");
            reader.setDaemon(true);
            reader.start();

            try {
                while (!p.waitFor(1, TimeUnit.SECONDS)) {
                    long now = System.nanoTime();
                    long elapsedNanos = now - started;
                    if (totalTimeoutSeconds > 0
                            && elapsedNanos >= TimeUnit.SECONDS.toNanos(totalTimeoutSeconds)) {
                        throw stopTimedOutProcess(p, reader, captured, command,
                                "command exceeded its " + totalTimeoutSeconds + " s deadline");
                    }
                    long silentNanos = now - lastOutput.get();
                    if (outputIdleTimeoutSeconds > 0
                            && silentNanos >= TimeUnit.SECONDS.toNanos(outputIdleTimeoutSeconds)) {
                        long silentSeconds = TimeUnit.NANOSECONDS.toSeconds(silentNanos);
                        long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(elapsedNanos);
                        if (stallHandler != null && stallHandler.continueWaiting(
                                List.copyOf(command), silentSeconds, elapsedSeconds)) {
                            // Treat the user's explicit choice as activity so the warning is not
                            // shown again immediately. Real output will update this independently.
                            lastOutput.set(System.nanoTime());
                            continue;
                        }
                        throw stopTimedOutProcess(p, reader, captured, command,
                                "no output for " + outputIdleTimeoutSeconds + " s");
                    }
                }
                reader.join(5_000);
                if (reader.isAlive()) {
                    closingReader.set(true);
                    try {
                        p.getInputStream().close();
                    } catch (IOException ignored) {
                        // The process has exited; failing to close its stale pipe must not turn a
                        // successful command into an installation failure.
                    }
                    reader.join(1_000);
                }
            } catch (InterruptedException e) {
                p.destroyForcibly();
                try {
                    p.getInputStream().close();
                } catch (IOException ignored) {
                    // Process teardown already supplies the important failure.
                }
                reader.interrupt();
                throw e;
            }

            if (reader.isAlive()) {
                throw new IOException("command output reader did not stop after the process exited");
            }
            IOException readError = readFailure.get();
            if (readError != null) {
                throw readError;
            }
            String text;
            synchronized (captured) {
                text = captured.toString();
            }
            int code = p.exitValue();
            if (code != 0) {
                throw new CommandFailed(command, code, text);
            }
            return text;
        }

        private static CommandFailed stopTimedOutProcess(
                Process process, Thread reader, StringBuilder captured,
                List<String> command, String reason) throws InterruptedException {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            try {
                process.getInputStream().close();
            } catch (IOException ignored) {
                // The timeout is the useful error; the process is already being torn down.
            }
            reader.join(5_000);
            String text;
            synchronized (captured) {
                text = captured.toString();
            }
            return new CommandFailed(command, -1, text + System.lineSeparator()
                    + "(" + reason + "; the process was killed)");
        }
    }
}
