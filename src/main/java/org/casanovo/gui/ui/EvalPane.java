package org.casanovo.gui.ui;

import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
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
 * field. Evaluation metrics are undefined when {@code top_match > 1}, so the
 * default {@code top_match: 1} is recommended.</p>
 */
public class EvalPane extends CommandPane {

    private final MultiFileField peakField;
    private final ScrollPane content;

    public EvalPane(Window owner) {
        peakField = new MultiFileField(owner, "annotatedSpectra", "annotated MGF files", "Annotated MGF (*.mgf)", "*.mgf");
        FxUtils.FormGrid form = new FxUtils.FormGrid();
        form.addRow("Annotated spectra:", peakField.node(), peakField.browseButton())
                .required("Annotated MGF (peptide in SEQ field)")
                .tooltip("Required. Annotated MGF file(s) with peptide sequences in the SEQ field. "
                        + "top_match: 1 (the default) is recommended; evaluation metrics are "
                        + "undefined when top_match > 1.");
        options.addToForm(owner, form);
        options.addConfigRow(owner, form);
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
    protected ValidationError validatePaneInputs() {
        if (PathFields.isEmpty(peakField.field())) {
            return new ValidationError(
                    "Please choose at least one annotated MGF file to evaluate against.",
                    peakField.field());
        }
        return PathFields.firstMissing(peakField.field());
    }

    @Override
    public CasanovoCommand buildCommand() {
        List<String> args = new ArrayList<>();
        options.appendArgs(args, true);
        args.add("--evaluate");
        args.addAll(PathFields.split(peakField.field()));
        return new CasanovoCommand("sequence", args);
    }

    @Override
    public List<File> resultSpectra() {
        List<File> out = new ArrayList<>();
        for (String p : PathFields.split(peakField.field())) {
            out.add(new File(p));
        }
        return out;
    }
}
