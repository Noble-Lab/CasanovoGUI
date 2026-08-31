package org.casanovo.gui.ui;

import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.casanovo.gui.core.ModList;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Structured checklist editor for {@code allowed_fixed_mods} / {@code allowed_var_mods}: one
 * checkbox per modified token already defined in the "Residues &amp; modifications" vocabulary,
 * so a user picks from what Casanovo actually knows instead of hand-typing "aa:mod_residue"
 * pairs into a single-line field. Entries that don't match a known token (typos, or masses not
 * yet added to the vocabulary) are kept in a free-text area rather than silently dropped.
 *
 * <p>The list parsing lives in {@link ModList}.</p>
 */
final class ModListEditor {

    private ModListEditor() {
    }

    /**
     * @param fieldLabel  dialog title, e.g. "Allowed variable mods"
     * @param residuesText current text of the "Residues & modifications" field
     * @param currentCsv  current comma-separated "aa:mod_residue" value being edited
     * @return the new comma-separated value, or empty if the user cancelled
     */
    static Optional<String> edit(Window owner, String fieldLabel, String residuesText, String currentCsv) {
        List<String> modTokens = ModList.modTokens(residuesText);

        // Only an entry in the exact form this editor writes is represented by its checkbox; anything
        // else (a different prefix for the same token, a typo) stays in "Other" verbatim, so no
        // hand-written entry is rewritten or lost behind a checkbox that can't express it.
        Set<String> selected = new LinkedHashSet<>();
        List<String> custom = new ArrayList<>();
        for (String entry : ModList.splitEntries(currentCsv)) {
            String token = ModList.tokenOf(entry);
            if (modTokens.contains(token) && entry.equals(ModList.entryFor(token))) {
                selected.add(token);
            } else {
                custom.add(entry);
            }
        }

        VBox checks = new VBox(4);
        List<CheckBox> boxes = new ArrayList<>();
        for (String token : modTokens) {
            CheckBox cb = new CheckBox(token);
            // A token may legitimately contain '_'; mnemonic parsing (on by default for CheckBox)
            // would swallow it and underline the next character instead of showing the real token.
            cb.setMnemonicParsing(false);
            cb.setSelected(selected.contains(token));
            boxes.add(cb);
            checks.getChildren().add(cb);
        }
        if (boxes.isEmpty()) {
            Label none = new Label("No modified tokens found in \"Residues & modifications\" — add one "
                    + "there first, e.g. \"M[Oxidation]\": 147.035400");
            none.setWrapText(true);
            checks.getChildren().add(none);
        }
        ScrollPane checkScroll = new ScrollPane(checks);
        checkScroll.setFitToWidth(true);
        checkScroll.setPrefHeight(220);

        TextArea customArea = new TextArea(String.join(", ", custom));
        customArea.setPromptText("aa:mod_residue, ... (tokens not listed above)");
        customArea.setPrefRowCount(2);
        customArea.setWrapText(true);

        Label help = new Label("Each entry is 'aa:mod_residue', e.g. M:M[Oxidation] for a residue mod or "
                + "nterm:[Acetyl]- for an N-terminal one. Check a box below to include a modification "
                + "already defined in \"Residues & modifications\" — the 'aa:' prefix is filled in "
                + "automatically. Anything not yet in that vocabulary can be typed in \"Other\", one "
                + "per line or separated by commas.");
        help.setWrapText(true);

        VBox content = new VBox(10, help, checkScroll, new Label("Other (custom):"), customArea);
        content.setPadding(new Insets(10));
        content.setPrefWidth(480);

        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle(fieldLabel);
        dialog.setResizable(true);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(content);

        // Casanovo has no representation for "no modifications", and an empty list does not produce
        // one: it serialises to a YAML null, and Config.validate_param skips a null outright, leaving
        // Casanovo's own packaged default in force (verified against 5.2.1 — the run completes, using
        // all seven default variable mods). So an empty list doesn't disable modifications, it hides
        // which ones are being searched. Don't let OK produce one.
        dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(ActionEvent.ACTION, e -> {
            if (collect(boxes, customArea).isEmpty()) {
                Alert empty = new Alert(Alert.AlertType.WARNING,
                        "Casanovo has no setting for \"no modifications\". An empty list does not turn "
                                + "them off — the search silently falls back to Casanovo's own default "
                                + "modifications, and this dialog would no longer show what the run "
                                + "uses. Check a modification or type one into \"Other\", or Cancel to "
                                + "leave the list unchanged.", ButtonType.OK);
                empty.setHeaderText(null);
                empty.initOwner(dialog.getDialogPane().getScene().getWindow());
                empty.showAndWait();
                e.consume();
            }
        });

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return Optional.empty();
        }
        return Optional.of(String.join(",", collect(boxes, customArea)));
    }

    /**
     * The entries the current checkbox and "Other" state produce. A set: the same entry checked above
     * and also typed into "Other" is written once.
     */
    private static Set<String> collect(List<CheckBox> boxes, TextArea customArea) {
        Set<String> out = new LinkedHashSet<>();
        for (CheckBox cb : boxes) {
            if (cb.isSelected()) {
                out.add(ModList.entryFor(cb.getText()));
            }
        }
        out.addAll(ModList.splitEntries(customArea.getText()));
        return out;
    }
}
