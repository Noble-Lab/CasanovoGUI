package org.casanovo.gui.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;

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
public class ConsoleView extends BorderPane {

    private final TextArea textArea = new TextArea();
    /** Length of text that is permanent; anything after it is the transient progress line. */
    private int committedLen = 0;

    /** Buffer for coalesced committed appends; flushed once per FX pulse. */
    private final StringBuilder pending = new StringBuilder();
    /** True if a flush is already scheduled; prevents N runLater tasks for N lines. */
    private boolean flushScheduled = false;

    public ConsoleView() {
        textArea.setEditable(false);
        textArea.setWrapText(false);
        // Monospace font only — colours are left to the active theme.
        textArea.setStyle("-fx-font-family: 'Consolas', 'Menlo', 'DejaVu Sans Mono', 'Courier New', monospace;"
                + " -fx-font-size: 13px;");

        Label title = new Label("Console");
        title.setStyle("-fx-font-weight: bold;");
        title.setPadding(new Insets(4, 6, 4, 6));

        Button clear = new Button("Clear console");
        clear.setOnAction(e -> clear());
        HBox south = new HBox(clear);
        south.setAlignment(Pos.CENTER_RIGHT);
        south.setPadding(new Insets(4, 6, 4, 6));

        setTop(title);
        setCenter(textArea);
        setBottom(south);
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
        textArea.appendText(batch);
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
}
