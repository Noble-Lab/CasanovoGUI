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
 * Tab for {@code casanovo sequence} &mdash; de novo peptide sequencing of MS/MS
 * spectra in mzML, mzXML or MGF files.
 */
public class SequencePane extends CommandPane {

    private final TextField peakField = FxUtils.wideField();
    private final CommonOptions options = new CommonOptions();
    private final ScrollPane content;

    public SequencePane(Window owner) {
        FxUtils.FormGrid form = new FxUtils.FormGrid();
        form.addRow("Spectrum file(s):", peakField,
                        FxUtils.fileButton(owner, peakField, true,
                                "MS/MS spectra (*.mzML, *.mzXML, *.mgf)", "*.mzML", "*.mzXML", "*.mgf"))
                .addNote("Required. One or more mzML / mzXML / MGF files (separate multiple with '"
                        + File.pathSeparator + "').");
        options.addToForm(owner, form);
        content = new ScrollPane(form.getGrid());
        content.setFitToWidth(true);
    }

    @Override
    public String getTitle() {
        return "Sequence";
    }

    @Override
    public Node getContent() {
        return content;
    }

    @Override
    public String validateInputs() {
        if (PathFields.isEmpty(peakField)) {
            return "Please choose at least one spectrum file (mzML/mzXML/MGF) to sequence.";
        }
        return PathFields.firstMissing(peakField);
    }

    @Override
    public CasanovoCommand buildCommand() {
        List<String> args = new ArrayList<>();
        options.appendArgs(args);
        args.addAll(PathFields.split(peakField));
        return new CasanovoCommand("sequence", args);
    }
}
