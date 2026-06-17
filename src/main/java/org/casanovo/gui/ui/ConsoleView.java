package org.casanovo.gui.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * A scrolling, read-only console showing the live stdout/stderr of the running
 * Casanovo process.
 *
 * <p>Supports an <em>in-place progress line</em>: {@link #showProgress(String)}
 * replaces the current trailing (uncommitted) line instead of appending, so
 * tqdm/Lightning progress refreshes show as one updating line rather than
 * thousands. {@link #appendLine(String)} commits a permanent line.</p>
 *
 * <p>Committed appends from the runner thread are <em>coalesced</em>: a single
 * scheduled {@code Platform.runLater} flushes whatever has accumulated since
 * the last flush. This prevents the FX thread from being flooded by tens of
 * thousands of one-line {@code runLater} tasks when a chatty subprocess emits
 * lines faster than the UI can render them.</p>
 *
 * <p>Colours are not hard coded so the console follows the active AtlantaFX
 * theme (light/dark); only the monospace font is set here.</p>
 *
 * <p>All mutating methods are safe to call from any thread.</p>
 */
public class ConsoleView extends BorderPane implements ConsoleOutput {

    private final TextArea textArea = new TextArea();
    /** Length of text that is permanent; anything after it is the transient progress line. */
    private int committedLen = 0;

    /** Buffer for coalesced committed appends; flushed once per FX pulse. */
    private final StringBuilder pending = new StringBuilder();
    /** True if a flush is already scheduled; prevents N runLater tasks for N lines. */
    private boolean flushScheduled = false;

    /** Bottom toolbar: an optional left slot ({@link #setLeftStatus}) plus "Clear console" pinned right. */
    private final HBox south;
    private final Region southSpacer = new Region();
    private final Button clearBtn = new Button("Clear console");
    private final Button copyBtn = new Button("Copy output");

    public ConsoleView() {
        textArea.setEditable(false);
        textArea.setWrapText(false);
        // Match the Carafe console: the app's sans-serif base font (not monospace).
        textArea.setStyle("-fx-font-family: 'Segoe UI', 'Inter', 'SF Pro Text', 'Helvetica Neue', sans-serif;"
                + " -fx-font-size: 13px;");

        Label title = new Label("Console");
        title.setStyle("-fx-font-weight: bold;");
        title.setPadding(new Insets(4, 6, 4, 6));

        clearBtn.setOnAction(e -> clear());
        copyBtn.setOnAction(e -> copyAll());
        HBox.setHgrow(southSpacer, Priority.ALWAYS);
        south = new HBox(8, southSpacer, clearBtn, copyBtn); // Clear/Copy pinned right; left slot via setLeftStatus
        south.setAlignment(Pos.CENTER_LEFT);
        south.setPadding(new Insets(4, 6, 4, 6));

        setTop(title);
        setCenter(textArea);
        setBottom(south);
    }

    /**
     * Place {@code node} at the left of the bottom toolbar (e.g. an execution readout);
     * the "Clear console" button stays pinned to the right.
     */
    public void setLeftStatus(Node node) {
        south.getChildren().setAll(node, southSpacer, clearBtn, copyBtn);
    }

    /** Copy the full console text to the system clipboard. */
    public void copyAll() {
        ClipboardContent content = new ClipboardContent();
        content.putString(textArea.getText());
        Clipboard.getSystemClipboard().setContent(content);
    }

    /** Append a permanent line. Any pending transient progress line is kept as-is. */
    public void appendLine(String line) {
        append(line + System.lineSeparator());
    }

    /**
     * Append permanent text exactly as given. Calls from background threads are
     * coalesced: only one {@code Platform.runLater} is scheduled per pulse,
     * regardless of how many appends arrive in between.
     */
    public void append(String text) {
        boolean schedule;
        synchronized (pending) {
            pending.append(text);
            schedule = !flushScheduled;
            if (schedule) {
                flushScheduled = true;
            }
        }
        if (schedule) {
            Platform.runLater(this::flushPending);
        }
    }

    private void flushPending() {
        String batch;
        synchronized (pending) {
            batch = pending.toString();
            pending.setLength(0);
            flushScheduled = false;
        }
        if (batch.isEmpty()) {
            return;
        }
        int end = textArea.getLength();
        if (committedLen > end) {
            committedLen = end;
        }
        // Overwrite any transient progress line with the committed text, so a finished
        // progress bar (tqdm's "...\r99%\r100%\n") commits as a single line instead of
        // having the last refresh concatenated onto the previous one.
        textArea.replaceText(committedLen, end, batch);
        committedLen = textArea.getLength();
        textArea.positionCaret(textArea.getLength());
    }

    /**
     * Show or replace the transient progress line (the text after the last
     * committed line). Successive calls overwrite each other in place.
     * Transient updates are already rate-limited by the caller, so we do not
     * coalesce them here.
     */
    public void showProgress(String line) {
        Platform.runLater(() -> {
            // Make sure any pending committed text is flushed first, so the
            // transient line appears at the true end of the committed region.
            flushPending();
            int end = textArea.getLength();
            if (committedLen > end) {
                committedLen = end;
            }
            textArea.replaceText(committedLen, end, line);
            textArea.positionCaret(textArea.getLength());
        });
    }

    public void clear() {
        Platform.runLater(() -> {
            synchronized (pending) {
                pending.setLength(0);
                flushScheduled = false;
            }
            textArea.clear();
            committedLen = 0;
        });
    }

    @Override
    public Region getView() {
        return this;
    }
}
