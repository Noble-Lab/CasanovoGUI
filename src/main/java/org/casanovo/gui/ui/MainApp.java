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
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.casanovo.gui.core.CasanovoCommand;
import org.casanovo.gui.core.ConfigFile;
import org.casanovo.gui.core.CasanovoConfig;
import org.casanovo.gui.core.CasanovoInstaller;
import org.casanovo.gui.core.exec.ExecutionBackend;
import org.casanovo.gui.core.exec.JobHandle;
import org.casanovo.gui.core.exec.JobRequest;
import org.casanovo.gui.core.exec.LocalBackend;
import org.casanovo.gui.core.remote.RemoteBackend;
import org.casanovo.gui.core.remote.RemoteSettings;
import org.casanovo.gui.core.CasanovoWeights;
import org.casanovo.gui.core.ConfigCache;
import org.casanovo.gui.core.DeviceProbe;
import org.casanovo.gui.core.ExampleData;
import org.casanovo.gui.core.Os;
import org.casanovo.gui.core.RawFiles;
import org.casanovo.gui.core.RawFileParserLauncher;
import org.casanovo.gui.core.Settings;
import org.casanovo.gui.core.TimsTof;
import org.casanovo.gui.core.UpdateChecker;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    // timsTOF profile: for a .d run the config's `residues` + `max_peaks` are swapped to the timsTOF
    // values (from the installed config_timstof.yaml) so the generated config passes Casanovo's vocab
    // check and the Parameters dialog reflects them; the prior values are restored for non-.d runs.
    private boolean timstofProfileActive;
    private String savedResidues;
    private String savedMaxPeaks;
    /** Handle to the current run (local today; a remote SSH job later). Null until the first run. */
    private JobHandle currentJob;
    /** Persisted remote-execution config; selects the backend and drives the Remote settings dialog. */
    private final RemoteSettings remoteSettings = new RemoteSettings();

    private final TabPane tabs = new TabPane();
    private ConsoleOutput console;
    private SplitPane split;
    /** Run bar (split into the params row and command row) and console view; hidden on the Plot tab. */
    private Region paramsRow;
    private Region cmdRow;
    private Region consoleView;
    /** The console wrapper that draws the dynamic border; also referenced (as a {@link Region}) via {@link #consoleView}. */
    private ConsoleFrame consoleFrame;
    private final List<CommandPane> panes = new ArrayList<>();
    /** The View tab (peptide-to-protein mapping); auto-populated with the result mzTab after a successful run. */
    private ViewPane viewPane;
    /** The View tab header; kept as a field so tab-locking can treat it specially (its mapping Run/Stop live inside it). */
    private Tab viewTab;

    private final Label settingsLabel = new Label();
    /** The Casanovo version, in its own readout label so a long executable path can't truncate it away. */
    private final Label casanovoVersionLabel = new Label();
    /** Installed Casanovo version shown in the execution readout; resolved off-thread, null until known. */
    private String installedCasanovoVersion;
    /** Managed-install result computed by the latest update-check worker, never via FX-thread I/O. */
    private boolean managedInstallAvailable;
    private final TextField commandPreview = new TextField();
    private final Button paramsButton = new Button("Parameters");
    private final CheckBox useGuiParams = new CheckBox("Use GUI parameters");
    private final Button runButton = new Button("Run Casanovo");
    private final Button stopButton = new Button("Stop");
    private final Label statusLabel = new Label("Ready.");
    private final ProgressBar progressBar = new ProgressBar(0);
    /** Shown in the status bar after a successful run; opens the run's output folder. */
    private final Hyperlink openOutputLink = new Hyperlink("Open output folder");
    private final SpectrumTrace spectrum = new SpectrumTrace();
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
    private volatile Thread installerThread;
    /** Set while a Limelight upload runs: a busy state of its own, with no cancel. */
    private volatile boolean uploading = false; // limelight
    private volatile boolean checkpointErrorSeen = false;

    // The device check that precedes a run: it is asynchronous, so it counts as a busy state
    // of its own — otherwise a second Run click (or an install) lands in the gap between
    // onRun() and the job actually starting.
    private volatile boolean checkingDevice = false;
    private volatile boolean deviceCheckCancelled = false;
    /**
     * Set when a device block was answered with a reinstall. The offer is then withheld until a
     * check passes: where uv is too old for {@code --torch-backend} the installer falls back to
     * an index that tops out at CUDA 12.1, so for a GPU newer than that the rebuilt environment
     * resolves the same wheel and blocks again — an unbounded multi-gigabyte loop.
     */
    private boolean reinstalledForDeviceBlock;

    /** Where "Load Example MS/MS Data" put the example, reused across clicks in one session. */
    private Path exampleRunFolder;

    /** Whether the last run's "Open output folder" link was showing when a device check began. */
    private boolean outputLinkWasVisible;

    private volatile Thread deviceCheckThread;

    /** Immutable values resolved before a run, carried together through raw-file conversion. */
    private record PreparedRun(CasanovoCommand command, int predictBatchSize) {
    }

    // Raw-file (.raw -> .mzML) conversion, run before the Casanovo process itself starts.
    private volatile boolean convertingRaw = false;
    private volatile Process rawConvertProc;
    private volatile boolean rawConvertCancelled;
    private volatile Thread rawConvertThread;

    // Inputs + output dir captured at run start, resolved when the run finishes so the
    // produced mzTab can auto-fill the View tab.
    private List<File> pendingSpectra;
    private File pendingOutputDir;
    private long pendingRunStartMs;
    /** Drives open PDV windows (e.g. peptide-click -> select PSM) over their control port. */
    private final org.casanovo.gui.core.PdvController pdvController = new org.casanovo.gui.core.PdvController();
    private Stage stage;
    private LimelightController limelight; // limelight

    /** Shared inline validation feedback (danger border + focus + red status); also used by the View tab. */
    private final InlineValidation validation = new InlineValidation(statusLabel);

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        limelight = new LimelightController(stage, settings, () -> console, config, () -> busyReason() != null, b -> { uploading = b; setBusy(b); }); // limelight
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
        applyConfigVisibility(); // hide the Config-file row while GUI parameters are used (the default)

        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        for (CommandPane p : panes) {
            Tab tab = new Tab(p.getTitle(), p.getContent());
            tab.setTooltip(tabTip(tabTooltip(p.getTitle())));
            tabs.getTabs().add(tab);
        }
        viewPane = new ViewPane(primaryStage, settings, statusLabel, progressBar, s -> console.appendLine(s),
                pdvController, validation);
        viewPane.runningProperty().addListener((o, a, b) -> {
            updateChromeForTab();
            refreshPreview(); // one job per window: enable/disable the Casanovo Run button as a mapping starts/ends
            refreshTabLock(isJobRunning() || convertingRaw); // lock the command tabs while the mapping runs
            // The View tab streams its mapping to the shared console; animate the border while it runs.
            consoleFrame.setState(b ? ConsoleBorderEffect.State.RUNNING : ConsoleBorderEffect.State.IDLE);
        });
        viewTab = new Tab("View", viewPane);
        viewTab.setTooltip(tabTip("Map the de novo peptides in an mzTab back to proteins in a reference "
                + "FASTA, with coverage and per-protein views."));
        tabs.getTabs().add(viewTab);
        tabs.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            clearValidationError();
            refreshPreview();
            updateChromeForTab();
        });

        buildRunBar(); // populates paramsRow and cmdRow
        // The running-activity animation overlays everything above the command line — the tab
        // content (form) plus the Parameters row — while a run is in progress; hidden otherwise.
        // Gated by the View-menu toggle. The command row stays below, uncovered.
        spectrum.setVisible(false);
        VBox overlaidContent = new VBox(tabs, paramsRow);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        StackPane topStack = new StackPane(overlaidContent, spectrum);
        // Keep the overlay off the tab-header strip: inset it below the header so it
        // covers only the tab content + params row, matching the highlighted region.
        installSpectrumHeaderOffset();
        VBox topArea = new VBox(topStack, cmdRow);
        VBox.setVgrow(topStack, Priority.ALWAYS);

        consoleFrame = new ConsoleFrame(console.getView());
        consoleFrame.setMotionEnabled(settings.isShowRunningAnimation()); // respect the "Show running animation" toggle
        consoleView = consoleFrame;
        split = new SplitPane(topArea, consoleView);
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
        fetchCasanovoVersionAsync(); // off-thread; the readout gains the version shortly after the UI shows
        console.setLeftStatus(buildExecutionReadout());
        refreshPreview();
        updateChromeForTab();
        updateRunningState(false);

        // Preferred size, but never larger than the screen's usable area, so the window
        // fits on small or DPI-scaled displays. The realized window (with its title bar and
        // borders) is clamped precisely after show(), below.
        javafx.geometry.Rectangle2D screen = javafx.stage.Screen.getPrimary().getVisualBounds();
        Scene scene = new Scene(root,
                Math.min(940, screen.getWidth()),
                Math.min(820, screen.getHeight()));
        // App-wide chrome polish (action bar, command chip, footer). Layered over the
        // AtlantaFX user-agent stylesheet, using theme tokens so it follows light/dark.
        java.net.URL appCss = getClass().getResource("/org/casanovo/gui/app.css");
        if (appCss != null) {
            scene.getStylesheets().add(appCss.toExternalForm());
        }
        // Scene-level accelerators so Run/Stop work from anywhere in the window. On the View tab the
        // main run/stop buttons are disabled, so route the shortcut to the mapping's own Run/Stop.
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.R, KeyCombination.SHORTCUT_DOWN),
                () -> {
                    if (isViewTab()) {
                        viewPane.fireRun();
                    } else if (!runButton.isDisabled()) {
                        runButton.fire();
                    }
                });
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.ESCAPE),
                () -> {
                    if (isViewTab()) {
                        viewPane.fireStop();
                    } else if (!stopButton.isDisabled()) {
                        stopButton.fire();
                    }
                });
        // Parameters editor shortcut (the File-menu item was removed; the run-bar button remains).
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.P, KeyCombination.SHORTCUT_DOWN),
                this::openParameters);
        primaryStage.setTitle("Casanovo GUI");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(Math.min(780, screen.getWidth()));
        primaryStage.setMinHeight(Math.min(640, screen.getHeight()));
        primaryStage.show();
        // The realized window includes the title bar/borders; shrink and nudge it if the
        // whole window still overflows the usable screen area.
        clampToScreen(primaryStage);

        maybeAutoCheckForUpdates();
        maybeCheckPyArrow();
        warmConfigCacheAsync();
    }

    @Override
    public void stop() {
        cancelJob();
    }

    /** Ensure the realized window fits within the primary screen's usable (taskbar-excluded) area. */
    private static void clampToScreen(Stage stage) {
        javafx.geometry.Rectangle2D vb = javafx.stage.Screen.getPrimary().getVisualBounds();
        if (stage.getHeight() > vb.getHeight()) {
            stage.setHeight(vb.getHeight());
        }
        if (stage.getWidth() > vb.getWidth()) {
            stage.setWidth(vb.getWidth());
        }
        // Pin the top/left first — a window centered while taller than the screen ends up with
        // its title bar above the screen top — then pull up/left if it still spills off the
        // bottom/right edge.
        if (stage.getY() < vb.getMinY()) {
            stage.setY(vb.getMinY());
        }
        if (stage.getY() + stage.getHeight() > vb.getMaxY()) {
            stage.setY(Math.max(vb.getMinY(), vb.getMaxY() - stage.getHeight()));
        }
        if (stage.getX() < vb.getMinX()) {
            stage.setX(vb.getMinX());
        }
        if (stage.getX() + stage.getWidth() > vb.getMaxX()) {
            stage.setX(Math.max(vb.getMinX(), vb.getMaxX() - stage.getWidth()));
        }
    }

    private MenuBar buildMenuBar() {
        Menu fileMenu = new Menu("File");
        MenuItem settingsItem = new MenuItem("Settings");
        settingsItem.setAccelerator(new KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN));
        settingsItem.setOnAction(e -> openSettings());
        MenuItem remoteItem = new MenuItem("Remote execution…");
        remoteItem.setOnAction(e -> openRemoteSettings());
        MenuItem exampleItem = new MenuItem("Load Example MS/MS Data\u2026");
        exampleItem.setOnAction(e -> loadExampleData());
        MenuItem exitItem = new MenuItem("Exit");
        exitItem.setAccelerator(new KeyCodeCombination(KeyCode.Q, KeyCombination.SHORTCUT_DOWN));
        exitItem.setOnAction(e -> stage.close());
        fileMenu.getItems().addAll(settingsItem, remoteItem, limelight.menuItem(), // limelight
                new javafx.scene.control.SeparatorMenuItem(), exampleItem,
                new javafx.scene.control.SeparatorMenuItem(), exitItem);

        Menu helpMenu = new Menu("Help");
        MenuItem checkUpdatesItem = new MenuItem("Check for Updates");
        checkUpdatesItem.setOnAction(e -> runUpdateCheck(true));
        CheckMenuItem autoCheckItem = new CheckMenuItem("Automatically check on startup");
        autoCheckItem.setSelected(UpdateChecker.isAutoCheckEnabled());
        autoCheckItem.setOnAction(e -> UpdateChecker.setAutoCheckEnabled(autoCheckItem.isSelected()));
        MenuItem envItem = new MenuItem("Environment Report");
        envItem.setOnAction(e -> showEnvironmentReport());
        MenuItem aboutItem = new MenuItem("About");
        aboutItem.setOnAction(e -> showAbout());
        helpMenu.getItems().addAll(checkUpdatesItem, autoCheckItem,
                new javafx.scene.control.SeparatorMenuItem(), envItem, aboutItem);

        return new MenuBar(fileMenu, buildViewMenu(), helpMenu);
    }

    /** Dialog to export the whole CasanovoGUI window as a high-resolution framed PNG at a chosen DPI. The
        scene's vector content re-renders at scale×, so it's crisp regardless of display DPI. */
    private void showExportDialog() {
        ImageExport.promptExportOptions(stage, java.util.List.of()).ifPresent(opts -> {
            javafx.scene.Node root = stage.getScene().getRoot();
            ImageExport.exportFramed(stage, java.util.List.of(root), "casanovo-gui.png", opts,
                    () -> statusLabel.setVisible(false), // keep the status-bar text out of the image
                    () -> statusLabel.setVisible(true),
                    statusLabel::setText);
        });
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
                    spectrum.applyTheme(); // keep the running animation's colours in sync
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

        CheckMenuItem animationItem = new CheckMenuItem("Show running animation");
        animationItem.setSelected(settings.isShowRunningAnimation());
        animationItem.setOnAction(e -> {
            settings.setShowRunningAnimation(animationItem.isSelected());
            settings.save();
            updateAnimation();
            consoleFrame.setMotionEnabled(animationItem.isSelected()); // gate the border motion too
        });
        viewMenu.getItems().add(animationItem);

        CheckMenuItem uniProtItem = new CheckMenuItem("Look up UniProt info on hover (Proteins table)");
        uniProtItem.setSelected(settings.isUniProtLookup());
        uniProtItem.setOnAction(e -> {
            settings.setUniProtLookup(uniProtItem.isSelected());
            settings.save();
        });
        viewMenu.getItems().add(uniProtItem);

        MenuItem exportItem = new MenuItem("Export window image");
        exportItem.setOnAction(e -> showExportDialog());
        viewMenu.getItems().addAll(new javafx.scene.control.SeparatorMenuItem(), exportItem);
        return viewMenu;
    }

    /**
     * Show and animate the spectrum band only while a Casanovo process is running
     * <em>and</em> the View-menu toggle is on; otherwise stop and collapse it.
     * Safe to call from the JavaFX thread at any time.
     */
    /**
     * Offset the spectrum overlay below the tab-header strip so it covers only the
     * tab <em>content</em> (the form area), leaving the De&nbsp;novo/DB&nbsp;Search/…
     * tabs visible. The {@code .tab-header-area} node exists only once the TabPane
     * skin is built, so resolve it on a later pulse and track its height.
     */
    private void installSpectrumHeaderOffset() {
        Runnable apply = () -> {
            javafx.scene.Node header = tabs.lookup(".tab-header-area");
            if (header instanceof Region hr) {
                StackPane.setMargin(spectrum, new Insets(hr.getHeight(), 0, 0, 0));
            }
        };
        Platform.runLater(() -> {
            javafx.scene.Node header = tabs.lookup(".tab-header-area");
            if (header instanceof Region hr) {
                apply.run();
                hr.heightProperty().addListener((o, a, b) -> apply.run());
            } else {
                Platform.runLater(apply); // skin not ready yet; try again next pulse
            }
        });
    }

    private void updateAnimation() {
        // The raw→mzML conversion runs before the Casanovo process, so isJobRunning() is still
        // false then; treat it as a running state too, so a raw-file run shows the overlay (and covers
        // the form) from the first click rather than only once Casanovo starts.
        boolean show = (isJobRunning() || convertingRaw) && settings.isShowRunningAnimation();
        spectrum.setVisible(show);
        if (show) {
            spectrum.start();
        } else {
            spectrum.stop();
        }
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
        console = makeConsole(colored);
        console.setLeftStatus(buildExecutionReadout());
        // The wrapper (consoleFrame == consoleView) stays put in the layout; only its inner
        // console view is replaced, so the split divider and the border overlay both persist.
        consoleFrame.setContent(console.getView());
    }

    private void buildRunBar() {
        HBox params = new HBox(8, paramsButton, useGuiParams);
        params.setAlignment(Pos.CENTER_LEFT);
        params.setPadding(new Insets(6, 10, 0, 10));
        // Hairline above the Parameters row sets the run controls apart from the tab form.
        params.getStyleClass().add("run-bar-top");

        // Keep the label plain ("Use GUI parameters"); the --config mechanism lives on hover.
        // Use the shared help-tooltip helper (wraps, 30s show duration) so this matches the form-field
        // tooltips in the panel rather than vanishing after JavaFX's default 5s.
        useGuiParams.setTooltip(FxUtils.helpTooltip(
                "Check to set Casanovo's parameters in the Parameters dialog; the GUI generates the "
                + "config and passes it as --config.\n"
                + "Uncheck to supply your own YAML config file instead."));
        paramsButton.setTooltip(FxUtils.helpTooltip(
                "Edit Casanovo's run parameters; the GUI writes them to the generated --config (Ctrl+P)."));

        runButton.getStyleClass().add("accent");
        runButton.setTooltip(new javafx.scene.control.Tooltip("Run the current Casanovo command (Ctrl+R)"));
        stopButton.getStyleClass().add("danger");
        stopButton.setTooltip(new javafx.scene.control.Tooltip("Stop the running Casanovo process (Esc)"));
        commandPreview.setEditable(false);
        // The command preview is read-only; skip it in tab order.
        commandPreview.setFocusTraversable(false);
        // Read as a generated-command chip (inset background via app.css), not an editable field.
        commandPreview.getStyleClass().add("command-preview");
        // Match the console output: the app's sans-serif base font (not monospace).
        commandPreview.setStyle("-fx-font-family: 'Segoe UI', 'Inter', 'SF Pro Text', 'Helvetica Neue', sans-serif; -fx-font-size: 13px;");
        HBox.setHgrow(commandPreview, Priority.ALWAYS);
        HBox command = new HBox(8, new Label("Command:"), commandPreview, stopButton, runButton);
        command.setAlignment(Pos.CENTER_LEFT);
        command.setPadding(new Insets(8, 10, 8, 10));

        paramsRow = params;
        cmdRow = command;
    }

    private Region buildStatusBar() {
        progressBar.setPrefWidth(220);
        progressBar.setVisible(false);
        statusLabel.getStyleClass().add("text-muted");
        openOutputLink.setVisible(false);
        openOutputLink.setManaged(false);
        openOutputLink.setOnAction(e -> openFolder(pendingOutputDir));
        HBox bar = new HBox(8, statusLabel, progressBar, openOutputLink);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 10, 4, 10));
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    /** Reveal or hide the status-bar "Open output folder" link. */
    private void showOpenOutputLink(boolean show) {
        openOutputLink.setVisible(show);
        openOutputLink.setManaged(show);
    }

    /** Open {@code dir} in the OS file manager (off the FX thread; Desktop.open can block). */
    private void openFolder(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().open(dir);
                    return;
                }
            } catch (Exception ignored) {
                // fall through to the HostServices fallback
            }
            Platform.runLater(() -> getHostServices().showDocument(dir.toURI().toString()));
        }, "open-folder");
        t.setDaemon(true);
        t.start();
    }

    /** The "Execution: <casanovo>" readout shown at the left of the console's bottom bar. */
    private Region buildExecutionReadout() {
        settingsLabel.getStyleClass().add("text-muted");
        settingsLabel.setMaxWidth(460);
        // The version sits in its own label with content-width priority, so a long executable path
        // (which truncates settingsLabel at 460px) can never clip the version off the end.
        casanovoVersionLabel.getStyleClass().add("text-muted");
        casanovoVersionLabel.setMinWidth(Region.USE_PREF_SIZE);
        HBox box = new HBox(6, new Label("Execution:"), settingsLabel, casanovoVersionLabel);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private void wireActions() {
        runButton.setOnAction(e -> onRun());
        stopButton.setOnAction(e -> onStop());
        paramsButton.setOnAction(e -> openParameters());
        useGuiParams.setOnAction(e -> {
            applyConfigVisibility();
            refreshPreview();
        });
    }

    /** Reveal the external Config-file row only when the user opts out of GUI-generated parameters. */
    private void applyConfigVisibility() {
        // Switching modes drops any pending inline error (e.g. a "config required" error on a field
        // that's about to be hidden), so it can't linger as a stuck red status + dangling border.
        clearValidationError();
        boolean showConfig = !useGuiParams.isSelected();
        for (CommandPane p : panes) {
            p.setConfigFileVisible(showConfig);
        }
    }

    private void openParameters() {
        // The scene accelerator calls this method directly, bypassing Button.fire(). Honour
        // the button's disabled state so Ctrl+P cannot mutate parameters while a device check
        // (or any other run/install busy state) is validating the current values.
        if (paramsButton.isDisabled()) {
            return;
        }
        // Same criterion as the run (the resolved --model), so the dialog shows exactly the residues the
        // run will use — even when the user typed a model that overrides the .d auto-selection. currentPane()
        // is null on a non-command tab (the Parameters shortcut is scene-wide) — then it's simply not a
        // timsTOF run.
        CommandPane pane = currentPane();
        applyTimstofProfile(pane != null && isTimstofCommand(pane.buildCommand()));
        boolean saved = new ConfigDialog(stage, config).showAndApply();
        if (saved) {
            useGuiParams.setSelected(true);
            applyConfigVisibility();
            refreshPreview();
        }
    }

    private void openSettings() {
        // The run in flight was validated against the current settings; changing them underneath
        // it would leave the verdict describing an environment that no longer exists.
        if (refuseWhileBusy("Settings")) {
            return;
        }
        boolean saved = new SettingsDialog(stage, settings, this::onInstall).showAndApply();
        if (saved) {
            // A different executable or Conda environment is a different PyTorch: the cached
            // device report describes the one that was just replaced.
            DeviceProbe.invalidate();
            refreshManagedInstallAsync(); // a different executable may or may not still be ours
            refreshSettingsLabel();
            fetchCasanovoVersionAsync(); // the executable/env may have changed — re-resolve the version
            refreshPreview();
        }
    }

    /**
     * Set up a ready-to-run analysis of the bundled 50-spectrum example.
     *
     * <p>Creates a fresh timestamped run folder in the user's home directory, unpacks the
     * example dataset into it, and points the <i>De novo</i> tab at both. Everything for the
     * run then lives in one self-contained folder, and the user only has to press
     * <i>Run Casanovo</i> &mdash; which is the point: it is the shortest path from a fresh
     * install to a completed analysis, with no file to find and no network needed.</p>
     */
    private void loadExampleData() {
        // Don't move the inputs out from under a run or a mapping in progress.
        if (refuseWhileBusy("Loading the example data")) {
            return;
        }
        // The example replaces whatever the fields held, so an inline error about them (a missing
        // spectrum file from an earlier Run) is about inputs that no longer exist.
        clearValidationError();
        // The example is a spectrum file, so it belongs to the De novo tab (pane 0).
        CommandPane pane = panes.get(0);
        if (!(pane instanceof SequencePane sequence)) {
            alert(Alert.AlertType.ERROR, "Could not load the example data",
                    "The De novo tab is not available in this build, so the example cannot be "
                            + "loaded into it.");
            return;
        }
        // One folder per session, not one per click: the menu item is something users press
        // while exploring, and a fresh ~/Casanovo_<timestamp> each time would litter the home
        // directory — while two clicks inside the same second would resolve to one folder and
        // overwrite the input of a run already started from the first.
        if (exampleRunFolder == null || !Files.isDirectory(exampleRunFolder)) {
            exampleRunFolder = ExampleData.newRunFolder();
        }
        Path runFolder = exampleRunFolder;
        File spectra;
        try {
            spectra = ExampleData.extractTo(runFolder);
        } catch (IOException ex) {
            alert(Alert.AlertType.ERROR, "Could not load the example data", ex.getMessage());
            return;
        }

        tabs.getSelectionModel().select(0);
        sequence.setSpectra(List.of(spectra));
        pane.setOutputDir(runFolder.toFile());

        console.appendLine("[example] Spectra:   " + spectra.getAbsolutePath());
        console.appendLine("[example] Output to: " + runFolder.toAbsolutePath());
        console.appendLine("[example] 50 HeLa MS/MS spectra \u2014 press Run Casanovo to sequence them.");
        statusLabel.setText("Example data loaded \u2014 press Run Casanovo.");
        refreshPreview();
    }

    /**
     * Whether {@code action} must be refused because the window is busy &mdash; and if so, say
     * which state refused it. The menu bar stays enabled while a run, install or check is in
     * flight, so a guard that simply returned would look like a dead menu item.
     */
    private String busyReason() {
        return isJobRunning() ? "a run is in progress"
                : installing ? "an install is in progress"
                : uploading ? "an upload to Limelight is in progress" // limelight
                : convertingRaw ? "a file conversion is in progress"
                : checkingDevice ? "the compute device is being checked"
                : viewPane.runningProperty().get() ? "a mapping is in progress"
                : null;
    }

    private boolean refuseWhileBusy(String action) {
        String why = busyReason();
        if (why == null) {
            return false;
        }
        noteBusy(action + " is unavailable while " + why + ".");
        return true;
    }

    /** Say why an action was refused, in both places the user is looking. */
    private void noteBusy(String message) {
        statusLabel.setText(message);
        console.appendLine("[busy] " + message);
    }

    private CommandPane currentPane() {
        int idx = tabs.getSelectionModel().getSelectedIndex();
        if (idx < 0 || idx >= panes.size()) {
            return null; // a non-command tab (e.g. the Plot tab) is selected
        }
        return panes.get(idx);
    }

    /** A wrapped, slightly-delayed tooltip describing what a top-level tab does. */
    private static Tooltip tabTip(String text) {
        Tooltip t = new Tooltip(text);
        t.setShowDelay(javafx.util.Duration.millis(300));
        t.setWrapText(true);
        t.setMaxWidth(320);
        return t;
    }

    /** Tooltip text for a command tab, keyed by its title. */
    private static String tabTooltip(String title) {
        return switch (title) {
            case "De novo" -> "De novo peptide sequencing of MS/MS spectrum file(s) (mzML/mzXML/MGF/raw) "
                    + "with a trained model; produces an mzTab of predicted peptides.";
            case "DB Search" -> "Score spectra against a protein database in FASTA format (casanovo db-search) "
                    + "instead of pure de novo sequencing.";
            case "Evaluate" -> "Sequence a spectrum file with known/annotated peptides and report "
                    + "accuracy metrics for the predictions.";
            case "Train" -> "Train or fine-tune a Casanovo model from annotated spectra.";
            default -> title;
        };
    }

    private void refreshPreview() {
        CommandPane pane = currentPane();
        if (pane == null) {
            // Non-command tab (Plot): nothing to preview or run.
            commandPreview.setText("");
            runButton.setDisable(true);
            return;
        }
        runButton.setDisable(busyReason() != null);
        try {
            CasanovoCommand cmd = effectiveCommand(pane, false);
            commandPreview.setText(cmd.toDisplayString(settings));
        } catch (RuntimeException ex) {
            commandPreview.setText("");
        }
    }

    /**
     * Declutter the Plot (non-command) tab: hide the run bar (command preview, Run/Stop,
     * Open in PDV, Parameters, Use GUI parameters) and the console so only the plot and its
     * settings show. Restore both on the command tabs.
     */
    private void updateChromeForTab() {
        boolean commandTab = currentPane() != null;
        // The View tab streams pepmap output to the shared console; show it only while a mapping
        // runs, and size it to sit just below the Run button so the settings panel stays unscrolled.
        boolean mapping = isViewTab() && viewPane.runningProperty().get();
        paramsRow.setVisible(commandTab);
        paramsRow.setManaged(commandTab);
        cmdRow.setVisible(commandTab);
        cmdRow.setManaged(commandTab);
        if (commandTab || mapping) {
            boolean added = false;
            if (!split.getItems().contains(consoleView)) {
                split.getItems().add(consoleView);
                added = true;
            }
            if (mapping) {
                // Size the console below the Run button synchronously (the metrics are pref-based and
                // stable), so the settings panel never momentarily crams and flashes a scroll bar.
                sizeViewConsole();
                Platform.runLater(this::sizeViewConsole); // refine once more after layout settles
            } else if (added) {
                split.setDividerPositions(0.62);
            }
        } else {
            split.getItems().remove(consoleView);
        }
    }

    /** Set the split divider so the console begins just below the View tab's Run button. */
    private void sizeViewConsole() {
        if (!split.getItems().contains(consoleView)) {
            return;
        }
        javafx.geometry.Bounds mp = viewPane.localToScene(viewPane.getBoundsInLocal());
        javafx.geometry.Bounds sb = split.localToScene(split.getBoundsInLocal());
        if (sb.getHeight() <= 0 || mp.getHeight() <= 0) {
            split.setDividerPositions(0.85); // safe: leaves the settings plenty of room until the next pulse
            return;
        }
        double y = (mp.getMinY() - sb.getMinY()) + viewPane.settingsExtent() + 10;
        split.setDividerPositions(Math.max(0.35, Math.min(0.9, y / sb.getHeight())));
    }

    private boolean isViewTab() {
        Tab t = tabs.getSelectionModel().getSelectedItem();
        return t != null && t.getContent() == viewPane;
    }

    /**
     * Confirm the selected accelerator is actually usable before launching Casanovo.
     *
     * <p>Casanovo's device selection happens inside PyTorch Lightning, so a mismatch between
     * the chosen device and the installed PyTorch otherwise surfaces only as a raw traceback
     * partway through a run &mdash; a CPU-only wheel with a GPU selected, or a CUDA wheel
     * carrying no kernels for the installed card. The probe runs off the FX thread and is
     * cached per install, so it costs about a second once and nothing thereafter. Skipped for
     * remote runs, where the environment that matters is the server's, not this machine's.</p>
     */
    private void checkDeviceThenRun(CommandPane pane, CasanovoCommand base, boolean guiOwnsConfig,
                                    String guiAccelerator, String guiPredictBatchSize) {
        if (remoteSettings.isEnabled() && remoteSettings.isConfigured()) {
            if (guiOwnsConfig) {
                buildThenRun(pane, ConfigFile.fallback(
                        true, guiAccelerator, guiPredictBatchSize));
            } else {
                resolveAcceleratorThenRun(pane, base, false, null, null);
            }
            return;
        }
        statusLabel.setText("Checking the compute device…");
        Thread probe = new Thread(() -> {
            DeviceProbe.Report report;
            DeviceProbe.Verdict verdict;
            ConfigFile.RunValues runValues = null;
            boolean gpuVisible;
            try {
                // Reading an external config is file I/O, so it happens here rather than on the
                // FX thread, where a config on a stalled network share would freeze the window.
                // The same value is carried to the run, so what was checked is what is tagged.
                runValues = resolveRunValues(
                        base, guiOwnsConfig, guiAccelerator, guiPredictBatchSize);
                report = DeviceProbe.probe(settings);
                verdict = DeviceProbe.validate(runValues.accelerator(), report);
                // nvidia-smi is a subprocess too. Keep it inside the safety net so a launch or
                // timeout failure still publishes a verdict and releases the busy state.
                gpuVisible = gpuVisibleForReinstall(report, verdict);
            } catch (Throwable t) {
                // Neither call is written to throw, but probe() still touches the filesystem, and
                // onDeviceVerdict is the only thing that clears checkingDevice — an exception
                // escaping here would leave Run, Install and Update disabled for the session.
                // Both stand-ins are built inline so nothing on this path can throw again.
                //
                // The tag matters as much as the flag: leaving it null would launch a run the
                // user set to 'cpu' without hiding the GPUs. What the GUI itself selected is
                // still known; only an unread config file is genuinely unknown.
                if (runValues == null) {
                    runValues = ConfigFile.fallback(
                            guiOwnsConfig, guiAccelerator, guiPredictBatchSize);
                }
                report = new DeviceProbe.Report(null, null, false, null, null, List.of(),
                        false, false, "the device check failed: " + t);
                verdict = new DeviceProbe.Verdict(DeviceProbe.Status.WARN,
                        "Could not check the compute device.",
                        "The check itself failed (" + t + "). The run will proceed; if it fails "
                                + "with a device error, select 'cpu' in Parameters → Accelerator.",
                        false, false);
                gpuVisible = false;
            }
            // Identity checks may touch a slow or disconnected filesystem, so resolve this on
            // the probe worker rather than from onDeviceVerdict on the FX application thread.
            boolean managedInstall = verdict.offerReinstall()
                    && CasanovoInstaller.managedVenvRoot(
                    settings.getCasanovoExecutable(), settings.isUseConda()).isPresent();
            DeviceProbe.Report finalReport = report;
            DeviceProbe.Verdict finalVerdict = verdict;
            ConfigFile.RunValues finalRunValues = runValues;
            boolean finalGpuVisible = gpuVisible;
            boolean finalManagedInstall = managedInstall;
            Platform.runLater(() -> onDeviceVerdict(pane, guiOwnsConfig, finalRunValues,
                    finalReport, finalVerdict, finalGpuVisible, finalManagedInstall));
        }, "device-probe");
        beginDeviceCheck(probe);
        try {
            probe.start();
        } catch (Throwable t) {
            // Nothing will post a verdict now, and onDeviceVerdict is what clears the flag. The
            // check is advisory — every other way it can fail warns and proceeds — so do that
            // here too rather than dropping the run the user asked for.
            endDeviceCheck();
            console.appendLine("[warn] Could not start the device check: " + t);
            buildThenRun(pane, ConfigFile.fallback(
                    guiOwnsConfig, guiAccelerator, guiPredictBatchSize));
        }
    }

    /**
     * Resolve the accelerator on a background thread &mdash; it reads the external config file
     * &mdash; and then start the run. Used where the device check is skipped (a remote run) but
     * the tag is still needed: the file may live on a share that takes a mount timeout to answer,
     * which must not be waited for on the FX thread. Holds the same busy state as a device check
     * so the window cannot start a second run inside the gap.
     */
    private void resolveAcceleratorThenRun(CommandPane pane, CasanovoCommand base,
                                           boolean guiOwnsConfig, String guiAccelerator,
                                           String guiPredictBatchSize) {
        Thread worker = new Thread(() -> {
            ConfigFile.RunValues resolved;
            Throwable failure = null;
            try {
                resolved = resolveRunValues(
                        base, guiOwnsConfig, guiAccelerator, guiPredictBatchSize);
            } catch (Throwable t) {
                // endDeviceCheck runs only from the posting below, so an escape here would leave
                // the window busy for the session. Keep the tag the GUI knows; an unread config
                // is the one case where the accelerator is genuinely unknown.
                failure = t;
                resolved = ConfigFile.fallback(
                        guiOwnsConfig, guiAccelerator, guiPredictBatchSize);
            }
            ConfigFile.RunValues runValues = resolved;
            Throwable why = failure;
            Platform.runLater(() -> {
                endDeviceCheck();
                if (why != null) {
                    console.appendLine("[warn] Could not read the run's accelerator: " + why);
                }
                if (checkAbandoned()) {
                    return;
                }
                buildThenRun(pane, runValues);
            });
        }, "accelerator-resolve");
        beginDeviceCheck(worker);
        try {
            worker.start();
        } catch (Throwable ex) {
            endDeviceCheck();
            console.appendLine("[warn] Could not read the run's accelerator off the UI thread: " + ex);
            buildThenRun(pane, ConfigFile.fallback(
                    guiOwnsConfig, guiAccelerator, guiPredictBatchSize));
        }
    }

    /**
     * Take the busy state a pre-run step holds, and publish {@code worker} so Stop can interrupt
     * it. Every asynchronous step before a run goes through here, so none of them can forget the
     * part that makes Stop work.
     */
    private void beginDeviceCheck(Thread worker) {
        checkingDevice = true;
        deviceCheckCancelled = false;
        // The step owns the window the way a run does: Stop is live, and Parameters, the tab
        // strip and Settings cannot change what the verdict is about while it is in flight.
        setBusy(true);
        // updateRunningState(true) clears the last run's output link on the premise that a new
        // run is starting; a step that ends in a block or a cancel starts nothing, so remember
        // whether to put it back.
        outputLinkWasVisible = openOutputLink.isVisible();
        updateRunningState(true);
        refreshPreview();
        worker.setDaemon(true);
        deviceCheckThread = worker;
    }

    /**
     * Whether the run this step was preparing must not start after all &mdash; the user pressed
     * Stop, or something else took the window while the step ran (this window allows one job).
     * Says which, since the click that started it produced no other visible result.
     */
    private boolean checkAbandoned() {
        if (deviceCheckCancelled) {
            statusLabel.setText("Stopped.");
            console.appendLine("[stopped] The device check was cancelled; nothing was run.");
            return true;
        }
        // checkingDevice is already cleared by endDeviceCheck when this runs, so the shared
        // predicate answers the right question: has something else taken the window?
        String why = busyReason();
        if (why != null) {
            statusLabel.setText("Another job started during the device check.");
            console.appendLine("[device] The run was not started: " + why + ".");
            return true;
        }
        return false;
    }


    /**
     * Clear the check's busy state. Reached from the verdict (including the cancelled one, since
     * Stop only interrupts the probe and lets the verdict land), and from a check that could not
     * be started. The running state is re-derived rather than simply cleared: something else —
     * a raw conversion, a job started from elsewhere — may hold the window now, and switching
     * Stop off under it would leave that one uncancellable.
     */
    private void endDeviceCheck() {
        checkingDevice = false;
        deviceCheckThread = null;
        setBusy(false);
        // busyReason() is the shared predicate, and checkingDevice is already cleared above, so
        // it answers exactly the question this asks — including the states a hand-listed subset
        // (an install, a Limelight upload, a mapping) would silently re-enable the window under.
        boolean somethingElseRunning = busyReason() != null;
        updateRunningState(somethingElseRunning);
        if (outputLinkWasVisible && !somethingElseRunning) {
            showOpenOutputLink(true); // nothing replaced the last run's results
        }
        refreshPreview();
    }

    /**
     * Build the command the run needs &mdash; which writes the generated config to the output
     * folder &mdash; and start it. Deliberately after the device check: a check that ends in a
     * block or a cancel would otherwise leave a config file, and a console line announcing it,
     * for a run that never happened.
     */
    private void buildThenRun(CommandPane pane, ConfigFile.RunValues runValues) {
        CasanovoCommand command;
        try {
            command = effectiveCommand(pane, true, runValues.accelerator())
                    .withAccelerator(runValues.accelerator());
        } catch (RuntimeException ex) {
            alert(Alert.AlertType.ERROR, "Cannot run", ex.getMessage());
            return;
        }
        refreshPreview();
        convertRawThenRun(pane, new PreparedRun(command, runValues.predictBatchSize()));
    }

    /** Act on the device check: start the run, or stop with an explanation and a way out. */
    /**
     * Whether a reinstall could plausibly produce the GPU build the verdict wants. A CPU-only
     * wheel is worth several gigabytes only when there is a device to match: without this, a
     * machine with no NVIDIA GPU is offered a reinstall that resolves the same CPU wheel and
     * blocks again, indefinitely. Runs {@code nvidia-smi}, so it stays on the worker &mdash; and
     * only for the case that needs it. On a Mac the question is the Metal backend, which
     * nvidia-smi says nothing about, so the offer stands.
     */
    private static boolean gpuVisibleForReinstall(DeviceProbe.Report report,
                                                  DeviceProbe.Verdict verdict) {
        if (!verdict.offerReinstall() || !report.isCpuOnlyBuild() || Os.isMac()) {
            return true;
        }
        return CasanovoInstaller.nvidiaDriverVersion().isPresent();
    }

    private void onDeviceVerdict(CommandPane pane, boolean guiOwnsConfig,
                                 ConfigFile.RunValues runValues,
                                 DeviceProbe.Report report, DeviceProbe.Verdict verdict,
                                 boolean gpuVisible, boolean managedInstall) {
        endDeviceCheck();
        if (checkAbandoned()) {
            return;
        }
        console.appendLine("[device] " + report.summary());
        if (verdict.status() != DeviceProbe.Status.BLOCK) {
            console.appendLine("[device] " + verdict.summary());
            if (verdict.status() == DeviceProbe.Status.WARN && !verdict.detail().isEmpty()) {
                console.appendLine("[warn] " + verdict.detail());
            }
            reinstalledForDeviceBlock = false; // this environment works; a future block is new
            buildThenRun(pane, runValues);
            return;
        }

        statusLabel.setText(verdict.summary());
        console.appendLine("[error] " + verdict.summary());
        console.appendLine("[error] " + verdict.detail());

        // Two ways out, each offered only when it would actually work: switching to the CPU
        // needs the GUI to own the configuration (an external config file is the user's to
        // edit), and reinstalling only makes sense for the environment we manage.
        // "Use GUI parameters" alone is not the condition: effectiveCommand also treats a pane
        // that supplies its own --config as externally configured, and switching the GUI's
        // accelerator would then change nothing at all.
        boolean canUseCpu = verdict.offerCpu() && guiOwnsConfig;
        boolean reinstallOffered = verdict.offerReinstall() && managedInstall && gpuVisible;
        boolean canReinstall = reinstallOffered && !reinstalledForDeviceBlock;
        if (!canUseCpu && !canReinstall) {
            alert(Alert.AlertType.ERROR, "Cannot use the selected device",
                    verdict.summary() + "\n\n" + verdict.detail());
            return;
        }

        StringBuilder message = new StringBuilder(verdict.summary())
                .append("\n\n").append(verdict.detail());
        if (!gpuVisible) {
            // Say why the way out the verdict suggested is not on offer, rather than leaving the
            // user to click a reinstall that resolves the same wheel and blocks again.
            message.append("\n\nNo NVIDIA driver is visible here (nvidia-smi reports none), so a "
                    + "reinstall would resolve the same CPU build. Select 'cpu' or 'auto' in "
                    + "Parameters → Accelerator, or check the driver first.");
            console.appendLine("[device] No NVIDIA driver detected; a reinstall would not help.");
        }
        if (reinstallOffered && reinstalledForDeviceBlock) {
            message.append("\n\nCasanovo was already reinstalled once for this block and the "
                    + "PyTorch it installed still cannot be used here, so reinstalling again "
                    + "would resolve the same wheel. Select 'cpu' in Parameters → Accelerator; if "
                    + "this GPU needs a newer CUDA build, update uv — the installer falls back to "
                    + "a CUDA 12.1 index when uv is too old to pick a backend itself — and "
                    + "reinstall from Settings.");
            console.appendLine("[device] A reinstall was already tried for this block; "
                    + "not offering it again.");
        }
        ButtonType reinstall = new ButtonType("Reinstall Casanovo");
        ButtonType useCpu = new ButtonType("Run on the CPU instead");
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        List<ButtonType> buttons = new ArrayList<>();
        if (canReinstall) {
            message.append("\n\nReinstalling rebuilds the managed Python environment from "
                    + "scratch and installs a PyTorch matched to this machine's driver. It downloads "
                    + "several gigabytes and takes a few minutes.");
            buttons.add(reinstall);
        }
        if (canUseCpu) {
            buttons.add(useCpu);
        }
        buttons.add(cancel);

        Alert ask = new Alert(Alert.AlertType.CONFIRMATION, message.toString(),
                buttons.toArray(new ButtonType[0]));
        ask.setTitle("Cannot use the selected device");
        ask.setHeaderText(null);
        ask.getDialogPane().setMinWidth(520);
        if (stage != null) {
            ask.initOwner(stage);
        }
        ButtonType choice = ask.showAndWait().orElse(cancel);
        if (choice == reinstall) {
            console.appendLine("[device] Reinstalling Casanovo with a driver-matched PyTorch\u2026");
            reinstalledForDeviceBlock = true; // one attempt per block, so a bad match cannot loop
            // installAll() clears the device-probe cache, so re-entering onRun re-probes the
            // rebuilt environment rather than trusting the verdict that sent us here.
            runInstall(this::onRun);
        } else if (choice == useCpu) {
            console.appendLine("[device] Using 'cpu' for this run only.");
            buildThenRun(pane, new ConfigFile.RunValues(
                    "cpu", runValues.predictBatchSize()));
        }
    }

    /**
     * Whether the GUI, rather than a file the user supplied, decides this run's parameters. A
     * pane that passes its own {@code --config} is externally configured even with "Use GUI
     * parameters" ticked, which is why this is not simply the checkbox.
     */
    private boolean guiOwnsConfig(CasanovoCommand base) {
        return useGuiParams.isSelected() && !base.getArguments().contains("--config");
    }

    /**
     * The accelerator the run will actually use: the GUI's own value when the GUI owns the
     * configuration, otherwise whatever the external config file selects. The launcher reads this
     * (as a tag on the command) to hide every GPU from a CPU run, so a run that loses it silently
     * loses that guard. Reading the external file is I/O, so callers resolve this once, off the
     * FX thread, and carry the answer through to the command they build.
     */
    private ConfigFile.RunValues resolveRunValues(CasanovoCommand base, boolean guiOwnsConfig,
                                                  String guiAccelerator,
                                                  String guiPredictBatchSize) {
        // One pass over the external file for both values: reading it twice doubles the wait on
        // the unresponsive share this was moved off the UI thread to survive.
        return ConfigFile.forRun(
                base, guiOwnsConfig, guiAccelerator, guiPredictBatchSize);
    }

    private CasanovoCommand effectiveCommand(CommandPane pane, boolean forRun) {
        return effectiveCommand(pane, forRun, null);
    }

    /** Build a command, optionally overriding the generated config for this run only. */
    private CasanovoCommand effectiveCommand(CommandPane pane, boolean forRun,
                                              String acceleratorOverride) {
        CasanovoCommand base = pane.buildCommand();
        if (!guiOwnsConfig(base)) {
            // Untagged on purpose: buildThenRun applies the accelerator its caller already
            // resolved, so neither this nor the preview reads the config file on the FX thread.
            return base;
        }
        String configPath;
        if (forRun) {
            if (!applyTimstofProfile(isTimstofCommand(base))) { // .d run -> timsTOF residues + max_peaks
                throw new RuntimeException("The timsTOF model is selected, but config_timstof.yaml could "
                        + "not be read from the Casanovo install, so a valid timsTOF config can't be built. "
                        + "Check the Casanovo installation, or turn off \"Use GUI parameters\" and supply a "
                        + "config file manually.");
            }
            try {
                configPath = writeEffectiveConfig(
                        resolveOutputDir(base), acceleratorOverride).getAbsolutePath();
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

    /**
     * Keep the config's timsTOF profile in sync with whether this is a timsTOF ({@code .d}) run.
     * On the first transition into timsTOF, save the current {@code residues} + {@code max_peaks}
     * and replace them with the timsTOF values (from the installed {@code config_timstof.yaml}); on
     * the way back, restore them. Idempotent — only acts on transitions. Called just before the
     * config is consumed (the Parameters dialog and run-config generation), so the timsTOF
     * checkpoint's vocabulary check passes and the dialog reflects the timsTOF parameters.
     */
    private boolean applyTimstofProfile(boolean timsTof) {
        if (timsTof && !timstofProfileActive) {
            Optional<TimsTof.Profile> prof = TimsTof.profile(settings);
            if (prof.isEmpty()) {
                console.appendLine("[timstof] Could not read config_timstof.yaml from the Casanovo "
                        + "install; timsTOF residues were not applied.");
                return false;
            }
            savedResidues = config.get("residues").getValue();
            savedMaxPeaks = config.get("max_peaks").getValue();
            config.get("residues").setValue(prof.get().residues());
            config.get("max_peaks").setValue(prof.get().maxPeaks());
            timstofProfileActive = true;
        } else if (!timsTof && timstofProfileActive) {
            config.get("residues").setValue(savedResidues);
            config.get("max_peaks").setValue(savedMaxPeaks);
            timstofProfileActive = false;
        }
        return true;
    }

    /** True when {@code cmd} runs the timsTOF model (auto-selected for {@code .d} input, or set by hand). */
    private static boolean isTimstofCommand(CasanovoCommand cmd) {
        List<String> a = cmd.getArguments();
        int i = a.indexOf("--model");
        return i >= 0 && i + 1 < a.size() && "timstof".equals(a.get(i + 1));
    }

    private void refreshSettingsLabel() {
        String base = settings.getCasanovoExecutable();
        settingsLabel.setText(base);
        settingsLabel.setTooltip(new javafx.scene.control.Tooltip(base));
        // Version in its own label so a long path can't truncate it (see buildExecutionReadout).
        boolean known = installedCasanovoVersion != null && !installedCasanovoVersion.isEmpty();
        casanovoVersionLabel.setText(known ? "·  Casanovo " + installedCasanovoVersion : "");
    }

    /**
     * Resolve the installed Casanovo version on a daemon thread — it may spawn a {@code casanovo
     * version} subprocess (up to ~25s for PATH/Conda installs) — then update the execution readout on
     * the FX thread. Never runs on the FX thread, so it cannot delay window loading.
     */
    private void fetchCasanovoVersionAsync() {
        Thread t = new Thread(() -> {
            String v = UpdateChecker.installedCasanovoVersion(settings).orElse(null);
            Platform.runLater(() -> {
                installedCasanovoVersion = v;
                refreshSettingsLabel();
            });
        }, "casanovo-version");
        t.setDaemon(true);
        t.start();
    }

    private void onRun() {
        if (refuseWhileBusy("Run")) {
            return; // one job per window: also bail while a pepmap mapping is in progress
        }
        CommandPane pane = currentPane();
        if (pane == null) {
            return; // not a command tab (e.g. Plot) — nothing to run
        }
        clearValidationError();
        ValidationError error = pane.validateInputs();
        if (error != null) {
            showValidationError(error);
            return;
        }
        // With GUI parameters off, the run relies on an external config file — require it.
        if (!useGuiParams.isSelected()) {
            ValidationError cfgError = pane.validateConfigFile();
            if (cfgError != null) {
                showValidationError(cfgError);
                return;
            }
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
        // Only what the check needs, and nothing that touches the disk: the run's config is
        // written later, by buildThenRun, once the device is known to be usable.
        CasanovoCommand base;
        try {
            base = pane.buildCommand();
        } catch (RuntimeException ex) {
            alert(Alert.AlertType.ERROR, "Cannot run", ex.getMessage());
            return;
        }
        checkDeviceThenRun(pane, base, guiOwnsConfig(base),
                config.get("accelerator").getValue(),
                config.get("predict_batch_size").getValue());
    }

    /**
     * Convert any Thermo {@code .raw} input files in {@code command}'s arguments to {@code .mzML}
     * before starting the run, substituting the converted paths back in. Proceeds immediately
     * (today's exact behavior/timing) when there are no {@code .raw} inputs.
     */
    private void convertRawThenRun(CommandPane pane, PreparedRun run) {
        CasanovoCommand command = run.command();
        List<File> rawFiles = new ArrayList<>();
        for (String a : command.getArguments()) {
            if (RawFiles.isRawFile(a)) {
                rawFiles.add(new File(a));
            }
        }
        if (rawFiles.isEmpty()) {
            proceedWithRun(pane, run, pane.resultSpectra());
            return;
        }

        File mzmlDir = new File(resolveOutputDir(command), "mzML");
        mzmlDir.mkdirs();
        if (!mzmlDir.isDirectory()) {
            alert(Alert.AlertType.ERROR, "Cannot run",
                    "Could not create the mzML output folder:\n" + mzmlDir.getAbsolutePath());
            return;
        }

        // Map each source .raw (by absolute path) to a UNIQUE .mzML target. Two selected raws that
        // share a basename (e.g. a/run.raw and b/run.raw) would otherwise collapse onto one target
        // and silently overwrite each other, so the colliding ones are disambiguated with a short
        // hash of their absolute path. The common (no-collision) case keeps a clean <base>.mzML name.
        Map<String, Long> baseCounts = new HashMap<>();
        for (File raw : rawFiles) {
            baseCounts.merge(stripExtension(raw.getName()).toLowerCase(Locale.ROOT), 1L, Long::sum);
        }
        Map<String, File> targets = new LinkedHashMap<>(); // raw file's absolute path -> its .mzML target
        List<File> toConvert = new ArrayList<>();
        for (File raw : rawFiles) {
            String base = stripExtension(raw.getName());
            String fileName = (baseCounts.get(base.toLowerCase(Locale.ROOT)) > 1)
                    ? base + "_" + Integer.toHexString(raw.getAbsolutePath().hashCode()) + ".mzML"
                    : base + ".mzML";
            File target = new File(mzmlDir, fileName);
            targets.put(raw.getAbsolutePath(), target);
            if (target.isFile() && target.lastModified() >= raw.lastModified()) {
                console.appendLine("[raw] Reusing cached mzML for " + raw.getName() + ": " + target.getName());
            } else {
                toConvert.add(raw);
            }
        }

        // Capture the pane's spectrum list now, on the FX thread — resultSpectra() reads JavaFX
        // fields, so the background conversion thread must not call it.
        List<File> originalSpectra = pane.resultSpectra();

        if (toConvert.isEmpty()) {
            proceedWithRun(pane, new PreparedRun(
                            RawFiles.substitutePaths(command, targets), run.predictBatchSize()),
                    RawFiles.substituteFiles(originalSpectra, targets));
            return;
        }

        rawConvertCancelled = false;
        convertingRaw = true;
        updateRunningState(true);
        consoleFrame.setState(ConsoleBorderEffect.State.RUNNING);
        updateAnimation(); // the conversion is a running state too — show the overlay now
        progressBar.setVisible(true);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        statusLabel.setText("Preparing ThermoRawFileParser…");

        Thread t = new Thread(() -> runRawConversion(pane, run, toConvert, targets, originalSpectra),
                "raw-conversion");
        t.setDaemon(true);
        rawConvertThread = t;
        t.start();
    }

    /** Background-thread half of {@link #convertRawThenRun}: resolves the converter, then converts each file. */
    private void runRawConversion(CommandPane pane, PreparedRun run, List<File> toConvert,
                                  Map<String, File> targets, List<File> originalSpectra) {
        CasanovoCommand command = run.command();
        Path exe;
        try {
            exe = RawFileParserLauncher.ensureRawFileParser(settings.getRawParserPath(),
                    msg -> Platform.runLater(() -> {
                        console.appendLine("[raw] " + msg);
                        statusLabel.setText(msg);
                    }));
        } catch (Exception ex) {
            // A Stop during the (uninterruptible) download surfaces as an InterruptedException here.
            if (rawConvertCancelled || ex instanceof InterruptedException) {
                Platform.runLater(() -> abortRawConversion("Stopped."));
            } else {
                String m = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                Platform.runLater(() -> abortRawConversion("Could not prepare ThermoRawFileParser: " + m));
            }
            return;
        }
        if (rawConvertCancelled) {
            Platform.runLater(() -> abortRawConversion("Stopped."));
            return;
        }

        for (int i = 0; i < toConvert.size(); i++) {
            File raw = toConvert.get(i);
            File target = targets.get(raw.getAbsolutePath());
            // Convert to a temp file and only publish it onto the final target on a clean exit, so a
            // cancelled/failed conversion can never leave a truncated .mzML the cache would reuse. The
            // temp name must still end in ".mzML": ThermoRawFileParser standardizes the -b output
            // extension to match -f (mzML), so a plain ".part" suffix gets ".mzML" appended by the tool
            // (yielding <name>.part.mzML) and the exact ".part" path would never exist. "<base>.part.mzML"
            // already has the extension the tool wants, so it is written verbatim; we then rename it.
            File part = new File(target.getParentFile(),
                    target.getName().replaceAll("(?i)\\.mzML$", "") + ".part.mzML");
            int idx = i + 1;
            int total = toConvert.size();
            Platform.runLater(() -> statusLabel.setText(
                    "Converting " + raw.getName() + " to mzML… (" + idx + "/" + total + ")"));
            boolean published = false;
            boolean icuMissing = false;
            try {
                part.delete();
                Process p = RawFileParserLauncher.convertToMzml(exe, raw, part,
                        msg -> Platform.runLater(() -> console.appendLine("[raw] " + msg)));
                rawConvertProc = p;
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        String out = line;
                        // The self-contained .NET build aborts on Linux when the ICU library is absent.
                        if (out.contains("valid ICU package") || out.contains("libicu")) {
                            icuMissing = true;
                        }
                        Platform.runLater(() -> console.appendLine("[raw] " + out));
                    }
                }
                int exit = p.waitFor();
                rawConvertProc = null;
                if (rawConvertCancelled) {
                    Platform.runLater(() -> abortRawConversion("Stopped."));
                    return;
                }
                if (exit != 0 || !part.isFile()) {
                    final boolean icu = icuMissing;
                    final String rawName = raw.getName();
                    Platform.runLater(() -> abortRawConversion(icu
                            ? "ThermoRawFileParser could not run: it needs the ICU library (libicu), which "
                              + "is not installed on this system.\n\nInstall it, then run again:\n"
                              + "  • Debian/Ubuntu:  sudo apt install libicu-dev\n"
                              + "  • Fedora/RHEL:    sudo dnf install libicu\n"
                              + "  • Alpine:         sudo apk add icu-libs\n\n"
                              + "More info: https://aka.ms/dotnet-missing-libicu"
                            : "ThermoRawFileParser failed to convert " + rawName + " (exit " + exit + ")."));
                    return;
                }
                publish(part, target);
                published = true;
            } catch (Exception ex) {
                rawConvertProc = null;
                String m = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                Platform.runLater(() -> abortRawConversion("Could not convert " + raw.getName() + ": " + m));
                return;
            } finally {
                if (!published) {
                    part.delete();
                }
            }
        }

        final CasanovoCommand substituted;
        final List<File> spectra;
        try {
            substituted = RawFiles.substitutePaths(command, targets);
            spectra = RawFiles.substituteFiles(originalSpectra, targets);
        } catch (Exception ex) {
            String m = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            Platform.runLater(() -> abortRawConversion("Could not finalize converted inputs: " + m));
            return;
        }
        Platform.runLater(() -> {
            // Stop may have arrived after the last conversion but before this runnable ran.
            if (rawConvertCancelled) {
                abortRawConversion("Stopped.");
                return;
            }
            convertingRaw = false;
            rawConvertThread = null;
            proceedWithRun(pane, new PreparedRun(substituted, run.predictBatchSize()), spectra);
        });
    }

    /** Atomically move a finished {@code .part} file onto its final {@code .mzML} target. */
    private static void publish(File part, File target) throws IOException {
        try {
            Files.move(part.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(part.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Reset the UI after a failed/cancelled raw conversion; the Casanovo run never starts. */
    private void abortRawConversion(String message) {
        convertingRaw = false;
        rawConvertProc = null;
        rawConvertThread = null;
        updateRunningState(false);
        updateAnimation(); // conversion ended (failed/cancelled) — hide the overlay
        consoleFrame.setState("Stopped.".equals(message)
                ? ConsoleBorderEffect.State.IDLE
                : ConsoleBorderEffect.State.ERROR);
        progressBar.setVisible(false);
        statusLabel.setText(message);
        if ("Stopped.".equals(message)) {
            console.appendLine("[stopped] Raw conversion was cancelled by the user.");
        } else {
            console.appendLine("[error] " + message);
            alert(Alert.AlertType.ERROR, "Raw conversion failed", message);
        }
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** The execution backend for a run: local, or a remote (SSH) backend when remote execution is enabled. */
    private ExecutionBackend backendFor() {
        if (remoteSettings.isEnabled() && remoteSettings.isConfigured()) {
            return new RemoteBackend(remoteSettings, this::promptHostKey,
                    passwordSupplierFor(remoteSettings.getUser(), remoteSettings.getHost()),
                    passphraseSupplierFor(remoteSettings.getKeyPath()),
                    MainApp::runStamp);
        }
        return new LocalBackend();
    }

    /** A supplier that prompts (on the FX thread) for the SSH password for {@code user@host}; null if cancelled. */
    private java.util.function.Supplier<char[]> passwordSupplierFor(String user, String host) {
        return () -> promptSecret("SSH password", "Password for " + user + "@" + host + ":");
    }

    /** A supplier that prompts (on the FX thread) for the passphrase of {@code keyPath}; null if cancelled. */
    private java.util.function.Supplier<char[]> passphraseSupplierFor(String keyPath) {
        return () -> promptSecret("Key passphrase", "Passphrase for " + keyPath + ":");
    }

    /** True while a Casanovo job is running (local or remote). */
    private boolean isJobRunning() {
        return currentJob != null && currentJob.isRunning();
    }

    /** Cancel the current job, if any (safe when nothing is running). */
    private void cancelJob() {
        if (currentJob != null) {
            currentJob.cancel();
        }
    }

    /**
     * The local inputs a remote backend must upload: the spectra, plus any config / model-ckpt / FASTA that
     * appears in the command as an existing local file. The {@code --output_dir}/{@code --output_root}/
     * {@code --verbosity} values are excluded (a destination and plain names, not files to stage).
     */
    private static List<File> collectInputs(CasanovoCommand command, List<File> spectra) {
        java.util.LinkedHashSet<File> inputs = new java.util.LinkedHashSet<>();
        for (File s : spectra) {
            inputs.add(s.getAbsoluteFile());
        }
        List<String> args = command.getArguments();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if ("--output_dir".equals(a) || "--output_root".equals(a) || "--verbosity".equals(a)) {
                i++; // skip these flag values — a destination / plain name, not a file to stage
                continue;
            }
            if (a.startsWith("--")) {
                continue; // a flag itself; a path value (config/model/FASTA) is a plain token, picked up below
            }
            File f = new File(a);
            if (f.exists()) {
                inputs.add(f.getAbsoluteFile());
            }
        }
        return new java.util.ArrayList<>(inputs);
    }

    /** Open the Remote-execution settings dialog; its "Test connection" probes with the current fields. */
    private void openRemoteSettings() {
        // Switching local/remote mid-check would apply the verdict to a different machine.
        if (refuseWhileBusy("Remote execution")) {
            return;
        }
        RemoteSettingsDialog dlg = new RemoteSettingsDialog(stage, remoteSettings,
                (host, port, user, auth, keyPath, knownHosts) -> RemoteBackend.testConnection(
                        host, port, user, auth, keyPath, knownHosts, this::promptHostKey,
                        passwordSupplierFor(user, host),
                        passphraseSupplierFor(keyPath)));
        if (dlg.showAndApply()) {
            refreshPreview();
        }
    }

    /** Blockingly ask the user (on the FX thread) to trust an unknown host key. Invoked from a backend thread. */
    private boolean promptHostKey(String fingerprint) {
        Boolean ok = blockingFx(() -> {
            Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                    "The server presented an unrecognized host key:\n\n" + fingerprint
                            + "\n\nTrust this host and continue connecting?",
                    ButtonType.YES, ButtonType.NO);
            a.setHeaderText("Unknown SSH host key");
            a.initOwner(stage);
            return a.showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
        });
        return Boolean.TRUE.equals(ok);
    }

    /** Blockingly prompt for a secret (password/passphrase) on the FX thread; never stored. Null if cancelled. */
    private char[] promptSecret(String title, String prompt) {
        return blockingFx(() -> {
            Dialog<char[]> d = new Dialog<>();
            d.setTitle(title);
            d.initOwner(stage);
            PasswordField pf = new PasswordField();
            pf.setPrefColumnCount(26);
            VBox box = new VBox(8, new Label(prompt), pf);
            box.setPadding(new Insets(8));
            d.getDialogPane().setContent(box);
            d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            d.setResultConverter(bt -> bt == ButtonType.OK ? pf.getText().toCharArray() : null);
            Platform.runLater(pf::requestFocus);
            return d.showAndWait().orElse(null);
        });
    }

    /** Run {@code onFx} on the FX thread and block until it returns — for prompts invoked from a backend thread. */
    private static <T> T blockingFx(java.util.concurrent.Callable<T> onFx) {
        if (Platform.isFxApplicationThread()) {
            try {
                return onFx.call();
            } catch (Exception e) {
                return null;
            }
        }
        java.util.concurrent.FutureTask<T> task = new java.util.concurrent.FutureTask<>(onFx);
        try {
            // Throws if the FX runtime is gone — then no one can answer the prompt, so bail.
            Platform.runLater(task);
            // No wall-clock cap: the modal dialog is the real bound, and a human may take a while to read a
            // host key or type a passphrase. The caller is a daemon thread, so an unanswered prompt can't
            // keep the JVM alive.
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            // FX toolkit not running, or the task itself threw — either way, don't wedge the caller.
            return null;
        }
    }

    /** Ask, without blocking the FX thread, whether a quiet installer should keep waiting. */
    private boolean continueWaitingForInstaller(List<String> command, long silentSeconds,
                                                long elapsedSeconds) {
        long silentMinutes = Math.max(1, silentSeconds / 60);
        long elapsedMinutes = Math.max(1, elapsedSeconds / 60);
        console.appendLine("[install] No output for " + silentMinutes
                + " minute(s); waiting for your decision.");
        Boolean keepWaiting = blockingFx(() -> {
            ButtonType keep = new ButtonType("Continue waiting", ButtonBar.ButtonData.OK_DONE);
            ButtonType stop = new ButtonType("Stop installation", ButtonBar.ButtonData.CANCEL_CLOSE);
            String executable = command.isEmpty() ? "installer command" : command.get(0);
            Alert alert = new Alert(Alert.AlertType.WARNING,
                    "The installer has produced no output for " + silentMinutes
                            + " minute(s) and has been running for " + elapsedMinutes + " minute(s).\n\n"
                            + "A slow internet connection can make this normal. Continue waiting?\n\n"
                            + "Command: " + executable,
                    keep, stop);
            alert.setTitle("Installation is taking a long time");
            alert.setHeaderText(null);
            if (stage != null) {
                alert.initOwner(stage);
            }
            return alert.showAndWait().orElse(stop) == keep;
        });
        boolean keep = Boolean.TRUE.equals(keepWaiting);
        console.appendLine(keep
                ? "[install] Continuing to wait."
                : "[install] Stopping at the user's request.");
        return keep;
    }

    /** The rest of a run, once the command's inputs are all in their final (non-{@code .raw}) form. */
    private void proceedWithRun(CommandPane pane, PreparedRun run, List<File> spectra) {
        CasanovoCommand command = run.command();
        File workingDir = inferWorkingDir(command);
        // Remember the inputs + where the result will land so "Open in PDV" can load it directly.
        pendingSpectra = spectra;
        pendingOutputDir = (workingDir != null) ? workingDir : new File(System.getProperty("user.dir"));
        limelight.onRunStarted(command, spectra); // limelight
        pendingRunStartMs = System.currentTimeMillis() - 3000L; // small clock-skew buffer
        console.append(System.lineSeparator() + "$ " + command.toDisplayString(settings)
                + System.lineSeparator());
        String runLabel = (pane instanceof SequencePane)
                ? "de novo peptide sequencing"
                : command.getSubcommand();
        runStatusBase = "Running " + runLabel + "…";
        statusLabel.setText(runStatusBase);
        predictTotalBatches = 0;
        predictBatchSize = run.predictBatchSize();
        updateRunningState(true);
        consoleFrame.setState(ConsoleBorderEffect.State.RUNNING);
        progressBar.setVisible(true);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        lastProgressMs = 0L;
        lastBarMs = 0L;
        checkpointErrorSeen = false;

        JobRequest request = new JobRequest(command, settings, workingDir,
                collectInputs(command, spectra), pendingOutputDir);
        currentJob = backendFor().start(request,
                this::onOutput,
                (exit, err) -> Platform.runLater(() -> onFinished(exit, err)),
                msg -> Platform.runLater(() -> console.appendLine("[remote] " + msg)));
        // After start(): the job's isRunning() is now true, so the overlay shows.
        updateAnimation();
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
                + "\n\nFix it in File → Settings, where \"Install Casanovo\" can "
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
            managedInstallAvailable = true;
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
        updateAnimation();
        progressBar.setProgress(exitCode == 0 ? 1.0 : 0.0);
        progressBar.setVisible(false);
        if (error != null) {
            consoleFrame.setState(ConsoleBorderEffect.State.ERROR);
            console.appendLine("[error] " + error.getMessage());
            statusLabel.setText("Failed to start.");
            alert(Alert.AlertType.ERROR, "Execution error", error.getMessage());
        } else if (exitCode == 0) {
            consoleFrame.setState(ConsoleBorderEffect.State.SUCCESS);
            console.appendLine("[done] Casanovo finished successfully (exit 0).");
            statusLabel.setText("Finished successfully.");
            captureResult();
            if (pendingOutputDir != null && pendingOutputDir.isDirectory()) {
                showOpenOutputLink(true);
            }
        } else if (exitCode == 130) {
            consoleFrame.setState(ConsoleBorderEffect.State.IDLE);
            console.appendLine("[stopped] Casanovo was cancelled by the user.");
            statusLabel.setText("Stopped.");
        } else {
            consoleFrame.setState(ConsoleBorderEffect.State.ERROR);
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
        if (checkingDevice) {
            statusLabel.setText("Stopping…");
            deviceCheckCancelled = true;
            Thread t = deviceCheckThread;
            if (t != null) {
                t.interrupt(); // unwinds the probe's waitFor, which also kills its interpreter
            }
            return;
        }
        if (uploading) { // limelight
            // The upload has no cancel machinery, so saying "Stopping…" would be a lie — and it
            // is not an install, which is what the shared busy flag used to make it look like.
            noteBusy("The Limelight upload cannot be stopped; it will finish on its own.");
            return;
        }
        if (installing) {
            statusLabel.setText("Stopping installation…");
            Thread t = installerThread;
            if (t != null) {
                t.interrupt(); // Runner kills its current child while unwinding waitFor.
            }
            return;
        }
        if (isJobRunning()) {
            statusLabel.setText("Stopping…");
            cancelJob();
        } else if (convertingRaw) {
            statusLabel.setText("Stopping…");
            rawConvertCancelled = true;
            Process p = rawConvertProc;
            if (p != null) {
                p.destroyForcibly();
            } else {
                // No subprocess yet (e.g. still downloading the converter): interrupt the worker so
                // its blocking HTTP download unwinds promptly instead of running to completion.
                Thread t = rawConvertThread;
                if (t != null) {
                    t.interrupt();
                }
            }
        }
    }

    /** Download + install Python and Casanovo in the background. */
    private void onInstall() {
        if (refuseWhileBusy("Installing Casanovo")) {
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
        // The choke point for every install: a conversion or a mapping in flight is using the
        // very venv uv is about to rewrite, and neither was in the hand-written guard this
        // replaces — which is how "add checkingDevice to five expressions" missed two states.
        if (refuseWhileBusy("Installing Casanovo")) {
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
                        CasanovoInstaller.defaultInstallRoot(), console::appendLine,
                        this::continueWaitingForInstaller);
                Platform.runLater(() -> {
                    settings.setCasanovoExecutable(exe);
                    settings.setUseConda(false);
                    settings.save();
                    managedInstallAvailable = true;
                    warmConfigCacheAsync();
                    finishInstallerTask();
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
                boolean stopped = ex instanceof InterruptedException
                        || Thread.currentThread().isInterrupted();
                String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                Platform.runLater(() -> {
                    if (stopped) {
                        console.appendLine("[install] Stopped.");
                        finishInstallerTask();
                        statusLabel.setText("Installation stopped.");
                        return;
                    }
                    console.appendLine("[install] FAILED: " + msg);
                    finishInstallerTask();
                    statusLabel.setText("Install failed.");
                    alert(Alert.AlertType.ERROR, "Install failed", msg);
                });
            }
        }, "casanovo-installer");
        startInstallerThread(t);
    }

    private void startInstallerThread(Thread thread) {
        installerThread = thread;
        stopButton.setDisable(false);
        thread.setDaemon(true);
        thread.start();
    }

    private void finishInstallerTask() {
        installerThread = null;
        installing = false;
        setBusy(false);
        stopButton.setDisable(true);
        refreshPreview();
    }

    /** Disable interactive controls while a long background task (install) runs. */
    private void setBusy(boolean busy) {
        runButton.setDisable(busy);
        paramsButton.setDisable(busy);
        useGuiParams.setDisable(busy);
        tabs.setDisable(busy);
    }

    private void updateRunningState(boolean running) {
        runButton.setDisable(running);
        stopButton.setDisable(!running);
        if (running) {
            showOpenOutputLink(false); // a new run/conversion started — hide last run's link until it succeeds
        }
        paramsButton.setDisable(running);
        useGuiParams.setDisable(running);
        refreshTabLock(running);
    }

    /**
     * Lock the tabs while <em>any</em> job runs — a Casanovo run or a View mapping — so no other panel can
     * be selected or edited (one job per window; to browse results during a run, launch a second CasanovoGUI).
     * A run's Run/Stop live in the command row outside the tabs, so a run locks the whole tab strip (headers
     * unselectable and the shown form grayed). A pepmap mapping's Run/Stop live inside the View tab, so a
     * mapping locks the command tabs but leaves the View tab operable (so its own Stop still works). The Stop
     * button, console and status bar stay live in both cases.
     */
    private void refreshTabLock(boolean casanovoRunning) {
        if (casanovoRunning) {
            tabs.setDisable(true);
            return;
        }
        tabs.setDisable(false);
        boolean mapping = viewPane.runningProperty().get();
        for (Tab t : tabs.getTabs()) {
            if (t != viewTab) {
                t.setDisable(mapping); // lock the command tabs while a mapping runs
            }
        }
    }

    /**
     * Report a failed input validation inline: highlight the offending field with a danger
     * border, focus it, and show the message in the status bar — no modal to dismiss. Falls
     * back to a modal only when the error names no specific field.
     */
    private void showValidationError(ValidationError error) {
        if (error.field() == null) {
            alert(Alert.AlertType.WARNING, "Cannot run", error.message());
            return;
        }
        validation.show(error.field(), error.message());
    }

    /** Remove any inline validation decoration (danger border + error status message). */
    private void clearValidationError() {
        validation.clear();
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
     * After a successful run, find the produced mzTab and auto-fill the View tab's
     * peptides field. No-op when the run had no spectra input or wrote no mzTab.
     */
    private void captureResult() {
        if (pendingSpectra == null || pendingSpectra.isEmpty() || pendingOutputDir == null) {
            return;
        }
        File mztab = findNewestMzTab(pendingOutputDir, pendingRunStartMs);
        limelight.onResultReady(mztab); // limelight
        if (mztab != null) {
            // Auto-fill the View tab's peptides field (mapping is run on demand).
            viewPane.setPeptides(mztab);
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

    /**
     * Show what this machine actually provides: OS, CPU, and the installed PyTorch's build
     * type and visible devices. A user reporting a device problem can copy this instead of
     * describing their setup, which is otherwise the hardest part of diagnosing one.
     */
    private void showEnvironmentReport() {
        // The report probes the configured environment, which during an install is a venv uv is
        // rewriting in place and during a run is a GPU the run is using.
        if (refuseWhileBusy("The environment report")) {
            return;
        }
        javafx.scene.control.TextArea area = new javafx.scene.control.TextArea("Querying PyTorch\u2026");
        area.setEditable(false);
        area.setPrefRowCount(12);
        area.setPrefColumnCount(64);
        area.setStyle("-fx-font-family: 'Consolas', 'Menlo', 'DejaVu Sans Mono', monospace;");

        Alert dialog = new Alert(Alert.AlertType.INFORMATION);
        dialog.setTitle("Environment Report");
        dialog.setHeaderText("Copy this into a bug report.");
        dialog.getDialogPane().setContent(area);
        dialog.setResizable(true);
        // Not the Alert default (APPLICATION_MODAL): the probe this waits on can hang — a wedged
        // driver, a stalled `conda run` — and a modal dialog would block input to the owner
        // window, taking with it the Stop button that is the only way to cancel the probe.
        dialog.initModality(javafx.stage.Modality.NONE);
        if (stage != null) {
            dialog.initOwner(stage);
        }

        // The probe launches an interpreter on its first call, so keep it off the FX thread.
        Thread worker = new Thread(() -> {
            String text;
            try {
                text = "CasanovoGUI     : " + UpdateChecker.guiVersion() + "\n"
                        + DeviceProbe.environmentReport(DeviceProbe.probe(settings));
            } catch (Throwable t) {
                // Nothing else ever fills this dialog, so an unguarded throw would leave it on
                // "Querying PyTorch…" for good. Why it failed is itself worth reporting.
                text = "Environment report failed: " + t;
            }
            String result = text;
            Platform.runLater(() -> {
                endDeviceCheck();
                area.setText(result);
            });
        }, "environment-report");
        // This launches an interpreter against the configured environment, exactly as a pre-run
        // check does, and it outlives a dialog the user can dismiss at once. Holding the same
        // busy state stops an install from rewriting that venv underneath it. The dialog stays
        // non-modal so the main window's Stop button remains available throughout the probe.
        beginDeviceCheck(worker);
        try {
            worker.start();
        } catch (Throwable t) {
            endDeviceCheck();
            area.setText("Environment report failed: " + t);
        }

        dialog.show();
    }

    private void showAbout() {
        String casa = (installedCasanovoVersion == null || installedCasanovoVersion.isEmpty())
                ? "not found" : installedCasanovoVersion;

        Label versions = new Label("Casanovo GUI " + UpdateChecker.guiVersion() + "\nCasanovo " + casa);
        // "de novo" italicised (Latin term of art), in the theme's default foreground.
        javafx.scene.text.TextFlow desc = italicPhrase("A GUI for Casanovo ", "de novo",
                " peptide sequencing.\nConfigure inputs, run, and visualize the results.",
                "-color-fg-default", false);

        Label citeHeader = new Label("How to cite:");
        citeHeader.setStyle("-fx-font-weight: bold;");
        Label authors = new Label("Bo Wen, Kai Li, Michael Riffle, Michael J. MacCoss, "
                + "Wout Bittremieux, William Stafford Noble.");
        authors.setWrapText(true);
        authors.setMaxWidth(460);
        // The manuscript title as a clickable citation link (opens the DOI on a primary click), with
        // "de novo" italic. A TextFlow (not a Hyperlink) so it can both wrap and italicise a fragment; the
        // AtlantaFX accent + underline make it read as a link.
        String doi = "https://doi.org/10.64898/2026.07.11.737889";
        javafx.scene.text.TextFlow title = italicPhrase(
                "CasanovoGUI: a cross-platform desktop application for deep learning-based ",
                "de novo", " peptide sequencing with Casanovo", "-color-accent-fg", true);
        title.setMaxWidth(460); // concrete cap so the long title wraps instead of widening the dialog
        title.setCursor(javafx.scene.Cursor.HAND);
        title.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                getHostServices().showDocument(doi); // primary-click only, like a real Hyperlink
            }
        });
        Label venue = new Label("bioRxiv 2026.07.11.737889.");
        javafx.scene.layout.VBox citation = new javafx.scene.layout.VBox(1, authors, title, venue);
        citation.setMaxWidth(Double.MAX_VALUE);

        Hyperlink repo = new Hyperlink("CasanovoGUI: github.com/Noble-Lab/CasanovoGUI");
        repo.setStyle("-fx-padding: 0;");
        repo.setOnAction(e -> getHostServices()
                .showDocument("https://github.com/Noble-Lab/CasanovoGUI"));

        javafx.scene.layout.VBox content = new javafx.scene.layout.VBox(10,
                versions, desc, citeHeader, citation, repo);
        content.setPrefWidth(480);
        content.setMaxWidth(480);

        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle("About Casanovo GUI");
        a.setHeaderText(null);
        a.getDialogPane().setContent(content);
        if (stage != null) {
            a.initOwner(stage);
        }
        a.showAndWait();
    }

    /**
     * A TextFlow of three fragments &mdash; {@code prefix} + {@code italicWord} (italic) + {@code suffix}
     * &mdash; each filled with the given AtlantaFX colour token ({@code -color-fg-default} for body text,
     * {@code -color-accent-fg} for a link; a raw Text otherwise defaults to black, invisible in the dark
     * theme) and optionally underlined. Lets the About citation italicise "de novo" inline (a Label can't)
     * while staying theme-aware.
     */
    private static javafx.scene.text.TextFlow italicPhrase(String prefix, String italicWord, String suffix,
                                                           String fillToken, boolean underline) {
        javafx.scene.text.Text pre = new javafx.scene.text.Text(prefix);
        javafx.scene.text.Text it = new javafx.scene.text.Text(italicWord);
        javafx.scene.text.Text post = new javafx.scene.text.Text(suffix);
        pre.setStyle("-fx-fill: " + fillToken + ";");
        it.setStyle("-fx-fill: " + fillToken + "; -fx-font-style: italic;");
        post.setStyle("-fx-fill: " + fillToken + ";");
        if (underline) {
            pre.setUnderline(true);
            it.setUnderline(true);
            post.setUnderline(true);
        }
        return new javafx.scene.text.TextFlow(pre, it, post);
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
            boolean managedInstall = CasanovoInstaller.managedVenvRoot(
                    settings.getCasanovoExecutable(), settings.isUseConda()).isPresent();
            Platform.runLater(() -> {
                managedInstallAvailable = managedInstall;
                onUpdateOutcome(outcome, manual);
            });
        }, "update-checker");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Re-answer "is the configured executable the venv we manage?" &mdash; the question
     * {@link #canSelfUpdate} needs and cannot ask on the FX thread, because
     * {@link CasanovoInstaller#managedVenvRoot} touches the filesystem. Clearing the answer
     * instead would hide the in-app "Update Casanovo" button for a genuinely managed install
     * until the next update check happened to run.
     */
    private void refreshManagedInstallAsync() {
        Thread t = new Thread(() -> {
            boolean managed = CasanovoInstaller.managedVenvRoot(
                    settings.getCasanovoExecutable(), settings.isUseConda()).isPresent();
            Platform.runLater(() -> managedInstallAvailable = managed);
        }, "managed-install-check");
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
            updateBanner.show(available, manual, this::onViewUpdate, this::onReleaseNotes,
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
                    .append(" (latest ").append(info.latestVersion);
            if (info.releaseDate != null) {
                sb.append(", released ").append(info.releaseDate);
            }
            sb.append(")");
        }
        if (outcome.networkError) {
            sb.append("\n\nNote: some version sources could not be reached.");
        }
        return sb.toString();
    }

    /**
     * Handle the banner's "View" link. PDV, pepmap and ThermoRawFileParser upgrade from the
     * Settings dialog (which has the one-click download), so open that; the GUI/Casanovo rows
     * open their release page in the browser.
     */
    private void onViewUpdate(UpdateChecker.UpdateInfo info) {
        if (info.target.opensUpgradeSettings()) {
            openSettings();
        } else {
            onReleaseNotes(info); // GUI/Casanovo: "View" opens the release page
        }
    }

    /** Open an update's release page in the browser — the "Release notes" banner link. */
    private void onReleaseNotes(UpdateChecker.UpdateInfo info) {
        if (info.pageUrl != null) {
            getHostServices().showDocument(info.pageUrl);
        }
    }

    /**
     * True when an update can be applied in-app: it's the Casanovo tool, the GUI
     * manages the exact configured executable and
     * Conda is not in use.
     */
    private boolean canSelfUpdate(UpdateChecker.UpdateInfo info) {
        return info.target == UpdateChecker.Target.CASANOVO && managedInstallAvailable;
    }

    /** Upgrade the GUI-managed Casanovo in place via {@code uv pip install -U casanovo}. */
    private void onUpdateCasanovo(UpdateChecker.UpdateInfo info) {
        if (refuseWhileBusy("Updating Casanovo")) {
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
                        CasanovoInstaller.defaultInstallRoot(), console::appendLine,
                        this::continueWaitingForInstaller);
                Platform.runLater(() -> {
                    finishInstallerTask();
                    statusLabel.setText("Casanovo updated.");
                    warmConfigCacheAsync(); // new version -> refresh the cached base config
                    updateBanner.removeTarget(UpdateChecker.Target.CASANOVO);
                    alert(Alert.AlertType.INFORMATION, "Update complete",
                            "Casanovo was updated to " + info.latestVersion + ".");
                });
            } catch (Exception ex) {
                boolean stopped = ex instanceof InterruptedException
                        || Thread.currentThread().isInterrupted();
                String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                Platform.runLater(() -> {
                    if (stopped) {
                        console.appendLine("[update] Stopped.");
                        finishInstallerTask();
                        statusLabel.setText("Update stopped.");
                        return;
                    }
                    console.appendLine("[update] FAILED: " + msg);
                    finishInstallerTask();
                    statusLabel.setText("Update failed.");
                    alert(Alert.AlertType.ERROR, "Update failed", msg);
                });
            }
        }, "casanovo-updater");
        startInstallerThread(t);
    }

    // ------------------------------------------------------- PyArrow self-check

    /**
     * On startup, detect a PyArrow/pylance ABI mismatch in a GUI-managed venv — the
     * combination that crashes Casanovo with exit 0xC0000005 — and offer a one-click
     * repair. Reads dist-info only: no Python launched, no network.
     */
    private void maybeCheckPyArrow() {
        Thread t = new Thread(() -> {
            Path venvRoot = CasanovoInstaller.managedVenvRoot(
                    settings.getCasanovoExecutable(), settings.isUseConda()).orElse(null);
            if (venvRoot == null) {
                return; // only the GUI-managed install can be auto-repaired
            }
            if (CasanovoInstaller.hasPyArrowMismatch(venvRoot)) {
                Platform.runLater(this::promptPyArrowRepair);
            }
        }, "pyarrow-check");
        t.setDaemon(true);
        t.start();
    }

    private void promptPyArrowRepair() {
        if (refuseWhileBusy("Repairing the Casanovo install")) {
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
                        CasanovoInstaller.defaultInstallRoot(), console::appendLine,
                        this::continueWaitingForInstaller);
                Platform.runLater(() -> {
                    finishInstallerTask();
                    statusLabel.setText("Casanovo repaired.");
                    alert(Alert.AlertType.INFORMATION, "Repair complete",
                            "PyArrow was re-pinned to a compatible version. Casanovo should now run.");
                });
            } catch (Exception ex) {
                boolean stopped = ex instanceof InterruptedException
                        || Thread.currentThread().isInterrupted();
                String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                Platform.runLater(() -> {
                    if (stopped) {
                        console.appendLine("[repair] Stopped.");
                        finishInstallerTask();
                        statusLabel.setText("Repair stopped.");
                        return;
                    }
                    console.appendLine("[repair] FAILED: " + msg);
                    finishInstallerTask();
                    statusLabel.setText("Repair failed.");
                    alert(Alert.AlertType.ERROR, "Repair failed", msg);
                });
            }
        }, "pyarrow-repair");
        startInstallerThread(t);
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
    private File writeEffectiveConfig(File outputDir, String acceleratorOverride) throws IOException {
        Optional<String> base = ConfigCache.cachedBase(settings);
        Map<String, String> overrides = acceleratorOverride == null
                ? Map.of() : Map.of("accelerator", acceleratorOverride);
        // overlayOnto/toYaml derive new_token_init from the (possibly timsTOF) residues, so the config
        // already bridges the aliased token for a .d run — no post-processing needed here.
        String yaml = base.isPresent()
                ? config.overlayOnto(base.get(), overrides) : config.toYaml(overrides);
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
