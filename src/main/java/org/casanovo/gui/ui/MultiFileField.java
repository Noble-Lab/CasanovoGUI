package org.casanovo.gui.ui;

import javafx.collections.SetChangeListener;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * A path-list input that mirrors the Carafe "train MS file(s)" control: pick a
 * single file (shown inline and editable) or several files (collapsed to a
 * "(N … selected)" hyperlink that opens a popup listing one file per row for
 * viewing/editing).
 *
 * <p>The backing {@link TextField} always holds the real paths joined by
 * {@link File#pathSeparator}, so {@link PathFields} (and therefore command
 * building / validation) keeps working against it unchanged.</p>
 */
final class MultiFileField {

    /** AtlantaFX's danger (validation-error) state, mirrored onto the collapsed summary box. */
    private static final PseudoClass DANGER = PseudoClass.getPseudoClass("danger");

    private final TextField field = FxUtils.wideField();
    private final Hyperlink link = new Hyperlink();
    private final StackPane node = new StackPane(field, link);
    private final Button browse = new Button("Browse");
    private final Window owner;
    /** Plural noun for the summary, e.g. {@code "MS/MS files"}. */
    private final String pluralNoun;
    private final String filterDesc;
    private final String[] extensions;
    private boolean syncing;

    MultiFileField(Window owner, String pluralNoun, String filterDesc, String... extensions) {
        this.owner = owner;
        this.pluralNoun = pluralNoun;
        this.filterDesc = filterDesc;
        this.extensions = extensions;

        field.setMaxWidth(Double.MAX_VALUE);
        node.setMaxWidth(Double.MAX_VALUE);

        StackPane.setAlignment(link, Pos.CENTER_LEFT);
        link.setUnderline(true);
        link.setFocusTraversable(false);
        link.setManaged(false);
        link.setVisible(false);
        link.setPadding(Insets.EMPTY);
        link.setTooltip(new Tooltip("Click to view or edit the selected files"));
        link.setOnAction(e -> {
            link.setVisited(false);
            showListDialog();
        });

        browse.setOnAction(e -> chooseFiles());

        // Keep the display in sync when the user types/pastes paths manually.
        field.textProperty().addListener((obs, o, n) -> {
            if (!syncing) {
                refreshDisplay();
            }
        });
        // Mirror the field's danger (validation-error) state onto the collapsed summary box, since
        // the backing field's own :danger border isn't visible while it's hidden.
        field.getPseudoClassStates().addListener((SetChangeListener<PseudoClass>) c -> refreshDisplay());
    }

    /** The backing field that holds the path-separator-joined paths. */
    TextField field() {
        return field;
    }

    /** The node to place in the form's field column (field + hyperlink overlay). */
    StackPane node() {
        return node;
    }

    /** The "Browse" button to place in the form's trailing column. */
    Button browseButton() {
        return browse;
    }

    private void chooseFiles() {
        FileChooser chooser = new FileChooser();
        FxUtils.applyExtFilter(chooser, filterDesc, extensions);
        File dir = FxUtils.initialDir(field.getText());
        if (dir != null) {
            chooser.setInitialDirectory(dir);
        }
        List<File> files = chooser.showOpenMultipleDialog(owner);
        if (files != null && !files.isEmpty()) {
            List<String> paths = new ArrayList<>();
            for (File f : files) {
                paths.add(f.getAbsolutePath());
            }
            setPaths(paths);
        }
    }

    private void setPaths(List<String> paths) {
        syncing = true;
        field.setText(String.join(File.pathSeparator, paths));
        syncing = false;
        refreshDisplay();
    }

    /** Collapse to the summary hyperlink for >1 paths; otherwise show the editable field. */
    private void refreshDisplay() {
        int count = PathFields.split(field).size();
        boolean multi = count > 1;
        if (multi) {
            link.setText("(" + count + " " + pluralNoun + " selected)");
        }
        link.setManaged(multi);
        link.setVisible(multi);
        field.setManaged(!multi);
        field.setVisible(!multi);
        // The hidden field takes its border with it, so in summary mode draw a matching
        // (theme-aware) box around the link to keep the input outlined — in the danger colour when
        // the field is flagged invalid, since the field's own :danger border isn't visible then.
        boolean invalid = field.getPseudoClassStates().contains(DANGER);
        String border = invalid ? "-color-danger-emphasis" : "-color-border-default";
        node.setStyle(multi
                ? "-fx-border-color: " + border + ";"
                + "-fx-border-width: 1px;"
                + "-fx-border-radius: 4px;"
                + "-fx-min-height: 30px;"
                + "-fx-padding: 0 8px 0 8px;"
                : "");
    }

    private void showListDialog() {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.initOwner(owner);
        dlg.setTitle("Selected " + pluralNoun);
        dlg.setHeaderText("One file per line. Edit to add or remove files.");
        dlg.setResizable(true);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);

        TextArea area = new TextArea(String.join(System.lineSeparator(), PathFields.split(field)));
        area.setPrefColumnCount(64);
        area.setPrefRowCount(14);
        dlg.getDialogPane().setContent(area);

        dlg.showAndWait().ifPresent(bt -> {
            if (bt == ButtonType.OK) {
                List<String> paths = new ArrayList<>();
                for (String line : area.getText().split("\\R")) {
                    String t = line.trim();
                    if (!t.isEmpty()) {
                        paths.add(t);
                    }
                }
                setPaths(paths);
            }
        });
    }
}
