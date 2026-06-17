package org.casanovo.gui.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.StyleClassedTextArea;

/**
 * Colour-coded console, drop-in alternative to {@link ConsoleView}: same output
 * API, but each line is rendered in a colour chosen by its type (errors red,
 * warnings amber, the command echo accented, etc.). Colours are AtlantaFX
 * theme variables (see {@code console.css}) so they follow the light/dark theme.
 *
 * <p>Rendering uses RichTextFX's {@link StyleClassedTextArea}, which is
 * viewport-virtualised: only the visible lines are laid out, so a long run does
 * not slow down. A line cap bounds memory on very long runs.</p>
 *
 * <p>The in-place progress line, coalesced committed appends and thread-safety
 * mirror {@link ConsoleView} exactly.</p>
 */
public class RichConsoleView extends BorderPane implements ConsoleOutput {

    /** Keep at most this many lines; older ones are trimmed from the top. */
    private static final int MAX_PARAGRAPHS = 5000;

    private final StyleClassedTextArea area = new StyleClassedTextArea();
    /** Length of text that is permanent; anything after it is the transient progress line. */
    private int committedLen = 0;

    /** Buffer for coalesced committed appends; flushed once per FX pulse. */
    private final StringBuilder pending = new StringBuilder();
    private boolean flushScheduled = false;

    private final HBox south;
    private final Region southSpacer = new Region();
    private final Button clearBtn = new Button("Clear console");
    private final Button copyBtn = new Button("Copy output");

    public RichConsoleView() {
        area.setEditable(false);
        area.setWrapText(false);
        area.getStyleClass().add("console-area");
        java.net.URL css = getClass().getResource("/org/casanovo/gui/console.css");
        if (css != null) {
            area.getStylesheets().add(css.toExternalForm());
        }

        Label title = new Label("Console");
        title.setStyle("-fx-font-weight: bold;");
        title.setPadding(new Insets(4, 6, 4, 6));

        clearBtn.setOnAction(e -> clear());
        copyBtn.setOnAction(e -> copyAll());
        HBox.setHgrow(southSpacer, Priority.ALWAYS);
        south = new HBox(8, southSpacer, clearBtn, copyBtn);
        south.setAlignment(Pos.CENTER_LEFT);
        south.setPadding(new Insets(4, 6, 4, 6));

        setTop(title);
        setCenter(new VirtualizedScrollPane<>(area));
        setBottom(south);
    }

    @Override
    public void setLeftStatus(Node node) {
        south.getChildren().setAll(node, southSpacer, clearBtn, copyBtn);
    }

    @Override
    public void copyAll() {
        ClipboardContent content = new ClipboardContent();
        content.putString(area.getText());
        Clipboard.getSystemClipboard().setContent(content);
    }

    @Override
    public void appendLine(String line) {
        append(line + System.lineSeparator());
    }

    @Override
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
        // Drop any transient progress line; the committed text takes its place.
        if (area.getLength() > committedLen) {
            area.deleteText(committedLen, area.getLength());
        }
        appendStyled(batch);
        committedLen = area.getLength();
        trimIfNeeded();
        area.moveTo(area.getLength());
        area.requestFollowCaret();
    }

    @Override
    public void showProgress(String line) {
        Platform.runLater(() -> {
            flushPending(); // commit anything buffered so the progress line sits at the true end
            if (area.getLength() > committedLen) {
                area.deleteText(committedLen, area.getLength());
            }
            int start = area.getLength();
            area.appendText(line);
            area.setStyleClass(start, area.getLength(), "console-progress");
            area.moveTo(area.getLength());
            area.requestFollowCaret();
        });
    }

    @Override
    public void clear() {
        Platform.runLater(() -> {
            synchronized (pending) {
                pending.setLength(0);
                flushScheduled = false;
            }
            area.clear();
            committedLen = 0;
        });
    }

    @Override
    public Region getView() {
        return this;
    }

    /** Append {@code text} line by line, styling each line by its type. */
    private void appendStyled(String text) {
        int i = 0;
        while (i < text.length()) {
            int nl = text.indexOf('\n', i);
            int end = (nl == -1) ? text.length() : nl + 1; // include the newline
            String segment = text.substring(i, end);
            int start = area.getLength();
            area.appendText(segment);
            area.setStyleClass(start, area.getLength(), styleFor(segment));
            i = end;
        }
    }

    /** Trim oldest lines once the document grows past {@link #MAX_PARAGRAPHS}. */
    private void trimIfNeeded() {
        int n = area.getParagraphs().size();
        if (n > MAX_PARAGRAPHS) {
            int cut = area.getAbsolutePosition(n - MAX_PARAGRAPHS, 0);
            if (cut > 0 && cut <= committedLen) {
                area.deleteText(0, cut);
                committedLen -= cut;
            }
        }
    }

    /** Map a line to a CSS style class (see {@code console.css}). */
    private static String styleFor(String raw) {
        String t = raw.strip();
        if (t.isEmpty()) {
            return "console-default";
        }
        if (t.startsWith("$ ")) {
            return "console-cmd";
        }
        if (t.startsWith("ERROR:") || t.startsWith("[error]")
                || t.contains("FAILED") || t.startsWith("Traceback")) {
            return "console-error";
        }
        if (t.startsWith("WARNING:") || t.startsWith("[stopped]")) {
            return "console-warning";
        }
        if (t.startsWith("[done]")) {
            return "console-success";
        }
        if (t.startsWith("DEBUG:")) {
            return "console-muted";
        }
        if (t.startsWith("INFO:")) {
            return "console-info";
        }
        if (t.startsWith("[")) {
            return "console-marker"; // GUI status markers: [install] [pdv] [config] [repair] [update] [hint]
        }
        return "console-default";
    }
}
