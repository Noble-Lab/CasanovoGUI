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

    private final TextField trainField = FxUtils.wideField();
    private final TextField validationField = FxUtils.wideField();
    private final CommonOptions options = new CommonOptions();
    private final ScrollPane content;

    public TrainPane(Window owner) {
        FxUtils.FormGrid form = new FxUtils.FormGrid();
        form.addRow("Training spectra:", trainField,
                        FxUtils.fileButton(owner, trainField, true,
                                "Annotated MGF / Lance (*.mgf, *.lance)", "*.mgf", "*.lance"))
                .addNote("Required. Annotated MGF (peptide in SEQ field) or a prebuilt .lance file.");
        form.addRow("Validation spectra (--validation_peak_path):", validationField,
                        FxUtils.fileButton(owner, validationField, false,
                                "Annotated MGF / Lance (*.mgf, *.lance)", "*.mgf", "*.lance"))
                .addNote("Required. Held-out annotated spectra for validation.");
        options.addToForm(owner, form);
        form.addNote("Tip: set a 'Model weights' file to fine-tune from existing weights instead of "
                + "training from scratch.");
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
    public String validateInputs() {
        if (PathFields.isEmpty(trainField)) {
            return "Please choose at least one training spectra file.";
        }
        String missing = PathFields.firstMissing(trainField);
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
        options.appendArgs(args);
        args.addAll(PathFields.split(trainField));
        return new CasanovoCommand("train", args);
    }
}
