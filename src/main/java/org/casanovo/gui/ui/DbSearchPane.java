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
 * Tab for {@code casanovo db-search} &mdash; database search using Casanovo as a
 * scoring function. Casanovo expects the peak path(s) first and the FASTA last:
 * {@code casanovo db-search spectra.mgf proteome.fasta}.
 */
public class DbSearchPane extends CommandPane {

    private final TextField peakField = FxUtils.wideField();
    private final TextField fastaField = FxUtils.wideField();
    private final CommonOptions options = new CommonOptions();
    private final ScrollPane content;

    public DbSearchPane(Window owner) {
        FxUtils.FormGrid form = new FxUtils.FormGrid();
        form.addRow("Spectrum file(s):", peakField,
                        FxUtils.fileButton(owner, peakField, true,
                                "MS/MS spectra (*.mzML, *.mzXML, *.mgf)", "*.mzML", "*.mzXML", "*.mgf"))
                .addNote("Required. mzML / mzXML / MGF (separate multiple with '"
                        + File.pathSeparator + "').");
        form.addRow("Protein database (FASTA):", fastaField,
                        FxUtils.fileButton(owner, fastaField, false,
                                "FASTA (*.fasta, *.fa)", "*.fasta", "*.fa", "*.gz"))
                .addNote("Required. Protein sequences in FASTA format. Digestion parameters are set "
                        + "in the Parameters dialog (default: tryptic).");
        options.addToForm(owner, form);
        form.addNote("Note: database searching is experimental and may run slowly for large databases.");
        content = new ScrollPane(form.getGrid());
        content.setFitToWidth(true);
    }

    @Override
    public String getTitle() {
        return "DB Search";
    }

    @Override
    public Node getContent() {
        return content;
    }

    @Override
    public String validateInputs() {
        if (PathFields.isEmpty(peakField)) {
            return "Please choose at least one spectrum file (mzML/mzXML/MGF).";
        }
        String missing = PathFields.firstMissing(peakField);
        if (missing != null) {
            return missing;
        }
        return PathFields.validateSingleFile(fastaField, "a protein FASTA database");
    }

    @Override
    public CasanovoCommand buildCommand() {
        List<String> args = new ArrayList<>();
        options.appendArgs(args);
        args.addAll(PathFields.split(peakField));
        args.add(fastaField.getText().trim());
        return new CasanovoCommand("db-search", args);
    }
}
