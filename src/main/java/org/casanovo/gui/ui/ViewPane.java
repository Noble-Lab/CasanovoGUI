package org.casanovo.gui.ui;

import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.SnapshotParameters;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.transform.Transform;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.casanovo.gui.core.MzTabScores;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Analysis tab (not a Casanovo command): loads a Casanovo mzTab result and plots,
 * for score thresholds from 0 to 1 (configurable), the number of PSMs and the
 * number of unique peptide sequences whose {@code search_engine_score[1]} is at or
 * above each threshold. The file input sits on top, the chart fills the center, and
 * the plot settings sit on the right.
 */
public class ViewPane extends BorderPane {

    private final Window owner;
    private final TextField fileField = new TextField();
    private final NumberAxis xAxis = new NumberAxis();
    private final NumberAxis yAxis = new NumberAxis();
    private final LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);

    private final Spinner<Double> minSpin = new Spinner<>(0.0, 1.0, 0.0, 0.05);
    private final Spinner<Double> maxSpin = new Spinner<>(0.0, 1.0, 1.0, 0.05);
    private final Spinner<Double> stepSpin = new Spinner<>(0.01, 1.0, 0.05, 0.01);
    private final CheckBox showPsms = new CheckBox("PSMs");
    private final CheckBox showPeptides = new CheckBox("Unique peptides");
    private final Label statusLabel = new Label("Browse to a Casanovo .mzTab result to plot.");

    /** PSMs from the last successfully read file; null until a file is loaded. */
    private List<MzTabScores.Psm> psms;

    public ViewPane(Window owner) {
        this.owner = owner;
        setPadding(new Insets(10));
        java.net.URL css = getClass().getResource("/org/casanovo/gui/settings.css");
        if (css != null) {
            getStylesheets().add(css.toExternalForm());
        }

        Label fileLabel = new Label("Result (mzTab):");
        fileField.setPromptText("Path to a Casanovo .mzTab result");
        fileField.setOnAction(e -> loadAndPlot());
        HBox.setHgrow(fileField, Priority.ALWAYS);
        Button browse = new Button("Browse");
        browse.setOnAction(e -> browse());
        HBox top = new HBox(8, fileLabel, fileField, browse);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(0, 0, 8, 0));
        setTop(top);

        xAxis.setLabel("Peptide score (search_engine_score[1])");
        yAxis.setLabel("Count (≥ threshold)");
        yAxis.setForceZeroInRange(true);
        chart.setAnimated(false);
        chart.setCreateSymbols(true);
        chart.setMinHeight(280);

        // Chart + settings + status, wrapped in a ScrollPane so a vertical scroll bar
        // appears when the window is too short to show the whole chart (including its
        // x-axis) next to the settings panel. fitToHeight lets the chart grow to fill a
        // tall window while still honoring the content's minimum height when short.
        BorderPane body = new BorderPane(chart);
        body.setRight(buildSettings());
        BorderPane.setMargin(statusLabel, new Insets(6, 0, 0, 0));
        body.setBottom(statusLabel);

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        setCenter(scroll);
    }

    private VBox buildSettings() {
        Label title = new Label("Plot settings");
        title.setStyle("-fx-font-weight: bold;");

        configSpinner(minSpin);
        configSpinner(maxSpin);
        configSpinner(stepSpin);
        showPsms.setSelected(true);
        showPeptides.setSelected(true);

        // Re-render from the cached PSMs (no file re-read) when any setting changes.
        Runnable refresh = () -> {
            if (psms != null) {
                updateChart();
            }
        };
        minSpin.valueProperty().addListener((o, a, b) -> refresh.run());
        maxSpin.valueProperty().addListener((o, a, b) -> refresh.run());
        stepSpin.valueProperty().addListener((o, a, b) -> refresh.run());
        showPsms.setOnAction(e -> refresh.run());
        showPeptides.setOnAction(e -> refresh.run());

        Button save = new Button("Save image…");
        save.setMaxWidth(Double.MAX_VALUE);
        save.setOnAction(e -> saveImage());
        Button view = new Button("View data…");
        view.setMaxWidth(Double.MAX_VALUE);
        view.setOnAction(e -> viewData());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        gridRow(grid, 0, "Score min", minSpin);
        gridRow(grid, 1, "Score max", maxSpin);
        gridRow(grid, 2, "Step", stepSpin);

        VBox box = new VBox(8,
                title,
                grid,
                new Separator(),
                new Label("Series"), showPsms, showPeptides,
                new Separator(),
                save, view);
        box.setPadding(new Insets(0, 0, 0, 12));
        box.setFillWidth(true);
        return box;
    }

    private static void configSpinner(Spinner<Double> s) {
        s.setEditable(true);
        s.getStyleClass().add("compact-spinner");
        s.getEditor().setPrefColumnCount(4);
        s.getEditor().setAlignment(Pos.CENTER_RIGHT);
        s.setMaxWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        // Commit typed text on focus loss so a hand-entered value is not dropped.
        s.focusedProperty().addListener((o, was, now) -> {
            if (!now) {
                try {
                    s.getValueFactory().setValue(Double.parseDouble(s.getEditor().getText().trim()));
                } catch (NumberFormatException ignored) {
                    s.getEditor().setText(s.getValue().toString());
                }
            }
        });
    }

    /** Add a settings row: a right-aligned label in column 0, the narrow control in column 1. */
    private static void gridRow(GridPane g, int row, String label, javafx.scene.control.Control control) {
        Label l = new Label(label);
        GridPane.setHalignment(l, HPos.RIGHT);
        g.add(l, 0, row);
        g.add(control, 1, row);
    }

    private void browse() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select Casanovo result (mzTab)");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("mzTab", "*.mztab", "*.mzTab"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        String cur = fileField.getText().trim();
        if (!cur.isEmpty()) {
            File parent = new File(cur).getParentFile();
            if (parent != null && parent.isDirectory()) {
                fc.setInitialDirectory(parent);
            }
        }
        File f = fc.showOpenDialog(owner);
        if (f != null) {
            fileField.setText(f.getAbsolutePath());
            loadAndPlot();
        }
    }

    /**
     * Point the panel at {@code mzTab} and render its plot. Used to auto-populate the
     * tab after a successful Casanovo run; safe to call on the JavaFX thread.
     */
    public void showResult(File mzTab) {
        if (mzTab == null) {
            return;
        }
        fileField.setText(mzTab.getAbsolutePath());
        loadAndPlot();
    }

    /** Read the file in the path field, cache its PSMs, and (re)draw the chart. */
    private void loadAndPlot() {
        String path = fileField.getText().trim();
        if (path.isEmpty()) {
            statusLabel.setText("Choose an mzTab file first.");
            return;
        }
        File f = new File(path);
        if (!f.isFile()) {
            statusLabel.setText("File not found: " + path);
            return;
        }
        try {
            psms = MzTabScores.read(f);
        } catch (IOException ex) {
            psms = null;
            chart.getData().clear();
            statusLabel.setText("Could not read mzTab: " + ex.getMessage());
            return;
        }
        if (psms.isEmpty()) {
            chart.getData().clear();
            statusLabel.setText("No scored PSMs found in the file.");
            return;
        }
        updateChart();
    }

    /** Recompute the curve from the cached PSMs + current settings and update the chart. */
    private void updateChart() {
        double min = minSpin.getValue();
        double max = maxSpin.getValue();
        double step = stepSpin.getValue();
        if (step <= 0 || max <= min) {
            statusLabel.setText("Invalid score range (need max > min and step > 0).");
            return;
        }
        MzTabScores.Curve curve = MzTabScores.cumulativeCounts(psms, min, max, step);
        chart.getData().clear();
        if (showPsms.isSelected()) {
            chart.getData().add(series("PSMs", curve.thresholds(), curve.psmCounts()));
        }
        if (showPeptides.isSelected()) {
            chart.getData().add(series("Unique peptides", curve.thresholds(), curve.peptideCounts()));
        }
        xAxis.setAutoRanging(false);
        xAxis.setLowerBound(min);
        xAxis.setUpperBound(max);
        xAxis.setTickUnit(step);
        statusLabel.setText(String.format(Locale.US,
                "%d PSMs loaded — %d PSMs / %d unique peptides at score ≥ %.2f.",
                psms.size(), curve.psmCounts()[0], curve.peptideCounts()[0], min));
    }

    private static XYChart.Series<Number, Number> series(String name, double[] x, int[] y) {
        XYChart.Series<Number, Number> s = new XYChart.Series<>();
        s.setName(name);
        for (int i = 0; i < x.length; i++) {
            s.getData().add(new XYChart.Data<>(x[i], y[i]));
        }
        return s;
    }

    /** Show the underlying counts as a table for the current settings. */
    private void viewData() {
        if (psms == null) {
            statusLabel.setText("Plot a file first.");
            return;
        }
        double min = minSpin.getValue();
        double max = maxSpin.getValue();
        double step = stepSpin.getValue();
        if (step <= 0 || max <= min) {
            statusLabel.setText("Invalid score range (need max > min and step > 0).");
            return;
        }
        MzTabScores.Curve c = MzTabScores.cumulativeCounts(psms, min, max, step);

        record Row(double threshold, int psms, int peptides) {
        }
        TableView<Row> table = new TableView<>();
        TableColumn<Row, String> tCol = new TableColumn<>("Score ≥");
        tCol.setCellValueFactory(d -> new ReadOnlyStringWrapper(
                String.format(Locale.US, "%.2f", d.getValue().threshold())));
        TableColumn<Row, Number> pCol = new TableColumn<>("PSMs");
        pCol.setCellValueFactory(d -> new ReadOnlyIntegerWrapper(d.getValue().psms()));
        TableColumn<Row, Number> qCol = new TableColumn<>("Unique peptides");
        qCol.setCellValueFactory(d -> new ReadOnlyIntegerWrapper(d.getValue().peptides()));
        table.getColumns().add(tCol);
        table.getColumns().add(pCol);
        table.getColumns().add(qCol);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        for (int i = 0; i < c.thresholds().length; i++) {
            table.getItems().add(new Row(c.thresholds()[i], c.psmCounts()[i], c.peptideCounts()[i]));
        }

        Dialog<Void> dlg = new Dialog<>();
        dlg.initOwner(owner);
        dlg.setTitle("Counts vs. score threshold");
        dlg.setResizable(true);
        dlg.getDialogPane().setContent(table);
        dlg.getDialogPane().setPrefSize(420, 520);
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dlg.showAndWait();
    }

    private void saveImage() {
        if (psms == null || chart.getData().isEmpty()) {
            statusLabel.setText("Nothing to save yet — plot a file first.");
            return;
        }
        FileChooser fc = new FileChooser();
        fc.setTitle("Save plot as PNG");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG image", "*.png"));
        fc.setInitialFileName("casanovo_score_plot.png");
        String cur = fileField.getText().trim();
        if (!cur.isEmpty()) {
            File parent = new File(cur).getParentFile();
            if (parent != null && parent.isDirectory()) {
                fc.setInitialDirectory(parent);
            }
        }
        File out = fc.showSaveDialog(owner);
        if (out == null) {
            return;
        }
        if (!out.getName().toLowerCase(Locale.ROOT).endsWith(".png")) {
            out = new File(out.getAbsolutePath() + ".png");
        }
        try {
            writePng(chart, out, 2.0);
            statusLabel.setText("Saved image: " + out.getAbsolutePath());
        } catch (IOException ex) {
            statusLabel.setText("Could not save image: " + ex.getMessage());
        }
    }

    /** Snapshot a node to a PNG at {@code scale}x, using ImageIO (java.desktop). */
    private static void writePng(javafx.scene.Node node, File out, double scale) throws IOException {
        SnapshotParameters params = new SnapshotParameters();
        params.setTransform(Transform.scale(scale, scale));
        WritableImage img = node.snapshot(params, null);
        int w = (int) Math.round(img.getWidth());
        int h = (int) Math.round(img.getHeight());
        java.awt.image.BufferedImage bimg =
                new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        PixelReader pr = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                bimg.setRGB(x, y, pr.getArgb(x, y));
            }
        }
        javax.imageio.ImageIO.write(bimg, "png", out);
    }
}
