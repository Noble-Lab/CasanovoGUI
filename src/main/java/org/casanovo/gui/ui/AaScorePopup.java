package org.casanovo.gui.ui;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.casanovo.gui.core.MzTabScores;

import java.util.ArrayList;
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
    private static VBox alignmentBox; // protein-match alignment(s) for the peptide (mismatches highlighted)
    private static ScrollPane alignmentScroll; // scrolls the alignment when a peptide hits many proteins
    private static SplitPane psmSplit; // draggable PSM-table / protein-mapping divider
    private static boolean psmDividerInit; // set the 55/45 divider once (preserve later drags)
    private static Label pdvHint; // shown only while PDV is active (visibility set per show())
    private static String currentPeptide = "";
    private static boolean hasMapping; // false for unmapped peptides → hide the protein-mapping panel
    private static java.util.function.Consumer<MzTabScores.PsmRow> onRowActivate; // double-click a row -> drive PDV
    private static VBox tableBox; // PSM-table panel (psmSplit's first item) — a field so export can snapshot it
    private static Label exportStatus; // toolbar status line ("Saved …")

    private static final Font ALIGN_FONT = Font.font("Monospaced", 13);
    private static final Color SUB_COLOR = Color.web("#C0392B");   // a true substitution
    private static final Color EQUIV_COLOR = Color.web("#2C6FBB"); // an I/L or X equivalence
    private static final Color MATCH_COLOR = Color.web("#888888"); // an exact-match residue

    /**
     * One distinct protein substring the peptide matched, for the alignment panel. {@code cls[i]}
     * classifies residue i of {@code onProtein}: 0 = match, 1 = substitution, 2 = I/L-or-X equivalence.
     * {@code proteins} lists the proteins sharing this substring; {@code substitutions} counts the 1s.
     */
    public record ProteinMatch(String onProtein, int[] cls, List<String> proteins, int substitutions) {
    }

    private AaScorePopup() {
    }

    /**
     * Show (or re-render) the PSMs of {@code peptide}. {@code columns} are the mzTab PSM column names;
     * {@code rows} are its PSM rows (already sorted best-first). {@code onDoubleClick} (nullable) is
     * invoked with the row the user double-clicks — used to drive an open PDV to that PSM's spectrum.
     * {@code pdvActive} shows a hint about navigating PSMs into PDV (only meaningful when PDV is on).
     * {@code proteinMatches} (nullable/empty) renders an alignment panel of the peptide against each
     * distinct protein substring it mapped to, highlighting mismatches.
     */
    public static void show(Window owner, String peptide, List<String> columns, List<MzTabScores.PsmRow> rows,
                            Set<String> emptyColumns, java.util.function.Consumer<MzTabScores.PsmRow> onDoubleClick,
                            boolean pdvActive, List<ProteinMatch> proteinMatches, boolean i2l) {
        if (stage == null) {
            build(owner);
        }
        onRowActivate = onDoubleClick;
        pdvHint.setVisible(pdvActive);
        pdvHint.setManaged(pdvActive);
        currentPeptide = peptide;
        exportStatus.setText(""); // clear any stale "Saved …" message from a previously shown peptide
        renderAlignment(peptide, proteinMatches, i2l);
        // Unmapped peptides have no protein mapping — hide that panel (and its divider) instead of showing
        // an empty "No protein mapping" placeholder; restore it for mapped peptides.
        hasMapping = proteinMatches != null && !proteinMatches.isEmpty();
        boolean mappingShown = psmSplit.getItems().contains(alignmentScroll);
        if (hasMapping && !mappingShown) {
            psmSplit.getItems().setAll(tableBox, alignmentScroll);
            javafx.application.Platform.runLater(() -> psmSplit.setDividerPositions(0.55));
        } else if (!hasMapping && mappingShown) {
            psmSplit.getItems().setAll(tableBox);
        }
        rebuildColumns(columns, emptyColumns, rows);
        table.getItems().setAll(rows);
        TableUtils.autoSizeColumns(table, 60); // fit columns to header + content, capped at 60 chars
        stage.setTitle("PSM: " + peptide + " (" + rows.size() + ")");
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
                // A first-open divider change relays out the taller table; pin row 0 back to the top after it.
                javafx.application.Platform.runLater(() -> table.scrollTo(0));
            });
        }
    }

    /** Populate the protein-mapping panel: the peptide aligned against every matched protein. */
    private static void renderAlignment(String peptide, List<ProteinMatch> matches, boolean i2l) {
        alignmentBox.getChildren().clear();
        if (matches == null || matches.isEmpty()) {
            Label none = new Label("No protein mapping for this peptide.");
            none.setStyle("-fx-font-size: 11px; -fx-opacity: 0.6;");
            alignmentBox.getChildren().add(none);
            return;
        }
        int proteinCount = matches.stream().mapToInt(m -> m.proteins().size()).sum();
        Label title = new Label("Protein mapping — " + proteinCount + " protein" + (proteinCount == 1 ? "" : "s")
                + "  ·  red = substitution, blue = " + (i2l ? "I/L or X" : "X"));
        title.setStyle("-fx-font-weight: bold; -fx-padding: 2 0 2 0;");
        alignmentBox.getChildren().add(title);
        for (ProteinMatch m : matches) {
            String summary = m.substitutions() == 0
                    ? (hasEquiv(m) ? "exact (" + (i2l ? "I/L or X" : "X") + " only)" : "exact")
                    : m.substitutions() + " substitution" + (m.substitutions() == 1 ? "" : "s");
            Label head = new Label(String.join(", ", m.proteins()) + "  (" + summary + ")");
            head.setStyle("-fx-font-size: 11px; -fx-opacity: 0.75;");
            head.setWrapText(true);
            alignmentBox.getChildren().add(head);
            if (anyDiff(m)) {
                alignmentBox.getChildren().add(alignRow("de novo", peptide, m.cls()));
                alignmentBox.getChildren().add(alignRow("protein", m.onProtein(), m.cls()));
            }
        }
    }

    private static boolean anyDiff(ProteinMatch m) {
        for (int c : m.cls()) {
            if (c != 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasEquiv(ProteinMatch m) {
        for (int c : m.cls()) {
            if (c == 2) {
                return true;
            }
        }
        return false;
    }

    /** One monospaced alignment row: each residue colored by its class (0 match, 1 sub, 2 equivalence). */
    private static TextFlow alignRow(String label, String seq, int[] cls) {
        TextFlow tf = new TextFlow();
        Text lab = new Text(String.format("%-9s", label));
        lab.setFont(ALIGN_FONT);
        lab.setFill(MATCH_COLOR);
        tf.getChildren().add(lab);
        for (int i = 0; i < seq.length(); i++) {
            Text t = new Text(seq.charAt(i) + " ");
            t.setFont(ALIGN_FONT);
            int c = i < cls.length ? cls[i] : 0;
            t.setFill(c == 1 ? SUB_COLOR : c == 2 ? EQUIV_COLOR : MATCH_COLOR);
            tf.getChildren().add(t);
        }
        return tf;
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
        // 55/45 split with the mapping (below); the table can still be dragged down to a 3-row minimum.
        table.setMinHeight(3 * 26 + 40);
        tableBox = new VBox(4, tableTitle, pdvHint, table);
        tableBox.setPadding(new Insets(2, 10, 10, 10));
        tableBox.getStyleClass().add("result-tabs"); // compact column headers via settings.css
        VBox.setVgrow(table, Priority.ALWAYS);

        // Protein mapping, below the PSM table; scrolls when a peptide hits many proteins.
        alignmentBox = new VBox(2);
        alignmentBox.setPadding(new Insets(4, 10, 8, 10));
        alignmentScroll = new ScrollPane(alignmentBox);
        alignmentScroll.setFitToWidth(true);
        alignmentScroll.setMinHeight(60);
        alignmentScroll.setPrefHeight(180);
        alignmentScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        // Draggable split between the PSM table and the protein mapping. The table keeps its 10-row
        // preferred height (the mapping takes the rest) until the user drags the divider.
        psmSplit = new SplitPane(tableBox, alignmentScroll);
        psmSplit.setOrientation(javafx.geometry.Orientation.VERTICAL);
        // 55/45 split (PSM table : protein mapping). Set once the split has a real height (during the
        // first layout, before the row-selection scrollTo) so it doesn't fight scrollTo(0).
        psmSplit.heightProperty().addListener((obs, oldH, newH) -> {
            if (!psmDividerInit && newH.doubleValue() > 100) {
                psmSplit.setDividerPositions(0.55);
                psmDividerInit = true;
            }
        });

        Button exportBtn = new Button("Export image");
        exportBtn.setTooltip(new Tooltip(
                "Save the per-residue chart, PSM table, and protein alignment as a high-resolution PNG (Ctrl+S)"));
        exportBtn.setOnAction(e -> exportImage());
        exportStatus = new Label();
        exportStatus.setStyle("-fx-font-size: 11px; -fx-opacity: 0.7;");
        HBox toolbar = new HBox(8, exportBtn, exportStatus);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(6, 8, 6, 8));

        VBox content = new VBox(chart, psmSplit); // the figure area below the toolbar
        VBox.setVgrow(psmSplit, Priority.ALWAYS);
        VBox root = new VBox(toolbar, content);
        VBox.setVgrow(content, Priority.ALWAYS);
        // Match the main window's base font (set on its root in MainApp) so the PSM table renders
        // identically to the View tab's tables; a separate popup Stage otherwise falls back to the
        // AtlantaFX default (System 14px) instead of the app's Segoe UI 13px. The chart sets its own
        // Arial font, so it is unaffected.
        root.setStyle("-fx-font-family: 'Segoe UI', 'Inter', 'SF Pro Text', 'Helvetica Neue', sans-serif; -fx-font-size: 13px;");

        stage = new Stage();
        // Deliberately NOT initOwner(owner): an owned window's minimize button is disabled on Windows,
        // and this PSM window should be independently minimizable. Instead, close it when the main window
        // closes so it doesn't outlive the app. (Minimizing the main window keeps showing == true, so the
        // PSM window stays open in that case.)
        if (owner != null) {
            owner.showingProperty().addListener((o, was, showing) -> {
                if (!showing && stage != null) {
                    stage.close();
                }
            });
        }
        try (java.io.InputStream icon = AaScorePopup.class.getResourceAsStream("/org/casanovo/gui/icon.png")) {
            if (icon != null) {
                stage.getIcons().add(new javafx.scene.image.Image(icon));
            }
        } catch (java.io.IOException ignored) {
            // no icon is fine
        }
        // Default 820 (chart + 10-row PSM table + protein mapping), but never taller than the monitor
        // (visual bounds exclude the taskbar; subtract a little for the title bar).
        double screenH = javafx.stage.Screen.getPrimary().getVisualBounds().getHeight();
        Scene scene = new Scene(root, 940, Math.min(820, screenH - 40));
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN), AaScorePopup::exportImage);
        java.net.URL css = AaScorePopup.class.getResource("/org/casanovo/gui/settings.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        stage.setScene(scene);
        stage.setMaxHeight(screenH); // the user can't drag it taller than the monitor either
    }

    /** Export the selected panels (per-residue chart, PSM table, protein mapping) of the PSM window as a
        high-resolution framed PNG. Each selected panel is snapshotted at its own size and stacked, so a
        subset isn't padded with empty space, and matches the on-screen panels (only the toolbar, not a
        panel, is excluded). */
    private static void exportImage() {
        List<String> labels = new ArrayList<>(List.of("Per-residue chart", "PSM table"));
        if (hasMapping) {
            labels.add("Protein mapping");
        }
        ImageExport.promptExportOptions(stage, labels).ifPresent(opts -> {
            boolean[] sel = opts.components();
            List<Node> panels = new ArrayList<>();
            if (sel[0]) {
                panels.add(chart);
            }
            if (sel[1]) {
                panels.add(tableBox);
            }
            if (sel.length > 2 && sel[2]) {
                panels.add(alignmentScroll); // the scroll viewport — export what's currently visible
            }
            String base = currentPeptide.replaceAll("[^A-Za-z0-9._-]", "_");
            String name = (base.isEmpty() ? "peptide" : base) + "-psms.png";
            ImageExport.exportFramed(stage, panels, name, opts, null, null, exportStatus::setText);
        });
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
