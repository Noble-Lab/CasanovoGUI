package org.casanovo.gui.ui;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.MenuItem;
import javafx.stage.Stage;
import org.casanovo.gui.core.CasanovoCommand;
import org.casanovo.gui.core.CasanovoConfig;
import org.casanovo.gui.core.ConfigCache;
import org.casanovo.gui.core.JavaLauncher;
import org.casanovo.gui.core.LimelightUploader;
import org.casanovo.gui.core.Os;
import org.casanovo.gui.core.Settings;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;

/**
 * Self-contained "Upload to Limelight" feature: after a <em>de novo</em> run, convert the
 * Casanovo mzTab to Limelight XML and upload it to a Limelight instance via two downloaded jars
 * (a converter, then an importer), streaming their output to the app console.
 *
 * <p><b>Origin.</b> Ported from PR #1 (<a href="https://github.com/Noble-Lab/CasanovoGUI/pull/1">
 * limelight-button</a>) by Michael Riffle &lt;mriffle@uw.edu&gt;. That PR was diverged and
 * conflicting; this port adapts it onto current {@code main} with three changes: de-novo only,
 * no Settings-dialog row (jars download on every upload), and maximum isolation.</p>
 *
 * <p><b>Removal recipe.</b> To remove the feature entirely: delete the three Limelight files
 * ({@code core/LimelightUploader.java}, {@code ui/LimelightDialog.java}, this file) and revert
 * the five {@code // limelight}-tagged lines in {@code MainApp.java}. Nothing else is touched —
 * {@code CasanovoRunner}, {@code Settings}, {@code SettingsDialog} and the run bar are untouched.</p>
 *
 * <p><b>Design choices.</b> This controller owns its File-menu {@link MenuItem} (initially
 * disabled), the last-de-novo-result state (FX-thread only), its own {@link Preferences} node
 * {@code org/casanovo/gui/limelight} (never touches {@code Settings.java}), and its own process
 * runner ({@link ProcessBuilder} + {@link Os#applyNativeEnv} + {@code redirectErrorStream} + a
 * daemon reader thread reproducing {@code CasanovoRunner}'s {@code \r}/{@code \n} split, with no
 * cancel machinery). Mutual exclusion reuses MainApp's {@code installing} busy flag via the
 * injected {@code setAppBusy}; the handler bails when {@code appBusy} is set. {@code console} is a
 * {@link Supplier} because {@code MainApp.console} is reassigned by {@code swapConsole}, so it is
 * always resolved fresh, never cached.</p>
 *
 * <p><b>Plaintext key.</b> The Limelight import key is persisted in the {@code limelight}
 * Preferences node as plaintext (Java Preferences offers no encryption). It is masked in the
 * dialog (a {@code PasswordField}) and in the console echo, but a local user with registry/prefs
 * access could read it. There is no Stop control during an upload (by design); the uploader's HTTP
 * timeouts (30s connect / 15min request) bound a hung download.</p>
 */
public final class LimelightController {

    private static final String PREF_URL = "url";
    private static final String PREF_KEY = "key";
    private static final String PREF_PROJECT_ID = "projectId";

    /** ANSI/VT control sequences the jars may emit; stripped before display (mirrors MainApp). */
    private static final Pattern ANSI = Pattern.compile("\\x1B\\[[0-9;?]*[ -/]*[@-~]");

    private final Stage stage;
    private final Settings settings;
    private final Supplier<ConsoleOutput> console;
    private final CasanovoConfig config;
    private final BooleanSupplier appBusy;
    private final Consumer<Boolean> setAppBusy;
    private final Preferences prefs = Preferences.userRoot().node("org/casanovo/gui/limelight");
    private final MenuItem menuItem;

    // Last de novo result — FX-thread only.
    private File lastMzTab;
    private List<File> lastSpectra = new ArrayList<>();
    private File lastConfig;
    private boolean lastWasDenovo;

    private volatile long lastProgressMs;

