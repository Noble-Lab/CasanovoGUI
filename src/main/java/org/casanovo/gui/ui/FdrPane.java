package org.casanovo.gui.ui;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import org.casanovo.gui.core.GlissadeChecks;
import org.casanovo.gui.core.GlissadeDiscoveries;
import org.casanovo.gui.core.GlissadeInstaller;
import org.casanovo.gui.core.GlissadeRunner;
import org.casanovo.gui.core.Settings;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The FDR tab: false-discovery-rate control for de novo peptides, via
 * <a href="https://github.com/Noble-Lab/glissade">glissade</a>.
 *
 * <p>Self-contained in the {@link ViewPane} mould rather than a {@link CommandPane}: glissade is
 * not a Casanovo sub-command, so it owns its Run/Stop, its own subprocess and its own results
 * table, and only borrows the window's status label, progress bar and console. {@code MainApp}
 * observes {@link #runningProperty()} to keep "one job per window" true.</p>
 *
 * <p>glissade needs four inputs that must describe the <em>same</em> spectra: a de novo result, the
 * Percolator PSM and peptide files from a database search of those spectra, and the reference
 * FASTA. It writes one file, {@code glissade_discoveries.tsv}, into its working directory.</p>
 */
public class FdrPane extends BorderPane {


    /** q-value the results table is filtered to until the user says otherwise. */
    private static final double DEFAULT_Q_CUTOFF = 0.05;

    private final Window owner;
    private final Settings settings;
    private final Label sharedStatus;
    private final ProgressBar sharedProgress;
    private final BiConsumer<String, Boolean> consoleOut;
    private final InlineValidation validation;

    private final TextField denovoField = new TextField();
    private final TextField psmField = new TextField();
    private final TextField peptideField = new TextField();
    private final TextField fastaField = new TextField();
    private final Button denovoBrowse;
    private final Button psmBrowse;
    private final Button peptideBrowse;
    private final Button fastaBrowse;

    private final Spinner<Integer> bootstrapSpin = new Spinner<>(1, 100_000, 1000, 100);
    private final Spinner<Double> cutoffSpin = new Spinner<>();
    private final Button runButton = new Button("Run glissade");
    private final Button stopButton = new Button("Stop");
    private final Label installLabel = new Label();
    private final Button installButton = new Button("Install");

    private final TableView<GlissadeDiscoveries.Row> table = new TableView<>();
    /**
     * What an empty table says. Every real run so far ends with nothing under the cutoff, so
     * leaving the pre-run wording there made a finished run look like one that never started.
     */
    private final Label tablePlaceholder =
            new Label("Run glissade to list de novo peptides with their q-values.");
    private final ObservableList<GlissadeDiscoveries.Row> shown = FXCollections.observableArrayList();
    private final Label summary = new Label();

    private final BooleanProperty fdrRunning = new SimpleBooleanProperty(false);
    private final GlissadeRunner runner = new GlissadeRunner();

    /** Every row of the last successful run; the cutoff spinner filters this in memory. */
    private List<GlissadeDiscoveries.Row> allRows = List.of();
    /** Whether {@link #allRows} came from a finished run — an empty table means different things. */
    private boolean hasResult;
    /** The pi0 glissade printed on its console line, if it got that far. */
    private OptionalDouble pi0 = OptionalDouble.empty();
    private File outputDir;
    private long runStartMs;
    private boolean cancelled;
    /** True while installThenMaybeRun is working; the install itself cannot be interrupted. */
    private boolean installing;
    /** True once anything has been written to the shared console, so it stays attached. */
    private boolean consoleUsed;
    /** Open for the duration of a run; written from the runner thread, guarded by logLock. */
    private Writer runLogWriter;
    private final Object logLock = new Object();
    /** The executable found by the last {@link #refreshInstallState()}, or null when missing. */
    private volatile Path glissadeExe;
    /** Whether the GUI may install: true only for the environment it manages itself. */
    private volatile boolean managedEnvironment;

    public FdrPane(Window owner, Settings settings, Label sharedStatus, ProgressBar sharedProgress,
                   BiConsumer<String, Boolean> consoleOut, InlineValidation validation) {
        this.owner = owner;
        this.settings = settings;
        this.sharedStatus = sharedStatus;
        this.sharedProgress = sharedProgress;
        this.consoleOut = consoleOut;
        this.validation = validation;

        denovoBrowse = FxUtils.fileButton(owner, denovoField, "mzTab", false,
                "De novo results", "*.mzTab", "*.mztab", "*.csv", "*.tab");
        psmBrowse = FxUtils.fileButton(owner, psmField, "percolator", false,
                "Percolator PSMs", "*.txt", "*.tsv");
        peptideBrowse = FxUtils.fileButton(owner, peptideField, "percolator", false,
                "Percolator peptides", "*.txt", "*.tsv");
        fastaBrowse = FxUtils.fileButton(owner, fastaField, "fasta", false,
                "FASTA", "*.fasta", "*.fa", "*.fas", "*.faa");

        setPadding(new Insets(10));
        setTop(new VBox(6, buildInputs(), buildOptions()));
        setCenter(buildResults());
        stopButton.setDisable(true);
        refreshInstallState();
    }

    // ---- layout ------------------------------------------------------------

    private VBox buildInputs() {
        VBox rows = new VBox(6,
                inputRow("PSMs (de novo):", denovoField, denovoBrowse,
                        "Required. The de novo result to control: a Casanovo .mzTab, an InstaNovo "
                                + ".csv or a DeepNovo .tab. Auto-filled after a sequencing run."),
                inputRow("PSMs (database search):", psmField, psmBrowse,
                        "Required. Percolator PSM output for the SAME spectra (e.g. "
                                + "percolator.target.psms.txt). glissade recognises Percolator output "
                                + "only by the word 'percolator' in the path."),
                inputRow("Peptides (database search):", peptideField, peptideBrowse,
                        "Required. Percolator peptide output for the same search (e.g. "
                                + "percolator.target.peptides.txt)."),
                inputRow("Protein database (FASTA):", fastaField, fastaBrowse,
                        "Required. The protein database the search used. De novo peptides found in "
                                + "it are treated as already known, not as discoveries."));
        rows.setPadding(new Insets(0, 0, 4, 0));
        return rows;
    }

    private HBox inputRow(String labelText, TextField field, Button browse, String help) {
        Label label = new Label(labelText);
        label.setMinWidth(190); // fits the longest label, "Peptides (database search):"
        field.setPromptText("Required.");
        field.getStyleClass().add("prompt-required");
        HBox.setHgrow(field, Priority.ALWAYS);
        Tooltip tip = FxUtils.tooltip(help);
        Tooltip.install(label, tip);
        Tooltip.install(field, tip);
        HBox row = new HBox(8, label, field, browse);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private VBox buildOptions() {
        bootstrapSpin.setPrefWidth(100);
        bootstrapSpin.setEditable(true);
        FxUtils.commitOnFocusLoss(bootstrapSpin);
        Tooltip.install(bootstrapSpin, FxUtils.tooltip(
                "How many bootstrap samples glissade draws while fitting (its -n option). More is "
                        + "steadier and slower; 1000 is glissade's own default."));

        cutoffSpin.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                0.001, 1.0, DEFAULT_Q_CUTOFF, 0.01));
        cutoffSpin.setPrefWidth(100);
        cutoffSpin.setEditable(true);
        FxUtils.commitOnFocusLoss(cutoffSpin);
        cutoffSpin.valueProperty().addListener((o, a, b) -> applyCutoff());
        Tooltip.install(cutoffSpin, FxUtils.tooltip(
                "Display filter: which q-value to list peptides down to. glissade has no threshold "
                        + "option, so changing this re-filters the existing result without re-running."));

        runButton.getStyleClass().add("accent");
        runButton.setOnAction(e -> run());
        stopButton.getStyleClass().add("danger");
        stopButton.setOnAction(e -> stop());
        installButton.setOnAction(e -> installThenMaybeRun(false));
        installLabel.getStyleClass().add("text-muted");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(8,
                new Label("Bootstraps:"), bootstrapSpin,
                new Label("q-value cutoff:"), cutoffSpin,
                runButton, stopButton,
                spacer, installLabel, installButton);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(row);
        box.setPadding(new Insets(0, 0, 8, 0));
        return box;
    }

    private VBox buildResults() {
        TableColumn<GlissadeDiscoveries.Row, String> pep = new TableColumn<>("Peptide");
        pep.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().peptide()));
        // Typed Double, formatted in the cell: a String column would sort "-1.500" before
        // "-12.300" and put a "1.00e-05" q-value after "0.0500".
        TableColumn<GlissadeDiscoveries.Row, Double> score =
                numberCol("Score", GlissadeDiscoveries.Row::score,
                        v -> String.format(Locale.ROOT, "%.3f", v));
        TableColumn<GlissadeDiscoveries.Row, Double> q =
                numberCol("q-value", GlissadeDiscoveries.Row::q, FdrPane::formatQ);
        table.getColumns().setAll(List.of(pep, score, q));
        table.setItems(shown);
        tablePlaceholder.setWrapText(true);
        table.setPlaceholder(tablePlaceholder);
        TableUtils.enableCellCopy(table);
        VBox.setVgrow(table, Priority.ALWAYS);

        summary.getStyleClass().add("text-muted");
        VBox box = new VBox(6, summary, table);
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    /** A right-aligned numeric column that sorts on the number and shows {@code text} of it. */
    private static TableColumn<GlissadeDiscoveries.Row, Double> numberCol(
            String name, Function<GlissadeDiscoveries.Row, Double> getter,
            Function<Double, String> text) {
        TableColumn<GlissadeDiscoveries.Row, Double> col = new TableColumn<>(name);
        col.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(getter.apply(c.getValue())));
        col.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(Double v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null ? "" : text.apply(v));
            }
        });
        // Size on what is displayed, not on Double.toString().
        col.getProperties().put(TableUtils.DISPLAY_TEXT,
                (Function<GlissadeDiscoveries.Row, String>) r -> text.apply(getter.apply(r)));
        col.setStyle("-fx-alignment: CENTER-RIGHT;");
        return col;
    }

    private static String formatQ(double q) {
        if (Double.isInfinite(q) || Double.isNaN(q)) {
            return "n/a";
        }
        return q < 1e-4 && q > 0
                ? String.format(Locale.ROOT, "%.2e", q)
                : String.format(Locale.ROOT, "%.4f", q);
    }

    // ---- install state -----------------------------------------------------

    /**
     * Re-read whether glissade is installed and repaint the status line. Called on construction and
     * whenever the tab is selected: glissade lives inside Casanovo's venv, and reinstalling or
     * updating Casanovo clears that venv, so a cached "installed" can go stale between visits.
     * Filesystem reads happen off the FX thread.
     */
    public void refreshInstallState() {
        String exe = settings.getCasanovoExecutable();
        boolean conda = settings.isUseConda();
        Thread t = new Thread(() -> {
            Optional<Path> found = GlissadeInstaller.findInstalledExe(exe, conda);
            boolean managed = GlissadeInstaller.targetVenv(exe, conda).isPresent();
            Optional<String> ref = GlissadeInstaller.installedRef();
            Platform.runLater(() -> {
                glissadeExe = found.orElse(null);
                managedEnvironment = managed;
                paintInstallState(found.isPresent(), managed, ref);
            });
        }, "glissade-install-check");
        t.setDaemon(true);
        t.start();
    }

    /**
     * @param ref the commit recorded for the environment the GUI manages; meaningless for a
     *            glissade the user installed in their own environment, so it is only read when
     *            {@code managed} is true
     */
    private void paintInstallState(boolean installed, boolean managed, Optional<String> ref) {
        if (!installed) {
            installLabel.setText("glissade: not installed");
            installButton.setText("Install");
            installButton.setVisible(managed);
            installButton.setManaged(managed);
            return;
        }
        String current = managed ? ref.orElse("") : "";
        boolean outdated = managed && !current.isEmpty()
                && !current.equals(GlissadeInstaller.GLISSADE_REF);
        if (outdated) {
            installLabel.setText("glissade " + GlissadeInstaller.shortRef(current) + " installed; "
                    + GlissadeInstaller.shortRef(GlissadeInstaller.GLISSADE_REF) + " available");
            installButton.setText("Reinstall");
        } else {
            installLabel.setText("glissade: installed"
                    + (current.isEmpty() ? "" : " (" + GlissadeInstaller.shortRef(current) + ")"));
            installButton.setText("Reinstall");
        }
        installButton.setVisible(managed);
        installButton.setManaged(managed);
    }

    // ---- actions -----------------------------------------------------------

    /** Auto-fill the de novo field after a sequencing run. */
    public void setDenovoResult(File mzTab) {
        if (mzTab != null) {
            denovoField.setText(mzTab.getAbsolutePath());
        }
    }

    /** Start a run, as the window's Ctrl+R would. */
    public void fireRun() {
        if (!runButton.isDisabled()) {
            run();
        }
    }

    /** Stop the run, as the window's Esc would. */
    public void fireStop() {
        if (!stopButton.isDisabled()) {
            stop();
        }
    }

    /** True while glissade is running; observed by MainApp for tab locking and console chrome. */
    public BooleanProperty runningProperty() {
        return fdrRunning;
    }

    private void run() {
        validation.clear();
        ValidationError error = validate();
        if (error != null) {
            validation.show(error.field(), error.message());
            return;
        }
        if (glissadeExe == null) {
            if (!managedEnvironment) {
                showManualInstallHelp();
                return;
            }
            Alert ask = new Alert(Alert.AlertType.CONFIRMATION,
                    "glissade is not installed. Install it into the Casanovo environment now? "
                            + "It is a few hundred kilobytes — every package it needs is already there.",
                    ButtonType.OK, ButtonType.CANCEL);
            ask.setTitle("Install glissade");
            ask.setHeaderText(null);
            ask.initOwner(owner);
            if (ask.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return;
            }
            installThenMaybeRun(true);
            return;
        }
        launch();
    }

    private void showManualInstallHelp() {
        Alert a = new Alert(Alert.AlertType.INFORMATION,
                "glissade is not installed in your Casanovo environment, and the GUI only installs "
                        + "into the environment it manages itself.\n\n"
                        + "Install it into your own environment with:\n\n"
                        + "  pip install \"git+https://github.com/Noble-Lab/glissade@"
                        + GlissadeInstaller.GLISSADE_REF + "#subdirectory=src\"\n\n"
                        + "If pip refuses on requires-python, clone the repository, change that one "
                        + "line in src/pyproject.toml to \">=3.11\", and run: pip install ./src",
                ButtonType.OK);
        a.setTitle("Install glissade");
        a.setHeaderText(null);
        a.initOwner(owner);
        a.showAndWait();
    }

    /** Install glissade off-thread, then optionally continue straight into the run. */
    private void installThenMaybeRun(boolean runAfter) {
        cancelled = false;
        installing = true;
        setRunning(true);
        status("Installing glissade…");
        String exe = settings.getCasanovoExecutable();
        boolean conda = settings.isUseConda();
        Thread t = new Thread(() -> {
            Exception failure = null;
            try {
                if (glissadeExe != null) {
                    GlissadeInstaller.uninstall(exe, conda, this::console);
                }
                GlissadeInstaller.install(exe, conda, this::console, null);
            } catch (Exception e) {
                failure = e;
            }
            final Exception outcome = failure;
            Optional<Path> found = GlissadeInstaller.findInstalledExe(exe, conda);
            Optional<String> ref = GlissadeInstaller.installedRef();
            Platform.runLater(() -> {
                installing = false;
                glissadeExe = found.orElse(null);
                paintInstallState(found.isPresent(), managedEnvironment, ref);
                setRunning(false);
                if (outcome != null) {
                    status("glissade install failed.");
                    alert(Alert.AlertType.ERROR, "Install failed",
                            String.valueOf(outcome.getMessage())
                                    + "\n\nFull log: "
                                    + GlissadeInstaller.glissadeDir().resolve("logs")
                                    .resolve("install.log"));
                    return;
                }
                if (cancelled && runAfter) {
                    // The install cannot be interrupted, but a Stop pressed during it still means
                    // "do not run" — without this the queued run would start regardless.
                    status("glissade installed; the run was stopped.");
                    return;
                }
                status("glissade ready.");
                if (runAfter && glissadeExe != null) {
                    launch();
                }
            });
        }, "glissade-installer");
        t.setDaemon(true);
        t.start();
    }

    private ValidationError validate() {
        ValidationError e = PathFields.validateSingleFile(denovoField, "a de novo result");
        if (e != null) {
            return e;
        }
        e = PathFields.validateSingleFile(psmField, "the Percolator PSM file");
        if (e != null) {
            return e;
        }
        e = PathFields.validateSingleFile(peptideField, "the Percolator peptide file");
        if (e != null) {
            return e;
        }
        e = PathFields.validateSingleFile(fastaField, "the reference FASTA");
        if (e != null) {
            return e;
        }
        // The absolute path, because that is what GlissadeRunner.command passes and therefore the
        // only string glissade's own substring checks ever see. A relative "psms.txt" typed while
        // sitting in a percolator/ folder is accepted by glissade and must be accepted here.
        String denovo = absolutePath(denovoField);
        if (GlissadeChecks.denovoFormat(denovo) == GlissadeChecks.DenovoFormat.UNSUPPORTED) {
            return new ValidationError("glissade reads .mzTab (Casanovo), .csv (InstaNovo) and "
                    + ".tab (DeepNovo) de novo results.", denovoField);
        }
        if (!GlissadeChecks.formatIsUnambiguous(denovo)) {
            return new ValidationError("Another format's extension appears earlier in this path ("
                    + denovo + "), and glissade picks its reader from the whole path — it would "
                    + "read this file as "
                    + GlissadeChecks.glissadeDenovoFormat(denovo).toString().toLowerCase(Locale.ROOT)
                    + " and exit without writing anything. Rename the file or the folder.",
                    denovoField);
        }
        if (!GlissadeChecks.looksLikePercolator(absolutePath(psmField))) {
            return new ValidationError(percolatorMessage(), psmField);
        }
        if (!GlissadeChecks.looksLikePercolator(absolutePath(peptideField))) {
            return new ValidationError(percolatorMessage(), peptideField);
        }
        if (GlissadeChecks.denovoFormat(denovo) == GlissadeChecks.DenovoFormat.MZTAB
                && !GlissadeChecks.mzTabHasScanRefs(new File(denovo))) {
            return new ValidationError("This mzTab has no scan numbers in spectra_ref (an MGF run "
                    + "writes index=…). glissade joins on scan number, so it needs a result from "
                    + "mzML/mzXML input.", denovoField);
        }
        return null;
    }

    /** The field's text as the absolute path the command line will carry. */
    private static String absolutePath(TextField field) {
        String text = field.getText() == null ? "" : field.getText().trim();
        return text.isEmpty() ? "" : new File(text).getAbsolutePath();
    }

    private static String percolatorMessage() {
        return "glissade recognises Percolator output only by the word 'percolator' in the path "
                + "(e.g. percolator.target.psms.txt). Rename the file or its folder.";
    }

    private void launch() {
        File denovo = new File(denovoField.getText().trim());
        File psms = new File(psmField.getText().trim());
        File peptides = new File(peptideField.getText().trim());
        File fasta = new File(fastaField.getText().trim());

        outputDir = resolveOutputDir(denovo);
        if (outputDir == null) {
            status("Could not create an output folder next to the de novo file.");
            return;
        }
        File tsv = new File(outputDir, GlissadeDiscoveries.OUTPUT_FILE);
        if (tsv.isFile() && !tsv.delete()) {
            status("Could not remove the previous " + GlissadeDiscoveries.OUTPUT_FILE + ".");
            return;
        }

        // Warnings that do not block: glissade will run, but its answer needs a caveat.
        if (GlissadeChecks.hasCrlf(fasta)) {
            console("[warning] The FASTA has Windows (CRLF) line endings. glissade strips only the "
                    + "newline, so a peptide spanning a line wrap may be missed in the reference.");
        }
        if (GlissadeChecks.denovoFormat(absolutePath(denovoField))
                != GlissadeChecks.DenovoFormat.MZTAB) {
            console("[warning] glissade's tail model is hard-coded to Casanovo's profile, so a "
                    + "DeepNovo/InstaNovo result is scored with that profile.");
        }

        List<String> command = GlissadeRunner.command(glissadeExe, denovo, psms, peptides, fasta,
                bootstrapSpin.getValue());
        runStartMs = System.currentTimeMillis() - 3000L; // small clock-skew buffer
        openLog(command);

        cancelled = false;
        setRunning(true);
        allRows = List.of();
        hasResult = false;
        pi0 = OptionalDouble.empty();
        shown.clear();
        summary.setText("");
        // Clear the last run's verdict too: a failed run must not leave "No peptides at q ≤ …"
        // from the previous one standing under an empty table.
        tablePlaceholder.setText("Running glissade…");
        console(System.lineSeparator() + "$ " + String.join(" ", command));
        status("Running glissade (this can take several minutes; the fit prints nothing until it "
                + "finishes)…");

        runner.start(command, outputDir, this::onOutput,
                (exit, err) -> Platform.runLater(() -> onFinished(exit, err, tsv)));
    }

    /** {@code <de novo dir>/<name>_glissade/}, or a temp folder when that cannot be created. */
    private File resolveOutputDir(File denovo) {
        String name = GlissadeDiscoveries.outputFolderName(denovo.getName());
        File parent = denovo.getAbsoluteFile().getParentFile();
        if (parent != null) {
            File dir = new File(parent, name);
            if (dir.isDirectory() || dir.mkdirs()) {
                return dir;
            }
        }
        try {
            return Files.createTempDirectory(name).toFile();
        } catch (IOException e) {
            return null;
        }
    }

    /** Open glissade.log for the run and write its header. One handle, held until the end. */
    private void openLog(List<String> command) {
        Path runLog = outputDir.toPath().resolve("glissade.log");
        synchronized (logLock) {
            try {
                runLogWriter = Files.newBufferedWriter(runLog, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                runLogWriter = null; // the console still has it all; a log is not worth failing a run
                return;
            }
        }
        logLine("=== glissade run " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                + " ===");
        logLine("glissade: " + glissadeExe
                + GlissadeInstaller.installedRef().map(r -> " (" + GlissadeInstaller.shortRef(r) + ")")
                .orElse(""));
        logLine("$ " + String.join(" ", command));
    }

    private void logLine(String line) {
        synchronized (logLock) {
            if (runLogWriter == null) {
                return;
            }
            try {
                runLogWriter.write(line);
                runLogWriter.write(System.lineSeparator());
            } catch (IOException ignored) {
                // A log that stops being writable must not fail the run.
            }
        }
    }

    private void closeLog() {
        synchronized (logLock) {
            if (runLogWriter == null) {
                return;
            }
            try {
                runLogWriter.close();
            } catch (IOException ignored) {
                // nothing useful to do at the end of a run
            }
            runLogWriter = null;
        }
    }

    /**
     * One chunk of glissade output, on the runner thread: the file write and the parse happen here
     * so the FX thread only does the UI. Progress refreshes are not logged - the log wants the
     * committed lines, not every redraw of a bar.
     */
    private void onOutput(String text, boolean isTransient) {
        if (!isTransient) {
            logLine(text);
        }
        OptionalDouble p = GlissadeDiscoveries.parsePi0(text);
        Platform.runLater(() -> {
            console(text, isTransient);
            if (p.isPresent()) {
                pi0 = p;
                status(String.format(Locale.ROOT,
                        "Fitting done (inferred π0 = %.3f); writing results…", p.getAsDouble()));
            }
        });
    }

    private void onFinished(int exit, Throwable error, File tsv) {
        closeLog();
        setRunning(false);
        // Every path below except the successful one leaves the table empty with no result behind
        // it; the successful one repaints this from applyCutoff().
        tablePlaceholder.setText(placeholderText(cutoffValue()));
        if (cancelled) {
            status("glissade stopped.");
            if (tsv.isFile() && !tsv.delete()) {
                console("[warning] Could not remove the partial " + tsv.getName() + ".");
            }
            return;
        }
        if (error != null) {
            status("glissade could not start.");
            alert(Alert.AlertType.ERROR, "glissade failed", String.valueOf(error.getMessage()));
            return;
        }
        if (exit != 0) {
            status("glissade failed (exit " + exit + ") — see the console.");
            return;
        }
        // glissade prints "Unsupported … file format" and exits 0 without writing anything, so a
        // zero exit alone is not success: the output file must be there and fresh.
        if (!tsv.isFile() || tsv.lastModified() < runStartMs) {
            status("glissade produced no output — check the console (unsupported input format?).");
            return;
        }
        try {
            allRows = GlissadeDiscoveries.read(tsv);
            hasResult = true;
        } catch (IOException e) {
            status("Could not read " + tsv.getName() + ": " + e.getMessage());
            return;
        }
        applyCutoff();
        status("Done. " + shown.size() + " peptides at q ≤ " + cutoffSpin.getValue() + ".");
    }

    private void applyCutoff() {
        double cutoff = cutoffValue();
        shown.setAll(GlissadeDiscoveries.atOrBelow(allRows, cutoff));
        TableUtils.autoSizeColumns(table, 60);
        tablePlaceholder.setText(placeholderText(cutoff));
        if (allRows.isEmpty()) {
            summary.setText("");
            return;
        }
        String pi = pi0.isPresent()
                ? String.format(Locale.ROOT, " · inferred π0 = %.3f", pi0.getAsDouble())
                : "";
        summary.setText(shown.size() + " of " + allRows.size() + " peptides at q ≤ " + cutoff
                + pi + " · output: "
                + new File(outputDir, GlissadeDiscoveries.OUTPUT_FILE).getAbsolutePath());
    }

    /**
     * The empty-table message for the current state: nothing run yet, a run that returned nothing
     * at all, or -- the usual case on real data -- a result whose q-values all sit above the
     * cutoff, where the fix is to raise it rather than to run again.
     */
    private String placeholderText(double cutoff) {
        if (!hasResult) {
            return "Run glissade to list de novo peptides with their q-values.";
        }
        if (allRows.isEmpty()) {
            return "glissade reported no de novo peptides for these inputs.";
        }
        return String.format(Locale.ROOT,
                "No peptides at q ≤ %s. glissade scored %,d, the best at q = %s — "
                        + "raise the cutoff to see them.",
                cutoff, allRows.size(), formatQ(bestQ()));
    }

    /** The cutoff in force, falling back to the default while the editable spinner is mid-edit. */
    private double cutoffValue() {
        return cutoffSpin.getValue() == null ? DEFAULT_Q_CUTOFF : cutoffSpin.getValue();
    }

    /** The smallest q-value in the result; the table is sorted by score, not by q. */
    private double bestQ() {
        return allRows.stream().mapToDouble(GlissadeDiscoveries.Row::q).min().orElse(Double.NaN);
    }

    private void stop() {
        cancelled = true;
        status(installing ? "The install must finish; the run will not start." : "Stopping…");
        runner.cancel();
    }

    private void setRunning(boolean r) {
        fdrRunning.set(r);
        runButton.setDisable(r);
        stopButton.setDisable(!r);
        installButton.setDisable(r);
        for (Node n : new Node[]{denovoField, psmField, peptideField, fastaField,
                denovoBrowse, psmBrowse, peptideBrowse, fastaBrowse, bootstrapSpin}) {
            n.setDisable(r);
        }
        sharedProgress.setVisible(r);
        if (r) {
            sharedProgress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        }
    }

    /** A committed console line (a warning, the echoed command line). */
    private void console(String line) {
        console(line, false);
    }

    /**
     * One chunk of console output. A transient chunk is a carriage-return progress refresh: it
     * overwrites the previous one instead of being committed, which is what keeps a bootstrap
     * progress bar from filling the console with thousands of lines and trimming the real output
     * out of it.
     */
    private void console(String line, boolean isTransient) {
        consoleUsed = true;
        consoleOut.accept(line, isTransient);
    }

    /**
     * Whether this pane has written anything to the shared console. MainApp keeps the console
     * attached while this is true, so "see the console" after a failed run still points at
     * something the user can read.
     */
    public boolean hasConsoleOutput() {
        return consoleUsed;
    }

    private void status(String message) {
        sharedStatus.setText(message);
    }

    private void alert(Alert.AlertType type, String title, String message) {
        Alert a = new Alert(type, message, ButtonType.OK);
        a.setTitle(title);
        a.setHeaderText(null);
        a.initOwner(owner);
        a.showAndWait();
    }
}
