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
    private static Label pdvHint; // shown only while PDV is active (visibility set per show())
    private static String currentPeptide = "";
    private static java.util.function.Consumer<MzTabScores.PsmRow> onRowActivate; // double-click a row -> drive PDV

    private AaScorePopup() {
    }

    /**
     * Show (or re-render) the PSMs of {@code peptide}. {@code columns} are the mzTab PSM column names;
     * {@code rows} are its PSM rows (already sorted best-first). {@code onDoubleClick} (nullable) is
     * invoked with the row the user double-clicks — used to drive an open PDV to that PSM's spectrum.
     * {@code pdvActive} shows a hint about navigating PSMs into PDV (only meaningful when PDV is on).
     */
    public static void show(Window owner, String peptide, List<String> columns, List<MzTabScores.PsmRow> rows,
                            Set<String> emptyColumns, java.util.function.Consumer<MzTabScores.PsmRow> onDoubleClick,
                            boolean pdvActive) {
        if (stage == null) {
            build(owner);
        }
        onRowActivate = onDoubleClick;
        pdvHint.setVisible(pdvActive);
        pdvHint.setManaged(pdvActive);
        currentPeptide = peptide;
        rebuildColumns(columns, emptyColumns, rows);
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
        pdvHint = new Label("ⓘ Select a row, single-click it, or use ↑/↓ to navigate PSMs and show the "
                + "selected PSM's annotated spectrum in PDV.");
        pdvHint.setStyle("-fx-font-size: 11px; -fx-opacity: 0.6;");
        VBox tableBox = new VBox(4, tableTitle, pdvHint, table);
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

    private static void rebuildColumns(List<String> columns, Set<String> emptyColumns,
                                       List<MzTabScores.PsmRow> rows) {
        table.getColumns().clear();
        int expIdx = indexOf(columns, "exp_mass_to_charge");
        int calcIdx = indexOf(columns, "calc_mass_to_charge");
        for (int i = 0; i < columns.size(); i++) {
            String name = columns.get(i);
            if (emptyColumns != null && emptyColumns.contains(name)) {
                continue; // column is null for every PSM in the mzTab — don't show it
            }
            if (name.equalsIgnoreCase("search_engine")) {
                continue; // not informative in this view — Casanovo is always the engine
            }
            final int idx = i;
            final int type = numericType(rows, idx); // 0 = text, 1 = integer, 2 = double
            TableColumn<MzTabScores.PsmRow, String> col = new TableColumn<>(name);
            col.setCellValueFactory(d -> {
                String[] v = d.getValue().values();
                String raw = idx < v.length ? v[idx] : "";
                return new ReadOnlyStringWrapper(format(raw, type));
            });
            if (name.equalsIgnoreCase("sequence")) {
                col.getProperties().put(TableUtils.NO_CAP, true); // show the full peptide sequence, uncapped
            }
            table.getColumns().add(col);
            // Right after calc_mass_to_charge, insert a computed precursor mass error column when both
            // the experimental and calculated m/z are available.
            if (i == calcIdx && expIdx >= 0) {
                table.getColumns().add(massErrorColumn(expIdx, calcIdx));
            }
        }
        // Widths are set by TableUtils.autoSizeColumns once the rows are in (see show()).
    }

    /** First index of {@code name} in {@code columns} (case-insensitive), or -1 if absent. */
    private static int indexOf(List<String> columns, String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    /** A computed column: experimental minus calculated m/z (precursor mass error), to 4 decimals. */
    private static TableColumn<MzTabScores.PsmRow, String> massErrorColumn(int expIdx, int calcIdx) {
        TableColumn<MzTabScores.PsmRow, String> col = new TableColumn<>("Δm/z (exp−calc)");
        col.setCellValueFactory(d -> {
            String[] v = d.getValue().values();
            String exp = expIdx < v.length ? v[expIdx] : "";
            String calc = calcIdx < v.length ? v[calcIdx] : "";
            if (exp == null || calc == null || exp.trim().isEmpty() || calc.trim().isEmpty()) {
                return new ReadOnlyStringWrapper("");
            }
            try {
                double diff = Double.parseDouble(exp.trim()) - Double.parseDouble(calc.trim());
                return new ReadOnlyStringWrapper(formatDouble(diff));
            } catch (NumberFormatException e) {
                return new ReadOnlyStringWrapper("");
            }
        });
        return col;
    }

    /**
     * Classify column {@code idx} from its (non-empty) values: 2 if all numeric with at least one
     * non-integer (a double column), 1 if every value is an integer, 0 if any value is non-numeric or
     * the column is empty (a text column).
     */
    private static int numericType(List<MzTabScores.PsmRow> rows, int idx) {
        boolean anyNumeric = false;
        boolean allInteger = true;
        for (MzTabScores.PsmRow r : rows) {
            String[] v = r.values();
            String s = (idx < v.length && v[idx] != null) ? v[idx].trim() : "";
            if (s.isEmpty()) {
                continue;
            }
            try {
                Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return 0; // a non-numeric value -> text column
            }
            anyNumeric = true;
            try {
                Long.parseLong(s);
            } catch (NumberFormatException e) {
                allInteger = false;
            }
        }
        if (!anyNumeric) {
            return 0;
        }
        return allInteger ? 1 : 2;
    }

    /** Decimal places shown for double-valued PSM columns. */
    private static final int DECIMALS = 4;

    /** Format a raw cell value by column type: integers as-is, doubles via {@link #formatDouble}, text untouched. */
    private static String format(String raw, int type) {
        if (raw == null || raw.isEmpty() || type == 0) {
            return raw == null ? "" : raw;
        }
        try {
            if (type == 1) {
                return Long.toString(Long.parseLong(raw.trim()));
            }
            return formatDouble(Double.parseDouble(raw.trim()));
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    /**
     * Format a double for the PSM table at {@link #DECIMALS} decimals. Whole numbers show without
     * decimals; magnitudes that would round to zero at this precision (or grow unwieldy) fall back to
     * scientific notation so tiny values — e.g. a sub-milli-Th mass error — stay visible instead of
     * collapsing to {@code 0.0000}. Mirrors PDV's PSM-table number formatter.
     */
    private static String formatDouble(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return String.valueOf(value);
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        double abs = Math.abs(value);
        if (abs >= 0.5 * Math.pow(10, -DECIMALS) && abs < 1e7) {
            return String.format(Locale.US, "%." + DECIMALS + "f", value);
        }
        return String.format(Locale.US, "%." + DECIMALS + "e", value);
    }
}
