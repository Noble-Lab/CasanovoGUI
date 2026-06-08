package org.casanovo.gui.ui;

import javafx.scene.Node;
import org.casanovo.gui.core.CasanovoCommand;

/**
 * Base class for every tab that configures one Casanovo sub-command.
 *
 * <p>Each subclass builds its own controls (exposed via {@link #getContent()})
 * and knows how to translate the current field values into a
 * {@link CasanovoCommand}. {@link #validateInputs()} returns a human-readable
 * error when the inputs are incomplete, or {@code null} when ready to run.</p>
 */
public abstract class CommandPane {

    /** Display name shown on the tab. */
    public abstract String getTitle();

    /** The JavaFX content node for this tab. */
    public abstract Node getContent();

    /**
     * Validate the current inputs.
     *
     * @return an error message to show the user, or {@code null} if inputs are valid.
     */
    public abstract String validateInputs();

    /** Build the command from the current field values. Assumes {@link #validateInputs()} passed. */
    public abstract CasanovoCommand buildCommand();
}
