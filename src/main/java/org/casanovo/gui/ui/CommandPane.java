package org.casanovo.gui.ui;

import javafx.scene.Node;
import org.casanovo.gui.core.CasanovoCommand;

/**
 * Base class for every tab that configures one Casanovo sub-command.
 *
 * <p>Each subclass builds its own controls (exposed via {@link #getContent()})
 * and knows how to translate the current field values into a
 * {@link CasanovoCommand}. {@link #validateInputs()} returns a {@link ValidationError}
 * when the inputs are incomplete, or {@code null} when ready to run.</p>
 */
public abstract class CommandPane {

    /**
     * Options shared by every sub-command (model, output dir, verbosity, and the external config
     * file). Held in the base so the shared config-file behaviour lives in one place; subclasses
     * add it to their form via {@code options.addToForm(...)} / {@code options.addConfigRow(...)}
     * and build their command from it.
     */
    protected final CommonOptions options = new CommonOptions();

    /** Display name shown on the tab. */
    public abstract String getTitle();

    /** The JavaFX content node for this tab. */
    public abstract Node getContent();

    /**
     * Validate the current inputs: the pane-specific checks first, then the shared output-directory
     * check, so no pane can forget the latter. Callers use this; panes implement
     * {@link #validatePaneInputs()}.
     *
     * @return a {@link ValidationError} naming the problem (and the field to highlight),
     *         or {@code null} if inputs are valid.
     */
    public final ValidationError validateInputs() {
        ValidationError error = validatePaneInputs();
        return error != null ? error : options.validateOutputDir();
    }

    /** Validate this pane's own required inputs (spectra, FASTA, …), excluding the shared options. */
    protected abstract ValidationError validatePaneInputs();

    /** Build the command from the current field values. Assumes {@link #validateInputs()} passed. */
    public abstract CasanovoCommand buildCommand();

    /**
     * Show or hide the external "Config file" row. Hidden while the GUI generates the config from
     * the Parameters dialog; shown when the user supplies a YAML file instead.
     */
    public void setConfigFileVisible(boolean visible) {
        options.setConfigFileVisible(visible);
    }

    /**
     * When the user opts out of GUI parameters, a config file is required. Returns a
     * {@link ValidationError} if it is missing, or {@code null} when set.
     */
    public ValidationError validateConfigFile() {
        return options.validateConfigFile();
    }

    /**
     * The spectrum input file(s) for panes that produce a viewable mzTab result
     * (sequence, sequence --evaluate, db-search). Empty by default. This lets the GUI load the
     * result straight into PDV after a run, without re-selecting the input files.
     */
    public java.util.List<java.io.File> resultSpectra() {
        return java.util.Collections.emptyList();
    }
}
