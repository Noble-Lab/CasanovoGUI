package org.casanovo.gui.ui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.scene.input.MouseButton;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.casanovo.gui.core.MzTabScores;
import org.casanovo.gui.core.TimsTof;
import org.casanovo.gui.core.PdvController;
import org.casanovo.gui.core.PepMapLauncher;
import org.casanovo.gui.core.Peptides;
import org.casanovo.gui.core.Settings;
import org.casanovo.gui.core.UniProtClient;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Analysis tab (not a Casanovo command): maps the peptide sequences from a Casanovo mzTab to a
 * reference protein FASTA by running the external <b>pepmap</b> jar as a subprocess, then presents
 * the result across several views (protein-level table, per-protein detail + coverage, shared and
 * unmapped peptides, and an overview). The mzTab + FASTA inputs sit on top, the result tabs fill
 * the center, and the mapping options sit on the right.
 */
public class ViewPane extends BorderPane {

    /** One row of pepmap's {@code peptide_map_detail.txt}. */
    public record MapRow(String peptide, String peptideOnProtein, String protein,
                         String start, String end, String preAa, String postAa) {
    }

    /**
     * A position where the de novo peptide differs from the protein substring it matched.
     * {@code index} is 1-based into the peptide; {@code equivalence} is true for an allowed I/L
     * difference (under {@code -i2l}) or an X wildcard, false for a true substitution.
     */
    public record Mismatch(int index, char query, char protein, boolean equivalence) {
    }

    /**
     * Protein-level aggregate. {@code coverage} is null when the protein is not found in the FASTA.
     * {@code mismatchPeptides} counts peptides that reach this protein only with ≥1 substitution.
     */
    private record ProteinRow(String protein, int totalPeptides, int uniquePeptides, int mismatchPeptides,
                              int totalPsms, int uniquePsms, Double coverage) {
    }

    /** One peptide mapped to the currently-selected protein. {@code unique} = maps to one protein only. */
    private record PeptideRow(String peptide, int start, int end, String preAa, String postAa,
                              int psms, boolean unique, double bestScore, int mismatches, int bestMatch) {
    }

    /** A peptide that mapped to one or more proteins. {@code minMismatches} = fewest substitutions across them. */
    private record MappedRow(String peptide, int proteinCount, String proteins, int psms,
                             double bestScore, int minMismatches) {
    }

    /** A de novo peptide that mapped to no protein. */
    private record UnmappedRow(String peptide, int psms, int length, double bestScore) {
    }

    /** Everything derived from one completed mapping; computed off-thread, applied on the FX thread. */
    private record Results(List<ProteinRow> proteins,
                           Map<String, List<MapRow>> rowsByProtein,
                           Map<String, List<MapRow>> rowsByPeptide,
                           Map<String, Set<String>> proteinsByPeptide,
                           Map<String, Integer> psmByPeptide,
                           Map<String, Integer> minMismatchByPeptide,
                           Map<String, String> seqByProtein,
                           boolean i2l,
                           int mismatches,
                           List<MappedRow> mapped,
                           List<UnmappedRow> unmapped,
                           int totalPeptides, int mappedPeptides,
                           int totalPsms, int mappedPsms, int uniquePeptides,
                           double[] cutoffs, int[] mappedByCutoff, int[] totalByCutoff) {
    }

    private static final String UNIQUE_HEX = "#1F6FB2"; // blue   – unique-peptide coverage (blue/orange is colorblind-safe)
    private static final String SHARED_HEX = "#E08000"; // orange – shared-peptide coverage
    private static final Color UNIQUE_COLOR = Color.web(UNIQUE_HEX);
    private static final Color SHARED_COLOR = Color.web(SHARED_HEX);
    private static final String MISMATCH_HEX = "#C0392B"; // red – a de novo↔protein substitution site
    private static final Color MISMATCH_COLOR = Color.web(MISMATCH_HEX);
    private static final Font MONO = Font.font("Monospaced", 13);
    private static final Font MONO_BOLD = Font.font("Monospaced", FontWeight.BOLD, 13);
    private static final int COVERAGE_WRAP = 60;
    private static final int ROW_HEIGHT = 26; // compact, consistent row height across all result tables
    private static final double PLOT_CELL_HEIGHT = 330; // fixed Overview cell height (width still resizes)

    private final Window owner;
    private final Settings settings;
    private final Consumer<String> consoleOut; // appends a line to the window's shared console
    private final PdvController pdvController;  // drives an open PDV window on peptide click (may be null)
    private final BooleanProperty mappingRunning = new SimpleBooleanProperty(false);
    private Region topInputs;   // inputs row, for sizing the console divider
    private VBox settingsBox;   // settings VBox, for sizing the console divider

    private final TextField mzTabField = new TextField();
    private final TextField fastaField = new TextField();
    private final Button mzBrowse = new Button("Browse");
    private final Button faBrowse = new Button("Browse");

    private final CheckBox i2lCheck = new CheckBox("Treat I / L as identical");
    private final Spinner<Double> scoreSpin = new Spinner<>(0.0, 1.0, 0.0, 0.05);
    private final Spinner<Integer> minLenSpin = new Spinner<>(0, 100, 0);
    private final Spinner<Integer> mismatchSpin = new Spinner<>(0, 5, 0);
    private final Spinner<Double> xShareSpin = new Spinner<>(0.0, 1.0, 0.0, 0.05);
    private final Spinner<Integer> cpusSpin = new Spinner<>(0, 256, 0);
    private final Spinner<Integer> maxMemSpin = new Spinner<>(1, 256, 4);

    private final Button runButton = new Button("Run");
    private final Button stopButton = new Button("Stop");

    // Visualization (PDV) settings — independent of the mapping parameters.
    private final CheckBox pdvVizCheck = new CheckBox("PDV");
    private final TextField pdvTolField = new TextField("0.05");
    private final ComboBox<String> pdvTolUnit = new ComboBox<>();

    private final TabPane resultTabs = new TabPane();
    private Tab proteinViewTab;

    // Overview
    private final javafx.scene.layout.GridPane statCards = new javafx.scene.layout.GridPane();
    /** Side length of each square plot cell; resizes with the Overview width, clamped to [300, 350]. */
    private final javafx.beans.property.DoubleProperty plotCellSize =
            new javafx.beans.property.SimpleDoubleProperty(350);
    private final Label overviewPlaceholder = new Label("Run a mapping to see the overview.");
    private VBox overviewChartsBox;
    private BarChart<Number, String> topChart = newTopChart();
    private javafx.scene.layout.StackPane topCell; // fixed 350x350 cell; its chart is rebuilt each run
    private final LineChart<Number, Number> cutoffChart =
            new LineChart<>(new NumberAxis(), new NumberAxis());
    private final ComboBox<String> cutoffMode = new ComboBox<>();
    private final ComboBox<String> topMode = new ComboBox<>();
    private final LineChart<Number, Number> scoreChart =
            new LineChart<>(new NumberAxis(), new NumberAxis());
    private final ComboBox<String> scoreMode = new ComboBox<>();
    private MzTabScores.Curve scoreCurve; // cumulative PSM/peptide counts vs score, from ALL mzTab PSMs
    private VBox cutoffBox; // the mapping (cutoff) plot cell — hidden when there is no mapping
    private VBox topBox;    // the top-proteins plot cell — hidden when there is no mapping
    private javafx.scene.layout.FlowPane chartsFlow; // wraps the plots; kept ≥2 columns when mapping
    private javafx.scene.layout.StackPane summaryCell; // holds the stat cards; inset to the plots' axis labels
    /** Chart plot-backgrounds already wired to re-align the cards (identity set; charts may be rebuilt). */
    private final java.util.Set<javafx.scene.Node> hookedPlots =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    // Proteins
    private final TableView<ProteinRow> proteinTable = new TableView<>();
    private final TextField proteinFilter = new TextField();
    private final TextField mappedFilter = new TextField();
    private final TextField unmappedFilter = new TextField();
    private Pager<ProteinRow> proteinPager;

    // UniProt hover popup for the Proteins table: a bare Popup shown to the right of the hovered
    // protein cell after a short delay. A Popup (not a Tooltip) is used because a Tooltip repositions
    // itself to the screen's top-left when its content is replaced asynchronously. uniProtHoverId
    // guards async content updates; the anchor is re-asserted after the fetch fills the content.
    private final javafx.stage.Popup uniProtPopup = new javafx.stage.Popup();
    private final VBox uniProtBox = new VBox(2);
    private final PauseTransition uniProtHoverDelay = new PauseTransition(Duration.millis(350));
    private String uniProtHoverId;
    private javafx.geometry.Bounds uniProtCellBounds; // screen bounds of the hovered cell, for placement

    // Protein view
    private final ComboBox<String> proteinSelector = new ComboBox<>();
    private final TableView<PeptideRow> proteinPeptideTable = new TableView<>();
    private Pager<PeptideRow> peptidePager;
    private final VBox coverageBox = new VBox(2);
    private final Label coverageLabel = new Label();

    // Shared / unmapped
    private final TableView<MappedRow> mappedTable = new TableView<>();
    private Pager<MappedRow> mappedPager;
    private final TableView<UnmappedRow> unmappedTable = new TableView<>();
    private Pager<UnmappedRow> unmappedPager;

    // Mismatch-only UI (hidden when a mapping found no mismatches): the mismatch columns + Mapped "Match" filter.
    private TableColumn<ProteinRow, Integer> proteinMismatchCol;
    private TableColumn<PeptideRow, Integer> proteinViewMismatchCol;
    private TableColumn<PeptideRow, Integer> proteinViewBestMatchCol;
    private TableColumn<MappedRow, Integer> mappedBestMatchCol;
    private ComboBox<String> matchFilter;
    private HBox matchBox;
    private Label coverageMismatchSwatch; // "mismatch site" legend swatch in the Protein-view coverage map

    private final Label sharedStatus;          // the window's shared bottom status label (same as de novo)
    private final ProgressBar sharedProgress;  // the window's shared bottom progress bar
    private final InlineValidation validation; // shared inline validation feedback (same instance as de novo)

    private volatile Process proc;
    private volatile boolean running;
    private volatile boolean cancelled;
    private Results results;
    private Map<String, MzTabScores.BestPsm> bestByPeptide = Map.of(); // bare peptide -> its best-scoring PSM
    private File loadedMzTab; // mzTab backing the current results; re-read for the per-PSM popup
    private Set<String> emptyPsmColumns = Set.of(); // PSM columns null for every PSM — hidden from the popup table

    public ViewPane(Window owner, Settings settings, Label sharedStatus, ProgressBar sharedProgress,
                    Consumer<String> consoleOut, PdvController pdvController, InlineValidation validation) {
        this.owner = owner;
        this.settings = settings;
        this.sharedStatus = sharedStatus;
        this.sharedProgress = sharedProgress;
        this.consoleOut = consoleOut;
        this.pdvController = pdvController;
        this.validation = validation;
        setPadding(new Insets(10));
        java.net.URL css = getClass().getResource("/org/casanovo/gui/settings.css");
        if (css != null) {
            getStylesheets().add(css.toExternalForm());
        }

        topInputs = buildInputs();
        setTop(topInputs);
        setCenter(buildResultTabs());
        TableUtils.enableCellCopy(proteinTable);
        TableUtils.enableCellCopy(proteinPeptideTable);
        TableUtils.enableCellCopy(mappedTable);
        TableUtils.enableCellCopy(unmappedTable);
        addAaScoreMenuItem(proteinPeptideTable, PeptideRow::peptide);
        addAaScoreMenuItem(mappedTable, MappedRow::peptide);
        addAaScoreMenuItem(unmappedTable, UnmappedRow::peptide);
        setRight(buildSettings());
        // No mapping has run yet (default mismatches = 0), so hide the mismatch columns/filter/legend; they
        // only carry data when a mapping allowed mismatches. applyResults updates this per run.
        setMismatchColumnsVisible(false);
        // Size columns to their (bold) headers now, and re-size on sort so a freshly-sorted column gets room
        // for its arrow even before any data loads. (Data loads also re-autoSize via the Pagers' setData.)
        autoSizeWithSort(proteinTable);
        autoSizeWithSort(proteinPeptideTable);
        autoSizeWithSort(mappedTable);
        autoSizeWithSort(unmappedTable);
        setRunning(false);
    }

    private VBox buildInputs() {
        Label mzLabel = new Label("Peptides (mzTab):");
        Label faLabel = new Label("Reference DB (FASTA):");
        mzLabel.setMinWidth(140);
        faLabel.setMinWidth(140);
        mzTabField.setPromptText("Required. Casanovo .mzTab result (auto-filled after a run)");
        mzTabField.getStyleClass().add("prompt-required");
        fastaField.setPromptText("Optional. Reference protein database in FASTA format");
        fastaField.getStyleClass().add("prompt-optional");
        HBox.setHgrow(mzTabField, Priority.ALWAYS);
        HBox.setHgrow(fastaField, Priority.ALWAYS);
        mzBrowse.setOnAction(e -> browseMzTab());
        faBrowse.setOnAction(e -> browseFasta());
        // Hover help on the label and the field, matching the input tooltips on the other tabs.
        Tooltip mzTip = tip("Required. The Casanovo .mzTab result to view — its de novo peptides are listed "
                + "and mapped here. Auto-filled after a sequencing run, or browse to load a previous result.");
        Tooltip.install(mzLabel, mzTip);
        Tooltip.install(mzTabField, mzTip);
        Tooltip faTip = tip("Optional. Reference protein database in FASTA format. De novo peptides are "
                + "mapped against it; leave blank to load every peptide as unmapped.");
        Tooltip.install(faLabel, faTip);
        Tooltip.install(fastaField, faTip);
        HBox mzRow = new HBox(8, mzLabel, mzTabField, mzBrowse);
        HBox faRow = new HBox(8, faLabel, fastaField, faBrowse);
        mzRow.setAlignment(Pos.CENTER_LEFT);
        faRow.setAlignment(Pos.CENTER_LEFT);
        VBox top = new VBox(6, mzRow, faRow);
        top.setPadding(new Insets(0, 0, 8, 0));
        return top;
    }

    // ---- result tabs -------------------------------------------------------

    private TabPane buildResultTabs() {
        resultTabs.getStyleClass().add("result-tabs"); // uniform top gap via settings.css
        resultTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        proteinViewTab = new Tab("Protein view", buildProteinViewTab());
        Tab overviewTab = new Tab("Overview", buildOverviewTab());
        Tab proteinsTab = new Tab("Proteins", buildProteinsTab());
        Tab mappedTab = new Tab("Mapped", buildMappedTab());
        Tab unmappedTab = new Tab("Unmapped", buildUnmappedTab());
        overviewTab.setTooltip(tip("Summary charts: PSM/peptide counts vs. peptide score, mapped peptides vs. score cutoff, and top proteins, plus overall totals (peptides, proteins, PSMs, mapping rate). Without a reference FASTA, only the score chart is shown."));
        proteinsTab.setTooltip(tip("Filterable table of reference proteins that received hits, with peptide, unique-peptide and PSM counts plus sequence-coverage %."));
        proteinViewTab.setTooltip(tip("Pick one protein to see its mapped peptides (positions, flanking residues, unique vs. shared) and a per-residue coverage map of its sequence."));
        mappedTab.setTooltip(tip("Every de novo peptide that mapped to at least one protein, with number of proteins, PSMs, best score and the matching protein list."));
        unmappedTab.setTooltip(tip("De novo peptides that did not map to any protein in the FASTA."));
        resultTabs.getTabs().addAll(
                overviewTab,
                proteinsTab,
                proteinViewTab,
                mappedTab,
                unmappedTab);
        return resultTabs;
    }

