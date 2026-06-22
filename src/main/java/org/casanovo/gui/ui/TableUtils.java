package org.casanovo.gui.ui;

import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
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

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Enables spreadsheet-style copy on a {@link TableView}: per-cell selection, Ctrl/Cmd+C, and a
 * right-click <em>Copy</em> menu. The copied text matches what's on screen (the rendered cell text),
 * and a multi-cell selection is tab/newline separated so it pastes cleanly into a spreadsheet.
 * Panel-agnostic — call {@link #enableCellCopy(TableView)} on any table.
 */
public final class TableUtils {

    private static final KeyCodeCombination COPY =
            new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);

    private TableUtils() {
    }

    public static void enableCellCopy(TableView<?> table) {
        table.getSelectionModel().setCellSelectionEnabled(true);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

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
