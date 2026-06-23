package org.casanovo.gui.ui;

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
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Spinner;
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
import org.casanovo.gui.core.MzTabScores;
import org.casanovo.gui.core.PdvController;
import org.casanovo.gui.core.PepMapLauncher;
import org.casanovo.gui.core.Peptides;
import org.casanovo.gui.core.Settings;

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

    /** Protein-level aggregate. {@code coverage} is null when the protein is not found in the FASTA. */
    private record ProteinRow(String protein, int totalPeptides, int uniquePeptides,
                              int totalPsms, int uniquePsms, Double coverage) {
    }

    /** One peptide mapped to the currently-selected protein. {@code unique} = maps to one protein only. */
    private record PeptideRow(String peptide, int start, int end, String preAa, String postAa,
                              int psms, boolean unique, double bestScore) {
    }

    /** A peptide that maps to two or more proteins. */
    private record MappedRow(String peptide, int proteinCount, String proteins, int psms,
                             double bestScore) {
    }

    /** A de novo peptide that mapped to no protein. */
    private record UnmappedRow(String peptide, int psms, int length, double bestScore) {
    }

    /** Everything derived from one completed mapping; computed off-thread, applied on the FX thread. */
    private record Results(List<ProteinRow> proteins,
                           Map<String, List<MapRow>> rowsByProtein,
                           Map<String, Set<String>> proteinsByPeptide,
                           Map<String, Integer> psmByPeptide,
                           Map<String, String> seqByProtein,
                           List<MappedRow> mapped,
                           List<UnmappedRow> unmapped,
                           int totalPeptides, int mappedPeptides,
                           int totalPsms, int mappedPsms, int uniquePeptides,
                           double[] cutoffs, int[] mappedByCutoff, int[] totalByCutoff) {
    }

    private static final String UNIQUE_HEX = "#1B7F2E"; // green  – unique-peptide coverage
    private static final String SHARED_HEX = "#E08000"; // orange – shared-peptide coverage
    private static final Color UNIQUE_COLOR = Color.web(UNIQUE_HEX);
    private static final Color SHARED_COLOR = Color.web(SHARED_HEX);
    private static final Color PLAIN_COLOR = Color.web("#444444");
    private static final Font MONO = Font.font("Monospaced", 13);
    private static final Font MONO_BOLD = Font.font("Monospaced", FontWeight.BOLD, 13);
    private static final int COVERAGE_WRAP = 60;
    private static final int ROW_HEIGHT = 26; // compact, consistent row height across all result tables

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
    private final Label overviewLabel = new Label();
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

    // Proteins
    private final TableView<ProteinRow> proteinTable = new TableView<>();
    private final TextField proteinFilter = new TextField();
    private final TextField mappedFilter = new TextField();
    private final TextField unmappedFilter = new TextField();
    private Pager<ProteinRow> proteinPager;

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

    private final Label sharedStatus;          // the window's shared bottom status label (same as de novo)
    private final ProgressBar sharedProgress;  // the window's shared bottom progress bar

    private volatile Process proc;
    private volatile boolean running;
    private volatile boolean cancelled;
    private Results results;
    private Map<String, MzTabScores.BestPsm> bestByPeptide = Map.of(); // bare peptide -> its best-scoring PSM
    private File loadedMzTab; // mzTab backing the current results; re-read for the per-PSM popup
    private Set<String> emptyPsmColumns = Set.of(); // PSM columns null for every PSM — hidden from the popup table

    public ViewPane(Window owner, Settings settings, Label sharedStatus, ProgressBar sharedProgress,
                    Consumer<String> consoleOut, PdvController pdvController) {
        this.owner = owner;
        this.settings = settings;
        this.sharedStatus = sharedStatus;
        this.sharedProgress = sharedProgress;
        this.consoleOut = consoleOut;
        this.pdvController = pdvController;
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
        setRunning(false);
    }

    private VBox buildInputs() {
        Label mzLabel = new Label("Peptides (mzTab):");
        Label faLabel = new Label("Reference DB (FASTA):");
        mzLabel.setMinWidth(140);
        faLabel.setMinWidth(140);
        mzTabField.setPromptText("Casanovo .mzTab result (auto-filled after a run)");
        fastaField.setPromptText("Optional. Reference protein database in FASTA format");
        HBox.setHgrow(mzTabField, Priority.ALWAYS);
        HBox.setHgrow(fastaField, Priority.ALWAYS);
        mzBrowse.setOnAction(e -> browseMzTab());
        faBrowse.setOnAction(e -> browseFasta());
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
        overviewLabel.setStyle("-fx-font-size: 13px;");
        overviewLabel.setMinHeight(Region.USE_PREF_SIZE);

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

        // Score plot first, then the mapping (cutoff) plot, then the top-proteins plot. A FlowPane keeps
        // all three on one row when wide enough and wraps the trailing one(s) otherwise. A 2-plot minimum
        // width keeps at least two columns (a sideways scroll bar appears only if even two won't fit);
        // when there is no mapping only the score plot is shown (see applyResults).
        chartsFlow = new javafx.scene.layout.FlowPane(2, 2, scoreBox, cutoffBox, topBox);
        chartsFlow.setMinWidth(2 * 350 + 2);
        VBox box = new VBox(10, chartsFlow, overviewLabel);
        box.setPadding(new Insets(2, 2, 2, 2));
        box.setVisible(false);
        box.setManaged(false);
        overviewChartsBox = box;
        overviewPlaceholder.setStyle("-fx-opacity: 0.55;");

        // Charts are shown only once there are results: an empty JavaFX chart ignores its size and
        // renders oversized, so until a run completes we show a placeholder instead.
        javafx.scene.layout.StackPane content = new javafx.scene.layout.StackPane(box, overviewPlaceholder);
        javafx.scene.layout.StackPane.setAlignment(box, Pos.TOP_LEFT);

        // Fixed 350x350 plots; if the window is too small to show them, scroll bars appear.
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true); // let the FlowPane wrap to the viewport instead of scrolling sideways
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        return scroll;
    }

    /** Wrap a chart in a fixed 350x350 cell (the cell forces the chart's size); the Overview
        scrolls if the window is too small to show both cells. */
    private static javafx.scene.layout.StackPane fixedCell(javafx.scene.chart.Chart chart) {
        // An empty chart's natural min height is large; drop it to 0 so the fixed cell can force 350.
        chart.setMinSize(0, 0);
        javafx.scene.layout.StackPane cell = new javafx.scene.layout.StackPane(chart);
        cell.setMinSize(350, 350);
        cell.setPrefSize(350, 350);
        cell.setMaxSize(350, 350);
        return cell;
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
        c.setMinSize(0, 0);
        ((NumberAxis) c.getXAxis()).setLabel("Peptides");
        return c;
    }

    private BorderPane buildProteinsTab() {
        strCol(proteinTable, "Protein", ProteinRow::protein, 240,
                "Reference protein identifier (from the FASTA header) that received at least one peptide hit.");
        TableColumn<ProteinRow, Integer> pepGroup = new TableColumn<>("Peptides");
        headerTip(pepGroup, "Peptides", "Distinct peptide sequences mapped to this protein.", false);
        pepGroup.getColumns().add(intColumn("Total", ProteinRow::totalPeptides,
                "All distinct peptides mapped to this protein, including peptides shared with other proteins."));
        pepGroup.getColumns().add(intColumn("Unique", ProteinRow::uniquePeptides,
                "Peptides that map to only this protein."));
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
        proteinTable.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                ProteinRow pr = proteinTable.getSelectionModel().getSelectedItem();
                if (pr != null) {
                    proteinSelector.setValue(pr.protein());
                    resultTabs.getSelectionModel().select(proteinViewTab);
                }
            }
        });

        proteinPager = new Pager<>(proteinTable);
        proteinPager.setFilter((p, q) -> p.protein().toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)));
        proteinFilter.setPromptText("Filter by protein…");
        proteinFilter.setPrefWidth(260);
        proteinFilter.textProperty().addListener((o, a, b) -> proteinPager.setQuery(b));
        HBox top = new HBox(8, new Label("Filter:"), proteinFilter,
                new Label("(double-click a row to open it in Protein view)"));
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
        Label pepHint = peptideHint();
        BorderPane.setMargin(pepHint, new Insets(4, 0, 0, 2));
        pepPane.setBottom(pepHint);

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
        strCol(mappedTable, "Proteins", MappedRow::proteins, 380,
                "List of all proteins this peptide maps to.");
        mappedTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        mappedTable.setFixedCellSize(ROW_HEIGHT);
        mappedTable.setPlaceholder(new Label("No mapped peptides."));
        onRowDoubleClick(mappedTable, MappedRow::peptide);
        mappedPager = new Pager<>(mappedTable);
        mappedPager.setFilter((r, q) -> r.peptide().toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)));
        mappedFilter.setPromptText("Filter by peptide…");
        mappedFilter.setPrefWidth(260);
        mappedFilter.textProperty().addListener((o, a, b) -> mappedPager.setQuery(b));
        HBox top = new HBox(8, new Label("Filter:"), mappedFilter);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(0, 0, 6, 0));
        BorderPane bp = new BorderPane(mappedTable);
        bp.setTop(top);
        Region hintSpacer = new Region();
        HBox.setHgrow(hintSpacer, Priority.ALWAYS);
        HBox bottom = new HBox(8, peptideHint(), hintSpacer, mappedPager.bar);
        bottom.setAlignment(Pos.CENTER_LEFT);
        bp.setBottom(bottom);
        BorderPane.setMargin(bottom, new Insets(6, 0, 0, 0));
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
        unmappedFilter.setPromptText("Filter by peptide…");
        unmappedFilter.setPrefWidth(260);
        unmappedFilter.textProperty().addListener((o, a, b) -> unmappedPager.setQuery(b));
        HBox top = new HBox(8, new Label("Filter:"), unmappedFilter);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(0, 0, 6, 0));
        BorderPane bp = new BorderPane(unmappedTable);
        bp.setTop(top);
        Region hintSpacer = new Region();
        HBox.setHgrow(hintSpacer, Priority.ALWAYS);
        HBox bottom = new HBox(8, peptideHint(), hintSpacer, unmappedPager.bar);
        bottom.setAlignment(Pos.CENTER_LEFT);
        bp.setBottom(bottom);
        BorderPane.setMargin(bottom, new Insets(6, 0, 0, 0));
        return bp;
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

    /** A muted one-line hint pointing users to the double-click per-residue plot. */
    private static Label peptideHint() {
        Label l = new Label("ⓘ Double-click a peptide to see its per-residue confidence.");
        l.setStyle("-fx-font-size: 11px; -fx-opacity: 0.6;");
        return l;
    }

    private static HBox buildLegend() {
        HBox h = new HBox(18, swatch(UNIQUE_HEX, "unique"), swatch(SHARED_HEX, "shared"));
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
        private final List<T> working = new ArrayList<>();
        private final Button prev = new Button("‹ Prev");
        private final Button next = new Button("Next ›");
        private final Label info = new Label();
        private final HBox bar;
        private BiPredicate<T, String> filter;
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
                refresh();
                return true;
            });
            prev.setOnAction(e -> {
                page--;
                refresh();
            });
            next.setOnAction(e -> {
                page++;
                refresh();
            });
            info.setMinWidth(150);
            bar = new HBox(8, prev, next, info);
            bar.setAlignment(Pos.CENTER_LEFT);
            refresh();
        }

        void setFilter(BiPredicate<T, String> f) {
            this.filter = f;
        }

        void setData(List<T> data) {
            model.setAll(data);
            page = 0;
            refresh();
            TableUtils.autoSizeColumns(table, 60); // fit columns to this page's content, capped at 60 chars
        }

        void setQuery(String q) {
            query = q == null ? "" : q.trim();
            page = 0;
            refresh();
        }

        private void refresh() {
            working.clear();
            for (T t : model) {
                if (filter == null || query.isEmpty() || filter.test(t, query)) {
                    working.add(t);
                }
            }
            Comparator<T> c = table.getComparator();
            if (c != null) {
                working.sort(c);
            }
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
                    best != null ? best.score() : Double.NaN));
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
        });
    }

    private void renderCoverage(String protein) {
        String seq = results.seqByProtein().get(protein);
        if (seq == null || seq.isEmpty()) {
            coverageLabel.setText("Coverage: protein sequence not found in the FASTA.");
            return;
        }
        int[] cat = coverageCategories(results.rowsByProtein().getOrDefault(protein, List.of()),
                results.proteinsByPeptide(), seq);
        int covered = 0;
        for (int c : cat) {
            if (c > 0) {
                covered++;
            }
        }
        coverageLabel.setText(String.format(Locale.US,
                "Coverage: %.1f%%  (%d / %d residues)", 100.0 * covered / seq.length(), covered, seq.length()));

        for (int start = 0; start < seq.length(); start += COVERAGE_WRAP) {
            int end = Math.min(start + COVERAGE_WRAP, seq.length());
            TextFlow line = new TextFlow();
            Text pos = new Text(String.format("%6d  ", start + 1));
            pos.setFont(MONO);
            pos.setFill(Color.web("#999999"));
            line.getChildren().add(pos);
            int i = start;
            while (i < end) {
                int c = cat[i];
                int j = i;
                while (j < end && cat[j] == c) {
                    j++;
                }
                Text t = new Text(seq.substring(i, j));
                t.setFont(c > 0 ? MONO_BOLD : MONO);
                t.setFill(c == 2 ? UNIQUE_COLOR : c == 1 ? SHARED_COLOR : PLAIN_COLOR);
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

    private static <S> void intCol(TableView<S> table, String name, ToIntFunction<S> getter, String tip) {
        TableColumn<S, Integer> col = new TableColumn<>(name);
        col.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(getter.applyAsInt(d.getValue())));
        col.setStyle("-fx-alignment: CENTER-RIGHT;");
        col.setPrefWidth(92);
        headerTip(col, name, tip, true);
        table.getColumns().add(col);
    }

    /** A right-aligned integer column returned (not added) — for nesting under a group header. */
    private static <S> TableColumn<S, Integer> intColumn(String name, ToIntFunction<S> getter, String tip) {
        TableColumn<S, Integer> col = new TableColumn<>(name);
        col.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(getter.applyAsInt(d.getValue())));
        col.setStyle("-fx-alignment: CENTER-RIGHT;");
        col.setPrefWidth(72);
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
                new FileChooser.ExtensionFilter("mzTab", "*.mztab", "*.mzTab"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        initialDir(fc, mzTabField.getText());
        File f = fc.showOpenDialog(owner);
        if (f != null) {
            mzTabField.setText(f.getAbsolutePath());
        }
    }

    private void browseFasta() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select reference protein database (FASTA)");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("FASTA", "*.fasta", "*.fa", "*.fas", "*.faa"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        initialDir(fc, fastaField.getText());
        File f = fc.showOpenDialog(owner);
        if (f != null) {
            fastaField.setText(f.getAbsolutePath());
        }
    }

    private static void initialDir(FileChooser fc, String currentPath) {
        String cur = currentPath == null ? "" : currentPath.trim();
        if (!cur.isEmpty()) {
            File parent = new File(cur).getParentFile();
            if (parent != null && parent.isDirectory()) {
                fc.setInitialDirectory(parent);
            }
        }
    }

    // ---- run ---------------------------------------------------------------

    private void runMapping() {
        if (running) {
            return;
        }
        File mzTab = new File(mzTabField.getText().trim());
        String fastaText = fastaField.getText().trim();
        File fasta = fastaText.isEmpty() ? null : new File(fastaText); // optional: no FASTA -> all unmapped
        if (mzTabField.getText().trim().isEmpty() || !mzTab.isFile()) {
            status("Choose a valid mzTab file first.");
            return;
        }
        if (fasta != null && !fasta.isFile()) {
            status("Reference FASTA not found. Clear it to load all peptides as unmapped, or pick a valid file.");
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
                new FileChooser.ExtensionFilter("Spectra (*.mzML, *.mzXML, *.mgf)", "*.mzML", "*.mzXML", "*.mgf"));
        File parent = mzTab.getParentFile();
        if (parent != null && parent.isDirectory()) {
            fc.setInitialDirectory(parent);
        }
        List<File> picked = fc.showOpenMultipleDialog(owner);
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
                Results res = computeResults(List.of(), psms, null);
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
                Results res = computeResults(rows, psms, fasta);
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
            // Only clean up the transient input when we fell back to a temp dir. When the output
            // lives next to the mzTab the user wants those files kept (results + peptides.txt + log).
            if (usingTempDir && pepFile != null) {
                try {
                    Files.deleteIfExists(pepFile.toPath());
                } catch (IOException ignored) {
                    // best effort
                }
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

    private static Results computeResults(List<MapRow> rows, List<MzTabScores.Psm> psms, File fasta)
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

        // peptide -> proteins ; protein -> its detail rows.
        Map<String, Set<String>> proteinsByPeptide = new HashMap<>();
        Map<String, List<MapRow>> rowsByProtein = new LinkedHashMap<>();
        for (MapRow r : rows) {
            proteinsByPeptide.computeIfAbsent(bare(r.peptide()), k -> new LinkedHashSet<>()).add(r.protein());
            rowsByProtein.computeIfAbsent(r.protein(), k -> new ArrayList<>()).add(r);
        }

        Map<String, String> seqByProtein = readFastaSequences(fasta, rowsByProtein.keySet());

        List<ProteinRow> proteinRows = new ArrayList<>();
        for (Map.Entry<String, List<MapRow>> en : rowsByProtein.entrySet()) {
            Set<String> peps = new LinkedHashSet<>();
            for (MapRow r : en.getValue()) {
                peps.add(bare(r.peptide()));
            }
            int uniquePep = 0;
            int totalPsm = 0;
            int uniquePsm = 0;
            for (String pep : peps) {
                int c = psmByPeptide.getOrDefault(pep, 0);
                totalPsm += c;
                if (proteinsByPeptide.getOrDefault(pep, Set.of()).size() <= 1) {
                    uniquePep++;
                    uniquePsm += c;
                }
            }
            Double cov = coverage(en.getValue(), proteinsByPeptide, seqByProtein.get(en.getKey()));
            proteinRows.add(new ProteinRow(en.getKey(), peps.size(), uniquePep, totalPsm, uniquePsm, cov));
        }
        proteinRows.sort(Comparator.comparingInt(ProteinRow::totalPeptides).reversed()
                .thenComparing(ProteinRow::protein));

        // every mapped peptide (>=1 protein); also count the proteotypic (single-protein) ones.
        List<MappedRow> mappedRows = new ArrayList<>();
        int uniquePeptides = 0;
        for (Map.Entry<String, Set<String>> en : proteinsByPeptide.entrySet()) {
            mappedRows.add(new MappedRow(en.getKey(), en.getValue().size(),
                    String.join("; ", en.getValue()), psmByPeptide.getOrDefault(en.getKey(), 0),
                    bestScore.getOrDefault(en.getKey(), Double.NaN)));
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

        return new Results(proteinRows, rowsByProtein, proteinsByPeptide, psmByPeptide, seqByProtein,
                mappedRows, unmapped, allPeptides.size(), mapped.size(), totalPsms, mappedPsms, uniquePeptides,
                cutoffs, mappedByCutoff, totalByCutoff);
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

    private void applyResults(Results res) {
        this.results = res;
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
        overviewLabel.setText(overviewText(res));
        // Mapping plots (cutoff + top proteins) only make sense when peptides actually mapped; with no
        // mapping (e.g. no FASTA) show just the score plot. The score plot is always present.
        boolean hasMapping = !res.mapped().isEmpty();
        cutoffBox.setVisible(hasMapping);
        cutoffBox.setManaged(hasMapping);
        topBox.setVisible(hasMapping);
        topBox.setManaged(hasMapping);
        chartsFlow.setMinWidth(hasMapping ? 2 * 350 + 2 : Region.USE_COMPUTED_SIZE);
        buildScoreChart();
        if (hasMapping) {
            buildTopChart(res);
            buildCutoffChart(res);
        }
    }

    /** Render the score plot (cumulative PSM or peptide counts vs peptide score) for the current mode. */
    private void buildScoreChart() {
        scoreChart.getData().clear();
        if (scoreCurve == null) {
            return;
        }
        String mode = scoreMode.getValue() == null ? "PSMs" : scoreMode.getValue();
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

    private static String overviewText(Results r) {
        int unmapped = r.totalPeptides() - r.mappedPeptides();
        double rate = r.totalPeptides() == 0 ? 0.0 : 100.0 * r.mappedPeptides() / r.totalPeptides();
        return String.format(Locale.US,
                "Peptides:  %,d total   ·   %,d mapped (%.1f%%)   ·   %,d unmapped\n"
                        + "Proteins:  %,d   ·   unique peptides: %,d   ·   shared peptides: %,d\n"
                        + "PSMs:  %,d mapped / %,d total",
                r.totalPeptides(), r.mappedPeptides(), rate, unmapped,
                r.proteins().size(), r.uniquePeptides(), r.mapped().size() - r.uniquePeptides(),
                r.mappedPsms(), r.totalPsms());
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
        overviewLabel.setText("");
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