    private ScrollPane buildOverviewTab() {
        statCards.setHgap(10);
        statCards.setVgap(10);
        // No padding here: the enclosing cell carries the inset that lines the grid up with the plots'
        // axis labels (syncSummaryToPlotArea); the grid then stretches to fill that inset rectangle so
        // all four card-block edges meet the neighbouring plots.
        statCards.setPadding(Insets.EMPTY);
        statCards.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        cutoffChart.setTitle("Mapped peptides");
        cutoffChart.setLegendVisible(false);
        cutoffChart.setAnimated(false);
        cutoffChart.setCreateSymbols(true);
        NumberAxis cutoffX = (NumberAxis) cutoffChart.getXAxis();
        cutoffX.setLabel("Score cutoff");
        cutoffX.setAutoRanging(false);
        cutoffX.setLowerBound(0.0);
        cutoffX.setUpperBound(1.0);
        cutoffX.setTickUnit(0.1);
        ((NumberAxis) cutoffChart.getYAxis()).setLabel("Mapped peptides");

        cutoffMode.getItems().setAll("Mapped peptides", "Mapping rate (%)");
        cutoffMode.setValue("Mapped peptides");
        cutoffMode.valueProperty().addListener((o, a, b) -> {
            if (results != null) {
                buildCutoffChart(results);
            }
        });
        HBox cutoffControls = new HBox(8, new Label("Show:"), cutoffMode);
        cutoffControls.setAlignment(Pos.CENTER);
        cutoffControls.setMaxWidth(Double.MAX_VALUE);
        cutoffBox = new VBox(4, cutoffControls, fixedCell(cutoffChart));

        topMode.getItems().setAll("Peptides", "Unique peptides", "PSMs", "Unique PSMs");
        topMode.setValue("Peptides");
        topMode.valueProperty().addListener((o, a, b) -> {
            if (results != null) {
                buildTopChart(results);
            }
        });
        topCell = fixedCell(topChart);
        HBox topControls = new HBox(8, new Label("Show:"), topMode);
        topControls.setAlignment(Pos.CENTER);
        topControls.setMaxWidth(Double.MAX_VALUE);
        topBox = new VBox(4, topControls, topCell);

        // Score plot (from the former View tab): cumulative PSM / peptide counts vs peptide score, over
        // ALL PSMs in the mzTab (independent of the Min peptide score setting).
        scoreChart.setLegendVisible(false);
        scoreChart.setAnimated(false);
        scoreChart.setCreateSymbols(true);
        NumberAxis scoreX = (NumberAxis) scoreChart.getXAxis();
        scoreX.setLabel("Peptide score");
        scoreX.setAutoRanging(false);
        scoreX.setLowerBound(0.0);
        scoreX.setUpperBound(1.0);
        scoreX.setTickUnit(0.1);
        ((NumberAxis) scoreChart.getYAxis()).setForceZeroInRange(true);
        scoreMode.getItems().setAll("PSMs", "Peptides");
        scoreMode.setValue("Peptides");
        scoreMode.valueProperty().addListener((o, a, b) -> {
            if (scoreCurve != null) {
                buildScoreChart();
            }
        });
        HBox scoreControls = new HBox(8, new Label("Show:"), scoreMode);
        scoreControls.setAlignment(Pos.CENTER);
        scoreControls.setMaxWidth(Double.MAX_VALUE);
        VBox scoreBox = new VBox(4, scoreControls, fixedCell(scoreChart));

        // Summary tile first, then the score plot, mapping (cutoff) plot, and top-proteins plot. A
        // FlowPane keeps them on one row when wide enough and wraps otherwise; a 2-plot minimum width
        // keeps at least two columns (a sideways scroll bar appears only if even two won't fit). When
        // there is no mapping only the summary + score tiles are shown (see applyResults).

        ComboBox<String> summaryMode = new ComboBox<>();
        summaryMode.getItems().add("Summary");
        summaryMode.setValue("Summary");
        HBox summaryControls = new HBox(8, new Label("Show:"), summaryMode);
        summaryControls.setAlignment(Pos.CENTER);
        summaryControls.setMaxWidth(Double.MAX_VALUE);
        summaryCell = fixedCellNode(statCards);
        VBox summaryBox = new VBox(4, summaryControls, summaryCell);

        chartsFlow = new javafx.scene.layout.FlowPane(2, 2, summaryBox, scoreBox, cutoffBox, topBox);
        chartsFlow.setMinWidth(2 * 300 + 12); // 2 cells + gap + slack, so ≥2 columns hold at the minimum
        // Re-align whenever any tile is (re)positioned or resized: covers first layout, window resize,
        // and the FlowPane wrapping to a different column count — which changes which plots are the
        // summary's neighbours. syncSummaryToPlotArea finds those neighbours by position, so it tracks
        // whatever plot now sits to the right / below regardless of tile order; refreshSummaryAlign also
        // wires up any newly laid-out chart so a later data change that shifts its axes re-aligns.
        for (javafx.scene.Node tile : chartsFlow.getChildrenUnmodifiable()) {
            tile.boundsInParentProperty().addListener((o, a, b) -> refreshSummaryAlign());
        }
        VBox box = new VBox(10, chartsFlow);
        box.setPadding(new Insets(2, 2, 2, 2));
        box.setVisible(false);
        box.setManaged(false);
        overviewChartsBox = box;
        // Right-click the Overview to export its four plots as a high-resolution PNG.
        Menu saveMenu = new Menu("Save image as…");
        for (int d : new int[]{150, 300, 600}) {
            final int dpi = d;
            MenuItem mi = new MenuItem(dpi + " DPI");
            mi.setOnAction(e -> saveOverviewImage(dpi));
            saveMenu.getItems().add(mi);
        }
        ContextMenu overviewMenu = new ContextMenu(saveMenu);
        box.setOnContextMenuRequested(e -> overviewMenu.show(box, e.getScreenX(), e.getScreenY()));
        // The charts consume mouse presses, so the menu's built-in auto-hide misses left-clicks inside
        // the Overview. Hide it explicitly on any non-right-button press (a filter runs before children).
        box.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            if (overviewMenu.isShowing() && e.getButton() != javafx.scene.input.MouseButton.SECONDARY) {
                overviewMenu.hide();
            }
        });
        overviewPlaceholder.setStyle("-fx-opacity: 0.55;");

        // Charts are shown only once there are results: an empty JavaFX chart ignores its size and
        // renders oversized, so until a run completes we show a placeholder instead.
        javafx.scene.layout.StackPane content = new javafx.scene.layout.StackPane(box, overviewPlaceholder);
        javafx.scene.layout.StackPane.setAlignment(box, Pos.TOP_LEFT);

        // The plots wrap/resize to the viewport; a vertical scroll bar appears when they don't all fit.
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true); // let the FlowPane wrap to the viewport instead of scrolling sideways
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        // Cell WIDTH fills the flow width; the cells keep a FIXED height (see fixedCell). So when the
        // vertical scroll bar appears/disappears it only changes widths — it can't change the content
        // height and flip the bar again, which is the flicker AS_NEEDED would otherwise cause.
        chartsFlow.widthProperty().addListener((o, a, w) -> updatePlotCellSize(w.doubleValue()));
        return scroll;
    }

    /** Right-click handler: choose a file, then export the Overview at the given scale. */
    private void saveOverviewImage(int dpi) {
        if (results == null) {
            status("Run a mapping first, then export the overview image.");
            return;
        }
        FileChooser fc = new FileChooser();
        fc.setTitle("Save overview image");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG image", "*.png"));
        fc.setInitialFileName("overview.png");
        File start = FxUtils.initialDir(mzTabField.getText());
        if (start != null) {
            fc.setInitialDirectory(start);
        }
        File chosen = fc.showSaveDialog(owner);
        if (chosen == null) {
            return;
        }
        File out = chosen.getName().toLowerCase(Locale.ROOT).endsWith(".png")
                ? chosen : new File(chosen.getParentFile(), chosen.getName() + ".png");
        try {
            exportOverview(dpi, out);
            status("Saved overview image (" + dpi + " DPI): " + out.getName());
        } catch (IOException ex) {
            status("Could not save image: " + ex.getMessage());
        }
    }

    /** Snapshot the four Overview plots to a high-resolution PNG, hiding the per-tile "Show:" selectors
        for a clean figure (chart titles stay). Vector charts → crisp at any scale. */
    private void exportOverview(int dpi, File out) throws IOException {
        List<javafx.scene.Node> hidden = new ArrayList<>();
        for (javafx.scene.Node tile : chartsFlow.getChildren()) {
            if (tile instanceof VBox vb && !vb.getChildren().isEmpty()) {
                javafx.scene.Node controls = vb.getChildren().get(0);
                if (controls.isManaged()) {
                    controls.setVisible(false);
                    controls.setManaged(false);
                    hidden.add(controls);
                }
            }
        }
        try {
            chartsFlow.applyCss();
            chartsFlow.layout();
            ImageExport.writeHiResPng(chartsFlow, dpi, ImageExport.sceneBackground(chartsFlow, Color.WHITE), out);
        } finally {
            for (javafx.scene.Node controls : hidden) {
                controls.setVisible(true);
                controls.setManaged(true);
            }
            chartsFlow.layout();
        }
    }

    /** Wrap a chart in a cell whose width fills the Overview (300–350) and whose height is fixed
        ({@link #PLOT_CELL_HEIGHT}) — the fixed height keeps a scroll-bar toggle from flickering the layout. */
    private javafx.scene.layout.StackPane fixedCell(javafx.scene.chart.Chart chart) {
        // An empty chart's natural min height is large; drop it to 0 so the cell can size the chart.
        chart.setMinSize(0, 0);
        javafx.scene.layout.StackPane cell = new javafx.scene.layout.StackPane(chart);
        cell.setMinSize(300, PLOT_CELL_HEIGHT);
        cell.setMaxSize(350, PLOT_CELL_HEIGHT);
        cell.prefWidthProperty().bind(plotCellSize);
        cell.setPrefHeight(PLOT_CELL_HEIGHT);
        return cell;
    }

    /** Wrap a node in a cell matching the chart cells: width fills the Overview (300–350), height fixed
        ({@link #PLOT_CELL_HEIGHT}). The card grid inside stretches to fill it (syncSummaryToPlotArea). */
    private javafx.scene.layout.StackPane fixedCellNode(javafx.scene.Node node) {
        javafx.scene.layout.StackPane cell = new javafx.scene.layout.StackPane(node);
        cell.setMinWidth(300);
        cell.setMaxWidth(350);
        cell.prefWidthProperty().bind(plotCellSize);
        cell.setMinHeight(PLOT_CELL_HEIGHT);
        cell.setPrefHeight(PLOT_CELL_HEIGHT);
        cell.setMaxHeight(PLOT_CELL_HEIGHT);
        return cell;
    }

    /** Set the plot cells' width to fill the Overview's flow width across its columns, clamped to [300, 350]. */
    private void updatePlotCellSize(double width) {
        if (width <= 0) {
            return;
        }
        double hgap = chartsFlow.getHgap();
        int cols = Math.max(1, (int) Math.floor((width + hgap) / (300 + hgap)));
        // Subtract a few px of slack: if the cells sum to exactly the width, pixel-snapping tips them
        // over and the FlowPane wraps to a single column.
        double size = (width - hgap * (cols - 1)) / cols - 4;
        plotCellSize.set(Math.max(300, Math.min(350, size)));
    }

    /** Bounds of a chart sub-node (its plot-background, an axis, …) expressed in the chart's own
        coordinates. The chart fills its cell, so these offsets equal the inset from the cell's edges.
        Null until the chart and the node have been laid out. */
    private static javafx.geometry.Bounds inChart(javafx.scene.chart.Chart chart, javafx.scene.Node child) {
        if (child == null || chart.getWidth() <= 0) {
            return null;
        }
        return chart.sceneToLocal(child.localToScene(child.getBoundsInLocal()));
    }

    /** Wire every laid-out chart's plot-background to re-align the cards (so a later data change that
        moves an axis re-aligns), then re-align now. Idempotent: each plot-background is hooked once.
        Backgrounds of charts that were rebuilt (e.g. the top-proteins chart) are pruned from the set so
        it doesn't grow every rebuild — and the rebuilt chart's fresh background is hooked on this call. */
    private void refreshSummaryAlign() {
        if (chartsFlow != null) {
            java.util.Set<javafx.scene.Node> current = chartsFlow.lookupAll(".chart-plot-background");
            hookedPlots.retainAll(current); // drop backgrounds of charts that have since been rebuilt/removed
            for (javafx.scene.Node bg : current) {
                if (hookedPlots.add(bg)) {
                    bg.boundsInParentProperty().addListener((o, a, b) -> syncSummaryToPlotArea());
                }
            }
        }
        syncSummaryToPlotArea();
    }

    /** Inset the summary cell so the (stretched) card grid lines its four edges up with the neighbouring
        plots: top with the score plot's data top (below its title) and bottom with that plot's x-axis
        label; left with the column-neighbour's y-axis label and right with its data edge. */
    private void syncSummaryToPlotArea() {
        if (summaryCell == null || statCards.getChildren().isEmpty() || chartsFlow == null) {
            return;
        }
        javafx.scene.Node tile = summaryTile();
        if (tile == null) {
            return;
        }
        XYChart<?, ?> rowRef = neighbourChart(tile, false); // to the right (same row)    -> top, bottom
        XYChart<?, ?> colRef = neighbourChart(tile, true);  // directly below (same column) -> left, right
        if (rowRef == null) {
            rowRef = colRef; // nothing to the right (e.g. single column): borrow the one neighbour we have
        }
        if (colRef == null) {
            colRef = rowRef; // nothing below (e.g. single row / no mapping): take left/right from the row
        }
        if (rowRef == null) {
            return; // no neighbour laid out yet
        }
        javafx.geometry.Bounds rowPlot = inChart(rowRef, rowRef.lookup(".chart-plot-background"));
        javafx.geometry.Bounds rowXAxis = inChart(rowRef, rowRef.getXAxis());
        javafx.geometry.Bounds colPlot = inChart(colRef, colRef.lookup(".chart-plot-background"));
        javafx.geometry.Bounds colYAxis = inChart(colRef, colRef.getYAxis());
        if (rowPlot == null || rowXAxis == null || colPlot == null || colYAxis == null) {
            return;
        }
        double colW = colRef.getWidth();
        double rowH = rowRef.getHeight();
        if (colW <= 0 || rowH <= 0) {
            return;
        }
        double top = Math.max(0, rowPlot.getMinY());            // below the chart title (data area top)
        double bottom = Math.max(0, rowH - rowXAxis.getMaxY()); // below the x-axis label
        double left = Math.max(0, colYAxis.getMinX());          // at the y-axis label
        double right = Math.max(0, colW - colPlot.getMaxX());   // at the data edge (no right-hand label)
        summaryCell.setPadding(new Insets(top, right, bottom, left));
    }

    /** The summary tile: the direct child of the charts FlowPane that wraps the stat-card grid. */
    private javafx.scene.Node summaryTile() {
        javafx.scene.Node n = summaryCell;
        while (n != null && n.getParent() != chartsFlow) {
            n = n.getParent();
        }
        return n;
    }

    /** The XY chart in the managed tile immediately to the right of (same row) or directly below (same
        column) {@code tile} within the charts FlowPane, located by laid-out position so it tracks whatever
        plot currently sits there — independent of tile order or how the FlowPane has wrapped. Null if
        there is no such tile yet or it holds no XY chart. */
    private XYChart<?, ?> neighbourChart(javafx.scene.Node tile, boolean below) {
        javafx.geometry.Bounds b = tile.getBoundsInParent();
        double eps = 4.0; // tiles in a row share a top; tiles in a column share a left (same cell width)
        javafx.scene.Node best = null;
        double bestKey = Double.MAX_VALUE;
        for (javafx.scene.Node other : chartsFlow.getChildrenUnmodifiable()) {
            if (other == tile || !other.isManaged() || !other.isVisible()) {
                continue;
            }
            javafx.geometry.Bounds ob = other.getBoundsInParent();
            double key;
            if (below) {
                if (Math.abs(ob.getMinX() - b.getMinX()) > eps || ob.getMinY() <= b.getMinY() + eps) {
                    continue;
                }
                key = ob.getMinY();
            } else {
                if (Math.abs(ob.getMinY() - b.getMinY()) > eps || ob.getMinX() <= b.getMinX() + eps) {
                    continue;
                }
                key = ob.getMinX();
            }
            if (key < bestKey) { // nearest neighbour in that direction
                bestKey = key;
                best = other;
            }
        }
        javafx.scene.Node chart = best == null ? null : best.lookup(".chart");
        return chart instanceof XYChart<?, ?> xy ? xy : null;
    }

    /** A fresh, configured Top-proteins bar chart. A new one is built per run so its category axis
        never carries the previous metric's categories (which scramble the bars) or a collapsed label
        area; auto-ranging on a clean chart sizes and shows every protein-name label. */
    private static BarChart<Number, String> newTopChart() {
        BarChart<Number, String> c = new BarChart<>(new NumberAxis(), new CategoryAxis());
        c.setTitle("Top proteins");
        c.setLegendVisible(false);
        c.setAnimated(false);
        c.setCategoryGap(3);
        // A 10px font with no tick-label gap lets all 12 protein names render even at the minimum (300px)
        // cell; with the default gap, JavaFX's CategoryAxis drops the 2 tightest labels to avoid overlap.
        CategoryAxis topAxis = (CategoryAxis) c.getYAxis();
        topAxis.setTickLabelFont(javafx.scene.text.Font.font(10));
        topAxis.setTickLabelGap(0);
        c.setMinSize(0, 0);
        ((NumberAxis) c.getXAxis()).setLabel("Peptides");
        return c;
    }

    private BorderPane buildProteinsTab() {
        addProteinNameColumn(proteinTable);
        TableColumn<ProteinRow, Integer> pepGroup = new TableColumn<>("Peptides");
        headerTip(pepGroup, "Peptides", "Distinct peptide sequences mapped to this protein.", false);
        pepGroup.getColumns().add(intColumn("Total", ProteinRow::totalPeptides,
                "All distinct peptides mapped to this protein, including peptides shared with other proteins."));
        pepGroup.getColumns().add(intColumn("Unique", ProteinRow::uniquePeptides,
                "Peptides that map to only this protein."));
        proteinMismatchCol = intColumn("Mismatch", ProteinRow::mismatchPeptides,
                "Peptides that reach this protein only with one or more substitutions (no exact match here).");
        pepGroup.getColumns().add(proteinMismatchCol);
        TableColumn<ProteinRow, Integer> psmGroup = new TableColumn<>("PSMs");
        headerTip(psmGroup, "PSMs", "Peptide-spectrum matches (de novo spectra) supporting this protein.", false);
        psmGroup.getColumns().add(intColumn("Total", ProteinRow::totalPsms,
                "All PSMs from peptides mapped to this protein, including peptides shared with other proteins."));
        psmGroup.getColumns().add(intColumn("Unique", ProteinRow::uniquePsms,
                "PSMs from peptides that map to only this protein."));
        proteinTable.getColumns().add(pepGroup);
        proteinTable.getColumns().add(psmGroup);
        coverageCol(proteinTable, "Coverage", ProteinRow::coverage,
                "Percent of the protein sequence covered by mapped peptides (— when the sequence is not in the FASTA).");
        proteinTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        proteinTable.setFixedCellSize(ROW_HEIGHT);
        proteinTable.setPlaceholder(new Label("No mapping yet."));
        // Row-scoped double-click (guarded by row.isEmpty()) so double-clicking empty space below
        // the rows doesn't reopen the Protein view for a stale selection; reads the row's own item.
        proteinTable.setRowFactory(tv -> {
            TableRow<ProteinRow> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (!row.isEmpty() && e.getClickCount() == 2) {
                    proteinSelector.setValue(row.getItem().protein());
                    resultTabs.getSelectionModel().select(proteinViewTab);
                }
            });
            return row;
        });

        proteinPager = new Pager<>(proteinTable);
        proteinPager.setFilter((p, q) -> p.protein().toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)));
        wireFilter(proteinFilter, "Filter by protein…", proteinPager);
        Label proteinsHint = new Label("ⓘ Double-click a row to open it in Protein view.");
        proteinsHint.getStyleClass().add("hint");
        HBox top = new HBox(8, new Label("Filter:"), proteinFilter, proteinsHint);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(0, 0, 6, 0));

        BorderPane bp = new BorderPane(proteinTable);
        bp.setTop(top);
        bp.setBottom(proteinPager.bar);
        BorderPane.setMargin(proteinPager.bar, new Insets(6, 0, 0, 0));
        return bp;
    }

    private BorderPane buildProteinViewTab() {
        proteinSelector.valueProperty().addListener((o, a, b) -> showProtein(b));
        proteinSelector.setPrefWidth(280);

        strCol(proteinPeptideTable, "Peptide", PeptideRow::peptide, 220,
                "De novo peptide sequence (with modifications) mapped to this protein.");
        intCol(proteinPeptideTable, "Start", PeptideRow::start,
                "Position (1-based) of the peptide's first residue in the protein sequence.");
        intCol(proteinPeptideTable, "End", PeptideRow::end,
                "Position (1-based) of the peptide's last residue in the protein sequence.");
        strCol(proteinPeptideTable, "Pre", PeptideRow::preAa, 44,
                "Flanking residue in the protein immediately before the peptide (— at the protein N-terminus).");
        strCol(proteinPeptideTable, "Post", PeptideRow::postAa, 44,
                "Flanking residue in the protein immediately after the peptide (— at the protein C-terminus).");
        intCol(proteinPeptideTable, "PSMs", PeptideRow::psms,
                "Number of peptide-spectrum matches in the mzTab for this peptide.");
        dblCol(proteinPeptideTable, "Best score", PeptideRow::bestScore,
                "Highest Casanovo peptide score among this peptide's PSMs.");
        proteinViewMismatchCol = intCol(proteinPeptideTable, "Mismatches", PeptideRow::mismatches,
                mismatchesColumnTip(true)); // refreshed per-run in applyResults from the recorded I/L setting
        proteinViewBestMatchCol = intCol(proteinPeptideTable, "Best match", PeptideRow::bestMatch,
                "Fewest substitutions needed to map this peptide to any protein (0 = an exact match somewhere). "
                        + "Can be lower than Mismatches when the peptide matches another protein better.");
        strCol(proteinPeptideTable, "Mapping", pr -> pr.unique() ? "unique" : "shared", 90,
                "Whether this peptide is unique to this protein or shared across several proteins.");
        proteinPeptideTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        proteinPeptideTable.setFixedCellSize(ROW_HEIGHT);
        proteinPeptideTable.setPlaceholder(new Label("Select a protein."));
        onRowDoubleClick(proteinPeptideTable, PeptideRow::peptide);
        peptidePager = new Pager<>(proteinPeptideTable);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(8, new Label("Protein:"), proteinSelector, spacer, peptidePager.bar);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(0, 0, 6, 0));

        BorderPane pepPane = new BorderPane(proteinPeptideTable);
        // Same single-click→PDV and double-click→PSMs behaviour as the Mapped/Unmapped tables, so
        // advertise both hints here too (this table previously showed only the double-click hint).
        VBox pepHints = peptideHintsBox();
        BorderPane.setMargin(pepHints, new Insets(4, 0, 0, 2));
        pepPane.setBottom(pepHints);

        coverageBox.setPadding(new Insets(4));
        ScrollPane covScroll = new ScrollPane(coverageBox);
        covScroll.setFitToWidth(true);
        VBox covPane = new VBox(4, coverageLabel, buildLegend(), covScroll);
        VBox.setVgrow(covScroll, Priority.ALWAYS);
        covPane.setPadding(new Insets(6, 0, 0, 0));

        SplitPane split = new SplitPane(pepPane, covPane);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.5);

        BorderPane bp = new BorderPane(split);
        bp.setTop(top);
        return bp;
    }

    private BorderPane buildMappedTab() {
        strCol(mappedTable, "Peptide", MappedRow::peptide, 220,
                "De novo peptide sequence (with modifications) that mapped to at least one protein.");
        intCol(mappedTable, "# proteins", MappedRow::proteinCount,
                "How many proteins this peptide maps to.");
        intCol(mappedTable, "PSMs", MappedRow::psms,
                "Number of peptide-spectrum matches in the mzTab for this peptide.");
        dblCol(mappedTable, "Best score", MappedRow::bestScore,
                "Highest Casanovo peptide score among this peptide's PSMs.");
        mappedBestMatchCol = intCol(mappedTable, "Best match", MappedRow::minMismatches,
                "Fewest substitutions needed to map this peptide to any protein (0 = an exact match somewhere).");
        strCol(mappedTable, "Proteins", MappedRow::proteins, 380,
                "All proteins this peptide maps to; each is annotated with its substitution count when > 0.");
        mappedTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        mappedTable.setFixedCellSize(ROW_HEIGHT);
        mappedTable.setPlaceholder(new Label("No mapped peptides."));
        onRowDoubleClick(mappedTable, MappedRow::peptide);
        mappedPager = new Pager<>(mappedTable);
        mappedPager.setFilter((r, q) -> {
            String ql = q.toLowerCase(Locale.ROOT);
            return r.peptide().toLowerCase(Locale.ROOT).contains(ql)
                    || r.proteins().toLowerCase(Locale.ROOT).contains(ql);
        });
        wireFilter(mappedFilter, "Filter by peptide or protein…", mappedPager);
        matchFilter = new ComboBox<>();
        matchFilter.getItems().addAll("All", "Exact only", "With mismatches");
        matchFilter.setValue("All");
        matchFilter.valueProperty().addListener((o, a, b) -> {
            java.util.function.Predicate<MappedRow> p =
                    "Exact only".equals(b) ? r -> r.minMismatches() == 0
                            : "With mismatches".equals(b) ? r -> r.minMismatches() > 0
                            : null;
            mappedPager.setExtraFilter(p);
        });
        matchBox = new HBox(8, new Label("Match:"), matchFilter);
        matchBox.setAlignment(Pos.CENTER_LEFT);
        HBox top = new HBox(8, new Label("Filter:"), mappedFilter, matchBox);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(0, 0, 6, 0));
        BorderPane bp = new BorderPane(mappedTable);
        bp.setTop(top);
        setHintsAndPagerBottom(bp, mappedPager);
        return bp;
    }

    private BorderPane buildUnmappedTab() {
        strCol(unmappedTable, "Peptide", UnmappedRow::peptide, 240,
                "De novo peptide sequence (with modifications) that did not map to any protein in the FASTA.");
        intCol(unmappedTable, "PSMs", UnmappedRow::psms,
                "Number of peptide-spectrum matches in the mzTab for this peptide.");
        intCol(unmappedTable, "Length", UnmappedRow::length,
                "Number of amino-acid residues in the peptide.");
        dblCol(unmappedTable, "Best score", UnmappedRow::bestScore,
                "Highest Casanovo peptide score among this peptide's PSMs.");
        unmappedTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        unmappedTable.setFixedCellSize(ROW_HEIGHT);
        unmappedTable.setPlaceholder(new Label("No unmapped peptides."));
        onRowDoubleClick(unmappedTable, UnmappedRow::peptide);
        unmappedPager = new Pager<>(unmappedTable);
        unmappedPager.setFilter((r, q) -> r.peptide().toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)));
        wireFilter(unmappedFilter, "Filter by peptide…", unmappedPager);
        HBox top = new HBox(8, new Label("Filter:"), unmappedFilter);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(0, 0, 6, 0));
        BorderPane bp = new BorderPane(unmappedTable);
        bp.setTop(top);
        setHintsAndPagerBottom(bp, unmappedPager);
        return bp;
    }

    /** Wire a results-table filter field: prompt, width, and live query into the pager. */
    private static void wireFilter(TextField field, String prompt, Pager<?> pager) {
        field.setPromptText(prompt);
        field.setPrefWidth(260);
        field.textProperty().addListener((o, a, b) -> pager.setQuery(b));
    }

    /** The stacked peptide hints (single-click → PDV, double-click → PSMs) shown under a peptide table. */
    private VBox peptideHintsBox() {
        return new VBox(0, peptideHint(), pdvPeptideHint());
    }

    /** Attach a bottom bar to {@code bp}: peptide hints on the left, the pager on the right. */
    private void setHintsAndPagerBottom(BorderPane bp, Pager<?> pager) {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bottom = new HBox(8, peptideHintsBox(), spacer, pager.bar);
        bottom.setAlignment(Pos.CENTER_LEFT);
        bp.setBottom(bottom);
        BorderPane.setMargin(bottom, new Insets(6, 0, 0, 0));
    }

    /**
     * Wire a peptide table's row clicks: a single left-click drives an open PDV window to this
     * peptide's best-PSM spectrum; a double-click opens the per-residue popup. Header/empty clicks
     * are ignored.
     */
    private <T> void onRowDoubleClick(TableView<T> table, Function<T, String> peptideOf) {
        table.setRowFactory(tv -> {
            TableRow<T> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (row.isEmpty()) {
                    return;
                }
                if (e.getClickCount() == 2) {
                    showAaScores(peptideOf.apply(row.getItem()));
                } else if (e.getClickCount() == 1 && e.getButton() == MouseButton.PRIMARY) {
                    drivePdv(peptideOf.apply(row.getItem()));
                }
            });
            return row;
        });
    }

    /**
     * Ask the PDV showing the current mzTab to select this peptide's best-scoring PSM and render its
     * spectrum. Silently does nothing when no PDV is open for this result (so plain row selection is
     * unaffected) or when the peptide has no known spectra_ref.
     */
    private void drivePdv(String peptide) {
        MzTabScores.BestPsm best = (loadedMzTab == null) ? null : bestByPeptide.get(Peptides.bare(peptide));
        if (best != null) {
            drivePdvRef(best.spectraRef());
        }
    }

    /** Drive an open PDV (for the current mzTab) to the spectrum with this spectra_ref; no-op otherwise. */
    private void drivePdvRef(String spectraRef) {
        if (pdvController == null || loadedMzTab == null || spectraRef == null || spectraRef.isEmpty()
                || !pdvController.hasInstance(loadedMzTab)) {
            return;
        }
        pdvController.select(loadedMzTab, spectraRef);
    }

    /** Add a "Show per-residue confidence" entry above Copy in a peptide table's right-click menu. */
    private <T> void addAaScoreMenuItem(TableView<T> table, Function<T, String> peptideOf) {
        MenuItem item = new MenuItem("Show per-residue confidence");
        item.setOnAction(e -> {
            T r = table.getSelectionModel().getSelectedItem();
            if (r != null) {
                showAaScores(peptideOf.apply(r));
            }
        });
        ContextMenu cm = table.getContextMenu();
        if (cm == null) {
            table.setContextMenu(new ContextMenu(item));
        } else {
            cm.getItems().add(0, item);
            cm.getItems().add(1, new SeparatorMenuItem());
        }
    }

    /** A muted one-line hint pointing users to the double-click per-residue/PSMs popup. */
    private static Label peptideHint() {
        Label l = new Label("ⓘ Double-click a peptide to see its PSMs and per-residue confidence.");
        l.getStyleClass().add("hint");
        return l;
    }

    /**
     * A muted one-line hint about single-click PDV spectrum annotation. Only visible while the PDV
     * checkbox is selected.
     */
    private Label pdvPeptideHint() {
        Label l = new Label("ⓘ Single-click a peptide to show the annotated spectrum of its best PSM in PDV.");
        l.getStyleClass().add("hint");
        l.visibleProperty().bind(pdvVizCheck.selectedProperty());
        l.managedProperty().bind(pdvVizCheck.selectedProperty());
        return l;
    }

    private HBox buildLegend() {
        coverageMismatchSwatch = swatch(MISMATCH_HEX, "mismatch site");
        HBox h = new HBox(18, swatch(UNIQUE_HEX, "unique"), swatch(SHARED_HEX, "shared"),
                coverageMismatchSwatch);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private static Label swatch(String hex, String text) {
        Label l = new Label("■ " + text);
        l.setStyle("-fx-text-fill: " + hex + ";");
        return l;
    }

    /** Client-side pagination for a TableView: 100 rows/page, with sort + optional filter over the full data. */
    private static final class Pager<T> {
        private static final int PAGE_SIZE = 100;
        private final TableView<T> table;
        private final ObservableList<T> model = FXCollections.observableArrayList();
        private final ObservableList<T> view = FXCollections.observableArrayList();
        private final List<T> sorted = new ArrayList<>();   // model in the current sort order
        private final List<T> working = new ArrayList<>();  // sorted, then filtered
        private final Button prev = new Button("‹ Prev");
        private final Button next = new Button("Next ›");
        private final Label info = new Label();
        private final HBox bar;
        private BiPredicate<T, String> filter;
        private java.util.function.Predicate<T> extraFilter; // optional class filter, ANDed with the text filter
        private String query = "";
        private int page;

        Pager(TableView<T> table) {
            this.table = table;
            table.setItems(view);
            // Re-sort the full data (not just the visible page) on every sort change. A sort POLICY is
            // used rather than a comparatorProperty listener: toggling a column to descending changes
            // its sort type without changing the sort-order list, which a comparator listener misses —
            // so descending sorts would silently not re-page.
            table.setSortPolicy(tv -> {
                page = 0;
                resort();
                return true;
            });
            prev.setOnAction(e -> {
                page--;
                showPage();
            });
            next.setOnAction(e -> {
                page++;
                showPage();
            });
            info.setMinWidth(150);
            bar = new HBox(8, prev, next, info);
            bar.setAlignment(Pos.CENTER_LEFT);
            resort();
        }

        void setFilter(BiPredicate<T, String> f) {
            this.filter = f;
        }

        void setExtraFilter(java.util.function.Predicate<T> p) {
            this.extraFilter = p;
            page = 0;
            applyFilter();
        }

        void setData(List<T> data) {
            model.setAll(data);
            page = 0;
            resort();
            TableUtils.autoSizeColumns(table, 60); // fit columns to this page's content, capped at 60 chars
        }

        void setQuery(String q) {
            query = q == null ? "" : q.trim();
            page = 0;
            applyFilter();
        }

        /** Re-sort the full data — call when the data or the sort column/direction changes. */
        private void resort() {
            sorted.clear();
            sorted.addAll(model);
            Comparator<T> c = table.getComparator();
            if (c != null) {
                sorted.sort(c);
            }
            applyFilter();
        }

        /** Re-filter the already-sorted data — call when the query/filter changes. Filtering a
         *  sorted list preserves the order, so no re-sort is needed here. */
        private void applyFilter() {
            working.clear();
            for (T t : sorted) {
                boolean classOk = extraFilter == null || extraFilter.test(t);
                boolean textOk = filter == null || query.isEmpty() || filter.test(t, query);
                if (classOk && textOk) {
                    working.add(t);
                }
            }
            showPage();
        }

        /** Slice the current page out of the filtered+sorted data — call on page navigation. */
        private void showPage() {
            int total = working.size();
            int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
            page = Math.max(0, Math.min(page, pages - 1));
            int from = page * PAGE_SIZE;
            int to = Math.min(from + PAGE_SIZE, total);
            view.setAll(working.subList(from, to));
            info.setText(total == 0 ? "0 rows"
                    : String.format(Locale.US, "%,d–%,d of %,d  ·  page %d/%d", from + 1, to, total, page + 1, pages));
            prev.setDisable(page <= 0);
            next.setDisable(page >= pages - 1);
        }
    }

    // ---- per-protein selection: peptide table + coverage -------------------

    private void showProtein(String protein) {
        coverageBox.getChildren().clear();
        coverageLabel.setText("");
        if (protein == null || results == null) {
            peptidePager.setData(List.of());
            return;
        }
        List<MapRow> rows = results.rowsByProtein().getOrDefault(protein, List.of());
        List<PeptideRow> peps = new ArrayList<>();
        for (MapRow r : rows) {
            String key = bare(r.peptide());
            boolean unique = results.proteinsByPeptide().getOrDefault(key, Set.of()).size() <= 1;
            MzTabScores.BestPsm best = bestByPeptide.get(key);
            peps.add(new PeptideRow(r.peptide(), parseIntSafe(r.start()), parseIntSafe(r.end()),
                    r.preAa(), r.postAa(), results.psmByPeptide().getOrDefault(key, 0), unique,
                    best != null ? best.score() : Double.NaN, substitutions(r, results.i2l()),
                    results.minMismatchByPeptide().getOrDefault(key, 0)));
        }
        peptidePager.setData(peps);
        renderCoverage(protein);
    }

    /** Double-click a peptide row -> pop its per-residue chart plus a table of all its PSMs. */
    private void showAaScores(String peptide) {
        String bare = Peptides.bare(peptide);
        if (loadedMzTab == null || !loadedMzTab.isFile()) {
            status("Source mzTab is not available for per-residue scores.");
            return;
        }
        MzTabScores.PsmTable psms;
        try {
            psms = MzTabScores.readPsmRowsForPeptide(loadedMzTab, bare);
        } catch (java.io.IOException e) {
            status("Could not read PSMs for " + peptide + ": " + e.getMessage());
            return;
        }
        if (psms.rows().isEmpty()) {
            status("No PSMs found for " + peptide + ".");
            return;
        }
        int refIdx = -1;
        for (int i = 0; i < psms.columns().size(); i++) {
            if (psms.columns().get(i).equalsIgnoreCase("spectra_ref")) {
                refIdx = i;
                break;
            }
        }
        final int refCol = refIdx;
        AaScorePopup.show(owner, bare, psms.columns(), psms.rows(), emptyPsmColumns, row -> {
            if (refCol >= 0 && refCol < row.values().length) {
                drivePdvRef(row.values()[refCol]);
            }
        }, pdvVizCheck.isSelected(), proteinMatchesFor(bare), results.i2l());
    }

    /** Build the per-residue alignment(s) of {@code bare} against each distinct protein substring it matched. */
    private List<AaScorePopup.ProteinMatch> proteinMatchesFor(String bare) {
        if (results == null) {
            return List.of();
        }
        List<MapRow> rows = results.rowsByPeptide().getOrDefault(bare, List.of());
        boolean i2l = results.i2l();
        LinkedHashMap<String, LinkedHashSet<String>> byOnProtein = new LinkedHashMap<>();
        for (MapRow r : rows) {
            String op = r.peptideOnProtein() == null ? "" : r.peptideOnProtein().trim().toUpperCase(Locale.ROOT);
            if (!op.isEmpty()) {
                byOnProtein.computeIfAbsent(op, k -> new LinkedHashSet<>()).add(r.protein());
            }
        }
        List<AaScorePopup.ProteinMatch> out = new ArrayList<>();
        for (Map.Entry<String, LinkedHashSet<String>> en : byOnProtein.entrySet()) {
            String op = en.getKey();
            int[] cls = new int[op.length()];
            int sub = 0;
            for (int i = 0; i < op.length() && i < bare.length(); i++) {
                char a = bare.charAt(i);
                char b = op.charAt(i);
                if (a == b) {
                    cls[i] = 0;
                } else if ((i2l && isIL(a) && isIL(b)) || a == 'X' || b == 'X') {
                    cls[i] = 2;
                } else {
                    cls[i] = 1;
                    sub++;
                }
            }
            out.add(new AaScorePopup.ProteinMatch(op, cls, new ArrayList<>(en.getValue()), sub));
        }
        out.sort(Comparator.comparingInt(AaScorePopup.ProteinMatch::substitutions));
        return out;
    }

    private void renderCoverage(String protein) {
        String seq = results.seqByProtein().get(protein);
        if (seq == null || seq.isEmpty()) {
            coverageLabel.setText("Coverage: protein sequence not found in the FASTA.");
            return;
        }
        List<MapRow> protRows = results.rowsByProtein().getOrDefault(protein, List.of());
        int[] cat = coverageCategories(protRows, results.proteinsByPeptide(), seq);
        // Substitution sites: protein positions where a mapped peptide's de novo residue disagrees.
        String[] subSite = new String[seq.length()]; // de novo residue(s) read here, or null
        for (MapRow r : protRows) {
            int s = parseIntSafe(r.start());
            if (s <= 0) {
                continue;
            }
            for (Mismatch m : mismatchesOf(r, results.i2l())) {
                if (m.equivalence()) {
                    continue; // I/L and X are not substitutions
                }
                int idx = s + m.index() - 2; // 0-based protein index
                if (idx >= 0 && idx < seq.length()) {
                    String prev = subSite[idx];
                    String q = String.valueOf(m.query());
                    subSite[idx] = prev == null ? q : (prev.contains(q) ? prev : prev + "/" + q);
                }
            }
        }
        int covered = 0;
        int subs = 0;
        for (int i = 0; i < cat.length; i++) {
            if (cat[i] > 0) {
                covered++;
            }
            if (subSite[i] != null) {
                subs++;
            }
        }
        coverageLabel.setText(String.format(Locale.US,
                "Coverage: %.1f%%  (%d / %d residues)%s", 100.0 * covered / seq.length(), covered, seq.length(),
                subs > 0 ? "  ·  " + subs + " mismatch site" + (subs == 1 ? "" : "s") : ""));

        // Uncovered residues and the position ruler must stay legible on dark themes; pick their
        // greys from the active theme, mirroring AaScoreChart.paint's posColor/tickColor selection.
        boolean dark = Themes.isDark();
        Color plainColor = dark ? Color.web("#b0b0b0") : Color.web("#444444");
        Color rulerColor = dark ? Color.web("#a0a0a0") : Color.web("#888888");
        for (int start = 0; start < seq.length(); start += COVERAGE_WRAP) {
            int end = Math.min(start + COVERAGE_WRAP, seq.length());
            TextFlow line = new TextFlow();
            Text pos = new Text(String.format("%6d  ", start + 1));
            pos.setFont(MONO);
            pos.setFill(rulerColor);
            line.getChildren().add(pos);
            int i = start;
            while (i < end) {
                if (subSite[i] != null) {
                    Text t = new Text(String.valueOf(seq.charAt(i)));
                    t.setFont(MONO_BOLD);
                    t.setFill(MISMATCH_COLOR);
                    t.setUnderline(true);
                    Tooltip.install(t, new Tooltip("Position " + (i + 1) + ": protein " + seq.charAt(i)
                            + ", de novo read " + subSite[i]));
                    line.getChildren().add(t);
                    i++;
                    continue;
                }
                int c = cat[i];
                int j = i;
                while (j < end && cat[j] == c && subSite[j] == null) {
                    j++;
                }
                Text t = new Text(seq.substring(i, j));
                t.setFont(c > 0 ? MONO_BOLD : MONO);
                t.setFill(c == 2 ? UNIQUE_COLOR : c == 1 ? SHARED_COLOR : plainColor);
                line.getChildren().add(t);
                i = j;
            }
            coverageBox.getChildren().add(line);
        }
    }

    /** Per-residue coverage: 0 = uncovered, 1 = shared-peptide, 2 = unique-peptide (takes priority). */
    private static int[] coverageCategories(List<MapRow> rows, Map<String, Set<String>> proteinsByPeptide,
                                            String seq) {
        int len = seq.length();
        int[] cat = new int[len];
        for (MapRow r : rows) {
            int s = parseIntSafe(r.start());
            int e = parseIntSafe(r.end());
            if (s <= 0 || e <= 0) {
                continue;
            }
            int val = proteinsByPeptide.getOrDefault(bare(r.peptide()), Set.of()).size() <= 1 ? 2 : 1;
            int from = Math.max(1, s) - 1;
            int to = Math.min(len, e);
            for (int i = from; i < to; i++) {
                if (cat[i] < val) {
                    cat[i] = val;
                }
            }
        }
        return cat;
    }

    private static Double coverage(List<MapRow> rows, Map<String, Set<String>> proteinsByPeptide, String seq) {
        if (seq == null || seq.isEmpty()) {
            return null;
        }
        int[] cat = coverageCategories(rows, proteinsByPeptide, seq);
        int covered = 0;
        for (int c : cat) {
            if (c > 0) {
                covered++;
            }
        }
        return 100.0 * covered / seq.length();
    }

    // ---- table column helpers ----------------------------------------------

    /**
     * Give {@code col} a header that shows {@code description} on hover. JavaFX has no
     * {@code TableColumn.setTooltip}, so the title is moved into a {@link Label} (carrying the
     * tooltip) used as the column's header graphic. {@code rightAligned} keeps numeric headers
     * right-aligned to match their right-aligned cells; the compact-header CSS still applies to the
     * label.
     */
    private static <S, T> void headerTip(TableColumn<S, T> col, String name, String description, boolean rightAligned) {
        Label header = new Label(name);
        header.setTooltip(tip(description));
        header.setMaxWidth(Double.MAX_VALUE); // fill the header so alignment within it takes effect
        header.setAlignment(rightAligned ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        col.setGraphic(header);
        col.setText(null); // avoid showing the title twice (text + graphic)
        // At first open (no data) size every column snugly to its header text plus cell insets. No sort
        // arrow is shown until a column is sorted, and by then the table has data and the Pagers have
        // re-run TableUtils.autoSizeColumns (which adds arrow padding), so we don't reserve arrow space
        // here. prefWidth overrides the data-oriented widths set above; minWidth keeps headers from
        // clipping. Harmless on group columns (their width is the sum of their leaves).
        javafx.scene.text.Text probe = new javafx.scene.text.Text(name);
        probe.setFont(javafx.scene.text.Font.font(13));
        double headerWidth = probe.getLayoutBounds().getWidth() * 1.15 + 20;
        col.setMinWidth(headerWidth);
        col.setPrefWidth(headerWidth);
    }

    /** Size a table's columns to their (bold) headers now, and again whenever its sort order changes — so a
        freshly-sorted column gets room for its sort arrow while unsorted columns stay snug to their names. */
    private static <S> void autoSizeWithSort(TableView<S> table) {
        TableUtils.autoSizeColumns(table, 60);
        table.getSortOrder().addListener((javafx.beans.Observable o) -> TableUtils.autoSizeColumns(table, 60));
    }

    /** Tooltip for the Protein-view "Mismatches" column — what's excluded depends on the I/L setting. */
    private static String mismatchesColumnTip(boolean i2l) {
        return "Substitutions between this peptide and the protein here (" + (i2l ? "I/L and X" : "X")
                + " equivalences excluded). Double-click the peptide to see them aligned against its confidence.";
    }

    private static <S> void strCol(TableView<S> table, String name, Function<S, String> getter, double prefW, String tip) {
        TableColumn<S, String> col = new TableColumn<>(name);
        col.setCellValueFactory(d -> new ReadOnlyStringWrapper(nullToEmpty(getter.apply(d.getValue()))));
        if (prefW > 0) {
            col.setPrefWidth(prefW);
        }
        headerTip(col, name, tip, false);
        table.getColumns().add(col);
    }

    /**
     * The "Protein" column for the Proteins table: a plain text column, but a UniProt-format
     * identifier gets a hover tooltip with its UniProt entry info, fetched lazily on hover and
     * cached. The lookup is gated by the View-menu toggle ({@link Settings#isUniProtLookup()}),
     * read live at hover time so the toggle takes effect immediately.
     */
    private void addProteinNameColumn(TableView<ProteinRow> table) {
        configureUniProtPopup();
        TableColumn<ProteinRow, String> col = new TableColumn<>("Protein");
        col.setCellValueFactory(d -> new ReadOnlyStringWrapper(nullToEmpty(d.getValue().protein())));
        col.setPrefWidth(240);
        headerTip(col, "Protein",
                "Reference protein identifier (from the FASTA header) that received at least one peptide hit. "
                        + "Hover a UniProt-format ID for its UniProt entry (toggle in the View menu).", false);
        col.setCellFactory(c -> {
            TableCell<ProteinRow, String> cell = new TableCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null || item.isEmpty() ? null : item);
                }
            };
            cell.setOnMouseEntered(e -> onProteinHoverEnter(cell));
            cell.setOnMouseExited(e -> onProteinHoverExit());
            return cell;
        });
        table.getColumns().add(col);
    }

    /** One-time setup of the shared UniProt popup (styled container, anchor policy). */
    private void configureUniProtPopup() {
        uniProtBox.setMaxWidth(420);
        uniProtBox.setStyle(
                "-fx-background-color: #2f3033;"
                        + "-fx-background-radius: 6;"
                        + "-fx-border-color: rgba(255,255,255,0.18);"
                        + "-fx-border-radius: 6;"
                        + "-fx-padding: 8 11 8 11;"
                        + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.4), 10, 0, 0, 2);");
        uniProtPopup.getContent().add(uniProtBox);
        // autoFix OFF: near the right edge JavaFX would otherwise shift the popup left onto the cursor,
        // which fires the cell's mouse-exit and makes the popup flash out. We place it ourselves
        // (right / below / left of the cell) in placeUniProtPopup so it never covers the cursor.
        uniProtPopup.setAutoFix(false);
        uniProtPopup.setAutoHide(false); // hidden explicitly on mouse-exit
        uniProtPopup.setAnchorLocation(javafx.stage.PopupWindow.AnchorLocation.WINDOW_TOP_LEFT);
    }

    /** Start the hover delay to show the UniProt popup for a UniProt-format protein cell. */
    private void onProteinHoverEnter(TableCell<ProteinRow, String> cell) {
        String id = cell.getItem();
        if (id == null || id.isEmpty() || !settings.isUniProtLookup()
                || UniProtClient.parse(id).isEmpty()) {
            return;
        }
        uniProtHoverDelay.stop();
        uniProtHoverDelay.setOnFinished(ev -> showUniProtPopup(cell, id));
        uniProtHoverDelay.playFromStart();
    }

    /** Cancel any pending hover and hide the popup when the pointer leaves a protein cell. */
    private void onProteinHoverExit() {
        uniProtHoverDelay.stop();
        uniProtHoverId = null;
        uniProtPopup.hide();
    }

    /**
     * Show the UniProt popup just past the right edge of {@code cell}, top-aligned to it. The anchor
     * is captured and re-asserted after the async fetch fills the content, so the popup never drifts
     * (a Tooltip would jump to the screen's top-left on content change). Content comes from the
     * cache, else a background fetch.
     */
    private void showUniProtPopup(TableCell<ProteinRow, String> cell, String id) {
        if (!id.equals(cell.getItem())) {
            return; // pointer moved on / cell recycled during the delay
        }
        javafx.geometry.Bounds b = cell.localToScreen(cell.getBoundsInLocal());
        if (b == null) {
            return;
        }
        uniProtHoverId = id;
        uniProtCellBounds = b;
        UniProtClient.Entry hit = UniProtClient.peek(id);
        if (hit != null) {
            setUniProtContent(hit);
        } else if (UniProtClient.knownMissing(id)) {
            setUniProtContent(null);
        } else {
            setUniProtMessage("Looking up " + id + " on UniProt…");
            UniProtClient.fetchAsync(id, entry -> Platform.runLater(() -> {
                if (id.equals(uniProtHoverId) && uniProtPopup.isShowing()) {
                    setUniProtContent(entry);
                    scheduleUniProtPlacement(); // content resized -> re-place
                }
            }));
        }
        if (uniProtPopup.isShowing()) {
            uniProtPopup.hide();
        }
        // Provisional position (just right of the cell); corrected once the popup is laid out — see
        // placeUniProtPopup, which flips it below/left when the right edge runs off-screen.
        uniProtPopup.show(cell, b.getMaxX() + 4, b.getMinY());
        scheduleUniProtPlacement();
    }

    private void scheduleUniProtPlacement() {
        Platform.runLater(this::placeUniProtPopup);
    }

    /**
     * Position the popup so it never overlaps the hovered cell — right of it when there is room, else
     * below it, else to its left. Keeping it off the cell means it can't land under the cursor and
     * trigger the cell's mouse-exit (which made it flash out near the screen's right edge).
     */
    private void placeUniProtPopup() {
        if (!uniProtPopup.isShowing() || uniProtCellBounds == null) {
            return;
        }
        javafx.geometry.Bounds cell = uniProtCellBounds;
        double w = uniProtPopup.getWidth();
        double h = uniProtPopup.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        javafx.geometry.Rectangle2D screen = screenFor(cell);
        double gap = 4;
        double x;
        double y;
        if (cell.getMaxX() + gap + w <= screen.getMaxX()) {
            x = cell.getMaxX() + gap;      // right of the cell (preferred)
            y = cell.getMinY();
        } else if (cell.getMaxY() + gap + h <= screen.getMaxY()) {
            x = cell.getMinX();            // below the cell
            y = cell.getMaxY() + gap;
        } else if (cell.getMinX() - gap - w >= screen.getMinX()) {
            x = cell.getMinX() - gap - w;  // left of the cell
            y = cell.getMinY();
        } else {
            x = cell.getMinX();            // last resort
            y = cell.getMaxY() + gap;
        }
        // Clamp only the axis that is already clear of the cell, so this can't pull the popup back over it.
        x = Math.max(screen.getMinX(), Math.min(x, screen.getMaxX() - w));
        y = Math.max(screen.getMinY(), Math.min(y, screen.getMaxY() - h));
        uniProtPopup.setAnchorX(x);
        uniProtPopup.setAnchorY(y);
    }

    /** The visual bounds of the screen the cell sits on (falls back to the primary screen). */
    private static javafx.geometry.Rectangle2D screenFor(javafx.geometry.Bounds cell) {
        for (javafx.stage.Screen s : javafx.stage.Screen.getScreensForRectangle(
                cell.getMinX(), cell.getMinY(), Math.max(1, cell.getWidth()), Math.max(1, cell.getHeight()))) {
            return s.getVisualBounds();
        }
        return javafx.stage.Screen.getPrimary().getVisualBounds();
    }

    private void setUniProtMessage(String message) {
        uniProtBox.getChildren().setAll(uniProtLabel(message, "#dddddd", 12, false, false));
    }

    /** Render a UniProt {@link UniProtClient.Entry} (or a "not found" note) into the popup. */
    private void setUniProtContent(UniProtClient.Entry e) {
        if (e == null) {
            setUniProtMessage("No UniProt info available.");
            return;
        }
        java.util.List<javafx.scene.Node> rows = new java.util.ArrayList<>();
        rows.add(uniProtLabel(e.proteinName().isEmpty() ? e.accession() : e.proteinName(),
                "#ffffff", 13, true, true));
        StringBuilder meta = new StringBuilder();
        if (!e.gene().isEmpty()) {
            meta.append("Gene ").append(e.gene());
        }
        if (!e.organism().isEmpty()) {
            meta.append(meta.length() > 0 ? "  ·  " : "").append(e.organism());
        }
        if (e.length() > 0) {
            meta.append(meta.length() > 0 ? "  ·  " : "").append(e.length()).append(" aa");
        }
        if (meta.length() > 0) {
            rows.add(uniProtLabel(meta.toString(), "#c9c9cf", 11, false, true));
        }
        if (!e.function().isEmpty()) {
            rows.add(uniProtLabel(e.function(), "#dddddd", 11, false, true));
        }
        rows.add(uniProtLabel("UniProt: " + e.accession()
                + (e.entryName().isEmpty() ? "" : " (" + e.entryName() + ")"), "#9a9aa2", 10, false, false));
        uniProtBox.getChildren().setAll(rows);
    }

    private static Label uniProtLabel(String text, String colorHex, int size, boolean bold, boolean wrap) {
        Label l = new Label(text);
        l.setWrapText(wrap);
        if (wrap) {
            l.setMaxWidth(400);
        }
        l.setStyle("-fx-text-fill: " + colorHex + "; -fx-font-size: " + size + "px;"
                + (bold ? " -fx-font-weight: bold;" : ""));
        return l;
    }

    private static <S> TableColumn<S, Integer> intCol(TableView<S> table, String name, ToIntFunction<S> getter,
                                                      String tip) {
        TableColumn<S, Integer> col = intColumnOf(name, getter, tip, 92);
        table.getColumns().add(col);
        return col;
    }

    /** A right-aligned integer column returned (not added) — for nesting under a group header. */
    private static <S> TableColumn<S, Integer> intColumn(String name, ToIntFunction<S> getter, String tip) {
        return intColumnOf(name, getter, tip, 72);
    }

    /** Shared builder for a right-aligned integer column with a header tooltip. */
    private static <S> TableColumn<S, Integer> intColumnOf(String name, ToIntFunction<S> getter, String tip,
                                                           double prefWidth) {
        TableColumn<S, Integer> col = new TableColumn<>(name);
        col.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(getter.applyAsInt(d.getValue())));
        col.setStyle("-fx-alignment: CENTER-RIGHT;");
        col.setPrefWidth(prefWidth);
        headerTip(col, name, tip, true);
        return col;
    }

    private static <S> void dblCol(TableView<S> table, String name, Function<S, Double> getter, String tip) {
        TableColumn<S, Double> col = new TableColumn<>(name);
        col.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(getter.apply(d.getValue())));
        col.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(Double v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty ? "" : scoreText(v));
            }
        });
        col.getProperties().put(TableUtils.DISPLAY_TEXT, (Function<S, String>) s -> scoreText(getter.apply(s)));
        col.setStyle("-fx-alignment: CENTER-RIGHT;");
        col.setPrefWidth(92);
        headerTip(col, name, tip, true);
        table.getColumns().add(col);
    }

    private static <S> void coverageCol(TableView<S> table, String name, Function<S, Double> getter, String tip) {
        TableColumn<S, Double> col = new TableColumn<>(name);
        col.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(getter.apply(d.getValue())));
        col.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(Double v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty ? "" : coverageText(v));
            }
        });
        col.getProperties().put(TableUtils.DISPLAY_TEXT, (Function<S, String>) s -> coverageText(getter.apply(s)));
        col.setStyle("-fx-alignment: CENTER-RIGHT;");
        col.setPrefWidth(96);
        headerTip(col, name, tip, true);
        table.getColumns().add(col);
    }

    private static String scoreText(Double v) {
        return v == null || Double.isNaN(v) ? "—" : String.format(Locale.US, "%.4f", v);
    }

    private static String coverageText(Double v) {
        return v == null ? "—" : String.format(Locale.US, "%.1f%%", v);
    }

    // ---- settings panel ----------------------------------------------------

    private ScrollPane buildSettings() {
        Label title = new Label("Mapping settings");
        title.setStyle("-fx-font-weight: bold;");

        // Memory: default "Auto" (0 = no -Xmx), settable from 1 GB up to this machine's RAM.
        int maxRamGb = detectMaxRamGb();
        maxMemSpin.setValueFactory(
                new javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory(0, maxRamGb, 0));
        maxMemSpin.getValueFactory().setConverter(zeroLabelConverter("Auto"));

        // CPUs: default "All" (0 = no -c, pepmap uses every core), settable from 1 up to the core count.
        int numCpus = detectCpuCount();
        cpusSpin.setValueFactory(
                new javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory(0, numCpus, 0));
        cpusSpin.getValueFactory().setConverter(zeroLabelConverter("All"));

        editable(scoreSpin);
        editable(minLenSpin);
        editable(mismatchSpin);
        editable(cpusSpin);
        editable(maxMemSpin);
        editable(xShareSpin);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        int r = 0;
        gridRow(grid, r++, "Min peptide score", scoreSpin,
                "Only map peptides whose best PSM score in the mzTab is at least this value. 0 maps every peptide.");
        gridRow(grid, r++, "Min peptide length", minLenSpin,
                "Skip peptides shorter than this many residues. 0 = no minimum length.");
        gridRow(grid, r++, "Allowed mismatches", mismatchSpin,
                "Maximum number of mismatched residues allowed between a peptide and a protein. 0 = exact matches only.");
        gridRow(grid, r++, "Allowed X fraction", xShareSpin,
                "Maximum fraction of the aligned region (the peptide-length window on the protein) that may consist of X (unknown) residues, range 0–1. 0 = disallow X positions.");
        gridRow(grid, r++, "CPUs", cpusSpin,
                "All (default) uses every CPU core. Set 1–" + numCpus
                        + " to limit the number of threads pepmap uses.");
        gridRow(grid, r++, "Max memory (GB)", maxMemSpin,
                "Auto (default) lets the JVM size the heap — no -Xmx is passed. Set 1–" + maxRamGb
                        + " GB to cap the pepmap heap (max = this machine's RAM).");

        runButton.setMaxWidth(Double.MAX_VALUE);
        stopButton.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(runButton, Priority.ALWAYS);
        HBox.setHgrow(stopButton, Priority.ALWAYS);
        runButton.setOnAction(e -> runMapping());
        runButton.setTooltip(tip("Run the peptide-to-protein mapping. Output files (mapping results plus a "
                + "pepmap.log of the command and parameters) are written to a folder beside the input mzTab file."));
        stopButton.setOnAction(e -> stop());
        HBox runRow = new HBox(8, runButton, stopButton);

        i2lCheck.setSelected(true); // treat I / L as identical by default
        i2lCheck.setTooltip(tip(
                "Match isoleucine (I) and leucine (L) as the same residue. Recommended for de novo peptides "
                        + "since I and L are identical in mass."));

        // Visualization (PDV) section — left-aligned controls below the parameter rows: a PDV checkbox
        // in the same standalone style as "Treat I / L as identical", then a left-aligned MS2 tol row.
        Separator vizSep = new Separator();
        vizSep.setStyle("-fx-padding: 0;"); // drop the theme's separator padding
        GridPane.setColumnSpan(vizSep, 2);
        GridPane.setMargin(vizSep, new Insets(-3, 0, -3, 0)); // pull in the surrounding row gap
        grid.add(vizSep, 0, r++);
        Label vizTitle = new Label("Visualization");
        vizTitle.setStyle("-fx-font-weight: bold;");
        GridPane.setColumnSpan(vizTitle, 2);
        grid.add(vizTitle, 0, r++);
        pdvVizCheck.setSelected(true);
        pdvVizCheck.setText("PDV"); // standalone left-aligned checkbox, like "Treat I / L as identical"
        String pdvTip = "When checked, clicking Run also opens the spectra in PDV with the PSM table "
                + "hidden. Clicking a peptide in the results then selects its best PSM's spectrum in that PDV window.";
        pdvVizCheck.setTooltip(tip(pdvTip));
        GridPane.setColumnSpan(pdvVizCheck, 2);
        grid.add(pdvVizCheck, 0, r++);
        pdvTolUnit.getItems().setAll("Da", "ppm");
        pdvTolUnit.getSelectionModel().select("Da");
        pdvTolField.setPrefWidth(54);
        pdvTolField.setMaxWidth(54);
        pdvTolField.setAlignment(Pos.CENTER_RIGHT);
        pdvTolUnit.setPrefWidth(66);
        pdvTolUnit.setMaxWidth(66);
        String pdvTolTip = "MS2 tolerance used for spectrum annotation in PDV.";
        Label pdvTolLabel = new Label("MS2 tol:");
        pdvTolLabel.setTooltip(tip(pdvTolTip));
        pdvTolField.setTooltip(tip(pdvTolTip));
        pdvTolUnit.setTooltip(tip(pdvTolTip));
        // Left-aligned row (label + value + unit), spanning the grid's full width.
        HBox pdvTolControl = new HBox(8, pdvTolLabel, pdvTolField, pdvTolUnit);
        pdvTolControl.setAlignment(Pos.CENTER_LEFT);
        // MS2 tol only applies when PDV is on, so gray it out (disabled) whenever PDV is unchecked.
        pdvTolControl.disableProperty().bind(pdvVizCheck.selectedProperty().not());
        GridPane.setColumnSpan(pdvTolControl, 2);
        grid.add(pdvTolControl, 0, r++);

        Separator runSep = new Separator();
        runSep.setStyle("-fx-padding: 0;");
        VBox.setMargin(runSep, new Insets(-3, 0, -3, 0));
        VBox box = new VBox(8,
                title,
                i2lCheck,
                grid,
                runSep,
                runRow);
        box.setPadding(new Insets(0, 0, 0, 12));
        box.setFillWidth(true);
        settingsBox = box;

        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        // No fixed width: with fitToWidth the panel sizes to its content's preferred width, so it
        // adapts to each OS's default font (Segoe UI / SF Pro / etc.) instead of a hard-coded value.
        return scroll;
    }

    /** Fire the mapping Run button (for the window's Ctrl+R accelerator), if it is enabled. */
    public void fireRun() {
        if (!runButton.isDisabled()) {
            runButton.fire();
        }
    }

    /** Fire the mapping Stop button (for the window's Esc accelerator), if it is enabled. */
    public void fireStop() {
        if (!stopButton.isDisabled()) {
            stopButton.fire();
        }
    }

    /** Distance from this pane's top down to just below the Run row (= inputs + settings content). */
    public double settingsExtent() {
        double inputs = topInputs == null ? 0 : topInputs.prefHeight(-1);
        double settings = settingsBox == null ? 0 : settingsBox.prefHeight(-1);
        return getInsets().getTop() + inputs + settings + 8;
    }

    private static void editable(Spinner<?> s) {
        s.setEditable(true);
        s.getStyleClass().add("compact-spinner");
        s.getEditor().setPrefColumnCount(4);
        s.getEditor().setAlignment(Pos.CENTER_RIGHT);
        s.setMaxWidth(Region.USE_PREF_SIZE);
        // Spinner.setEditable(true) does NOT commit typed text to the value on focus loss, so a value
        // typed but not confirmed with Enter would be silently ignored when the spinner is read at Run
        // time. Commit (and clamp) the editor text through the value factory whenever focus leaves.
        s.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                commitSpinner(s);
            }
        });
    }

    /** Parse the spinner editor's current text into its value factory, clamped to range (JavaFX does
        not commit editable-spinner text on focus loss). Unparseable text keeps the last valid value. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void commitSpinner(Spinner<?> s) {
        SpinnerValueFactory factory = s.getValueFactory();
        if (!s.isEditable() || factory == null || factory.getConverter() == null) {
            return;
        }
        StringConverter converter = factory.getConverter();
        try {
            Object parsed = converter.fromString(s.getEditor().getText());
            if (parsed != null) {
                factory.setValue(clampSpinnerValue(factory, parsed));
            }
        } catch (RuntimeException ignored) {
            // keep the last valid value
        }
        // Normalize the editor text back to the committed (possibly clamped) value.
        s.getEditor().setText(converter.toString(factory.getValue()));
    }

    /** Clamp a parsed value to the Integer/Double factory's [min, max]; other factory types unchanged. */
    private static Object clampSpinnerValue(SpinnerValueFactory<?> factory, Object value) {
        if (factory instanceof SpinnerValueFactory.IntegerSpinnerValueFactory f && value instanceof Integer v) {
            return Math.max(f.getMin(), Math.min(f.getMax(), v));
        }
        if (factory instanceof SpinnerValueFactory.DoubleSpinnerValueFactory f && value instanceof Double v) {
            return Math.max(f.getMin(), Math.min(f.getMax(), v));
        }
        return value;
    }

    /** Total physical RAM in whole GB (floored), for capping the max-memory spinner. */
    private static int detectMaxRamGb() {
        try {
            if (java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                    instanceof com.sun.management.OperatingSystemMXBean os) {
                int gb = (int) (os.getTotalMemorySize() / (1024L * 1024 * 1024));
                if (gb >= 1) {
                    return gb;
                }
            }
        } catch (Throwable ignored) {
            // com.sun.management may be unavailable; fall back below.
        }
        return 64;
    }

    /** Available CPU cores (at least 1), for capping the CPUs spinner. */
    private static int detectCpuCount() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    /** Spinner text converter that displays 0 as {@code zeroLabel} (e.g. "Auto" / "All"). */
    private static javafx.util.StringConverter<Integer> zeroLabelConverter(String zeroLabel) {
        return new javafx.util.StringConverter<>() {
            @Override
            public String toString(Integer v) {
                return (v == null || v <= 0) ? zeroLabel : v.toString();
            }

            @Override
            public Integer fromString(String s) {
                if (s == null) {
                    return 0;
                }
                String t = s.trim();
                if (t.isEmpty() || t.equalsIgnoreCase(zeroLabel)) {
                    return 0;
                }
                try {
                    return Integer.parseInt(t);
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        };
    }

    /** Add a settings row: a right-aligned label in column 0, the narrow control in column 1. */
    private static void gridRow(GridPane g, int row, String label, javafx.scene.control.Control control, String tipText) {
        Label l = new Label(label);
        GridPane.setHalignment(l, HPos.RIGHT);
        l.setTooltip(tip(tipText));
        control.setTooltip(tip(tipText));
        g.add(l, 0, row);
        g.add(control, 1, row);
    }

    /** A wrapped, reasonably-snappy tooltip. */
    private static Tooltip tip(String text) {
        Tooltip t = new Tooltip(text);
        t.setShowDelay(javafx.util.Duration.millis(300));
        t.setWrapText(true);
        t.setMaxWidth(300);
        return t;
    }

    /** Point the panel at {@code mzTab} (auto-fill after a successful Casanovo run). */
    public void setPeptides(File mzTab) {
        if (mzTab != null) {
            mzTabField.setText(mzTab.getAbsolutePath());
        }
    }

    private void browseMzTab() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select Casanovo result (mzTab)");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("mzTab", FxUtils.caseVariants("*.mzTab")),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        File start = FxUtils.startDir(mzTabField.getText(), "mzTab");
        if (start != null) {
            fc.setInitialDirectory(start);
        }
        File f = fc.showOpenDialog(owner);
        if (f != null) {
            mzTabField.setText(f.getAbsolutePath());
            FxUtils.rememberBrowseDir("mzTab", f);
        }
    }

    private void browseFasta() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select reference protein database (FASTA)");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("FASTA", FxUtils.caseVariants("*.fasta", "*.fa", "*.fas", "*.faa")),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        File start = FxUtils.startDir(fastaField.getText(), "fasta");
        if (start != null) {
            fc.setInitialDirectory(start);
        }
        File f = fc.showOpenDialog(owner);
        if (f != null) {
            fastaField.setText(f.getAbsolutePath());
            FxUtils.rememberBrowseDir("fasta", f);
        }
    }

    // ---- run ---------------------------------------------------------------

    private void runMapping() {
        if (running) {
            return;
        }
        validation.clear();
        File mzTab = new File(mzTabField.getText().trim());
        String fastaText = fastaField.getText().trim();
        File fasta = fastaText.isEmpty() ? null : new File(fastaText); // optional: no FASTA -> all unmapped
        if (mzTabField.getText().trim().isEmpty() || !mzTab.isFile()) {
            validation.show(mzTabField, "Choose a valid mzTab file first.");
            return;
        }
        if (fasta != null && !fasta.isFile()) {
            validation.show(fastaField,
                    "Reference FASTA not found. Clear it to load all peptides as unmapped, or pick a valid file.");
            return;
        }

        // PDV visualization gate (on the FX thread, before launching anything): resolve the spectra,
        // prompting if the recorded files are missing. Cancelling skips PDV but still runs the mapping.
        // Mapping uses only the mzTab peptides + FASTA and never reads spectra, so this can't affect it.
        List<File> pdvSpectra = pdvVizCheck.isSelected() ? resolveSpectraForPdv(mzTab) : null;

        PepMapLauncher.Options opts = new PepMapLauncher.Options();
        opts.i2l = i2lCheck.isSelected();
        opts.minLength = minLenSpin.getValue();
        opts.mismatches = mismatchSpin.getValue();
        opts.xShare = xShareSpin.getValue();
        opts.cpus = cpusSpin.getValue();
        opts.maxMemGb = maxMemSpin.getValue();
        double minScore = scoreSpin.getValue();
        String pepmapOverride = settings.getPepmapJar();

        cancelled = false;
        setRunning(true);
        clearResults();
        resultTabs.getSelectionModel().selectFirst(); // jump to Overview while the mapping runs

        // Open PDV (spectrum-only) on its own thread alongside the mapping, if requested and resolved.
        // launchAndRegister swallows all failures, so it can never disrupt the mapping below.
        if (pdvSpectra != null && !pdvSpectra.isEmpty() && pdvController != null) {
            pdvController.launchAndRegister(mzTab, pdvSpectra, parsePdvTol(),
                    pdvTolUnit.getValue() == null ? "Da" : pdvTolUnit.getValue(),
                    true, settings.getPdvJar(), consoleOut);
        }

        Thread t = new Thread(() -> mapInBackground(mzTab, fasta, opts, pepmapOverride, minScore), "pepmap-mapping");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Resolve the spectrum file(s) PDV should load for {@code mzTab}: the {@code ms_run} locations
     * recorded in the mzTab when they exist on this machine, otherwise a chooser asking the user to
     * locate them. Returns null if the user cancels (the caller then runs the mapping without PDV).
     */
    private List<File> resolveSpectraForPdv(File mzTab) {
        List<File> recorded;
        try {
            recorded = MzTabScores.readMsRunLocations(mzTab);
        } catch (IOException e) {
            recorded = List.of();
        }
        // PDV can't read Bruker timsTOF .d data yet, so skip PDV for a .d run (and don't prompt for files).
        if (recorded.stream().anyMatch(f -> TimsTof.looksLikeDotD(f.getPath()))) {
            consoleOut.accept("PDV does not support Bruker timsTOF .d data yet — skipping PDV visualization.");
            return null;
        }
        boolean allPresent = !recorded.isEmpty();
        for (File f : recorded) {
            if (!f.isFile()) {
                allPresent = false;
                break;
            }
        }
        if (allPresent) {
            return recorded;
        }
        String missingName = recorded.stream().filter(f -> !f.isFile()).map(File::getName).findFirst().orElse(null);
        FileChooser fc = new FileChooser();
        fc.setTitle(missingName == null
                ? "Select spectrum file(s) for PDV"
                : "Locate spectrum file(s) for PDV — not found: " + missingName);
        fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Spectra (*.mzML, *.mzXML, *.mgf)",
                        FxUtils.caseVariants("*.mzML", "*.mzXML", "*.mgf")));
        // Spectra usually sit beside the mzTab, so start in its folder. (The pick is still saved under
        // "spectra" below so the Sequence/DB Search tabs reopen here.)
        File start = FxUtils.startDir(mzTab.getAbsolutePath(), "spectra");
        if (start != null) {
            fc.setInitialDirectory(start);
        }
        List<File> picked = fc.showOpenMultipleDialog(owner);
        if (picked != null && !picked.isEmpty()) {
            FxUtils.rememberBrowseDir("spectra", picked.get(0));
        }
        return (picked == null || picked.isEmpty()) ? null : picked;
    }

    /** Parse the PDV fragment-tolerance field, falling back to a unit-appropriate default. */
    private double parsePdvTol() {
        try {
            return Double.parseDouble(pdvTolField.getText().trim());
        } catch (NumberFormatException e) {
            return "ppm".equals(pdvTolUnit.getValue()) ? 20.0 : 0.05;
        }
    }

    private void mapInBackground(File mzTab, File fasta, PepMapLauncher.Options opts, String pepmapOverride,
                                 double minScore) {
        File pepFile = null;
        File tempDir = null;
        boolean usingTempDir = false;
        Consumer<String> log = msg -> Platform.runLater(() -> {
            consoleOut.accept(msg);
            status(msg);
        });
        Platform.runLater(() -> consoleOut.accept(System.lineSeparator() + "$ pepmap peptide-to-protein mapping"));
        try {
            // 1. mzTab PSMs (optionally score-filtered) -> distinct bare peptides (mods stripped, as pepmap does).
            //    The same pass keeps each peptide's best per-residue aa_scores for the double-click chart.
            MzTabScores.Detailed detailed = MzTabScores.readWithAaScores(mzTab);
            List<MzTabScores.Psm> all = detailed.psms();
            // Score plot uses ALL PSMs (no min-score cutoff): cumulative counts across scores 0..1.
            final MzTabScores.Curve scoreCurveLocal = MzTabScores.cumulativeCounts(all, 0.0, 1.0, 0.05);
            // PSM columns that are null for every PSM are hidden from the per-residue table; report them.
            final List<String> emptyColsLocal = MzTabScores.detectEmptyPsmColumns(mzTab);
            if (!emptyColsLocal.isEmpty()) {
                Platform.runLater(() -> consoleOut.accept(
                        "PSM columns null for every PSM in the mzTab (hidden from the per-residue table): "
                                + String.join(", ", emptyColsLocal)));
            }
            List<MzTabScores.Psm> psms = minScore > 0
                    ? all.stream().filter(p -> p.score() >= minScore).toList()
                    : all;
            final Map<String, MzTabScores.BestPsm> bestMap = detailed.bestByPeptide();
            LinkedHashSet<String> bareSeqs = new LinkedHashSet<>();
            for (MzTabScores.Psm p : psms) {
                String b = bare(p.sequence());
                if (!b.isEmpty()) {
                    bareSeqs.add(b);
                }
            }
            if (bareSeqs.isEmpty()) {
                Platform.runLater(() -> {
                    status(minScore > 0 ? "No peptides at score ≥ " + minScore + "." : "No peptides found in the mzTab.");
                    setRunning(false);
                });
                return;
            }
            final int totalInput = bareSeqs.size();

            // No reference FASTA provided: skip pepmap entirely and load every de novo peptide as
            // unmapped (empty mappings -> computeResults puts them all in the Unmapped table).
            if (fasta == null) {
                Results res = computeResults(List.of(), psms, null, opts.i2l, opts.mismatches);
                Platform.runLater(() -> {
                    scoreCurve = scoreCurveLocal;
                    emptyPsmColumns = Set.copyOf(emptyColsLocal);
                    bestByPeptide = bestMap;
                    loadedMzTab = mzTab;
                    applyResults(res);
                    status("No reference FASTA — loaded " + totalInput + " peptides as unmapped.");
                    setRunning(false);
                });
                return;
            }

            // Output goes next to the mzTab, in a dedicated <mzTabBaseName>_pepmap subfolder so the
            // results persist and don't clutter (or collide with) the mzTab's directory. If that
            // parent isn't usable/writable, fall back to a temp dir (then cleaned up as before).
            File outDir = resolveOutputDir(mzTab, log);
            usingTempDir = outDir == null;
            if (usingTempDir) {
                outDir = Files.createTempDirectory("casanovogui_pepmap_").toFile();
                tempDir = outDir;
            }
            pepFile = new File(outDir, "peptides.txt");
            try (BufferedWriter w = Files.newBufferedWriter(pepFile.toPath(), StandardCharsets.UTF_8)) {
                for (String s : bareSeqs) {
                    w.write(s);
                    w.write("\n");
                }
            }

            // 2. Ensure the pepmap jar (Settings override -> cache -> download).
            Platform.runLater(() -> status("Preparing pepmap…"));
            Path jar = PepMapLauncher.ensurePepmap(pepmapOverride, log);

            // 3. Run mapping and stream the output for status. The exact command line is captured
            //    just before launch and written, with all settings, to pepmap.log in the output dir.
            Platform.runLater(() -> status("Mapping " + totalInput + " peptides…"));
            final File outDirFinal = outDir;
            Process p = PepMapLauncher.runMapping(jar, pepFile, fasta, outDir, opts, log,
                    cmd -> writePepmapLog(outDirFinal, mzTab, fasta, jar, cmd, opts, minScore, log));
            proc = p;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (isJvmUnsafeWarning(line)) {
                        continue; // hide the JVM's own sun.misc.Unsafe deprecation warnings (JDK noise)
                    }
                    final String raw = line;
                    Platform.runLater(() -> consoleOut.accept(raw));
                    String ln = line.trim();
                    if (!ln.isEmpty() && isStatusLine(ln)) {
                        Platform.runLater(() -> status(ln));
                    }
                }
            }
            int exit = p.waitFor();
            File detail = new File(outDir, "peptide_map_detail.txt");

            if (cancelled) {
                Platform.runLater(() -> {
                    status("Mapping stopped.");
                    setRunning(false);
                });
            } else if (exit == 0 && detail.isFile()) {
                Platform.runLater(() -> status("Summarizing results…"));
                List<MapRow> rows = parseDetail(detail);
                Results res = computeResults(rows, psms, fasta, opts.i2l, opts.mismatches);
                Platform.runLater(() -> {
                    scoreCurve = scoreCurveLocal;
                    emptyPsmColumns = Set.copyOf(emptyColsLocal);
                    bestByPeptide = bestMap;
                    loadedMzTab = mzTab;
                    applyResults(res);
                    status("Done.");
                    setRunning(false);
                });
            } else {
                Platform.runLater(() -> {
                    status("pepmap failed (exit " + exit + "). Check the mzTab/FASTA and pepmap jar.");
                    setRunning(false);
                });
            }
        } catch (Exception ex) {
            String m = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            Platform.runLater(() -> {
                status("Error: " + m);
                setRunning(false);
            });
        } finally {
            proc = null;
            // When we fell back to a temp dir, remove it and everything pepmap wrote into it — the
            // results are already parsed into memory. Output that lives next to the mzTab is kept,
            // since the user wants those files (results + peptides.txt + log).
            if (usingTempDir && tempDir != null) {
                deleteRecursively(tempDir);
            }
        }
    }

    /**
     * Output directory derived from the mzTab's parent: {@code <mzTabParent>/<mzTabBaseName>_pepmap/}.
     * Returns the created directory, or {@code null} when the mzTab parent is missing or not writable
     * (the caller then falls back to a temp dir). A clear note is logged on fallback.
     */
    private static File resolveOutputDir(File mzTab, Consumer<String> log) {
        File parent = mzTab.getAbsoluteFile().getParentFile();
        if (parent == null || !parent.isDirectory() || !Files.isWritable(parent.toPath())) {
            log.accept("mzTab folder is not writable; using a temporary output folder instead: "
                    + (parent == null ? mzTab.getAbsolutePath() : parent.getAbsolutePath()));
            return null;
        }
        String name = mzTab.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        File outDir = new File(parent, base + "_pepmap");
        try {
            Files.createDirectories(outDir.toPath());
            return outDir;
        } catch (IOException e) {
            log.accept("Could not create output folder " + outDir.getAbsolutePath()
                    + " (" + e.getMessage() + "); using a temporary output folder instead.");
            return null;
        }
    }

    /** Best-effort recursive delete of a directory (or file) and everything under it. */
    private static void deleteRecursively(File f) {
        if (f == null) {
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteRecursively(k);
            }
        }
        try {
            Files.deleteIfExists(f.toPath());
        } catch (IOException ignored) {
            // best effort
        }
    }

    /**
     * Write {@code pepmap.log} into {@code outDir} (UTF-8, overwriting any previous run): a timestamp,
     * the inputs/output paths, the resolved jar, the full command line, and every parameter setting.
     * A logging failure is reported via {@code log} but never aborts the mapping.
     */
    private static void writePepmapLog(File outDir, File mzTab, File fasta, Path jar, List<String> cmd,
                                       PepMapLauncher.Options opts, double minScore, Consumer<String> log) {
        File logFile = new File(outDir, "pepmap.log");
        StringBuilder sb = new StringBuilder();
        sb.append("pepmap mapping run\n");
        sb.append("Timestamp:        ").append(java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append('\n');
        sb.append("Input mzTab:      ").append(mzTab.getAbsolutePath()).append('\n');
        sb.append("Reference FASTA:  ").append(fasta.getAbsolutePath()).append('\n');
        sb.append("Output folder:    ").append(outDir.getAbsolutePath()).append('\n');
        sb.append("pepmap jar:       ").append(jar).append('\n');
        sb.append('\n');
        sb.append("Command line:\n").append(String.join(" ", cmd)).append('\n');
        sb.append('\n');
        sb.append("Parameter settings:\n");
        sb.append("  Treat I/L as identical (-i2l): ").append(opts.i2l).append('\n');
        sb.append("  Min peptide score (GUI filter): ").append(minScore).append('\n');
        sb.append("  Min peptide length (-l):        ").append(opts.minLength).append('\n');
        sb.append("  Allowed mismatches (-mm):       ").append(opts.mismatches).append('\n');
        sb.append("  Max X share (-x):               ").append(opts.xShare).append('\n');
        sb.append("  CPUs (-c, 0 = all):             ").append(opts.cpus).append('\n');
        sb.append("  Max memory GB (-Xmx, 0 = auto): ").append(opts.maxMemGb).append('\n');
        try {
            Files.writeString(logFile.toPath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.accept("Could not write pepmap.log (" + e.getMessage() + ").");
        }
    }

    /** Whether a pepmap stdout line is worth surfacing as a concise status update. */
    private static boolean isStatusLine(String s) {
        return s.contains("ndexing") || s.contains("apping") || s.contains("proteins in database")
                || s.contains("valid unique peptides") || s.contains("saved to file");
    }

    /**
     * True for the JVM's own {@code sun.misc.Unsafe} deprecation warning block (emitted by newer JDKs
     * when pepmap's zstd dependency calls Unsafe). These four lines are JDK noise, not pepmap output,
     * so they are dropped from the console. Matching by message keeps this independent of JDK version —
     * if a JDK doesn't print them, there is simply nothing to filter.
     */
    private static boolean isJvmUnsafeWarning(String s) {
        String t = s.strip();
        if (!t.startsWith("WARNING:")) {
            return false;
        }
        return t.contains("sun.misc.Unsafe")
                || t.contains("terminally deprecated method")
                || t.contains("Please consider reporting this to the maintainers");
    }

    // ---- derive the result model (off the FX thread) -----------------------

    private static Results computeResults(List<MapRow> rows, List<MzTabScores.Psm> psms, File fasta, boolean i2l,
                                          int mismatches)
            throws IOException {
        // PSM count + best score per bare peptide; and every distinct de novo peptide.
        Map<String, Integer> psmByPeptide = new HashMap<>();
        Map<String, Double> bestScore = new HashMap<>();
        LinkedHashSet<String> allPeptides = new LinkedHashSet<>();
        for (MzTabScores.Psm p : psms) {
            String key = bare(p.sequence());
            if (key.isEmpty()) {
                continue;
            }
            allPeptides.add(key);
            psmByPeptide.merge(key, 1, Integer::sum);
            bestScore.merge(key, p.score(), Math::max);
        }

        // peptide -> proteins ; protein -> its detail rows ; peptide -> its detail rows.
        Map<String, Set<String>> proteinsByPeptide = new HashMap<>();
        Map<String, List<MapRow>> rowsByProtein = new LinkedHashMap<>();
        Map<String, List<MapRow>> rowsByPeptide = new LinkedHashMap<>();
        for (MapRow r : rows) {
            String pep = bare(r.peptide());
            proteinsByPeptide.computeIfAbsent(pep, k -> new LinkedHashSet<>()).add(r.protein());
            rowsByProtein.computeIfAbsent(r.protein(), k -> new ArrayList<>()).add(r);
            rowsByPeptide.computeIfAbsent(pep, k -> new ArrayList<>()).add(r);
        }

        // fewest substitutions each peptide needs to match SOME protein (0 = matches exactly somewhere).
        Map<String, Integer> minMismatchByPeptide = new HashMap<>();
        for (Map.Entry<String, List<MapRow>> en : rowsByPeptide.entrySet()) {
            int min = Integer.MAX_VALUE;
            for (MapRow r : en.getValue()) {
                min = Math.min(min, substitutions(r, i2l));
            }
            minMismatchByPeptide.put(en.getKey(), min == Integer.MAX_VALUE ? 0 : min);
        }

        Map<String, String> seqByProtein = readFastaSequences(fasta, rowsByProtein.keySet());

        List<ProteinRow> proteinRows = new ArrayList<>();
        for (Map.Entry<String, List<MapRow>> en : rowsByProtein.entrySet()) {
            // fewest substitutions each peptide needs to match THIS protein.
            Map<String, Integer> minSubForProtein = new LinkedHashMap<>();
            for (MapRow r : en.getValue()) {
                minSubForProtein.merge(bare(r.peptide()), substitutions(r, i2l), Math::min);
            }
            int uniquePep = 0;
            int totalPsm = 0;
            int uniquePsm = 0;
            int mismatchPep = 0;
            for (Map.Entry<String, Integer> pe : minSubForProtein.entrySet()) {
                String pep = pe.getKey();
                int c = psmByPeptide.getOrDefault(pep, 0);
                totalPsm += c;
                if (proteinsByPeptide.getOrDefault(pep, Set.of()).size() <= 1) {
                    uniquePep++;
                    uniquePsm += c;
                }
                if (pe.getValue() > 0) {
                    mismatchPep++;
                }
            }
            Double cov = coverage(en.getValue(), proteinsByPeptide, seqByProtein.get(en.getKey()));
            proteinRows.add(new ProteinRow(en.getKey(), minSubForProtein.size(), uniquePep, mismatchPep,
                    totalPsm, uniquePsm, cov));
        }
        proteinRows.sort(Comparator.comparingInt(ProteinRow::totalPeptides).reversed()
                .thenComparing(ProteinRow::protein));

        // every mapped peptide (>=1 protein); also count the proteotypic (single-protein) ones.
        List<MappedRow> mappedRows = new ArrayList<>();
        int uniquePeptides = 0;
        for (Map.Entry<String, Set<String>> en : proteinsByPeptide.entrySet()) {
            String pep = en.getKey();
            // fewest substitutions per protein, for the annotated protein list ("P12345 (1 mm)").
            Map<String, Integer> minSubByProtein = new LinkedHashMap<>();
            for (MapRow r : rowsByPeptide.getOrDefault(pep, List.of())) {
                minSubByProtein.merge(r.protein(), substitutions(r, i2l), Math::min);
            }
            List<String> labels = new ArrayList<>();
            for (String prot : en.getValue()) {
                int mm = minSubByProtein.getOrDefault(prot, 0);
                labels.add(mm > 0 ? prot + " (" + mm + " mm)" : prot);
            }
            mappedRows.add(new MappedRow(pep, en.getValue().size(), String.join("; ", labels),
                    psmByPeptide.getOrDefault(pep, 0), bestScore.getOrDefault(pep, Double.NaN),
                    minMismatchByPeptide.getOrDefault(pep, 0)));
            if (en.getValue().size() < 2) {
                uniquePeptides++;
            }
        }
        mappedRows.sort(Comparator.comparingInt(MappedRow::psms).reversed()
                .thenComparing(MappedRow::peptide));

        // unmapped = de novo peptides that reached no protein.
        Set<String> mapped = proteinsByPeptide.keySet();
        List<UnmappedRow> unmapped = new ArrayList<>();
        for (String pep : allPeptides) {
            if (!mapped.contains(pep)) {
                unmapped.add(new UnmappedRow(pep, psmByPeptide.getOrDefault(pep, 0), pep.length(),
                        bestScore.getOrDefault(pep, Double.NaN)));
            }
        }
        unmapped.sort(Comparator.comparingInt(UnmappedRow::psms).reversed()
                .thenComparing(UnmappedRow::peptide));

        int totalPsms = 0;
        for (int c : psmByPeptide.values()) {
            totalPsms += c;
        }
        int mappedPsms = 0;
        for (String pep : mapped) {
            mappedPsms += psmByPeptide.getOrDefault(pep, 0);
        }

        // Mapped-unique-peptides vs score-cutoff curve (x = cutoff 0..1 step 0.05).
        double[] cutoffs = new double[21];
        int[] mappedByCutoff = new int[21];
        for (int i = 0; i < 21; i++) {
            cutoffs[i] = i * 0.05;
        }
        int[] totalByCutoff = new int[21];
        for (String pep : mapped) {
            double s = bestScore.getOrDefault(pep, Double.NaN);
            if (Double.isNaN(s)) {
                continue;
            }
            int upTo = Math.min(20, (int) Math.floor(s / 0.05 + 1e-9));
            for (int i = 0; i <= upTo; i++) {
                mappedByCutoff[i]++;
            }
        }
        for (String pep : allPeptides) {
            double s = bestScore.getOrDefault(pep, Double.NaN);
            if (Double.isNaN(s)) {
                continue;
            }
            int upTo = Math.min(20, (int) Math.floor(s / 0.05 + 1e-9));
            for (int i = 0; i <= upTo; i++) {
                totalByCutoff[i]++;
            }
        }

        return new Results(proteinRows, rowsByProtein, rowsByPeptide, proteinsByPeptide, psmByPeptide,
                minMismatchByPeptide, seqByProtein, i2l, mismatches, mappedRows, unmapped, allPeptides.size(),
                mapped.size(), totalPsms, mappedPsms, uniquePeptides, cutoffs, mappedByCutoff, totalByCutoff);
    }

    /**
     * Read protein sequences for the {@code wanted} ids. pepmap may report a protein as the FASTA
     * header's first token, the full header, or a pipe-delimited piece (e.g. UniProt
     * {@code sp|P12345|NAME}), so each candidate key is indexed and only those in {@code wanted} kept.
     */
    private static Map<String, String> readFastaSequences(File fasta, Set<String> wanted) throws IOException {
        Map<String, String> seqs = new HashMap<>();
        if (fasta == null || !fasta.isFile() || wanted.isEmpty()) {
            return seqs;
        }
        try (BufferedReader r = Files.newBufferedReader(fasta.toPath(), StandardCharsets.UTF_8)) {
            String full = null;
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith(">")) {
                    flushFasta(seqs, wanted, full, sb);
                    full = line.substring(1).trim();
                    sb.setLength(0);
                } else {
                    sb.append(line.trim());
                }
            }
            flushFasta(seqs, wanted, full, sb);
        }
        return seqs;
    }

    private static void flushFasta(Map<String, String> seqs, Set<String> wanted, String header, StringBuilder sb) {
        if (header == null || header.isEmpty() || sb.length() == 0) {
            return;
        }
        String seq = sb.toString();
        String firstToken = header.split("\\s+")[0];
        addIfWanted(seqs, wanted, header, seq);
        addIfWanted(seqs, wanted, firstToken, seq);
        if (firstToken.contains("|")) {
            for (String part : firstToken.split("\\|")) {
                addIfWanted(seqs, wanted, part, seq);
            }
        }
    }

    private static void addIfWanted(Map<String, String> seqs, Set<String> wanted, String key, String seq) {
        if (!key.isEmpty() && wanted.contains(key)) {
            seqs.putIfAbsent(key, seq);
        }
    }

    // ---- apply to the UI (FX thread) ---------------------------------------

    /** Show/hide the mismatch columns, the Mapped "Match" filter, and the coverage "mismatch site" legend.
        These carry meaning only when a mapping allowed mismatches; hidden otherwise (incl. at first open). */
    private void setMismatchColumnsVisible(boolean show) {
        proteinMismatchCol.setVisible(show);
        proteinViewMismatchCol.setVisible(show);
        proteinViewBestMatchCol.setVisible(show);
        mappedBestMatchCol.setVisible(show);
        matchBox.setVisible(show);
        matchBox.setManaged(show);
        coverageMismatchSwatch.setVisible(show);
        coverageMismatchSwatch.setManaged(show);
        if (!show) {
            matchFilter.setValue("All");
        }
    }

    private void applyResults(Results res) {
        this.results = res;
        // The mismatch columns and the Mapped "Match" filter only make sense when mismatches were allowed.
        setMismatchColumnsVisible(res.mismatches() > 0);
        // The "Mismatches" column tooltip wording depends on whether I/L counted as a substitution this run.
        ((Label) proteinViewMismatchCol.getGraphic()).setTooltip(tip(mismatchesColumnTip(res.i2l())));
        overviewChartsBox.setVisible(true);
        overviewChartsBox.setManaged(true);
        overviewPlaceholder.setVisible(false);
        proteinPager.setData(res.proteins());
        List<String> names = new ArrayList<>();
        for (ProteinRow p : res.proteins()) {
            names.add(p.protein());
        }
        proteinSelector.getItems().setAll(names);
        proteinSelector.setValue(names.isEmpty() ? null : names.get(0));
        showProtein(proteinSelector.getValue()); // refresh even when the value did not change
        mappedPager.setData(res.mapped());
        unmappedPager.setData(res.unmapped());
        buildStatCards(res);
        // Mapping plots (cutoff + top proteins) only make sense when peptides actually mapped; with no
        // mapping (e.g. no FASTA) show just the score plot. The score plot is always present.
        boolean hasMapping = !res.mapped().isEmpty();
        cutoffBox.setVisible(hasMapping);
        cutoffBox.setManaged(hasMapping);
        topBox.setVisible(hasMapping);
        topBox.setManaged(hasMapping);
        chartsFlow.setMinWidth(2 * 300 + 12); // 2 cells + gap + slack, so ≥2 columns hold at the minimum
        buildScoreChart();
        if (hasMapping) {
            buildTopChart(res);
            buildCutoffChart(res);
        }
        // Re-align the (re-sized) cards once the charts have laid out and their data area is known.
        javafx.application.Platform.runLater(this::refreshSummaryAlign);
    }

    /** Render the score plot (cumulative PSM or peptide counts vs peptide score) for the current mode. */
    private void buildScoreChart() {
        scoreChart.getData().clear();
        if (scoreCurve == null) {
            return;
        }
        String mode = scoreMode.getValue() == null ? "Peptides" : scoreMode.getValue();
        int[] counts = "Peptides".equals(mode) ? scoreCurve.peptideCounts() : scoreCurve.psmCounts();
        double[] thr = scoreCurve.thresholds();
        XYChart.Series<Number, Number> s = new XYChart.Series<>();
        for (int i = 0; i < thr.length; i++) {
            s.getData().add(new XYChart.Data<>(thr[i], counts[i]));
        }
        scoreChart.getData().add(s);
        scoreChart.setTitle(mode);
        ((NumberAxis) scoreChart.getYAxis()).setLabel(mode + " (≥ score)");
    }

    /** Replace the overview's text summary with a row of KPI stat cards (dashboard style). */
    private void buildStatCards(Results r) {
        statCards.getChildren().clear();
        List<javafx.scene.Node> cards = new ArrayList<>();
        int total = r.totalPeptides();
        int mapped = r.mappedPeptides();
        if (!r.mapped().isEmpty()) {
            int unmapped = total - mapped;
            int shared = r.mapped().size() - r.uniquePeptides();
            double pepRate = total == 0 ? 0 : (double) mapped / total;
            double psmRate = r.totalPsms() == 0 ? 0 : (double) r.mappedPsms() / r.totalPsms();
            // Per-level mismatch breakdown, shown as extra lines inside the Peptides-mapped card.
            java.util.TreeMap<Integer, Integer> byMm = new java.util.TreeMap<>();
            for (int v : r.minMismatchByPeptide().values()) {
                byMm.merge(v, 1, Integer::sum);
            }
            List<String> breakdown = null;
            if (byMm.keySet().stream().anyMatch(k -> k > 0)) {
                breakdown = new ArrayList<>();
                for (Map.Entry<Integer, Integer> e : byMm.entrySet()) {
                    int lvl = e.getKey();
                    breakdown.add((lvl == 0 ? "exact" : lvl + " mm") + ": " + fmt(e.getValue()));
                }
            }
            cards.add(statCard("Peptides mapped", fmt(mapped),
                    String.format(Locale.US, "%.1f%% of %,d", pepRate * 100, total), pepRate, breakdown));
            cards.add(statCard("Unmapped", fmt(unmapped),
                    total == 0 ? null : String.format(Locale.US, "%.1f%% of total", 100.0 * unmapped / total), null));
            cards.add(statCard("Proteins", fmt(r.proteins().size()), null, null));
            cards.add(statCard("Unique peptides", fmt(r.uniquePeptides()), "proteotypic", null));
            cards.add(statCard("Shared peptides", fmt(shared), null, null));
            cards.add(statCard("PSMs mapped", fmt(r.mappedPsms()),
                    String.format(Locale.US, "%.1f%% of %,d", psmRate * 100, r.totalPsms()), psmRate));
        } else {
            cards.add(statCard("Peptides", fmt(total), "loaded from the mzTab", null));
            cards.add(statCard("PSMs", fmt(r.totalPsms()), null, null));
        }
        for (int i = 0; i < cards.size(); i++) {
            statCards.add(cards.get(i), i % 2, i / 2);
        }
        // Stretch the grid to fill the plot rectangle: equal halves across, evenly-grown rows down.
        int cols = Math.min(2, cards.size());
        int rows = (cards.size() + cols - 1) / cols;
        List<javafx.scene.layout.ColumnConstraints> ccs = new ArrayList<>();
        for (int c = 0; c < cols; c++) {
            javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints();
            cc.setPercentWidth(100.0 / cols);
            ccs.add(cc);
        }
        statCards.getColumnConstraints().setAll(ccs);
        List<javafx.scene.layout.RowConstraints> rcs = new ArrayList<>();
        for (int ri = 0; ri < rows; ri++) {
            javafx.scene.layout.RowConstraints rc = new javafx.scene.layout.RowConstraints();
            rc.setVgrow(javafx.scene.layout.Priority.ALWAYS);
            rcs.add(rc);
        }
        statCards.getRowConstraints().setAll(rcs);
    }

    private static String fmt(int n) {
        return String.format(Locale.US, "%,d", n);
    }

    private static javafx.scene.Node statCard(String caption, String value, String sub, Double progress) {
        return statCard(caption, value, sub, progress, null);
    }

    /** A KPI "info box": a caption, a big value, an optional sub-line, an optional progress bar, and
        optional extra muted lines (e.g. the per-level mismatch breakdown). */
    private static javafx.scene.Node statCard(String caption, String value, String sub, Double progress,
                                              List<String> extra) {
        Label cap = new Label(caption);
        cap.getStyleClass().add("stat-caption");
        Label val = new Label(value);
        val.getStyleClass().add("stat-value");
        VBox card = new VBox(2, cap, val);
        card.setAlignment(Pos.CENTER_LEFT); // centre content vertically when the tile is stretched to fill
        card.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE); // fill the grid cell so the block meets the plots' edges
        card.getStyleClass().add("stat-card");
        if (sub != null) {
            Label s = new Label(sub);
            s.getStyleClass().add("stat-sub");
            card.getChildren().add(s);
        }
        if (progress != null) {
            javafx.scene.control.ProgressBar pb = new javafx.scene.control.ProgressBar(progress);
            pb.setPrefHeight(5);
            pb.setMaxWidth(Double.MAX_VALUE);
            card.getChildren().add(pb);
        }
        if (extra != null) {
            for (String line : extra) {
                Label e = new Label(line);
                e.getStyleClass().add("stat-sub");
                card.getChildren().add(e);
            }
        }
        return card;
    }

    private void buildTopChart(Results res) {
        BarChart<Number, String> chart = newTopChart(); // fresh chart: no stale categories ever
        String mode = topMode.getValue() == null ? "Peptides" : topMode.getValue();
        java.util.function.ToIntFunction<ProteinRow> metric = switch (mode) {
            case "Unique peptides" -> ProteinRow::uniquePeptides;
            case "PSMs" -> ProteinRow::totalPsms;
            case "Unique PSMs" -> ProteinRow::uniquePsms;
            default -> ProteinRow::totalPeptides;
        };
        // Recompute the top 12 from ALL proteins for the selected metric, highest first. Cap at 12:
        // the fixed-height square plot can't fit more rows without JavaFX clipping the end labels.
        List<ProteinRow> sorted = new ArrayList<>(res.proteins());
        sorted.sort(Comparator.comparingInt(metric).reversed().thenComparing(ProteinRow::protein));
        List<ProteinRow> top = sorted.size() > 12 ? sorted.subList(0, 12) : sorted;
        XYChart.Series<Number, String> series = new XYChart.Series<>();
        Set<String> usedLabels = new LinkedHashSet<>();
        // Build bottom-to-top: add the lowest-ranked first so the highest ends up at the top.
        for (int i = top.size() - 1; i >= 0; i--) {
            ProteinRow p = top.get(i);
            String full = p.protein();
            String label = shortLabel(full, usedLabels);
            XYChart.Data<Number, String> d = new XYChart.Data<>(metric.applyAsInt(p), label);
            // The axis label is truncated for long names, so show the full name on hover over the bar.
            d.nodeProperty().addListener((o, a, node) -> {
                if (node != null) {
                    Tooltip.install(node, new Tooltip(full));
                }
            });
            series.getData().add(d);
        }
        chart.getData().add(series);
        ((NumberAxis) chart.getXAxis()).setLabel(mode);
        topChart = chart;
        topCell.getChildren().setAll(chart);
    }

    /** Truncate a protein name longer than 30 chars (keeps the category axis narrow so the bars
        don't get squeezed); the full name is available on hover. Kept unique so categories don't collide. */
    private static String shortLabel(String name, Set<String> used) {
        int max = 30;
        String base = name.length() > max ? name.substring(0, max - 1) + "…" : name;
        String label = base;
        int n = 2;
        while (!used.add(label)) {
            label = base + " (" + n + ")";
            n++;
        }
        return label;
    }

    private void buildCutoffChart(Results res) {
        cutoffChart.getData().clear();
        boolean pct = "Mapping rate (%)".equals(cutoffMode.getValue());
        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        for (int i = 0; i < res.cutoffs().length; i++) {
            double y;
            if (pct) {
                int tot = res.totalByCutoff()[i];
                if (tot == 0) {
                    continue; // no peptides at/above this cutoff -> rate is undefined, not 0%
                }
                y = 100.0 * res.mappedByCutoff()[i] / tot;
            } else {
                y = res.mappedByCutoff()[i];
            }
            series.getData().add(new XYChart.Data<>(res.cutoffs()[i], y));
        }
        cutoffChart.getData().add(series);
        ((NumberAxis) cutoffChart.getYAxis()).setLabel(pct ? "Mapping rate (%)" : "Mapped peptides");
        cutoffChart.setTitle(pct ? "Mapping rate" : "Mapped peptides");
    }

    private void clearResults() {
        results = null;
        bestByPeptide = Map.of();
        if (overviewChartsBox != null) {
            overviewChartsBox.setVisible(false);
            overviewChartsBox.setManaged(false);
        }
        overviewPlaceholder.setVisible(true);
        proteinPager.setData(List.of());
        peptidePager.setData(List.of());
        mappedPager.setData(List.of());
        unmappedPager.setData(List.of());
        proteinSelector.getItems().clear();
        proteinSelector.setValue(null);
        coverageBox.getChildren().clear();
        coverageLabel.setText("");
        topChart.getData().clear();
        cutoffChart.getData().clear();
        statCards.getChildren().clear();
    }

    // ---- parse pepmap detail -----------------------------------------------

    private static List<MapRow> parseDetail(File f) throws IOException {
        List<MapRow> rows = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
            String header = r.readLine();
            if (header == null) {
                return rows;
            }
            String[] h = header.split("\t", -1);
            Map<String, Integer> idx = new HashMap<>();
            for (int i = 0; i < h.length; i++) {
                idx.put(h[i].trim(), i);
            }
            int ip = idx.getOrDefault("peptide", 0);
            int iop = idx.getOrDefault("peptide_on_protein", 1);
            int ipr = idx.getOrDefault("protein", 2);
            int is = idx.getOrDefault("peptide_start", 3);
            int ie = idx.getOrDefault("peptide_end", 4);
            int ipre = idx.getOrDefault("pre_aa", 5);
            int ipost = idx.getOrDefault("post_aa", 6);
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] d = line.split("\t", -1);
                rows.add(new MapRow(cell(d, ip), cell(d, iop), cell(d, ipr),
                        cell(d, is), cell(d, ie), cell(d, ipre), cell(d, ipost)));
            }
        }
        return rows;
    }

    private static String cell(String[] a, int i) {
        return (i >= 0 && i < a.length) ? a[i] : "";
    }

    /** Strip non-letters and upper-case — the same normalization pepmap applies to its input. */
    private static String bare(String s) {
        return Peptides.bare(s);
    }

    private static boolean isIL(char c) {
        return c == 'I' || c == 'L';
    }

    /**
     * Classify every position where the de novo peptide differs from the {@code peptideOnProtein}
     * substring it matched. I/L (when {@code i2l}) and X are flagged as equivalences, not substitutions.
     */
    static List<Mismatch> mismatchesOf(MapRow r, boolean i2l) {
        String q = bare(r.peptide());
        String p = r.peptideOnProtein() == null ? "" : r.peptideOnProtein().trim().toUpperCase(Locale.ROOT);
        List<Mismatch> out = new ArrayList<>();
        int n = Math.min(q.length(), p.length());
        for (int i = 0; i < n; i++) {
            char a = q.charAt(i);
            char b = p.charAt(i);
            if (a == b) {
                continue;
            }
            boolean equiv = (i2l && isIL(a) && isIL(b)) || a == 'X' || b == 'X';
            out.add(new Mismatch(i + 1, a, b, equiv));
        }
        return out;
    }

    /** Number of true substitutions (excludes I/L and X equivalences) between a peptide and its match. */
    static int substitutions(MapRow r, boolean i2l) {
        int c = 0;
        for (Mismatch m : mismatchesOf(r, i2l)) {
            if (!m.equivalence()) {
                c++;
            }
        }
        return c;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // ---- actions -----------------------------------------------------------

    private void stop() {
        cancelled = true;
        Process p = proc;
        if (p != null && p.isAlive()) {
            p.destroy();
        }
        status("Stopping…");
    }

    private void setRunning(boolean r) {
        running = r;
        mappingRunning.set(r);
        runButton.setDisable(r);
        stopButton.setDisable(!r);
        // Lock the inputs while a mapping runs, so the settings can't drift from what the run used.
        for (javafx.scene.Node n : new javafx.scene.Node[]{i2lCheck, scoreSpin, minLenSpin, mismatchSpin,
                xShareSpin, cpusSpin, maxMemSpin, mzTabField, fastaField, mzBrowse, faBrowse}) {
            n.setDisable(r);
        }
        sharedProgress.setVisible(r);
        if (r) {
            sharedProgress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        }
    }

    /** True while a mapping subprocess is running; observed by MainApp to show/size the console. */
    public BooleanProperty runningProperty() {
        return mappingRunning;
    }

    private void status(String msg) {
        sharedStatus.setText(msg);
    }
}