    public LimelightController(Stage stage, Settings settings, Supplier<ConsoleOutput> console,
                               CasanovoConfig config, BooleanSupplier appBusy,
                               Consumer<Boolean> setAppBusy) {
        this.stage = stage;
        this.settings = settings;
        this.console = console;
        this.config = config;
        this.appBusy = appBusy;
        this.setAppBusy = setAppBusy;
        this.menuItem = new MenuItem("Upload to Limelight");
        this.menuItem.setDisable(true);
        this.menuItem.setOnAction(e -> onUploadToLimelight());
    }

    // ------------------------------------------------------------ public surface (MainApp hooks)

    /** The File-menu item, wired internally; initially disabled. */
    public MenuItem menuItem() {
        return menuItem;
    }

    /**
     * Record whether a starting run is a de novo search and its inputs, and disable the item —
     * a new run supersedes the previous uploadable result. Both De novo and Evaluate emit the
     * {@code sequence} subcommand, so Evaluate is excluded via its {@code --evaluate} argument.
     * Call on the FX thread.
     */
    public void onRunStarted(CasanovoCommand command, List<File> spectra) {
        lastWasDenovo = "sequence".equals(command.getSubcommand())
                && !command.getArguments().contains("--evaluate");
        lastSpectra = spectra == null ? new ArrayList<>() : new ArrayList<>(spectra);
        lastConfig = configArgOf(command);
        lastMzTab = null;
        menuItem.setDisable(true);
    }

    /** Enable the item iff a de novo mzTab result exists (tolerates null). Call on the FX thread. */
    public void onResultReady(File mzTab) {
        lastMzTab = mzTab;
        menuItem.setDisable(!(lastWasDenovo && mzTab != null && mzTab.isFile()));
    }

    // ------------------------------------------------------------ orchestration

    private void onUploadToLimelight() {
        if (appBusy.getAsBoolean()) {
            return;
        }
        if (!(lastWasDenovo && lastMzTab != null && lastMzTab.isFile())) {
            alert(Alert.AlertType.INFORMATION, "Nothing to upload",
                    "Run a De novo search first. The most recent de novo result is uploaded to Limelight.");
            return;
        }
        final File mzTab = lastMzTab;
        // Scan files: only mzML / mzXML inputs are uploadable; others are skipped (--no-scan-files).
        final List<File> scanFiles = new ArrayList<>();
        for (File f : lastSpectra) {
            String n = f.getName().toLowerCase();
            if (f.isFile() && (n.endsWith(".mzml") || n.endsWith(".mzxml"))) {
                scanFiles.add(f);
            }
        }

        LimelightDialog dlg = new LimelightDialog(stage,
                prefs.get(PREF_URL, ""), prefs.get(PREF_KEY, ""), prefs.get(PREF_PROJECT_ID, ""),
                stripExtension(mzTab.getName()), buildInputsSummary(mzTab, scanFiles));
        if (!dlg.showAndCollect()) {
            return;
        }
        final String url = dlg.getUrl();
        final String key = dlg.getKey();
        final String projectId = dlg.getProjectId();
        final String description = dlg.getDescription();
        prefs.put(PREF_URL, url);
        prefs.put(PREF_KEY, key);
        prefs.put(PREF_PROJECT_ID, projectId);

        final File configForRun = lastConfig;
        final File outXml = new File(mzTab.getParentFile(),
                stripExtension(mzTab.getName()) + ".limelight.xml");

        setAppBusy.accept(true);
        console.get().append(System.lineSeparator() + "[limelight] Preparing upload…"
                + System.lineSeparator());

        Thread t = new Thread(() -> {
            try {
                String javaExe = JavaLauncher.find(console.get()::appendLine);
                Path converterJar = LimelightUploader.ensureConverterJar(console.get()::appendLine, null);
                Path importerJar = LimelightUploader.ensureImporterJar(console.get()::appendLine, null);
                File cfg = resolveConfig(configForRun);
                console.get().appendLine("[limelight] Config: " + cfg.getAbsolutePath());
                List<String> convertCmd =
                        LimelightUploader.convertCommand(javaExe, converterJar, mzTab, cfg, outXml);
                List<String> uploadCmd = LimelightUploader.uploadCommand(javaExe, importerJar, url, key,
                        projectId, outXml, description, scanFiles);
                File workDir = mzTab.getParentFile();
                Platform.runLater(() -> runPipeline(convertCmd, uploadCmd, workDir, outXml));
            } catch (Exception ex) {
                String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                Platform.runLater(() -> finish(false, "Preparation failed: " + msg));
            }
        }, "limelight-prepare");
        t.setDaemon(true);
        t.start();
    }

