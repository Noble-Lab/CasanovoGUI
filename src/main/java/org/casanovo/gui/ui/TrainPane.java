package org.casanovo.gui.ui;

import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.stage.Window;
import org.casanovo.gui.core.CasanovoCommand;

import java.util.ArrayList;
import java.util.List;

/**
 * Tab for {@code casanovo train} &mdash; train a new model or fine-tune an
 * existing one from annotated spectra. Form:
 * {@code casanovo train --validation_peak_path <val> [options] <train...>}.
 * Supplying a model file fine-tunes from those weights.
 */
public class TrainPane extends CommandPane {

    private final MultiFileField trainField;
    private final TextField validationField = FxUtils.wideField();
    private final ScrollPane content;

    public TrainPane(Window owner) {
        trainField = new MultiFileField(owner, "training spectra files",
                "Annotated MGF / Lance (*.mgf, *.lance)", "*.mgf", "*.lance");
        FxUtils.FormGrid form = new FxUtils.FormGrid();
        form.addRow("Training spectra:", trainField.node(), trainField.browseButton())
                .required("Annotated MGF or .lance file")
                .tooltip("Required. Annotated MGF (peptide in SEQ field) or a prebuilt .lance file.");
        form.addRow("Validation spectra:", validationField,
                        FxUtils.fileButton(owner, validationField, false,
                                "Annotated MGF / Lance (*.mgf, *.lance)", "*.mgf", "*.lance"))
                .required("Held-out annotated spectra")
                .tooltip("Required. Held-out annotated spectra for validation. (--validation_peak_path)");
        options.addToForm(owner, form);
        form.addNote("Tip: set a 'Model weights' file to fine-tune from existing weights instead of "
                + "training from scratch.");
        options.addConfigRow(owner, form);
        content = new ScrollPane(form.getGrid());
        content.setFitToWidth(true);
    }

    @Override
    public String getTitle() {
        return "Train";
    }

    @Override
    public Node getContent() {
        return content;
    }

    @Override
    protected ValidationError validatePaneInputs() {
        if (PathFields.isEmpty(trainField.field())) {
            return new ValidationError("Please choose at least one training spectra file.",
                    trainField.field());
        }
        ValidationError missing = PathFields.firstMissing(trainField.field());
        if (missing != null) {
            return missing;
        }
        return PathFields.validateSingleFile(validationField, "a validation spectra file");
    }

    @Override
    public CasanovoCommand buildCommand() {
        List<String> args = new ArrayList<>();
        args.add("--validation_peak_path");
        args.add(validationField.getText().trim());
        // false: a blank model here means "train from scratch" — do not pin to orbitrap.
        options.appendArgs(args, false);
        args.addAll(PathFields.split(trainField.field()));
        return new CasanovoCommand("train", args);
    }
}
