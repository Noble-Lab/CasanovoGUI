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
 * spectra in mzML, mzXML or MGF files.
 */
public class SequencePane extends CommandPane {

    private final MultiFileField peakField;
    private final CommonOptions options = new CommonOptions();
    private final ScrollPane content;

    public SequencePane(Window owner) {
        peakField = new MultiFileField(owner, "MS/MS files",
                "MS/MS spectra (*.mzML, *.mzXML, *.mgf, *.raw)", "*.mzML", "*.mzXML", "*.mgf", "*.raw");
        FxUtils.FormGrid form = new FxUtils.FormGrid();
        form.addRow("Spectrum file(s):", peakField.node(), peakField.browseButton())
                .addNote("Required. One or more mzML/mzXML/MGF/raw files. "
                        + "Select multiple in the browser, or separate paths with '"
                        + File.pathSeparator + "'.");
        options.addToForm(owner, form);
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
    public String validateInputs() {
        if (PathFields.isEmpty(peakField.field())) {
            return "Please choose at least one spectrum file (mzML/mzXML/MGF/raw) to sequence.";
        }
        return PathFields.firstMissing(peakField.field());
    }

    @Override
    public CasanovoCommand buildCommand() {
        List<String> args = new ArrayList<>();
        options.appendArgs(args, true);
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
