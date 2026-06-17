package org.casanovo.gui.ui;

import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.stage.Window;
import org.casanovo.gui.core.CasanovoCommand;

import java.util.ArrayList;
import java.util.List;

/**
 * Tab for {@code casanovo configure} &mdash; writes a YAML file pre-filled with
 * Casanovo's current defaults, which can then be edited and reused.
 *
 * <p>Casanovo's {@code configure} subcommand does <em>not</em> accept a single
 * {@code --output} path; it uses the same pair as the other subcommands:
 * {@code --output_dir <folder>} for where the file goes, and
 * {@code --output_root <name>} for the base name (Casanovo appends the
 * extension itself).</p>
 */
public class ConfigurePane extends CommandPane {

    private final TextField outputDirField = FxUtils.wideField();
    private final TextField outputRootField = FxUtils.wideField();
    private final ScrollPane content;

    public ConfigurePane(Window owner) {
        FxUtils.FormGrid form = new FxUtils.FormGrid();
        form.addRow("Output directory (--output_dir):", outputDirField,
                        FxUtils.dirButton(owner, outputDirField))
                .addNote("Optional. Folder where the generated YAML will be written. "
                        + "Leave blank for Casanovo's default location.");
        form.addRow("Output root name (--output_root):", outputRootField)
                .addNote("Optional. Base name for the generated YAML "
                        + "(Casanovo appends the extension). Leave blank for Casanovo's default.");
        form.addNote("Generates a config.yaml of Casanovo defaults. Tip: you can instead edit every "
                + "parameter in the Parameters dialog and use 'Save to file' there.");
        content = new ScrollPane(form.getGrid());
        content.setFitToWidth(true);
    }

    @Override
    public String getTitle() {
        return "Configure";
    }

    @Override
    public Node getContent() {
        return content;
    }

    @Override
    public String validateInputs() {
        return null; // all optional
    }

    @Override
    public CasanovoCommand buildCommand() {
        List<String> args = new ArrayList<>();
        String dir = outputDirField.getText() == null ? "" : outputDirField.getText().trim();
        if (!dir.isEmpty()) {
            args.add("--output_dir");
            args.add(dir);
        }
        String root = outputRootField.getText() == null ? "" : outputRootField.getText().trim();
        if (!root.isEmpty()) {
            args.add("--output_root");
            args.add(root);
        }
        return new CasanovoCommand("configure", args);
    }
}
