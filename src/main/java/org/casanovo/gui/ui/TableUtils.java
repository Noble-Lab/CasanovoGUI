package org.casanovo.gui.ui;

import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Labeled;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Enables spreadsheet-style copy on a {@link TableView}: per-cell selection, Ctrl/Cmd+C, and a
 * right-click <em>Copy</em> menu. The copied text matches what's on screen (the rendered cell text),
 * and a multi-cell selection is tab/newline separated so it pastes cleanly into a spreadsheet.
 * Panel-agnostic — call {@link #enableCellCopy(TableView)} on any table.
 */
public final class TableUtils {

    private static final KeyCodeCombination COPY =
            new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);

    /** Column property key (a {@code Function<S,String>}) giving a cell's displayed text for sizing. */
    public static final String DISPLAY_TEXT = "tableutils.displayText";

    /** Column property key (a {@code Boolean}) exempting a column from the character cap when sizing. */
    public static final String NO_CAP = "tableutils.noCap";

    private static final double FUDGE = 1.15;     // Font.font(13) probe underestimates the cell render font
    private static final double HEADER_PAD = 30;  // sorted header: text + sort arrow + cell insets
    private static final double HEADER_PAD_TIGHT = 14; // unsorted header: text + insets only (no arrow shown)
    private static final double CELL_PAD = 16;    // cell insets
    private static final double MIN_W = 40;       // never narrower than this

    private TableUtils() {
    }

    /**
     * Size every column to fit the wider of its header and its content (measured over the table's
     * current rows), but never wider than {@code capChars} characters. A formatted column may register
     * a {@code Function<S,String>} under {@link #DISPLAY_TEXT} so the displayed (not raw) text is
     * measured; otherwise the cell value's {@code toString()} is used. Call after the rows are set
     * (e.g. after a page changes) — the table must use {@code UNCONSTRAINED_RESIZE_POLICY} for the
     * preferred widths to take effect.
     */
    public static <S> void autoSizeColumns(TableView<S> table, int capChars) {
        Text probe = new Text();
        probe.setFont(Font.font(13)); // ~the table cell font, for a quick width estimate
        Text headerProbe = new Text();
        headerProbe.setFont(Font.font(null, FontWeight.BOLD, 13)); // column headers render bold — measure bold
        for (TableColumn<S, ?> col : table.getColumns()) {
            autoSizeLeaf(table, col, probe, headerProbe, capChars);
        }
    }

    private static <S> void autoSizeLeaf(TableView<S> table, TableColumn<S, ?> col, Text probe, Text headerProbe,
                                         int cap) {
        if (!col.getColumns().isEmpty()) { // grouped column: size its leaves (its own width is their sum)
            for (TableColumn<S, ?> sub : col.getColumns()) {
                autoSizeLeaf(table, sub, probe, headerProbe, cap);
            }
            return;
        }
        int colCap = Boolean.TRUE.equals(col.getProperties().get(NO_CAP)) ? Integer.MAX_VALUE : cap;
        String header = col.getText();
        if ((header == null || header.isEmpty()) && col.getGraphic() instanceof Labeled lbl) {
            header = lbl.getText(); // headerTip moves the title into a header-graphic Label
        }
        // Measure the header with the BOLD probe (headers render bold). Reserve sort-arrow room only on a
        // SORTED column (the arrow shows only when sorted), so unsorted columns hug their header and stay
        // tight. A plain probe / always-on arrow padding left them too narrow or too wide. Callers re-run
        // this on sort-order changes so a freshly-sorted column grows to fit its arrow.
        double headerPad = table.getSortOrder().contains(col) ? HEADER_PAD : HEADER_PAD_TIGHT;
        double w = textWidth(headerProbe, clip(header, colCap)) + headerPad;
        Object fn = col.getProperties().get(DISPLAY_TEXT);
        for (S item : table.getItems()) {
            String s;
            if (fn instanceof Function<?, ?>) {
                @SuppressWarnings("unchecked")
                Function<S, String> f = (Function<S, String>) fn;
                s = f.apply(item);
            } else {
                Object v = col.getCellData(item);
                s = v == null ? "" : v.toString();
            }
            w = Math.max(w, textWidth(probe, clip(s, colCap)) + CELL_PAD);
        }
        col.setPrefWidth(Math.max(MIN_W, w));
    }

    private static double textWidth(Text probe, String s) {
        probe.setText(s);
        return probe.getLayoutBounds().getWidth() * FUDGE;
    }

    private static String clip(String s, int cap) {
        if (s == null) {
            return "";
        }
        return s.length() > cap ? s.substring(0, cap) : s;
    }

    public static void enableCellCopy(TableView<?> table) {
        table.getSelectionModel().setCellSelectionEnabled(true);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        // Sorting while a cell selection is active trips a JavaFX bug (IndexOutOfBoundsException in
        // ControlUtils.updateSelectedIndices, via fireCustomSelectedCellsListChangeEvent). Clear the
        // selection on a column-header press — captured before the click reaches the header's sort
        // handler — so there are no selected cells to remap during the sort.
        table.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getTarget() instanceof javafx.scene.Node node) {
                for (javafx.scene.Node n = node; n != null; n = n.getParent()) {
                    if (n instanceof javafx.scene.control.skin.TableColumnHeader) {
                        table.getSelectionModel().clearSelection();
                        break;
                    }
                }
            }
        });

        table.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (COPY.match(e)) {
                copySelection(table);
                e.consume();
            }
        });

        MenuItem copy = new MenuItem("Copy");
        copy.setOnAction(e -> copySelection(table));
        table.setContextMenu(new ContextMenu(copy));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void copySelection(TableView<?> table) {
        var cells = table.getSelectionModel().getSelectedCells();
        if (cells.isEmpty()) {
            return;
        }
        // Snapshot the visible cells' rendered text so copied values match what's shown (e.g. a
        // formatted score), keyed by row index + column identity.
        Map<String, String> rendered = new HashMap<>();
        for (Node n : table.lookupAll(".table-cell")) {
            if (n instanceof TableCell c && c.getTableColumn() != null && c.getIndex() >= 0) {
                rendered.put(c.getIndex() + ":" + System.identityHashCode(c.getTableColumn()),
                        c.getText() == null ? "" : c.getText());
            }
        }
        // row -> (column position -> text), both sorted, to preserve on-screen layout.
        TreeMap<Integer, TreeMap<Integer, String>> grid = new TreeMap<>();
        for (Object obj : cells) {
            TablePosition pos = (TablePosition) obj;
            int row = pos.getRow();
            TableColumn column = pos.getTableColumn();
            if (row < 0 || column == null) {
                continue;
            }
            String text = rendered.get(row + ":" + System.identityHashCode(column));
            if (text == null) {
                Object v = column.getCellData(row);
                text = v == null ? "" : v.toString();
            }
            grid.computeIfAbsent(row, k -> new TreeMap<>()).put(pos.getColumn(), text);
        }

        StringBuilder sb = new StringBuilder();
        boolean firstRow = true;
        for (TreeMap<Integer, String> rowCells : grid.values()) {
            if (!firstRow) {
                sb.append('\n');
            }
            firstRow = false;
            boolean firstCol = true;
            for (String t : rowCells.values()) {
                if (!firstCol) {
                    sb.append('\t');
                }
                firstCol = false;
                sb.append(t);
            }
        }

        ClipboardContent content = new ClipboardContent();
        content.putString(sb.toString());
        Clipboard.getSystemClipboard().setContent(content);
    }
}