    /** Run convert → (on success) upload, streaming both to the console. Call on the FX thread. */
    private void runPipeline(List<String> convertCmd, List<String> uploadCmd,
                             File workDir, File outXml) {
        console.get().append(System.lineSeparator() + "$ " + displayCommand(convertCmd)
                + System.lineSeparator());
        runProcess(convertCmd, workDir, this::onOutput,
                (code, err) -> Platform.runLater(() -> {
                    if (err != null) {
                        finish(false, "Conversion failed: " + err.getMessage());
                    } else if (code != 0) {
                        finish(false, "Conversion exited with code " + code + ".");
                    } else if (!outXml.isFile()) {
                        finish(false, "Conversion finished but produced no XML file.");
                    } else {
                        console.get().append(System.lineSeparator() + "$ " + maskedUploadDisplay(uploadCmd)
                                + System.lineSeparator());
                        runProcess(uploadCmd, workDir, this::onOutput,
                                (c2, e2) -> Platform.runLater(() -> {
                                    if (e2 != null) {
                                        finish(false, "Upload failed: " + e2.getMessage());
                                    } else if (c2 == 0) {
                                        finish(true, "Uploaded to Limelight successfully.");
                                    } else {
                                        finish(false, "Upload exited with code " + c2 + ".");
                                    }
                                }));
                    }
                }));
    }

    /** Stream jar output to the console, ANSI-stripped, throttling transient progress refreshes. */
    private void onOutput(String text, boolean isTransient) {
        text = ANSI.matcher(text).replaceAll("");
        ConsoleOutput out = console.get();
        if (isTransient) {
            long now = System.currentTimeMillis();
            if (now - lastProgressMs < 80) {
                return;
            }
            lastProgressMs = now;
            out.showProgress(text);
        } else {
            out.appendLine(text);
        }
    }

    /** Clear the busy state and report the outcome. Call on the FX thread. */
    private void finish(boolean ok, String message) {
        setAppBusy.accept(false);
        console.get().appendLine("[limelight] " + message);
        alert(ok ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR,
                ok ? "Limelight upload" : "Limelight upload failed", message);
    }

    /**
     * The config file for the conversion: the one the run used when available, otherwise the
     * installed version's default (generated via {@code casanovo configure}, cached). Spawns
     * Casanovo for the fallback, so call this OFF the FX thread.
     */
    private File resolveConfig(File configForRun) throws IOException {
        if (configForRun != null && configForRun.isFile()) {
            return configForRun;
        }
        console.get().appendLine("[limelight] No config from the run; generating the default…");
        ConfigCache.warm(settings);
        Optional<String> base = ConfigCache.cachedBase(settings);
        String yaml = base.isPresent() ? base.get() : config.toYaml();
        return CasanovoConfig.writeTempConfig(yaml);
    }

