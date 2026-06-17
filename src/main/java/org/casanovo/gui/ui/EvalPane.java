package org.casanovo.gui.ui;

import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.stage.Window;
import org.casanovo.gui.core.CasanovoCommand;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Tab for evaluating de novo sequencing performance:
 * {@code casanovo sequence --evaluate} against annotated spectra.
 *
 * <p>Input must be an annotated MGF where the peptide is in the {@code SEQ}
 * field. Casanovo requires {@code top_match: 1} when evaluating.</p>
 */
public class EvalPane extends CommandPane {

    private final TextField peakField = FxUtils.wideField();
    private final CommonOptions options = new CommonOptions();
    private final ScrollPane content;

    public EvalPane(Window owner) {
        FxUtils.FormGrid form = new FxUtils.FormGrid();
        form.addRow("Annotated spectra:", peakField,
                        FxUtils.fileButton(owner, peakField, true, "Annotated MGF (*.mgf)", "*.mgf"))
                .addNote("Required. Annotated MGF file(s) with peptide sequences in the SEQ field. "
                        + "Requires top_match: 1 in the parameters.");
        options.addToForm(owner, form);
        content = new ScrollPane(form.getGrid());
        content.setFitToWidth(true);
    }

    @Override
    public String getTitle() {
        return "Evaluate";
    }

    @Override
    public Node getContent() {
        return content;
    }

    @Override
    public String validateInputs() {
        if (PathFields.isEmpty(peakField)) {
            return "Please choose at least one annotated MGF file to evaluate against.";
        }
        return PathFields.firstMissing(peakField);
    }

    @Override
    public CasanovoCommand buildCommand() {
        List<String> args = new ArrayList<>();
        options.appendArgs(args, true);
        args.add("--evaluate");
        args.addAll(PathFields.split(peakField));
        return new CasanovoCommand("sequence", args);
    }

    @Override
    public List<File> resultSpectra() {
        List<File> out = new ArrayList<>();
        for (String p : PathFields.split(peakField)) {
            out.add(new File(p));
        }
        return out;
    }
}
