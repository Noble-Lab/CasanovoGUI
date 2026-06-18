package org.casanovo.gui.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.casanovo.gui.core.CasanovoCommand;
import org.casanovo.gui.core.CasanovoConfig;
import org.casanovo.gui.core.CasanovoInstaller;
import org.casanovo.gui.core.CasanovoRunner;
import org.casanovo.gui.core.CasanovoWeights;
import org.casanovo.gui.core.ConfigCache;
import org.casanovo.gui.core.Os;
import org.casanovo.gui.core.PdvLauncher;
import org.casanovo.gui.core.PyVenv;
import org.casanovo.gui.core.Settings;
import org.casanovo.gui.core.UpdateChecker;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The JavaFX application: a settings bar, a tab per Casanovo sub-command, a
 * Parameters row, a command preview with Run/Stop, and a live Console.
 */
public class MainApp extends Application {

    private final Settings settings = new Settings();
    private final CasanovoConfig config = new CasanovoConfig();
    private final CasanovoRunner runner = new CasanovoRunner();

    private final TabPane tabs = new TabPane();
    private ConsoleOutput console;
    private SplitPane split;
    private final List<CommandPane> panes = new ArrayList<>();

    private final Label settingsLabel = new Label();
    private final TextField commandPreview = new TextField();
    private final Button paramsButton = new Button("Parameters");
    private final CheckBox useGuiParams = new CheckBox("Use GUI parameters (generate --config)");
    private final Button pdvButton = new Button("Open in PDV");
    private final Button runButton = new Button("Run Casanovo");
    private final Button stopButton = new Button("Stop");
    private final Label statusLabel = new Label("Ready.");
    private final ProgressBar progressBar = new ProgressBar(0);
    private final UpdateBanner updateBanner = new UpdateBanner();

    private static final Pattern PCT = Pattern.compile("(\\d+)%\\|");
    /**
     * tqdm/Lightning-Rich "&lt;done&gt;/&lt;total&gt;" token. The total is a count when known,
     * else a placeholder that varies by version ("--", "?", "None", "inf"), so it is captured
     * loosely and treated as "unknown" unless it parses as a number.
     */
    private static final Pattern RICH_COUNT = Pattern.compile("(\\d+)/(\\S+)");
    /** ANSI/VT control sequences (colour, cursor) emitted once FORCE_COLOR makes Rich stream live. */
    private static final Pattern ANSI = Pattern.compile("\\x1B\\[[0-9;?]*[ -/]*[@-~]");
    /** Casanovo logs "Test dataset contains N spectra." — the count of spectra to be predicted. */
    private static final Pattern SPECTRA_COUNT = Pattern.compile("dataset contains (\\d+) spectra");
    /** "predict_batch_size: N" line in the run's --config YAML. */
    private static final Pattern BATCH_SIZE = Pattern.compile("^\\s*predict_batch_size:\\s*(\\d+)");
    private volatile long lastProgressMs = 0L;
    /** Last time a real "%|" tqdm bar was shown; non-%| progress noise is dropped within 1 s of it. */
    private volatile long lastBarMs = 0L;
    /** Base status text for the current run ("Running …"), so progress can append a live count. */
    private String runStatusBase = "";
    /** Effective predict batch size for the current run (spectra per Lightning batch). */
    private volatile int predictBatchSize = 1024;
    /**
     * Total Lightning batches for the current prediction, derived from the logged spectrum count
     * and {@link #predictBatchSize}. 0 = not yet known (Lightning itself reports the total as "--"
     * because the dataset is streamed), so the bar stays animated until this is resolved.
     */
    private volatile int predictTotalBatches = 0;

    private volatile boolean installing = false;
    private volatile boolean checkpointErrorSeen = false;

    // Most recent successful result (sequence / db-search / evaluate), so "Open in PDV"
    // can load it directly. Captured at run start, resolved when the run finishes.
    private List<File> lastResultSpectra;
    private File lastResultMzTab;
    private List<File> pendingSpectra;
    private File pendingOutputDir;
    private long pendingRunStartMs;
    // Most recently launched PDV process + the result it shows, to avoid duplicate windows.
    private Process lastPdvProcess;
    private File lastPdvMzTab;
    private Stage stage;

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        Themes.apply(settings.getTheme());
        console = makeConsole(settings.isColoredConsole());
        try (java.io.InputStream icon = getClass().getResourceAsStream("/org/casanovo/gui/icon.png")) {
            if (icon != null) {
                primaryStage.getIcons().add(new javafx.scene.image.Image(icon));
            }
        } catch (Exception ignored) {
            // no icon is fine
        }
        useGuiParams.setSelected(true);

