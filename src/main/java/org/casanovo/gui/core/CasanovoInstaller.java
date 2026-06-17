package org.casanovo.gui.core;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
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
import java.util.List;
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
    private static final String PYTHON_VERSION = "3.11";

    /**
     * PyArrow range compatible with the {@code pylance} version Casanovo pins. pylance's
     * native extension links an older PyArrow C++ ABI, so a too-new PyArrow crashes
     * Casanovo with a hard access violation (exit {@code 0xC0000005}). Single source of
     * truth for the pin used at install, update and repair time.
     */
    private static final String PYARROW_PIN = "pyarrow>=14,<17";

    /** PyArrow major version at/above which the ABI clash occurs (the pin's {@code <17} ceiling). */
    private static final int PYARROW_BAD_MAJOR = 17;

    private CasanovoInstaller() {
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
     * Install Python + Casanovo under {@code installRoot}.
     *
     * @param installRoot directory to install into (created if needed)
     * @param logSink     receives human-readable progress lines
     * @return absolute path to the {@code casanovo} executable in the new venv
     * @throws Exception if a required download or install step fails
     */
    public static String installAll(Path installRoot, Consumer<String> logSink) throws Exception {
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
        Runner cmd = new Runner(log);

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

        // ---- 4-5. GPU detection + pre-install CUDA PyTorch so casanovo keeps the GPU build ----
        installCudaTorchIfGpu(uvExe, installRoot, isWindows, cmd, log);

        // ---- 6. Install Casanovo ----
        log.info("Installing Casanovo (this can take several minutes)...");
        cmd.run(List.of(uvExe.toString(), "pip", "install", "casanovo"), installRoot);

        // ---- 6b. Constrain PyArrow to a pylance-compatible version ----
        // Casanovo pins pylance==0.15.0, whose native extension links an older PyArrow
        // C++ ABI (NOT stable across majors). pylance only declares pyarrow>=12, so uv
        // otherwise resolves the newest PyArrow (24.x) and loading lance against that
        // mismatched ABI crashes the interpreter with a hard access violation (exit
        // 0xC0000005 / -1073741819) the moment Casanovo's import chain touches it -- even
        // `casanovo version` dies. Holding PyArrow in the 16.x line (contemporaneous with
        // pylance 0.15.0) avoids the crash.
        pinPyArrow(uvExe, installRoot, cmd, log);

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
     * CPU one) and can bump {@code torch} without bumping {@code torchvision} in
     * lockstep, producing an ABI mismatch that crashes Casanovo on import.
     * {@code --upgrade-package casanovo} upgrades only Casanovo and the deps its
     * new version strictly requires, leaving the carefully-installed CUDA
     * torch/torchvision/torchaudio stack in place (the installed versions still
     * satisfy Casanovo's {@code torch>=2.2}).</p>
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
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

        Path logsDir = installRoot.resolve("logs");
        Files.createDirectories(logsDir);
        Logger log = new Logger(logsDir.resolve("install.log"), logSink);
        Runner cmd = new Runner(log);

        Path uvExe = installRoot.resolve("uv").resolve(isWindows ? "uv.exe" : "uv");
        if (!Files.exists(uvExe)) {
            uvExe = findExecutable(installRoot.resolve("uv"), isWindows ? "uv.exe" : "uv");
        }
        Path venvRoot = installRoot.resolve(".venv");

        log.info("=== Casanovo update started ===");

        // Snapshot the GPU-sensitive trio so we can tell whether the upgrade disturbed it.
        String torchBefore = torchStackSignature(venvRoot);

        log.info("Updating Casanovo (preserving the existing PyTorch / GPU stack)...");
        // --upgrade-package casanovo (not -U): upgrade Casanovo only, leaving the
        // CUDA torch/torchvision/torchaudio trio untouched where its constraints allow.
        cmd.run(List.of(uvExe.toString(), "pip", "install",
                "--upgrade-package", "casanovo", "casanovo"), installRoot);

        // Self-heal: if the upgrade moved any of torch/torchvision/torchaudio (e.g.
        // a raised torch floor dragged in a CPU build from PyPI and/or desynced
        // torchvision), re-establish a matched CUDA trio for this machine.
        String torchAfter = torchStackSignature(venvRoot);
        if (!torchAfter.equals(torchBefore)) {
            log.info("PyTorch stack changed during the upgrade:");
            log.info("  before: " + torchBefore);
            log.info("  after:  " + torchAfter);
            log.info("Re-establishing a matched GPU PyTorch stack...");
            installCudaTorchIfGpu(uvExe, installRoot, isWindows, cmd, log);
        } else {
            log.info("PyTorch stack unchanged (" + torchAfter + ") -> no GPU repair needed.");
        }

        pinPyArrow(uvExe, installRoot, cmd, log);

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
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path logsDir = installRoot.resolve("logs");
        Files.createDirectories(logsDir);
        Logger log = new Logger(logsDir.resolve("install.log"), logSink);
        Runner cmd = new Runner(log);
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
        cmd.run(List.of(uvExe.toString(), "pip", "install", PYARROW_PIN), workDir);
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
     * Detect an NVIDIA driver and, when one capable of a CUDA build is present,
     * install a matched {@code torch}/{@code torchvision}/{@code torchaudio} trio
     * from the corresponding PyTorch CUDA wheel index. Installing the three
     * together from a single index guarantees an internally consistent,
     * GPU-enabled stack. A no-op (keeps the CPU stack) when no capable driver is
     * found. Used by both {@link #installAll} and {@link #updateCasanovo}.
     */
    private static void installCudaTorchIfGpu(Path uvExe, Path workDir, boolean isWindows,
                                              Runner cmd, Logger log)
            throws IOException, InterruptedException {
        String driverVersion = detectNvidiaDriver(cmd, workDir, isWindows, log);
        String torchIndexUrl;
        if (driverVersion != null && versionAtLeast(driverVersion, "531.14")) {
            torchIndexUrl = "https://download.pytorch.org/whl/cu121";
            log.info("NVIDIA driver " + driverVersion + " detected -> installing CUDA 12.1 PyTorch.");
        } else if (driverVersion != null && versionAtLeast(driverVersion, "522.06")) {
            torchIndexUrl = "https://download.pytorch.org/whl/cu118";
            log.info("NVIDIA driver " + driverVersion + " detected -> installing CUDA 11.8 PyTorch.");
        } else {
            torchIndexUrl = null;
            log.info("No CUDA-capable NVIDIA driver detected -> using CPU PyTorch.");
        }
        if (torchIndexUrl != null) {
            log.info("Installing CUDA PyTorch wheels (torch, torchvision, torchaudio)...");
            cmd.run(List.of(uvExe.toString(), "pip", "install", "torch", "torchvision", "torchaudio",
                    "--index-url", torchIndexUrl), workDir);
        }
    }

    /**
     * A compact, comparable signature of the GPU-sensitive trio's installed
     * versions (read from {@code dist-info} metadata via {@link PyVenv} — no
     * Python is launched). Includes the local build segment, so a CUDA-to-CPU
     * swap (e.g. {@code 2.5.1+cu121} -> {@code 2.12.0}) registers as a change.
     */
    private static String torchStackSignature(Path venvRoot) {
        return "torch=" + PyVenv.packageVersion(venvRoot, "torch").orElse("?")
                + "; torchvision=" + PyVenv.packageVersion(venvRoot, "torchvision").orElse("?")
                + "; torchaudio=" + PyVenv.packageVersion(venvRoot, "torchaudio").orElse("?");
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
    private static final class Logger {
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
            try {
                Files.writeString(logFile, msg + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
            }
        }
    }

    /** Runs a command, streaming its merged output live to the logger. */
    private static final class Runner {
        private final Logger log;

        Runner(Logger log) {
            this.log = log;
        }

        String run(List<String> command, Path workDir) throws IOException, InterruptedException {
            log.info("$ " + String.join(" ", command));
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            Os.applyNativeEnv(pb); // Windows-only MKL/OpenMP safeguard; no-op elsewhere
            // applyNativeEnv also sets FORCE_COLOR (for the run console's live Rich progress),
            // but the install console does not strip ANSI — so opt out here. NO_COLOR overrides
            // FORCE_COLOR, giving uv/Casanovo plain text instead of raw colour escapes.
            pb.environment().putIfAbsent("NO_COLOR", "1");
            Process p = pb.start();

            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    log.info(line);
                    captured.write(line.getBytes(StandardCharsets.UTF_8));
                    captured.write('\n');
                }
            }
            int code = p.waitFor();
            if (code != 0) {
                throw new IOException("Command failed (exit " + code + "): " + String.join(" ", command));
            }
            return captured.toString(StandardCharsets.UTF_8);
        }
    }
}
