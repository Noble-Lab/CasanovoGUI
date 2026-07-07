package org.casanovo.gui.ui;

import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.stage.Window;
import org.casanovo.gui.core.CasanovoCommand;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Tab for {@code casanovo sequence} &mdash; de novo peptide sequencing of MS/MS
 * spectra in mzML, mzXML, MGF or raw files.
 */
public class SequencePane extends CommandPane {

    private final MultiFileField peakField;
    private final ScrollPane content;

    public SequencePane(Window owner) {
        peakField = new MultiFileField(owner, "spectra", true, "MS/MS files",
                "MS/MS spectra (*.mzML, *.mzXML, *.mgf, *.raw)", "*.mzML", "*.mzXML", "*.mgf", "*.raw");
        FxUtils.FormGrid form = new FxUtils.FormGrid();
        form.addRow("Spectrum file(s):", peakField.node(), peakField.browseButton())
                .required("mzML / mzXML / MGF / raw file(s) or timsTOF .d folder(s)")
                .tooltip("Required. One input type per run: spectrum files (mzML/mzXML/MGF/raw) OR Bruker "
                        + "timsTOF .d folders. Browse asks the type first; a .d input auto-selects the timsTOF "
                        + "model. Switching type replaces the current selection.");
        options.addToForm(owner, form);
        options.trackModelInput(peakField); // Model-weights placeholder follows the input type (.d -> timsTOF)
        options.addConfigRow(owner, form);
        content = new ScrollPane(form.getGrid());
        content.setFitToWidth(true);
    }

    @Override
    public String getTitle() {
        return "De novo";
    }

    @Override
    public Node getContent() {
        return content;
    }

    @Override
    protected ValidationError validatePaneInputs() {
        if (PathFields.isEmpty(peakField.field())) {
            return new ValidationError(
                    "Please choose at least one spectrum file (mzML/mzXML/MGF/raw) to sequence.",
                    peakField.field());
        }
        ValidationError mixed = PathFields.validateSingleSpectrumType(peakField.field());
        if (mixed != null) {
            return mixed;
        }
        return PathFields.firstMissing(peakField.field());
    }

    @Override
    public CasanovoCommand buildCommand() {
        List<String> args = new ArrayList<>();
        List<String> peaks = PathFields.split(peakField.field());
        options.appendArgs(args, true, peaks);
        args.addAll(peaks);
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