        panes.add(new SequencePane(primaryStage));
        panes.add(new DbSearchPane(primaryStage));
        panes.add(new EvalPane(primaryStage));
        panes.add(new TrainPane(primaryStage));
        panes.add(new ConfigurePane(primaryStage));

        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        for (CommandPane p : panes) {
            tabs.getTabs().add(new Tab(p.getTitle(), p.getContent()));
        }
        tabs.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> refreshPreview());

        VBox topArea = new VBox(tabs, buildRunBar());
        VBox.setVgrow(tabs, Priority.ALWAYS);

        split = new SplitPane(topArea, console.getView());
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.62);

        BorderPane root = new BorderPane();
        // The update banner sits between the menu bar and the settings bar; it is
        // hidden (and takes no space) until a check finds something.
        root.setTop(new VBox(buildMenuBar(), updateBanner));
        root.setCenter(split);
        root.setBottom(buildStatusBar());
        // Match the Carafe GUI base font: Segoe UI 13px (with cross-platform fallbacks).
        root.setStyle("-fx-font-family: 'Segoe UI', 'Inter', 'SF Pro Text', 'Helvetica Neue', sans-serif; -fx-font-size: 13px;");

        wireActions();
        refreshSettingsLabel();
        console.setLeftStatus(buildExecutionReadout());
        refreshPreview();
        updateRunningState(false);

        Scene scene = new Scene(root, 940, 820);
        // Scene-level accelerators so Run/Stop work from anywhere in the window.
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.R, KeyCombination.SHORTCUT_DOWN),
                () -> { if (!runButton.isDisabled()) runButton.fire(); });
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.ESCAPE),
                () -> { if (!stopButton.isDisabled()) stopButton.fire(); });
        primaryStage.setTitle("Casanovo GUI");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(780);
        primaryStage.setMinHeight(640);
        primaryStage.show();

        maybeAutoCheckForUpdates();
        maybeCheckPyArrow();
        warmConfigCacheAsync();
    }

    @Override
    public void stop() {
        runner.cancel();
    }

    private MenuBar buildMenuBar() {
        Menu fileMenu = new Menu("File");
        MenuItem settingsItem = new MenuItem("Settings");
        settingsItem.setAccelerator(new KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN));
        settingsItem.setOnAction(e -> openSettings());
        MenuItem paramsItem = new MenuItem("Parameters");
        paramsItem.setAccelerator(new KeyCodeCombination(KeyCode.P, KeyCombination.SHORTCUT_DOWN));
        paramsItem.setOnAction(e -> openParameters());
        MenuItem installItem = new MenuItem("Install Casanovo");
        installItem.setOnAction(e -> onInstall());
        MenuItem pdvItem = new MenuItem("Open in PDV");
        pdvItem.setAccelerator(new KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN));
        pdvItem.setOnAction(e -> openPdv());
        MenuItem exitItem = new MenuItem("Exit");
        exitItem.setAccelerator(new KeyCodeCombination(KeyCode.Q, KeyCombination.SHORTCUT_DOWN));
        exitItem.setOnAction(e -> stage.close());
        fileMenu.getItems().addAll(settingsItem, paramsItem, installItem, pdvItem,
                new javafx.scene.control.SeparatorMenuItem(), exitItem);

        Menu helpMenu = new Menu("Help");
        MenuItem checkUpdatesItem = new MenuItem("Check for Updates");
        checkUpdatesItem.setOnAction(e -> runUpdateCheck(true));
        CheckMenuItem autoCheckItem = new CheckMenuItem("Automatically check on startup");
        autoCheckItem.setSelected(UpdateChecker.isAutoCheckEnabled());
        autoCheckItem.setOnAction(e -> UpdateChecker.setAutoCheckEnabled(autoCheckItem.isSelected()));
        MenuItem aboutItem = new MenuItem("About");
        aboutItem.setOnAction(e -> showAbout());
        helpMenu.getItems().addAll(checkUpdatesItem, autoCheckItem,
                new javafx.scene.control.SeparatorMenuItem(), aboutItem);

        return new MenuBar(fileMenu, buildViewMenu(), helpMenu);
    }

    private Menu buildViewMenu() {
        Menu viewMenu = new Menu("View");
        Menu themeMenu = new Menu("Theme");
        ToggleGroup group = new ToggleGroup();
        for (String name : Themes.THEME_NAMES) {
            RadioMenuItem item = new RadioMenuItem(name);
            item.setToggleGroup(group);
            if (name.equals(settings.getTheme())) {
                item.setSelected(true);
            }
            item.setOnAction(e -> {
                if (Themes.apply(name)) {
                    settings.setTheme(name);
                    settings.save();
                }
            });
            themeMenu.getItems().add(item);
        }
        viewMenu.getItems().add(themeMenu);

        CheckMenuItem coloredItem = new CheckMenuItem("Colored console output");
        coloredItem.setSelected(settings.isColoredConsole());
        coloredItem.setOnAction(e -> {
            settings.setColoredConsole(coloredItem.isSelected());
            settings.save();
            swapConsole(coloredItem.isSelected());
        });
        viewMenu.getItems().add(coloredItem);
        return viewMenu;
    }

    /** Create the console implementation for the given preference. */
    private ConsoleOutput makeConsole(boolean colored) {
        return colored ? new RichConsoleView() : new ConsoleView();
    }

    /**
     * Swap the console widget in place (live), preserving the split divider and the
     * left-status readout. The previous console's text is not carried over.
     */
    private void swapConsole(boolean colored) {
        ConsoleOutput previous = console;
        console = makeConsole(colored);
        console.setLeftStatus(buildExecutionReadout());
        int idx = split.getItems().indexOf(previous.getView());
        if (idx >= 0) {
            double[] dividers = split.getDividerPositions();
            split.getItems().set(idx, console.getView());
            split.setDividerPositions(dividers);
        }
    }

    private Region buildRunBar() {
        HBox paramsRow = new HBox(8, paramsButton, useGuiParams);
        paramsRow.setAlignment(Pos.CENTER_LEFT);
        paramsRow.setPadding(new Insets(6, 10, 0, 10));

        runButton.getStyleClass().add("accent");
        runButton.setTooltip(new javafx.scene.control.Tooltip("Run the current Casanovo command (Ctrl+R)"));
        stopButton.getStyleClass().add("danger");
        stopButton.setTooltip(new javafx.scene.control.Tooltip("Stop the running Casanovo process (Esc)"));
        pdvButton.setTooltip(new javafx.scene.control.Tooltip(
                "Open the spectra + Casanovo mzTab in PDV (downloads PDV on first use)"));
        commandPreview.setEditable(false);
        // The command preview is read-only; skip it in tab order.
        commandPreview.setFocusTraversable(false);
        commandPreview.setStyle("-fx-font-family: 'Consolas', 'Menlo', 'DejaVu Sans Mono', 'Courier New', monospace; -fx-font-size: 13px;");
        HBox.setHgrow(commandPreview, Priority.ALWAYS);
        HBox cmdRow = new HBox(8, new Label("Command:"), commandPreview, stopButton, runButton, pdvButton);
        cmdRow.setAlignment(Pos.CENTER_LEFT);
        cmdRow.setPadding(new Insets(8, 10, 8, 10));

        return new VBox(paramsRow, cmdRow);
    }

    private Region buildStatusBar() {
        progressBar.setPrefWidth(220);
        progressBar.setVisible(false);
        statusLabel.getStyleClass().add("text-muted");
        HBox bar = new HBox(8, statusLabel, progressBar);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(3, 10, 3, 10));
        return bar;
    }

    /** The "Execution: <casanovo>" readout shown at the left of the console's bottom bar. */
    private Region buildExecutionReadout() {
        settingsLabel.getStyleClass().add("text-muted");
        settingsLabel.setMaxWidth(460);
        HBox box = new HBox(6, new Label("Execution:"), settingsLabel);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private void wireActions() {
        runButton.setOnAction(e -> onRun());
        stopButton.setOnAction(e -> onStop());
        paramsButton.setOnAction(e -> openParameters());
        pdvButton.setOnAction(e -> openPdv());
        useGuiParams.setOnAction(e -> refreshPreview());
    }

    private void openParameters() {
        boolean saved = new ConfigDialog(stage, config).showAndApply();
        if (saved) {
            useGuiParams.setSelected(true);
            refreshPreview();
        }
    }

    private void openSettings() {
        boolean saved = new SettingsDialog(stage, settings).showAndApply();
        if (saved) {
            refreshSettingsLabel();
            refreshPreview();
        }
    }

    private CommandPane currentPane() {
        int idx = tabs.getSelectionModel().getSelectedIndex();
        return panes.get(Math.max(0, idx));
    }

    private void refreshPreview() {
        try {
            CasanovoCommand cmd = effectiveCommand(currentPane(), false);
            commandPreview.setText(cmd.toDisplayString(settings));
        } catch (RuntimeException ex) {
            commandPreview.setText("");
        }
    }

    private CasanovoCommand effectiveCommand(CommandPane pane, boolean forRun) {
        CasanovoCommand base = pane.buildCommand();
        if (!useGuiParams.isSelected()
                || "configure".equals(base.getSubcommand())
                || base.getArguments().contains("--config")) {
            return base;
        }
        String configPath;
        if (forRun) {
            try {
                configPath = writeEffectiveConfig(resolveOutputDir(base)).getAbsolutePath();
            } catch (IOException e) {
                throw new RuntimeException("Could not write generated config: " + e.getMessage(), e);
            }
        } else {
            configPath = "<generated-config.yaml>";
        }
        List<String> args = new ArrayList<>();
        args.add("--config");
        args.add(configPath);
        args.addAll(base.getArguments());
        return new CasanovoCommand(base.getSubcommand(), args);
    }

    private void refreshSettingsLabel() {
        if (settings.isUseConda() && !settings.getCondaEnv().isEmpty()) {
            settingsLabel.setText(settings.getCasanovoExecutable()
                    + "  (conda env: " + settings.getCondaEnv() + ")");
        } else {
            settingsLabel.setText(settings.getCasanovoExecutable() + "  (PATH / direct)");
        }
        settingsLabel.setTooltip(new javafx.scene.control.Tooltip(settingsLabel.getText()));
    }

    private void onRun() {
        if (runner.isRunning() || installing) {
            return;
        }
        CommandPane pane = currentPane();
        String error = pane.validateInputs();
        if (error != null) {
            alert(Alert.AlertType.WARNING, "Cannot run", error);
            return;
        }
        // Casanovo missing? Offer to install it now and then run the analysis automatically.
        if (!casanovoAvailable()) {
            Alert ask = new Alert(Alert.AlertType.CONFIRMATION,
                    "Casanovo is not found or installed yet.\n\n"
                            + "Install the latest Casanovo now and then run the analysis?\n\n"
                            + "Downloads a private Python runtime + Casanovo into "
                            + CasanovoInstaller.defaultInstallRoot()
                            + "\n(needs internet; takes a few minutes).",
                    ButtonType.YES, ButtonType.NO);
            ask.setTitle("Casanovo not found");
            ask.setHeaderText(null);
            if (stage != null) {
                ask.initOwner(stage);
            }
            if (ask.showAndWait().orElse(ButtonType.NO) == ButtonType.YES) {
                runInstall(this::onRun); // install, then re-enter onRun (now available) and run
            }
            return;
        }
        // Pre-check: if a concrete path was configured (not just "casanovo"), make
        // sure it exists before attempting to launch — gives a clear, actionable
        // error instead of a generic IOException after the spawn fails.
        String execCheck = checkExecutable();
        if (execCheck != null) {
            alert(Alert.AlertType.ERROR, "Casanovo not found", execCheck);
            return;
        }
        CasanovoCommand command;
        try {
            command = effectiveCommand(pane, true);
        } catch (RuntimeException ex) {
            alert(Alert.AlertType.ERROR, "Cannot run", ex.getMessage());
            return;
        }
        refreshPreview();

        File workingDir = inferWorkingDir(command);
        // Remember the inputs + where the result will land so "Open in PDV" can load it directly.
        pendingSpectra = pane.resultSpectra();
        pendingOutputDir = (workingDir != null) ? workingDir : new File(System.getProperty("user.dir"));
        pendingRunStartMs = System.currentTimeMillis() - 3000L; // small clock-skew buffer
        console.append(System.lineSeparator() + "$ " + command.toDisplayString(settings)
                + System.lineSeparator());
        String runLabel = (pane instanceof SequencePane)
                ? "de novo peptide sequencing"
                : command.getSubcommand();
        runStatusBase = "Running " + runLabel + "…";
        statusLabel.setText(runStatusBase);
        predictTotalBatches = 0;
        predictBatchSize = readPredictBatchSize(command);
        updateRunningState(true);
        progressBar.setVisible(true);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        lastProgressMs = 0L;
        lastBarMs = 0L;
        checkpointErrorSeen = false;

        runner.start(command, settings, workingDir,
                this::onOutput,
                (exit, err) -> Platform.runLater(() -> onFinished(exit, err)));
    }

    /**
     * Verify the configured Casanovo executable exists when it looks like a
     * concrete file path. Returns an error message, or {@code null} if the
     * check passes (or cannot be performed — e.g. PATH-relative name, or Conda
     * mode where the env resolves the binary).
     */
    private String checkExecutable() {
        if (settings.isUseConda()) {
            return null; // conda run resolves the executable inside the env
        }
        String exe = settings.getCasanovoExecutable();
        if (exe == null || exe.trim().isEmpty()) {
            return null;
        }
        // A bare name like "casanovo" relies on PATH — can't reliably check here.
        if (!exe.contains(File.separator) && !exe.contains("/")) {
            return null;
        }
        File f = new File(exe);
        if (f.isFile()) {
            return null;
        }
        return "The configured Casanovo executable could not be found:\n" + exe
                + "\n\nFix it in File → Settings, or click \"Install Casanovo\" to "
                + "download Python + Casanovo into ~/.casanovo-gui.";
    }

    /**
     * Whether a runnable Casanovo can be found, checked in the order the command would
     * actually resolve (so "available" means the spawn will succeed):
     * <ol>
     *   <li>Conda mode → the env resolves it.</li>
     *   <li>An explicitly configured concrete path → it must exist.</li>
     *   <li>The GUI's own managed venv → adopt it (preferred over an unknown system
     *       Casanovo, since the installer pinned a compatible PyArrow/PyTorch stack).</li>
     *   <li>A bare {@code casanovo} on PATH.</li>
     * </ol>
     */
    private boolean casanovoAvailable() {
        if (settings.isUseConda()) {
            return true; // conda run resolves it inside the env
        }
        String exe = settings.getCasanovoExecutable();
        if (exe.contains(File.separator) || exe.contains("/")) {
            return new File(exe).isFile(); // explicit concrete path
        }
        // Bare name: prefer the GUI's own managed install over whatever is on PATH.
        File managed = CasanovoInstaller.managedExecutable().toFile();
        if (managed.isFile()) {
            settings.setCasanovoExecutable(managed.getAbsolutePath());
            settings.setUseConda(false);
            settings.save();
            refreshSettingsLabel();
            refreshPreview();
            return true;
        }
        return onPath(exe);
    }

    /** True if {@code name} resolves on {@code PATH} (trying Windows executable suffixes). */
    private static boolean onPath(String name) {
        String path = System.getenv("PATH");
        if (path == null || path.isEmpty()) {
            return false;
        }
        String[] exts = Os.isWindows()
                ? new String[]{"", ".exe", ".cmd", ".bat"}
                : new String[]{""};
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isEmpty()) {
                continue;
            }
            for (String ext : exts) {
                if (new File(dir, name + ext).isFile()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Handle one chunk of process output (called from the runner's background
     * thread). Transient chunks are progress refreshes: collapsed into a single
     * console line and used to drive the progress bar, throttled so the UI
     * thread is not flooded. Committed chunks are appended as permanent lines.
     */
    private void onOutput(String text, boolean isTransient) {
        // FORCE_COLOR makes Rich stream progress live, but at the cost of embedded
        // colour/cursor escape codes — strip them so the console and parser see plain text.
        text = ANSI.matcher(text).replaceAll("");
        // 1) A real tqdm "<pct>%|<bar>|" chunk (db-search "Scoring candidates", spectrum loading)
        //    is the live progress — show it as the single updating line, whether it arrived as a
        //    \r refresh or newline-terminated.
        if (text.indexOf("%|") >= 0) {
            lastBarMs = System.currentTimeMillis();
            showProgressThrottled(text);
            return;
        }
        // 2) A non-%| progress chunk: Lightning's "Predicting" Rich bar (the live bar in de novo),
        //    or — in db-search — an interleave fragment ("3641.07PSM/s]") / "Predicting 0/--" that
        //    collides with the tqdm bar in the pipe. While a %| bar is live (just seen), drop this
        //    noise so the console isn't flooded with rate tails; otherwise show it (de novo's bar).
        if (isTransient || isProgressNoise(text)) {
            if (System.currentTimeMillis() - lastBarMs < 1000) {
                return;
            }
            showProgressThrottled(text);
            return;
        }
        // 3) A real committed log line. Drop whitespace-only lines: Lightning's Rich bar pads to
        //    the terminal width, which after ANSI stripping leaves a committed line of spaces.
        //    Casanovo's own log is all prefixed INFO:/WARNING: lines, so no meaningful blank is lost.
        if (text.isBlank()) {
            return;
        }
        if (looksLikeCheckpointError(text)) {
            checkpointErrorSeen = true;
        }
        maybeCaptureSpectrumCount(text);
        console.appendLine(text);
    }

    /** A progress chunk with no "%|" bar — a tqdm rate tail or Lightning's Rich "Predicting" bar. */
    private static boolean isProgressNoise(String s) {
        return s.indexOf("/s]") >= 0 || s.indexOf("it/s") >= 0
                || s.indexOf("Predicting") >= 0 || s.indexOf('•') >= 0;
    }

    /** Show a progress refresh as the single transient line, rate-limited to protect the FX thread. */
    private void showProgressThrottled(String text) {
        long now = System.currentTimeMillis();
        if (now - lastProgressMs < 80) {
            return; // throttle high-frequency progress refreshes
        }
        lastProgressMs = now;
        console.showProgress(text);
        final String t = text;
        Platform.runLater(() -> updateProgressBar(t));
    }

    /** Output signatures of a corrupt/incompatible model checkpoint (e.g. a partial download). */
    private static boolean looksLikeCheckpointError(String line) {
        String l = line.toLowerCase();
        return l.contains("weights file incompatible")
                || l.contains("failed finding central directory")
                || l.contains("pytorchstreamreader failed");
    }

    /**
     * Derive the prediction's total batch count from Casanovo's "… dataset contains N spectra."
     * log line. Lightning reports the total as "--" because the dataset is streamed, but
     * {@code totalBatches = ceil(N / predict_batch_size)} lets us show a real progress bar.
     */
    private void maybeCaptureSpectrumCount(String line) {
        Matcher m = SPECTRA_COUNT.matcher(line);
        if (m.find()) {
            try {
                long n = Long.parseLong(m.group(1));
                int bs = Math.max(1, predictBatchSize);
                predictTotalBatches = (int) ((n + bs - 1) / bs); // ceiling division
            } catch (NumberFormatException ignored) {
                // leave total unknown -> animated bar
            }
        }
    }

    /**
     * Read {@code predict_batch_size} from the run's {@code --config} YAML (the exact file
     * Casanovo reads). Defaults to 1024 — Casanovo's own default — when absent or unreadable.
     */
    private int readPredictBatchSize(CasanovoCommand command) {
        List<String> args = command.getArguments();
        int idx = args.indexOf("--config");
        if (idx >= 0 && idx + 1 < args.size()) {
            File cfg = new File(args.get(idx + 1));
            if (cfg.isFile()) {
                try {
                    for (String line : Files.readAllLines(cfg.toPath())) {
                        Matcher m = BATCH_SIZE.matcher(line);
                        if (m.find()) {
                            return Integer.parseInt(m.group(1));
                        }
                    }
                } catch (IOException | NumberFormatException ignored) {
                    // fall through to the default
                }
            }
        }
        return 1024;
    }

    /** Drive the progress bar from a tqdm "NN%|" or Lightning-Rich "done/total" token. */
    private void updateProgressBar(String line) {
        // tqdm style: "NN%|" gives an exact percentage.
        Matcher m = PCT.matcher(line);
        if (m.find()) {
            try {
                double pct = Integer.parseInt(m.group(1)) / 100.0;
                // A stage hitting 100% does not mean the run is done: Casanovo still
                // aggregates predictions and writes the output file afterwards, and
                // further stages may follow. Show the animated indeterminate bar so a
                // completed stage doesn't look stuck/finished while work continues.
                progressBar.setProgress(pct >= 1.0
                        ? ProgressBar.INDETERMINATE_PROGRESS
                        : Math.max(0, pct));
                return;
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        // Lightning's Rich progress bar: "<done>/<total>". For de novo Lightning prints the
        // total as "--" (the dataset is streamed), so we substitute the total we derived from
        // the logged spectrum count (see maybeCaptureSpectrumCount). With a total we show a real
        // filling bar; without one (e.g. the count line was suppressed) we fall back to an
        // animated bar plus the live count.
        Matcher r = RICH_COUNT.matcher(line);
        if (r.find()) {
            try {
                int done = Integer.parseInt(r.group(1));
                String total = r.group(2);
                int t = total.chars().allMatch(Character::isDigit)
                        ? Integer.parseInt(total)
                        : predictTotalBatches;
                progressBar.setProgress(t > 0 && done < t
                        ? (double) done / t
                        : ProgressBar.INDETERMINATE_PROGRESS);
                statusLabel.setText(runStatusBase
                        + (t > 0 ? " (" + done + "/" + t + " batches)"
                                 : " (" + done + " batches)"));
                return;
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        // No parseable progress token -> keep an animated bar going.
        if (progressBar.getProgress() >= 1.0 || progressBar.getProgress() < 0) {
            progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        }
    }

    private File inferWorkingDir(CasanovoCommand command) {
        List<String> args = command.getArguments();
        int idx = args.indexOf("--output_dir");
        if (idx >= 0 && idx + 1 < args.size()) {
            File d = new File(args.get(idx + 1));
            if (d.isDirectory()) {
                return d;
            }
        }
        return null;
    }

    private void onFinished(int exitCode, Throwable error) {
        updateRunningState(false);
        progressBar.setProgress(exitCode == 0 ? 1.0 : 0.0);
        progressBar.setVisible(false);
        if (error != null) {
            console.appendLine("[error] " + error.getMessage());
            statusLabel.setText("Failed to start.");
            alert(Alert.AlertType.ERROR, "Execution error", error.getMessage());
        } else if (exitCode == 0) {
            console.appendLine("[done] Casanovo finished successfully (exit 0).");
            statusLabel.setText("Finished successfully.");
            captureResultForPdv();
        } else if (exitCode == 130) {
            console.appendLine("[stopped] Casanovo was cancelled by the user.");
            statusLabel.setText("Stopped.");
        } else {
            console.appendLine("[error] Casanovo exited with code " + exitCode + ".");
            statusLabel.setText("Exited with code " + exitCode + ".");
            if (checkpointErrorSeen) {
                maybeOfferModelRepair();
            }
        }
    }

    /**
     * After a run fails while loading the model weights, look for a corrupt cached
     * checkpoint (a truncated download) and offer to delete it and retry — Casanovo
     * re-downloads the model on the next run. Does nothing when the cached checkpoints
     * are all valid: the failure is then a genuinely incompatible or user-supplied
     * model, which must not be deleted.
     */
    private void maybeOfferModelRepair() {
        List<File> corrupt = CasanovoWeights.findCorruptCheckpoints();
        if (corrupt.isEmpty()) {
            console.appendLine("[hint] The model failed to load, but the cached checkpoints look intact. "
                    + "If you passed a custom --model, check that file; otherwise the weights may be "
                    + "incompatible with this Casanovo version.");
            return;
        }
        StringBuilder list = new StringBuilder();
        for (File f : corrupt) {
            list.append("\n  - ").append(f.getName())
                    .append(" (").append(f.length() / (1024 * 1024)).append(" MB)");
        }
        ButtonType clearRetry = new ButtonType("Clear & retry", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert a = new Alert(Alert.AlertType.WARNING,
                "Casanovo failed while loading the model weights, and a cached checkpoint looks like a"
                        + " corrupt / incomplete download:" + list
                        + "\n\nDelete it and retry? Casanovo will re-download the model"
                        + " (this can take a few minutes).",
                clearRetry, cancel);
        a.setTitle("Corrupt model checkpoint");
        a.setHeaderText(null);
        if (stage != null) {
            a.initOwner(stage);
        }
        if (a.showAndWait().orElse(cancel) != clearRetry) {
            return;
        }
        int deleted = 0;
        for (File f : corrupt) {
            if (f.delete()) {
                deleted++;
                console.appendLine("[repair] deleted corrupt checkpoint: " + f.getAbsolutePath());
            } else {
                console.appendLine("[repair] could not delete: " + f.getAbsolutePath());
            }
        }
        if (deleted > 0) {
            statusLabel.setText("Deleted corrupt model; retrying…");
            onRun(); // re-run the current command; Casanovo re-downloads the model
        }
    }

    private void onStop() {
        if (runner.isRunning()) {
            statusLabel.setText("Stopping…");
            runner.cancel();
        }
    }

    /** Download + install Python and Casanovo in the background. */
    private void onInstall() {
        if (installing || runner.isRunning()) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "This will download a private Python runtime and install Casanovo into:\n"
                        + CasanovoInstaller.defaultInstallRoot()
                        + "\n\nIt needs internet access and can take several minutes. Continue?",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("Install Casanovo");
        confirm.setHeaderText(null);
        if (stage != null) {
            confirm.initOwner(stage);
        }
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }

        runInstall(null);
    }

    /**
     * Download + install Casanovo on a background thread (callers handle confirmation).
     * On success the new venv executable is selected and saved; then {@code afterSuccess}
     * runs if given (e.g. proceed to the analysis), otherwise an "install complete" notice
     * is shown.
     */
    private void runInstall(Runnable afterSuccess) {
        if (installing || runner.isRunning()) {
            return;
        }
        installing = true;
        setBusy(true);
        statusLabel.setText("Installing Casanovo…");
        console.append(System.lineSeparator() + "[install] Starting Casanovo installation…"
                + System.lineSeparator());

        Thread t = new Thread(() -> {
            try {
                String exe = CasanovoInstaller.installAll(
                        CasanovoInstaller.defaultInstallRoot(), console::appendLine);
                Platform.runLater(() -> {
                    settings.setCasanovoExecutable(exe);
                    settings.setUseConda(false);
                    settings.save();
                    warmConfigCacheAsync();
                    installing = false;
                    setBusy(false);
                    refreshSettingsLabel();
                    refreshPreview();
                    statusLabel.setText("Casanovo installed.");
                    if (afterSuccess != null) {
                        afterSuccess.run();
                    } else {
                        alert(Alert.AlertType.INFORMATION, "Install complete",
                                "Casanovo was installed and selected:\n" + exe);
                    }
                });
            } catch (Exception ex) {
                String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                Platform.runLater(() -> {
                    console.appendLine("[install] FAILED: " + msg);
                    installing = false;
                    setBusy(false);
                    statusLabel.setText("Install failed.");
                    alert(Alert.AlertType.ERROR, "Install failed", msg);
                });
            }
        }, "casanovo-installer");
        t.setDaemon(true);
        t.start();
    }

    /** Disable interactive controls while a long background task (install) runs. */
    private void setBusy(boolean busy) {
        pdvButton.setDisable(busy);
        runButton.setDisable(busy);
        paramsButton.setDisable(busy);
        useGuiParams.setDisable(busy);
        tabs.setDisable(busy);
    }

    private void updateRunningState(boolean running) {
        runButton.setDisable(running);
        stopButton.setDisable(!running);
        tabs.setDisable(running);
        paramsButton.setDisable(running);
        useGuiParams.setDisable(running);
        pdvButton.setDisable(running);
    }

    private void alert(Alert.AlertType type, String title, String msg) {
        Alert a = new Alert(type, msg, ButtonType.OK);
        a.setTitle(title);
        a.setHeaderText(null);
        if (stage != null) {
            a.initOwner(stage);
        }
        a.showAndWait();
    }

    /**
     * Open the Casanovo result in PDV's DeNovo viewer. When a recent run produced a
     * result, its spectra + mzTab are loaded directly; otherwise the user picks the
     * inputs as before. PDV is downloaded on first use.
     */
    private void openPdv() {
        if (installing || runner.isRunning()) {
            return;
        }
        // Mark busy at entry (not after the dialogs) so a second click can't slip through
        // while a chooser / tolerance dialog is open or PDV is still starting up. Reset on
        // every cancel path; the launch thread resets it once PDV has started.
        installing = true;
        setBusy(true);
        java.util.List<File> spectra;
        File mzTab;
        boolean directLoad = lastResultMzTab != null && lastResultMzTab.isFile()
                && lastResultSpectra != null && !lastResultSpectra.isEmpty();
        if (directLoad) {
            // Load the most recent Casanovo result directly — no need to re-pick files.
            spectra = lastResultSpectra;
            mzTab = lastResultMzTab;
        } else {
            FileChooser specChooser = new FileChooser();
            specChooser.setTitle("Select spectrum file(s) (mzML / mzXML / MGF)");
            specChooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("Spectra (*.mzML, *.mzXML, *.mgf)", "*.mzML", "*.mzXML", "*.mgf"));
            spectra = specChooser.showOpenMultipleDialog(stage);
            if (spectra == null || spectra.isEmpty()) {
                resetPdvBusy();
                return;
            }
            FileChooser mzTabChooser = new FileChooser();
            mzTabChooser.setTitle("Select Casanovo result (mzTab)");
            mzTabChooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("mzTab (*.mztab)", "*.mztab", "*.mzTab"));
            File parent = spectra.get(0).getParentFile();
            if (parent != null) {
                mzTabChooser.setInitialDirectory(parent);
            }
            mzTab = mzTabChooser.showOpenDialog(stage);
            if (mzTab == null) {
                resetPdvBusy();
                return;
            }
        }

        // Guard against opening a duplicate PDV window for a result that's already open
        // (e.g. an accidental second click while PDV is still starting up).
        if (lastPdvProcess != null && lastPdvProcess.isAlive() && mzTab.equals(lastPdvMzTab)) {
            ButtonType again = new ButtonType("Open another", ButtonBar.ButtonData.OK_DONE);
            Alert dup = new Alert(Alert.AlertType.CONFIRMATION,
                    "PDV is already open for this result (" + mzTab.getName() + ").\n\nOpen another window?",
                    again, ButtonType.CANCEL);
            dup.setTitle("PDV already open");
            dup.setHeaderText(null);
            if (stage != null) {
                dup.initOwner(stage);
            }
            if (dup.showAndWait().orElse(ButtonType.CANCEL) != again) {
                resetPdvBusy();
                return;
            }
        }

        // Fragment m/z tolerance: value + Da/ppm unit, matching PDV's setting
        // ("Fragment m/z Tolerance: 0.05 Da (or ppm)").
        // Sensible defaults: 0.05 for Da, 20 for ppm. When the unit changes,
        // swap the field value only if it still holds the previous unit's
        // unedited default — preserves anything the user typed manually.
        final String DA_DEFAULT = "0.05";
        final String PPM_DEFAULT = "20";
        TextField tolValueField = new TextField(DA_DEFAULT);
        tolValueField.setPrefColumnCount(8);
        ComboBox<String> tolUnitCombo = new ComboBox<>();
        tolUnitCombo.getItems().addAll("Da", "ppm");
        tolUnitCombo.getSelectionModel().select("Da");
        tolUnitCombo.valueProperty().addListener((obs, oldUnit, newUnit) -> {
            String cur = tolValueField.getText() == null ? "" : tolValueField.getText().trim();
            if ("ppm".equals(newUnit) && DA_DEFAULT.equals(cur)) {
                tolValueField.setText(PPM_DEFAULT);
            } else if ("Da".equals(newUnit) && PPM_DEFAULT.equals(cur)) {
                tolValueField.setText(DA_DEFAULT);
            }
        });

        HBox tolRow = new HBox(8, tolValueField, tolUnitCombo);
        tolRow.setAlignment(Pos.CENTER_LEFT);
        GridPane tolGrid = new GridPane();
        tolGrid.setHgap(8);
        tolGrid.setVgap(8);
        tolGrid.setPadding(new Insets(12));
        tolGrid.add(new Label("Fragment m/z Tolerance:"), 0, 0);
        tolGrid.add(tolRow, 1, 0);

        Dialog<ButtonType> tolDialog = new Dialog<>();
        tolDialog.setTitle("Fragment tolerance");
        tolDialog.setHeaderText((directLoad
                ? "Loading result: " + mzTab.getName() + "  ("
                  + spectra.size() + " spectrum file" + (spectra.size() == 1 ? "" : "s") + ")\n"
                : "")
                + "Fragment ion tolerance for spectrum annotation in PDV");
        if (stage != null) {
            tolDialog.initOwner(stage);
        }
        ButtonType okType = new ButtonType("Open in PDV", ButtonBar.ButtonData.OK_DONE);
        tolDialog.getDialogPane().getButtonTypes().addAll(okType, ButtonType.CANCEL);
        tolDialog.getDialogPane().setContent(tolGrid);

        java.util.Optional<ButtonType> tolResult = tolDialog.showAndWait();
        if (!tolResult.isPresent() || tolResult.get() != okType) {
            resetPdvBusy();
            return;
        }
        final String fTolUnit = tolUnitCombo.getValue() == null ? "Da" : tolUnitCombo.getValue();
        double tolVal;
        try {
            tolVal = Double.parseDouble(tolValueField.getText().trim());
        } catch (NumberFormatException ignored) {
            tolVal = "ppm".equals(fTolUnit) ? 20.0 : 0.05;
        }
        final double fTol = tolVal;

        statusLabel.setText("Preparing PDV…");
        console.append(System.lineSeparator() + "[pdv] Opening "
                + (directLoad ? "last result (" + mzTab.getName() + ")" : "results")
                + " in PDV…" + System.lineSeparator());

        final java.util.List<File> fSpectra = spectra;
        final File fMzTab = mzTab;
        Thread t = new Thread(() -> {
            try {
                Path jar = PdvLauncher.ensurePdv(settings.getPdvJar(), console::appendLine);
                Process proc = PdvLauncher.launchDenovo(jar, fSpectra, fMzTab, fTol, fTolUnit, console::appendLine);
                Platform.runLater(() -> {
                    lastPdvProcess = proc;
                    lastPdvMzTab = fMzTab;
                    installing = false;
                    setBusy(false);
                    statusLabel.setText("PDV launched.");
                });
            } catch (Exception ex) {
                String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                Platform.runLater(() -> {
                    console.appendLine("[pdv] FAILED: " + msg);
                    installing = false;
                    setBusy(false);
                    statusLabel.setText("PDV launch failed.");
                    alert(Alert.AlertType.ERROR, "PDV error", msg);
                });
            }
        }, "pdv-launcher");
        t.setDaemon(true);
        t.start();
    }

    /** Release the busy state set at the start of {@link #openPdv()} on a cancel path. */
    private void resetPdvBusy() {
        installing = false;
        setBusy(false);
    }

    /**
     * After a successful run, remember the produced mzTab and its input spectra so the
     * next "Open in PDV" loads them directly. No-op (so "Open in PDV" keeps prompting for
     * files) when the run had no spectra input or wrote no mzTab.
     */
    private void captureResultForPdv() {
        if (pendingSpectra == null || pendingSpectra.isEmpty() || pendingOutputDir == null) {
            return;
        }
        File mztab = findNewestMzTab(pendingOutputDir, pendingRunStartMs);
        if (mztab != null) {
            lastResultSpectra = pendingSpectra;
            lastResultMzTab = mztab;
            console.appendLine("[pdv] Result ready: " + mztab.getName()
                    + " — click \"Open in PDV\" to view it (inputs loaded automatically).");
        }
    }

    /** Newest {@code *.mztab} in {@code dir} modified at/after {@code sinceMs}, or null. */
    private static File findNewestMzTab(File dir, long sinceMs) {
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".mztab"));
        if (files == null) {
            return null;
        }
        File newest = null;
        for (File f : files) {
            if (f.isFile() && f.lastModified() >= sinceMs
                    && (newest == null || f.lastModified() > newest.lastModified())) {
                newest = f;
            }
        }
        return newest;
    }

    private void showAbout() {
        alert(Alert.AlertType.INFORMATION, "About Casanovo GUI",
                "Casanovo GUI " + UpdateChecker.guiVersion() + "\n\n"
                        + "A JavaFX front-end for Casanovo de novo peptide sequencing.\n"
                        + "Configure inputs, run, and watch the console.\n\n"
                        + "Casanovo: https://github.com/Noble-Lab/casanovo");
    }

    // ------------------------------------------------------------ update checks

    /** Silent background check on startup, subject to the opt-out and 12h throttle. */
    private void maybeAutoCheckForUpdates() {
        if (!UpdateChecker.shouldAutoCheckOnStartup()) {
            return;
        }
        runUpdateCheck(false);
    }

    /**
     * Run an update check off the FX thread. When {@code manual} is true the user
     * triggered it from the Help menu, so we give feedback even when nothing is
     * found; auto-checks stay silent unless there's an update to show.
     */
    private void runUpdateCheck(boolean manual) {
        if (manual) {
            statusLabel.setText("Checking for updates…");
        }
        Thread t = new Thread(() -> {
            UpdateChecker.CheckOutcome outcome = UpdateChecker.checkAll(settings);
            Platform.runLater(() -> onUpdateOutcome(outcome, manual));
        }, "update-checker");
        t.setDaemon(true);
        t.start();
    }

    private void onUpdateOutcome(UpdateChecker.CheckOutcome outcome, boolean manual) {
        List<UpdateChecker.UpdateInfo> available = new ArrayList<>();
        for (UpdateChecker.UpdateInfo info : outcome.infos) {
            if (info.updateAvailable) {
                available.add(info);
            }
        }
        if (!available.isEmpty()) {
            updateBanner.show(available, manual, getHostServices(),
                    this::onUpdateCasanovo, this::canSelfUpdate);
            if (manual) {
                statusLabel.setText("Update available.");
            }
            return;
        }
        if (manual) {
            if (outcome.infos.isEmpty() && outcome.networkError) {
                alert(Alert.AlertType.INFORMATION, "Check for updates",
                        "Couldn't check for updates. Please check your internet connection.");
            } else {
                alert(Alert.AlertType.INFORMATION, "Check for updates", upToDateMessage(outcome));
            }
            statusLabel.setText("Up to date.");
        }
    }

    private String upToDateMessage(UpdateChecker.CheckOutcome outcome) {
        StringBuilder sb = new StringBuilder("You're up to date.\n");
        for (UpdateChecker.UpdateInfo info : outcome.infos) {
            sb.append("\n").append(info.displayName).append(": ").append(info.currentVersion)
                    .append(" (latest ").append(info.latestVersion).append(")");
        }
        if (outcome.networkError) {
            sb.append("\n\nNote: some version sources could not be reached.");
        }
        return sb.toString();
    }

    /**
     * True when an update can be applied in-app: it's the Casanovo tool, the GUI
     * manages the install (executable lives under {@code ~/.casanovo-gui}) and
     * Conda is not in use.
     */
    private boolean canSelfUpdate(UpdateChecker.UpdateInfo info) {
        if (info.target != UpdateChecker.Target.CASANOVO || settings.isUseConda()) {
            return false;
        }
        try {
            Path exe = Paths.get(settings.getCasanovoExecutable()).toAbsolutePath().normalize();
            Path root = CasanovoInstaller.defaultInstallRoot().toAbsolutePath().normalize();
            return exe.startsWith(root);
        } catch (Exception e) {
            return false;
        }
    }

    /** Upgrade the GUI-managed Casanovo in place via {@code uv pip install -U casanovo}. */
    private void onUpdateCasanovo(UpdateChecker.UpdateInfo info) {
        if (installing || runner.isRunning()) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Update Casanovo from " + info.currentVersion + " to " + info.latestVersion + "?\n\n"
                        + "This upgrades Casanovo in:\n"
                        + CasanovoInstaller.defaultInstallRoot()
                        + "\nwhile keeping your current PyTorch / GPU setup.\n\n"
                        + "It needs internet access and can take a few minutes. Continue?",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("Update Casanovo");
        confirm.setHeaderText(null);
        if (stage != null) {
            confirm.initOwner(stage);
        }
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }

        installing = true;
        setBusy(true);
        statusLabel.setText("Updating Casanovo…");
        console.append(System.lineSeparator() + "[update] Updating Casanovo…" + System.lineSeparator());

        Thread t = new Thread(() -> {
            try {
                CasanovoInstaller.updateCasanovo(
                        CasanovoInstaller.defaultInstallRoot(), console::appendLine);
                Platform.runLater(() -> {
                    installing = false;
                    setBusy(false);
                    statusLabel.setText("Casanovo updated.");
                    warmConfigCacheAsync(); // new version -> refresh the cached base config
                    updateBanner.removeTarget(UpdateChecker.Target.CASANOVO);
                    alert(Alert.AlertType.INFORMATION, "Update complete",
                            "Casanovo was updated to " + info.latestVersion + ".");
                });
            } catch (Exception ex) {
                String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                Platform.runLater(() -> {
                    console.appendLine("[update] FAILED: " + msg);
                    installing = false;
                    setBusy(false);
                    statusLabel.setText("Update failed.");
                    alert(Alert.AlertType.ERROR, "Update failed", msg);
                });
            }
        }, "casanovo-updater");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------- PyArrow self-check

    /**
     * On startup, detect a PyArrow/pylance ABI mismatch in a GUI-managed venv — the
     * combination that crashes Casanovo with exit 0xC0000005 — and offer a one-click
     * repair. Reads dist-info only: no Python launched, no network.
     */
    private void maybeCheckPyArrow() {
        Path venvRoot = managedVenvRoot();
        if (venvRoot == null) {
            return; // only the GUI-managed install can be auto-repaired
        }
        Thread t = new Thread(() -> {
            if (CasanovoInstaller.hasPyArrowMismatch(venvRoot)) {
                Platform.runLater(this::promptPyArrowRepair);
            }
        }, "pyarrow-check");
        t.setDaemon(true);
        t.start();
    }

    /** Venv root of a GUI-managed Casanovo install (executable under ~/.casanovo-gui), or null. */
    private Path managedVenvRoot() {
        if (settings.isUseConda()) {
            return null;
        }
        try {
            Path exe = Paths.get(settings.getCasanovoExecutable()).toAbsolutePath().normalize();
            Path root = CasanovoInstaller.defaultInstallRoot().toAbsolutePath().normalize();
            if (!exe.startsWith(root)) {
                return null;
            }
            return PyVenv.venvRootForExecutable(settings.getCasanovoExecutable()).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private void promptPyArrowRepair() {
        if (installing || runner.isRunning()) {
            return;
        }
        ButtonType repairBtn = new ButtonType("Repair now", ButtonBar.ButtonData.OK_DONE);
        ButtonType notNow = new ButtonType("Not now", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert confirm = new Alert(Alert.AlertType.WARNING,
                "The Casanovo install in:\n" + CasanovoInstaller.defaultInstallRoot()
                        + "\nhas a PyArrow version incompatible with its pinned pylance, which"
                        + " crashes Casanovo on startup (exit 0xC0000005).\n\n"
                        + "Repair it now? This runs 'uv pip install \"pyarrow>=14,<17\"' and takes a few seconds.",
                repairBtn, notNow);
        confirm.setTitle("Repair Casanovo install");
        confirm.setHeaderText(null);
        if (stage != null) {
            confirm.initOwner(stage);
        }
        if (confirm.showAndWait().orElse(notNow) == repairBtn) {
            runPyArrowRepair();
        }
    }

    private void runPyArrowRepair() {
        installing = true;
        setBusy(true);
        statusLabel.setText("Repairing Casanovo (PyArrow)…");
        console.append(System.lineSeparator() + "[repair] Re-pinning PyArrow…" + System.lineSeparator());
        Thread t = new Thread(() -> {
            try {
                CasanovoInstaller.repairPyArrow(
                        CasanovoInstaller.defaultInstallRoot(), console::appendLine);
                Platform.runLater(() -> {
                    installing = false;
                    setBusy(false);
                    statusLabel.setText("Casanovo repaired.");
                    alert(Alert.AlertType.INFORMATION, "Repair complete",
                            "PyArrow was re-pinned to a compatible version. Casanovo should now run.");
                });
            } catch (Exception ex) {
                String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                Platform.runLater(() -> {
                    console.appendLine("[repair] FAILED: " + msg);
                    installing = false;
                    setBusy(false);
                    statusLabel.setText("Repair failed.");
                    alert(Alert.AlertType.ERROR, "Repair failed", msg);
                });
            }
        }, "pyarrow-repair");
        t.setDaemon(true);
        t.start();
    }

    // -------------------------------------------------------- config generation

    /**
     * Write the config Casanovo will run with: the user's parameters overlaid on the
     * installed version's base config (from {@code casanovo configure}, cached) when
     * available, otherwise the GUI's self-generated full config as a fallback. The
     * overlay path stays valid even when a Casanovo release adds new config options.
     *
     * <p>The file is saved <em>next to the output</em> — in {@code outputDir} (the run's
     * {@code --output_dir}, or the current directory when none is set) — with a
     * timestamped name, so the exact parameters are kept alongside the results. If that
     * location cannot be written, it falls back to a temporary file.</p>
     */
    private File writeEffectiveConfig(File outputDir) throws IOException {
        Optional<String> base = ConfigCache.cachedBase(settings);
        String yaml = base.isPresent() ? config.overlayOnto(base.get()) : config.toYaml();
        if (outputDir != null && outputDir.isDirectory()) {
            File dest = new File(outputDir, "casanovo-gui-config-" + runStamp() + ".yaml");
            try {
                CasanovoConfig.writeConfigTo(yaml, dest);
                console.appendLine("[config] Run config saved: " + dest.getAbsolutePath());
                return dest;
            } catch (IOException e) {
                console.appendLine("[config] Could not save config next to output ("
                        + e.getMessage() + "); using a temporary file instead.");
            }
        }
        return CasanovoConfig.writeTempConfig(yaml);
    }

    /**
     * The output directory the run will write to: the {@code --output_dir} value
     * (created if it does not yet exist), or the current working directory when no
     * output directory was specified.
     */
    private File resolveOutputDir(CasanovoCommand base) {
        List<String> args = base.getArguments();
        int idx = args.indexOf("--output_dir");
        if (idx >= 0 && idx + 1 < args.size()) {
            File d = new File(args.get(idx + 1).trim());
            if (!d.exists()) {
                d.mkdirs();
            }
            if (d.isDirectory()) {
                return d;
            }
        }
        return new File(System.getProperty("user.dir"));
    }

    /** Timestamp ({@code yyyyMMddHHmmss}) for naming saved run configs, mirroring Casanovo's output naming. */
    private static String runStamp() {
        return java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
    }

    /** Pre-generate the installed version's base config in the background so run-time prep is instant. */
    private void warmConfigCacheAsync() {
        Thread t = new Thread(() -> ConfigCache.warm(settings), "config-cache-warm");
        t.setDaemon(true);
        t.start();
    }
}
