package org.casanovo.gui.ui;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.casanovo.gui.core.MzTabScores;

import java.util.List;
import java.util.Locale;
import java.util.Set;

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
    private static java.util.function.Consumer<MzTabScores.PsmRow> onRowActivate; // double-click a row -> drive PDV

    private AaScorePopup() {
    }

    /**
     * Show (or re-render) the PSMs of {@code peptide}. {@code columns} are the mzTab PSM column names;
     * {@code rows} are its PSM rows (already sorted best-first). {@code onDoubleClick} (nullable) is
     * invoked with the row the user double-clicks — used to drive an open PDV to that PSM's spectrum.
     */
    public static void show(Window owner, String peptide, List<String> columns, List<MzTabScores.PsmRow> rows,
                            Set<String> emptyColumns, java.util.function.Consumer<MzTabScores.PsmRow> onDoubleClick) {
        if (stage == null) {
            build(owner);
        }
        onRowActivate = onDoubleClick;
        currentPeptide = peptide;
        rebuildColumns(columns, emptyColumns);
        table.getItems().setAll(rows);
        TableUtils.autoSizeColumns(table, 60); // fit columns to header + content, capped at 60 chars
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
        // Keep the chart at least as tall as its own content (residues + bars + x-axis + legend). If it
        // is allowed to shrink below that in a short window, the BorderPane's bottom (legend) overlaps
        // the canvas's x-axis. The table takes any remaining space (and scrolls when space is tight).
        chart.setMinHeight(250);

        table = new TableView<>();
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.setFixedCellSize(26); // compact rows, matching the Mapping result tables
        table.setPlaceholder(new Label("No PSMs for this peptide."));
        // Double-clicking a PSM row drives an open PDV to that PSM's spectrum (callback set per show()).
        table.setRowFactory(tv -> {
            javafx.scene.control.TableRow<MzTabScores.PsmRow> r = new javafx.scene.control.TableRow<>();
            r.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !r.isEmpty() && onRowActivate != null) {
                    onRowActivate.accept(r.getItem());
                }
            });
            return r;
        });
        TableUtils.enableCellCopy(table);
        // Re-render the chart for whichever PSM is selected (uses the current peptide's residues), and
        // also drive an open PDV to that PSM's spectrum — so selecting a row signals PDV, same as a
        // double-click does.
        table.getSelectionModel().selectedItemProperty().addListener((o, a, row) -> {
            if (row != null) {
                chart.setData(currentPeptide, row.aaScores(),
                        String.format(Locale.US, "Peptide score: %.4f", row.score()));
                if (onRowActivate != null) {
                    onRowActivate.accept(row);
                }
            }
        });

        Label tableTitle = new Label("PSMs (sorted by peptide score)");
        tableTitle.setStyle("-fx-font-weight: bold;");
        VBox tableBox = new VBox(4, tableTitle, table);
        tableBox.setPadding(new Insets(2, 10, 10, 10));
        tableBox.getStyleClass().add("result-tabs"); // compact column headers via settings.css
        VBox.setVgrow(table, Priority.ALWAYS);

        VBox root = new VBox(chart, tableBox);
        // Match the main window's base font (set on its root in MainApp) so the PSM table renders
        // identically to the View tab's tables; a separate popup Stage otherwise falls back to the
        // AtlantaFX default (System 14px) instead of the app's Segoe UI 13px. The chart sets its own
        // Arial font, so it is unaffected.
        root.setStyle("-fx-font-family: 'Segoe UI', 'Inter', 'SF Pro Text', 'Helvetica Neue', sans-serif; -fx-font-size: 13px;");
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

    private static void rebuildColumns(List<String> columns, Set<String> emptyColumns) {
        table.getColumns().clear();
        for (int i = 0; i < columns.size(); i++) {
            if (emptyColumns != null && emptyColumns.contains(columns.get(i))) {
                continue; // column is null for every PSM in the mzTab — don't show it
            }
            final int idx = i;
            TableColumn<MzTabScores.PsmRow, String> col = new TableColumn<>(columns.get(i));
            col.setCellValueFactory(d -> {
                String[] v = d.getValue().values();
                return new ReadOnlyStringWrapper(idx < v.length ? v[idx] : "");
            });
            if (columns.get(i).equalsIgnoreCase("sequence")) {
                col.getProperties().put(TableUtils.NO_CAP, true); // show the full peptide sequence, uncapped
            }
            table.getColumns().add(col);
        }
        // Widths are set by TableUtils.autoSizeColumns once the rows are in (see show()).
    }
}