    /** A short, read-only summary of what an upload will include, shown in the dialog. */
    private String buildInputsSummary(File mzTab, List<File> scanFiles) {
        StringBuilder sb = new StringBuilder();
        sb.append("Result mzTab: ").append(mzTab.getName()).append('\n');
        sb.append("Config: ").append(
                lastConfig != null && lastConfig.isFile()
                        ? lastConfig.getName()
                        : "auto-generated default").append('\n');
        if (scanFiles.isEmpty()) {
            sb.append("Scan files: none (mzML/mzXML only) — uploading with --no-scan-files");
        } else {
            List<String> names = new ArrayList<>();
            for (File f : scanFiles) {
                names.add(f.getName());
            }
            sb.append("Scan files: ").append(String.join(", ", names));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ own process runner

    /**
     * Launch {@code command} asynchronously on a daemon thread, streaming merged stdout/stderr to
     * {@code onOutput} (text, isTransient) and reporting completion via {@code onFinished}
     * (exitCode, throwable). No cancel machinery. Callbacks run on the reader thread.
     */
    private void runProcess(List<String> command, File workDir,
                            BiConsumer<String, Boolean> onOutput,
                            BiConsumer<Integer, Throwable> onFinished) {
        Thread worker = new Thread(() -> {
            int exit = -1;
            Throwable error = null;
            try {
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                Os.applyNativeEnv(pb);
                if (workDir != null && workDir.isDirectory()) {
                    pb.directory(workDir);
                }
                Process p = pb.start();
                readStream(p.getInputStream(), onOutput);
                exit = p.waitFor();
            } catch (IOException e) {
                error = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                error = e;
            } finally {
                onFinished.accept(error == null ? exit : -1, error);
            }
        }, "limelight-runner");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Read the process output, splitting on {@code \n}, {@code \r\n} and bare {@code \r}. Bare-{@code \r}
     * chunks are transient (progress) refreshes; everything else is a committed line. (Reproduces
     * {@code CasanovoRunner.readStream}.)
     */
    private static void readStream(InputStream in, BiConsumer<String, Boolean> onOutput)
            throws IOException {
        try (PushbackReader r = new PushbackReader(
                new InputStreamReader(in, StandardCharsets.UTF_8), 1)) {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = r.read()) != -1) {
                char ch = (char) c;
                if (ch == '\n') {
                    onOutput.accept(sb.toString(), false);
                    sb.setLength(0);
                } else if (ch == '\r') {
                    int next = r.read();
                    if (next == '\n') {
                        onOutput.accept(sb.toString(), false);
                    } else {
                        onOutput.accept(sb.toString(), true);
                        if (next != -1) {
                            r.unread(next);
                        }
                    }
                    sb.setLength(0);
                } else {
                    sb.append(ch);
                }
            }
            if (sb.length() > 0) {
                onOutput.accept(sb.toString(), false);
            }
        }
    }

    // ------------------------------------------------------------ static helpers

    /** The {@code --config} argument of a command (an existing file), or null. */
    private static File configArgOf(CasanovoCommand command) {
        List<String> args = command.getArguments();
        int idx = args.indexOf("--config");
        if (idx >= 0 && idx + 1 < args.size()) {
            File f = new File(args.get(idx + 1));
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }

    /** Strip a single trailing extension from a file name ({@code a.mztab} -> {@code a}). */
    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Render an OS command as a shell-style string for the console echo (quoting spaces). */
    private static String displayCommand(List<String> cmd) {
        StringBuilder sb = new StringBuilder();
        for (String part : cmd) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            if (part.isEmpty()) {
                sb.append("\"\"");
            } else if (part.chars().anyMatch(Character::isWhitespace)) {
                sb.append('"').append(part).append('"');
            } else {
                sb.append(part);
            }
        }
        return sb.toString();
    }

    /** Like {@link #displayCommand} but masks the Limelight submit key so it never hits the console. */
    private static String maskedUploadDisplay(List<String> cmd) {
        List<String> shown = new ArrayList<>();
        for (String part : cmd) {
            shown.add(part.startsWith("--user-submit-import-key=")
                    ? "--user-submit-import-key=********"
                    : part);
        }
        return displayCommand(shown);
    }

    private void alert(Alert.AlertType type, String title, String message) {
        Alert a = new Alert(type, message, ButtonType.OK);
        a.setTitle(title);
        a.setHeaderText(null);
        if (stage != null) {
            a.initOwner(stage);
        }
        a.showAndWait();
    }
}
