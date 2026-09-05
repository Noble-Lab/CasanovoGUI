package org.casanovo.gui.core;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Installs <a href="https://github.com/Noble-Lab/glissade">glissade</a> &mdash; FDR control for de
 * novo peptides &mdash; <em>into the Python environment the GUI already manages for Casanovo</em>.
 *
 * <p><b>Why share that environment.</b> glissade declares numpy&nbsp;&lt;&nbsp;2, pandas&nbsp;&gt;
 * 2.3, scipy&nbsp;&gt;&nbsp;1.15 and matplotlib&nbsp;&gt;&nbsp;3.10, and the managed Casanovo venv
 * already satisfies every one of them &mdash; Casanovo pins the same {@code numpy<2.0} itself. So
 * the install adds exactly one pure-Python package and moves nothing else. The alternative, a
 * second venv on the CPython 3.13 its metadata asks for, cannot even be built: numpy 1.x has no
 * cp313 wheel, so it would have to run on the numpy its author did <em>not</em> pin.</p>
 *
 * <p><b>The one hack.</b> glissade's {@code requires-python = ">=3.13"} is aspirational: the code
 * compiles, imports and runs on 3.11. That single line is the only thing uv refuses on, so
 * {@link #patchRequiresPython} rewrites it in our own downloaded copy &mdash; asserting there was
 * exactly one such line, so an upstream reformat fails loudly rather than silently installing
 * nothing.</p>
 *
 * <p><b>The safety belt.</b> Every install runs {@code uv pip install --no-deps}. The resolver
 * therefore never runs against Casanovo's venv and cannot upgrade, downgrade or remove anything in
 * it, whatever glissade's metadata says today or tomorrow. The cost is that a genuinely new
 * upstream dependency surfaces as an {@code ImportError}, which the post-install
 * {@code glissade --help} catches by name.</p>
 *
 * <p>Only the GUI's own venv is ever written to: {@link #targetVenv} delegates to
 * {@link CasanovoInstaller#managedVenvRoot}, which is empty for a Conda or {@code PATH} Casanovo.
 * For those, {@link #findInstalledExe} still <em>finds</em> a glissade the user installed
 * themselves next to their {@code casanovo}, but nothing is installed on their behalf.</p>
 */
public final class GlissadeInstaller {

    /**
     * The glissade commit this GUI installs. glissade has no releases, no tags and no PyPI entry,
     * and describes itself as under development, so a pinned SHA is the only reproducible
     * reference. Bumping this constant is what "Reinstall" offers.
     */
    public static final String GLISSADE_REF = "3b70cf921968a3335bf47a766cbd007b77c2c187";

    /** What {@code requires-python} is rewritten to; matches {@link CasanovoInstaller#PYTHON_VERSION}. */
    static final String MIN_PYTHON = "3.11";

    /** Distribution name, as it appears in {@code site-packages} and to {@code uv pip}. */
    static final String PACKAGE = "glissade";

    private static final String ARCHIVE_URL =
            "https://github.com/Noble-Lab/glissade/archive/%s.zip";

    /** {@code glissade --help} must answer within this; it imports numpy/pandas/scipy/matplotlib. */
    private static final long HELP_TIMEOUT_SECONDS = 120;

    /** Matches the whole {@code requires-python = "..."} line, however it is spaced or quoted. */
    private static final Pattern REQUIRES_PYTHON =
            Pattern.compile("(?m)^[ \\t]*requires-python[ \\t]*=.*$");

    private GlissadeInstaller() {
    }

    // ---- locations ---------------------------------------------------------

    /** glissade's own bookkeeping folder, beside the other helpers under {@code ~/.casanovo-gui}. */
    public static Path glissadeDir() {
        return glissadeDir(CasanovoInstaller.defaultInstallRoot());
    }

    /** {@link #glissadeDir()} under an explicit install root, so tests can isolate it. */
    static Path glissadeDir(Path installRoot) {
        return installRoot.resolve("glissade");
    }

    /**
     * The venv this installer may write to: the GUI-managed one, and only that. Empty for a Conda
     * environment, a {@code PATH} name, or any executable the user pointed at themselves &mdash;
     * those belong to the user, and installing into them unasked is exactly the interference this
     * feature must not cause.
     */
    public static Optional<Path> targetVenv(String casanovoExecutable, boolean useConda) {
        return CasanovoInstaller.managedVenvRoot(casanovoExecutable, useConda);
    }

    /** The {@code glissade} launcher inside {@code venvRoot} (it may not exist). */
    static Path exeIn(Path venvRoot) {
        return Os.isWindows()
                ? venvRoot.resolve("Scripts").resolve("glissade.exe")
                : venvRoot.resolve("bin").resolve("glissade");
    }

    /** The interpreter inside {@code venvRoot}. */
    static Path pythonIn(Path venvRoot) {
        return Os.isWindows()
                ? venvRoot.resolve("Scripts").resolve("python.exe")
                : venvRoot.resolve("bin").resolve("python");
    }

    /**
     * A usable {@code glissade} executable, or empty.
     *
     * <p>In the managed venv this is the one we install. Otherwise &mdash; a Conda or {@code PATH}
     * Casanovo &mdash; it looks for a {@code glissade} launcher <em>beside</em> the configured
     * {@code casanovo}, which is where a user who installed it into their own environment (or a
     * developer's {@code pip install -e}) would have put it. Nothing is written either way.</p>
     */
    public static Optional<Path> findInstalledExe(String casanovoExecutable, boolean useConda) {
        Optional<Path> managed = targetVenv(casanovoExecutable, useConda);
        if (managed.isPresent()) {
            return installedExeIn(managed.get());
        }
        if (useConda) {
            // "conda run -n env casanovo": the configured path is conda's, not an env layout we
            // can reason about. Say nothing rather than guess.
            return Optional.empty();
        }
        return PyVenv.venvRootForExecutable(casanovoExecutable)
                .flatMap(GlissadeInstaller::installedExeIn);
    }

    /**
     * Whether glissade is really installed in {@code venvRoot}: the launcher exists <em>and</em>
     * the distribution is present.
     *
     * <p>Both halves matter. Reinstalling or updating Casanovo runs {@code uv venv --clear}, which
     * deletes glissade along with everything else while our {@code VERSION.txt} survives &mdash;
     * so installed-state is derived from the venv, never from the marker file.</p>
     */
    static Optional<Path> installedExeIn(Path venvRoot) {
        Path exe = exeIn(venvRoot);
        if (!Files.isRegularFile(exe)) {
            return Optional.empty();
        }
        return PyVenv.packageVersion(venvRoot, PACKAGE).isPresent()
                ? Optional.of(exe)
                : Optional.empty();
    }

    /** The commit recorded by the last successful install, or empty. */
    public static Optional<String> installedRef() {
        return installedRef(CasanovoInstaller.defaultInstallRoot());
    }

    /** {@link #installedRef()} under an explicit install root. */
    static Optional<String> installedRef(Path installRoot) {
        Path marker = glissadeDir(installRoot).resolve("VERSION.txt");
        try {
            if (Files.isRegularFile(marker)) {
                String ref = Files.readString(marker, StandardCharsets.UTF_8).trim();
                return ref.isEmpty() ? Optional.empty() : Optional.of(ref);
            }
        } catch (IOException ignored) {
            // A missing or unreadable marker just means "unknown commit".
        }
        return Optional.empty();
    }

    /** Short form of a commit SHA, for the status line. */
    public static String shortRef(String ref) {
        return ref == null ? "" : ref.substring(0, Math.min(7, ref.length()));
    }

    // ---- install -----------------------------------------------------------

    /**
     * Download glissade at {@link #GLISSADE_REF} and install it into the managed Casanovo venv.
     * Blocking; callers must stay off the UI thread.
     *
     * @param casanovoExecutable the executable configured in Settings
     * @param useConda           whether that environment is Conda-managed
     * @param logSink            receives every line of progress, or {@code null}
     * @param stallHandler       asked whether to keep waiting on a silent command, or {@code null}
     * @throws IllegalStateException if the GUI does not own the Casanovo environment
     */
    public static void install(String casanovoExecutable, boolean useConda,
                               Consumer<String> logSink,
                               CasanovoInstaller.StallHandler stallHandler) throws Exception {
        Path installRoot = CasanovoInstaller.defaultInstallRoot();
        Path venvRoot = targetVenv(casanovoExecutable, useConda).orElseThrow(
                () -> new IllegalStateException(
                        "The GUI only installs into the Python environment it manages. "
                                + "Install glissade into your own environment instead."));

        Path root = glissadeDir(installRoot);
        Path logsDir = root.resolve("logs");
        Files.createDirectories(logsDir);
        CasanovoInstaller.Logger log =
                new CasanovoInstaller.Logger(logsDir.resolve("install.log"), logSink);
        CasanovoInstaller.Runner cmd = new CasanovoInstaller.Runner(log, 0, 0, stallHandler);

        log.info("=== glissade installation started ===");
        log.info("Target environment: " + venvRoot.toAbsolutePath());
        log.info("Commit: " + GLISSADE_REF);

        Path uvExe = ensureUv(installRoot, log, cmd);

        // ---- source ----
        Path srcRoot = root.resolve("src");
        deleteRecursively(srcRoot);
        Files.createDirectories(srcRoot);
        String url = String.format(ARCHIVE_URL, GLISSADE_REF);
        log.info("Downloading glissade from: " + url);
        Path archive = root.resolve("glissade.zip");
        Downloads.download(url, archive);
        log.info("Extracting glissade...");
        Downloads.unzip(archive, srcRoot);

        // The archive holds a single top-level folder; its src/ is the installable project.
        Path project = singleChild(srcRoot).resolve("src");
        Path pyproject = project.resolve("pyproject.toml");
        if (!Files.isRegularFile(pyproject)) {
            throw new IOException("glissade archive has no src/pyproject.toml under " + srcRoot);
        }

        // ---- relax requires-python ----
        String original = Files.readString(pyproject, StandardCharsets.UTF_8);
        String patched = patchRequiresPython(original);
        Files.writeString(pyproject, patched, StandardCharsets.UTF_8);
        log.info("Relaxed requires-python to >=" + MIN_PYTHON
                + " (glissade runs on " + MIN_PYTHON + "; its >=3.13 is aspirational).");

        // ---- install ----
        Path python = pythonIn(venvRoot);
        if (!Files.isRegularFile(python)) {
            throw new IOException("No Python interpreter at " + python
                    + ". Install or repair Casanovo first.");
        }
        log.info("Installing glissade into the Casanovo environment (no dependencies are touched)...");
        cmd.run(List.of(uvExe.toString(), "pip", "install",
                "--python", python.toString(), "--no-deps", project.toString()), root);

        // ---- verify ----
        Path exe = exeIn(venvRoot);
        if (!Files.isRegularFile(exe)) {
            throw new IOException("glissade installed but no launcher appeared at " + exe);
        }
        log.info("Verifying glissade...");
        // --help imports numpy, pandas, scipy and matplotlib at module scope, so this is a full
        // dependency preflight: with --no-deps nothing else checks them.
        new CasanovoInstaller.Runner(log, 0, HELP_TIMEOUT_SECONDS, null)
                .run(List.of(exe.toString(), "--help"), root);

        Files.writeString(root.resolve("VERSION.txt"), GLISSADE_REF, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.deleteIfExists(archive);
        log.info("glissade " + shortRef(GLISSADE_REF) + " ready: " + exe.toAbsolutePath());
    }

    /** Remove glissade from the managed venv (used before a reinstall). Never touches anything else. */
    public static void uninstall(String casanovoExecutable, boolean useConda,
                                 Consumer<String> logSink) throws Exception {
        Path installRoot = CasanovoInstaller.defaultInstallRoot();
        Optional<Path> venvRoot = targetVenv(casanovoExecutable, useConda);
        if (venvRoot.isEmpty()) {
            return;
        }
        Path root = glissadeDir(installRoot);
        Files.createDirectories(root);
        CasanovoInstaller.Logger log =
                new CasanovoInstaller.Logger(root.resolve("logs").resolve("install.log"), logSink);
        Path uvExe = ensureUv(installRoot, log, new CasanovoInstaller.Runner(log));
        new CasanovoInstaller.Runner(log, 0, HELP_TIMEOUT_SECONDS, null).run(
                List.of(uvExe.toString(), "pip", "uninstall",
                        "--python", pythonIn(venvRoot.get()).toString(), PACKAGE), root);
        Files.deleteIfExists(root.resolve("VERSION.txt"));
    }

    /**
     * Rewrite the {@code requires-python} line to {@code >=}{@value #MIN_PYTHON}.
     *
     * <p>This is the one load-bearing edit in the whole feature, so it refuses to guess: exactly
     * one such line must exist. If upstream ever reformats or removes it, the install fails with a
     * message naming the file instead of quietly producing an environment nobody checked.</p>
     *
     * @throws IllegalStateException if there is not exactly one {@code requires-python} line
     */
    static String patchRequiresPython(String pyprojectText) {
        Matcher m = REQUIRES_PYTHON.matcher(pyprojectText);
        int found = 0;
        while (m.find()) {
            found++;
        }
        if (found != 1) {
            throw new IllegalStateException("Expected exactly one requires-python line in "
                    + "glissade's pyproject.toml, found " + found
                    + ". Upstream changed its packaging; the GUI's pin needs updating.");
        }
        return m.reset().replaceAll(
                Matcher.quoteReplacement("requires-python = \">=" + MIN_PYTHON + "\""));
    }

    // ---- plumbing ----------------------------------------------------------

    /**
     * The {@code uv} binary, downloading it into {@code ~/.casanovo-gui/uv} if the Casanovo
     * installer has not already put it there. Deliberately a copy of that installer's logic rather
     * than a refactor of it: keeping this feature additive is worth ~30 duplicated lines, and the
     * Casanovo install path is the one thing that must not change.
     */
    private static Path ensureUv(Path installRoot, CasanovoInstaller.Logger log,
                                 CasanovoInstaller.Runner cmd) throws Exception {
        Path uvDir = installRoot.resolve("uv");
        Files.createDirectories(uvDir);
        String exeName = Os.isWindows() ? "uv.exe" : "uv";
        Optional<Path> existing = Downloads.findExecutable(uvDir, exeName);
        if (existing.isPresent()) {
            log.info("Using uv: " + existing.get().toAbsolutePath());
            return existing.get();
        }

        String uvUrl = Downloads.uvDownloadUrl();
        Path archive = uvDir.resolve(Downloads.uvArchiveName());
        log.info("Downloading uv from: " + uvUrl);
        Downloads.download(uvUrl, archive);
        log.info("Extracting uv...");
        if (Os.isWindows()) {
            Downloads.unzip(archive, uvDir);
        } else {
            cmd.run(List.of("tar", "-xzf", archive.toString(), "-C", uvDir.toString()), installRoot);
        }
        Path uvExe = Downloads.findExecutable(uvDir, exeName)
                .orElseThrow(() -> new FileNotFoundException(exeName + " not found under " + uvDir));
        try {
            uvExe.toFile().setExecutable(true);
        } catch (SecurityException ignored) {
            // best effort; a non-executable uv fails loudly on first use anyway
        }
        return uvExe;
    }

    /** The single directory inside {@code dir} (a GitHub source archive has exactly one). */
    static Path singleChild(Path dir) throws IOException {
        try (var entries = Files.list(dir)) {
            List<Path> dirs = entries.filter(Files::isDirectory).toList();
            if (dirs.size() != 1) {
                throw new IOException("Expected one folder in the glissade archive, found "
                        + dirs.size() + " in " + dir);
            }
            return dirs.get(0);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
