package org.casanovo.gui.ui;

import javafx.beans.value.ChangeListener;
import javafx.css.PseudoClass;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;

/**
 * Inline form-validation feedback shared by the command tabs and the View tab: flag the offending
 * field with AtlantaFX's danger border, focus it, and show the message in a status {@link Label}
 * styled as an error — no modal. Only one field is flagged at a time, matching the single shared
 * status bar.
 *
 * <p>The decoration clears when the user edits the flagged field, on the next check, or as soon as
 * anything else writes a new message to the status label — so an ordinary status is never left
 * painted error-red. When cleared by an edit/check (not superseded by another writer) the status
 * text that preceded the error is restored, so clearing a validation error mid-run doesn't wipe a
 * live "Running…" message.</p>
 */
final class InlineValidation {

    /** AtlantaFX's danger state for inputs is a pseudo-class ({@code .text-input:danger}), which
        recolours the field's own border (focused included) — unlike a plain {@code .danger} style
        class, which AtlantaFX ignores for inputs. */
    private static final PseudoClass DANGER = PseudoClass.getPseudoClass("danger");

    private final Label status;
    /** The status text shown before the error replaced it; restored when the error is cleared. */
    private String savedText;
    private Control invalidField;
    /** Clears the decoration as soon as the user starts editing the flagged field. */
    private final ChangeListener<String> onEdit = (obs, old, val) -> clear();
    /** Drops the error styling if anything else overwrites the status text (a newer message wins). */
    private final ChangeListener<String> onStatusReplaced = (obs, old, val) -> teardown(false);

    InlineValidation(Label status) {
        this.status = status;
    }

    /** Flag {@code field} as invalid: danger border, focus, and a danger-styled status message. */
    void show(Control field, String message) {
        clear();
        savedText = status.getText();
        invalidField = field;
        field.pseudoClassStateChanged(DANGER, true);
        if (field instanceof TextInputControl tic) {
            tic.textProperty().addListener(onEdit);
        }
        status.getStyleClass().add("status-error");
        status.setText(message);
        // Watch for anyone else replacing the status text. Added AFTER our own setText above, so our
        // message doesn't trip it; a later write by any other code auto-dismisses the error styling.
        status.textProperty().addListener(onStatusReplaced);
        // Don't focus a hidden node (e.g. a MultiFileField's backing field collapsed to its
        // summary link); the caller keeps a visible danger cue via MultiFileField.
        if (field.isVisible()) {
            field.requestFocus();
        }
    }

    /** Remove the inline decoration and restore the status text that preceded the error. */
    void clear() {
        teardown(true);
    }

    /**
     * Tear down the decoration: drop the danger border + listeners and the error style class. When
     * {@code restoreText} is true (an explicit clear / field edit) the pre-error status text is put
     * back; when false (another writer already replaced the text) that newer text is left in place.
     */
    private void teardown(boolean restoreText) {
        status.textProperty().removeListener(onStatusReplaced);
        if (invalidField != null) {
            invalidField.pseudoClassStateChanged(DANGER, false);
            if (invalidField instanceof TextInputControl tic) {
                tic.textProperty().removeListener(onEdit);
            }
            invalidField = null;
        }
        if (status.getStyleClass().remove("status-error") && restoreText) {
            status.setText(savedText);
        }
    }
}
