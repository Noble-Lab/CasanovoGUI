package org.casanovo.gui.ui;

import javafx.scene.Node;
import javafx.scene.layout.Region;

/**
 * The console contract shared by the plain {@link ConsoleView} (monochrome
 * {@code TextArea}) and the colour-coded {@link RichConsoleView} (RichTextFX),
 * so the two are interchangeable without changing any call site. The output
 * methods are deliberately identical to the original {@code ConsoleView} API.
 *
 * <p>All mutating methods are safe to call from any thread.</p>
 */
public interface ConsoleOutput {

    /** Append a permanent line (plus a line separator). */
    void appendLine(String line);

    /** Append permanent text exactly as given. */
    void append(String text);

    /** Show or replace the transient progress line (the text after the last committed line). */
    void showProgress(String line);

    /** Clear all console text. */
    void clear();

    /** Copy the full console text to the system clipboard. */
    void copyAll();

    /** Place {@code node} at the left of the bottom toolbar; Clear/Copy stay pinned right. */
    void setLeftStatus(Node node);

    /** The view to mount in the scene graph. */
    Region getView();
}
