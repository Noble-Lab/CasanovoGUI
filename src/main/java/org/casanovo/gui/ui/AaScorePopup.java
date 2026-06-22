package org.casanovo.gui.ui;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.casanovo.gui.core.MzTabScores;

import java.util.List;
import java.util.Locale;

/**
 * Reusable popup showing a peptide's per-residue confidence ({@link AaScoreChart}) on top and, below
 * it, a table of every PSM for that peptide — all mzTab columns, sorted by peptide score. A single
 * window is reused across calls. The best PSM (first row) is selected by default; selecting another
 * row re-renders the chart for that PSM. Any panel can call {@link #show}.
 */
public final class AaScorePopup {

    private static Stage stage;
    private static AaScoreChart chart;
    private static TableView<MzTabScores.PsmRow> table;
    private static String currentPeptide = "";

    private static final double COL_MIN = 60;    // never narrower than this
    private static final double COL_MAX = 320;   // cap; long values (aa_scores, spectra_ref) scroll within the cell
    private static final double COL_PAD = 16;    // fixed cell padding slack
    private static final double HEADER_PAD = 24; // header label padding
    private static final double FUDGE = 1.15;    // our Font.font(13) probe underestimates the cell render font

    private AaScorePopup() {
    }

    /**
     * Show (or re-render) the PSMs of {@code peptide}. {@code columns} are the mzTab PSM column names;
     * {@code rows} are its PSM rows (already sorted best-first).
     */
    public static void show(Window owner, String peptide, List<String> columns, List<MzTabScores.PsmRow> rows) {
        if (stage == null) {
            build(owner);
        }
        currentPeptide = peptide;
        rebuildColumns(columns, rows);
        table.getItems().setAll(rows);
        stage.setTitle("Per-residue confidence — " + peptide
                + "  (" + rows.size() + " PSM" + (rows.size() == 1 ? "" : "s") + ")");
        if (stage.isShowing()) {
            stage.toFront();
        } else {
            stage.show();
        }
        if (rows.isEmpty()) {
            chart.setData(peptide, new double[0], null);
        } else {
            // Render the best PSM right away, then highlight its row once the table is laid out
            // (selecting before layout can land on the wrong row).
            MzTabScores.PsmRow best = rows.get(0);
            chart.setData(peptide, best.aaScores(),
                    String.format(Locale.US, "Peptide score: %.4f", best.score()));
            javafx.application.Platform.runLater(() -> {
                table.getSelectionModel().clearAndSelect(0);
                table.scrollTo(0);
            });
        }
    }

    private static void build(Window owner) {
        chart = new AaScoreChart();
        chart.setMinHeight(280);

        table = new TableView<>();
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.setFixedCellSize(26); // compact rows, matching the Mapping result tables
        table.setPlaceholder(new Label("No PSMs for this peptide."));
        TableUtils.enableCellCopy(table);
        // Re-render the chart for whichever PSM is selected (uses the current peptide's residues).
        table.getSelectionModel().selectedItemProperty().addListener((o, a, row) -> {
            if (row != null) {
                chart.setData(currentPeptide, row.aaScores(),
                        String.format(Locale.US, "Peptide score: %.4f", row.score()));
            }
        });

        Label tableTitle = new Label("PSMs (sorted by peptide score)");
        tableTitle.setStyle("-fx-font-weight: bold;");
        VBox tableBox = new VBox(4, tableTitle, table);
        tableBox.setPadding(new Insets(8, 10, 10, 10));
        tableBox.getStyleClass().add("result-tabs"); // compact column headers via settings.css
        VBox.setVgrow(table, Priority.ALWAYS);

        VBox root = new VBox(chart, tableBox);
        VBox.setVgrow(tableBox, Priority.ALWAYS);

        stage = new Stage();
        if (owner != null) {
            stage.initOwner(owner);
        }
        try (java.io.InputStream icon = AaScorePopup.class.getResourceAsStream("/org/casanovo/gui/icon.png")) {
            if (icon != null) {
                stage.getIcons().add(new javafx.scene.image.Image(icon));
            }
        } catch (java.io.IOException ignored) {
            // no icon is fine
        }
        Scene scene = new Scene(root, 940, 620); // ~10 compact rows visible by default
        java.net.URL css = AaScorePopup.class.getResource("/org/casanovo/gui/settings.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        stage.setScene(scene);
    }

    private static void rebuildColumns(List<String> columns, List<MzTabScores.PsmRow> rows) {
        table.getColumns().clear();
        Text probe = new Text();
        probe.setFont(Font.font(13)); // ~the table cell font, for a quick width estimate
        for (int i = 0; i < columns.size(); i++) {
            final int idx = i;
            TableColumn<MzTabScores.PsmRow, String> col = new TableColumn<>(columns.get(i));
            col.setCellValueFactory(d -> {
                String[] v = d.getValue().values();
                return new ReadOnlyStringWrapper(idx < v.length ? v[idx] : "");
            });
            // Fit to the wider of the header and the longest cell value (longest picked by length,
            // measured once), then clamp to [COL_MIN, COL_MAX].
            probe.setText(columns.get(i));
            double w = probe.getLayoutBounds().getWidth() * FUDGE + HEADER_PAD;
            String widestCell = "";
            for (MzTabScores.PsmRow r : rows) {
                String[] v = r.values();
                String cell = (idx < v.length && v[idx] != null) ? v[idx] : "";
                if (cell.length() > widestCell.length()) {
                    widestCell = cell;
                }
            }
            probe.setText(widestCell);
            w = Math.max(w, probe.getLayoutBounds().getWidth() * FUDGE + COL_PAD);
            // Never cap the peptide (sequence) column, so the full sequence is always shown.
            double cap = columns.get(i).equalsIgnoreCase("sequence") ? Double.MAX_VALUE : COL_MAX;
            col.setPrefWidth(Math.max(COL_MIN, Math.min(w, cap)));
            table.getColumns().add(col);
        }
    }
}
