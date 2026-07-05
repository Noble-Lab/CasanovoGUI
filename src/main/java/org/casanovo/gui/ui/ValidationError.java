package org.casanovo.gui.ui;

import javafx.scene.control.Control;

/**
 * The result of validating a command pane's inputs: a human-readable {@code message}
 * plus the offending {@code field} (nullable) so the UI can highlight and focus it
 * inline instead of only showing a modal dialog.
 */
public record ValidationError(String message, Control field) {
}
