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

    private final MultiFileField peakField;
    private final TextField fastaField = FxUtils.wideField();
    private final CommonOptions options = new CommonOptions();
    private final ScrollPane content;

    public DbSearchPane(Window owner) {
        peakField = new MultiFileField(owner, "MS/MS files",
                "MS/MS spectra (*.mzML, *.mzXML, *.mgf, *.raw)", "*.mzML", "*.mzXML", "*.mgf", "*.raw");
        FxUtils.FormGrid form = new FxUtils.FormGrid();
        form.addRow("Spectrum file(s):", peakField.node(), peakField.browseButton())
                .addNote("Required. mzML/mzXML/MGF/raw files. Select multiple in the "
                        + "browser, or separate paths with '" + File.pathSeparator + "'.");
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
        if (PathFields.isEmpty(peakField.field())) {
            return "Please choose at least one spectrum file (mzML/mzXML/MGF).";
        }
        String missing = PathFields.firstMissing(peakField.field());
        if (missing != null) {
            return missing;
        }
        return PathFields.validateSingleFile(fastaField, "a protein FASTA database");
    }

    @Override
    public CasanovoCommand buildCommand() {
        List<String> args = new ArrayList<>();
        options.appendArgs(args, true);
        args.addAll(PathFields.split(peakField.field()));
        args.add(fastaField.getText().trim());
        return new CasanovoCommand("db-search", args);
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
