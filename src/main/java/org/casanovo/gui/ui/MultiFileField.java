package org.casanovo.gui.ui;

import javafx.collections.SetChangeListener;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.casanovo.gui.core.TimsTof;

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
    /** Browse-memory key: reopens the chooser at the folder last used for this kind of file. */
    private final String key;
    /** When set, Browse first asks the input type — including Bruker timsTOF {@code .d} folders. */
    private final boolean allowDotD;
    private boolean syncing;

    MultiFileField(Window owner, String key, String pluralNoun, String filterDesc, String... extensions) {
        this(owner, key, false, pluralNoun, filterDesc, extensions);
    }

    MultiFileField(Window owner, String key, boolean allowDotD, String pluralNoun, String filterDesc,
                   String... extensions) {
        this.owner = owner;
        this.key = key;
        this.pluralNoun = pluralNoun;
        this.filterDesc = filterDesc;
        this.extensions = extensions;
        this.allowDotD = allowDotD;

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

    /**
     * Add Bruker timsTOF {@code .d} folders. JavaFX has no multi-select folder chooser, so a single
     * pick can add several: choose one {@code .d} folder, <em>or</em> a parent folder that holds
     * several {@code .d} folders and they are all added. Each is validated (ends {@code .d} + has
     * {@code analysis.tdf}) and appended (repeat Browse to add {@code .d} from other locations).
     */
    private void chooseDotD() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select a .d folder — or a folder containing several .d folders");
        File dir = FxUtils.startDir(field.getText(), "dotd");
        if (dir != null) {
            chooser.setInitialDirectory(dir);
        }
        File picked = chooser.showDialog(owner);
        if (picked == null) {
            return;
        }
        List<File> chosen;
        if (TimsTof.isDotD(picked)) {
            chosen = List.of(picked); // the picked folder itself is a .d
        } else {
            List<File> found = new ArrayList<>();
            File[] subs = picked.listFiles();
            if (subs != null) {
                for (File s : subs) {
                    if (TimsTof.isDotD(s)) {
                        found.add(s);
                    }
                }
            }
            if (found.isEmpty()) {
                warn("No timsTOF .d folder found at:\n" + picked.getAbsolutePath()
                        + "\n\nPick a folder that ends in \".d\" and contains \"analysis.tdf\", "
                        + "or a folder that contains such .d folders.");
                return;
            }
            found.sort((x, y) -> x.getName().compareToIgnoreCase(y.getName()));
            // One .d -> add it; several -> let the user tick which to add (a subset is fine).
            chosen = found.size() == 1 ? found : pickDotDs(found);
            if (chosen == null || chosen.isEmpty()) {
                return; // cancelled, or nothing ticked
            }
        }
        // One type per run: if the field currently holds spectrum files, drop them and switch to .d.
        // Classify by path shape (name ends ".d"), not on-disk validity, so a momentarily-unavailable
        // .d (e.g. an unmounted share) isn't mistaken for a file and silently discarded.
        List<String> existing = PathFields.split(field);
        boolean hasFiles = false;
        for (String q : existing) {
            if (q != null && !q.isBlank() && !TimsTof.looksLikeDotD(q)) {
                hasFiles = true;
                break;
            }
        }
        List<String> paths = hasFiles ? new ArrayList<>() : new ArrayList<>(existing);
        for (File d : chosen) {
            String p = d.getAbsolutePath();
            if (!paths.contains(p)) {
                paths.add(p);
            }
        }
        setPaths(paths);
        // Reopen at the folder that CONTAINS the .d(s): the parent when the user picked a .d directly,
        // else the picked container itself — never inside a .d (which would show Bruker internals).
        File anchor = TimsTof.isDotD(picked) ? picked.getParentFile() : picked;
        if (anchor != null) {
            FxUtils.rememberBrowseDir("dotd", anchor);
        }
    }

    /**
     * Modal checkbox picker over the {@code .d} folders found in the chosen directory — tick the ones
     * to add (all pre-ticked, so "all" is one click and a subset is a couple of unticks). Returns the
     * ticked folders, or {@code null} if cancelled. This is the native way to multi-select folders,
     * which JavaFX's {@code DirectoryChooser} can't do directly.
     */
    private List<File> pickDotDs(List<File> candidates) {
        List<CheckBox> boxes = new ArrayList<>();
        VBox listBox = new VBox(4);
        for (File f : candidates) {
            CheckBox cb = new CheckBox(f.getName());
            cb.setSelected(true);
            cb.setUserData(f);
            boxes.add(cb);
            listBox.getChildren().add(cb);
        }
        CheckBox all = new CheckBox("Select all");
        all.setSelected(true);
        all.setOnAction(e -> boxes.forEach(b -> b.setSelected(all.isSelected())));
        ScrollPane scroll = new ScrollPane(listBox);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(260);
        VBox content = new VBox(8, all, new Separator(), scroll);
        content.setPadding(new Insets(6));

        Dialog<ButtonType> dlg = new Dialog<>();
        if (owner != null) {
            dlg.initOwner(owner);
        }
        dlg.setTitle("Select .d folders");
        dlg.setHeaderText(candidates.size() + " timsTOF .d folders found — tick the ones to add:");
        dlg.getDialogPane().setContent(content);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dlg.setResizable(true);
        useAppIcon(dlg);
        if (dlg.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return null;
        }
        List<File> result = new ArrayList<>();
        for (CheckBox b : boxes) {
            if (b.isSelected()) {
                result.add((File) b.getUserData());
            }
        }
        return result;
    }

    private void warn(String message) {
        Alert a = new Alert(Alert.AlertType.WARNING, message, ButtonType.OK);
        a.setHeaderText(null);
        if (owner != null) {
            a.initOwner(owner);
        }
        a.showAndWait();
    }

    private static final String FILES_LABEL = "mzML/mzXML/MGF/raw";
    private static final String DOT_D_LABEL = "Bruker timsTOF (.d folder)";

    /**
     * Browse. For a plain field, a native multi-file picker. For a timsTOF-enabled field, first ask
     * the input type — spectrum <em>files</em> (mzML/mzXML/MGF/raw, picked together) or Bruker
     * {@code .d} <em>folders</em> — then open the matching native chooser. A run uses ONE type only,
     * so picking one type replaces the other.
     */
    private void chooseFiles() {
        if (!allowDotD) {
            addFiles(filterDesc, extensions); // plain field: one native multi-file picker
            return;
        }
        ComboBox<String> types = new ComboBox<>();
        types.getItems().addAll(FILES_LABEL, DOT_D_LABEL);
        types.getSelectionModel().selectFirst();
        types.setMaxWidth(Double.MAX_VALUE);

        // A hint under the dropdown for each type. Its width is pinned so the .d tip WRAPS to a few
        // lines (rather than widening the dialog); the window is then re-fitted for height below.
        Label hint = new Label(hintForType(types.getValue()));
        hint.setWrapText(true);
        hint.setMinWidth(420);
        hint.setPrefWidth(420);
        hint.setMaxWidth(420);
        hint.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        hint.getStyleClass().add("text-muted");

        VBox box = new VBox(8, types, hint);
        box.setPadding(new Insets(4, 2, 2, 2));
        box.setPrefWidth(440);
        box.setMaxWidth(440);

        Dialog<ButtonType> dlg = new Dialog<>();
        if (owner != null) {
            dlg.initOwner(owner);
        }
        dlg.setTitle("Add spectrum file(s)");
        dlg.setHeaderText("Select the input MS/MS file type:");
        dlg.getDialogPane().setContent(box);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dlg.setResizable(true);
        useAppIcon(dlg);

        types.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            hint.setText(hintForType(n));
            if (dlg.getDialogPane().getScene() != null) {
                Window w = dlg.getDialogPane().getScene().getWindow();
                javafx.application.Platform.runLater(w::sizeToScene);
            }
        });

        if (dlg.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        if (DOT_D_LABEL.equals(types.getValue())) {
            chooseDotD();
        } else {
            addFiles(filterDesc, extensions);
        }
    }

    /** The hint shown under the type dropdown for each input type. */
    private static String hintForType(String type) {
        if (DOT_D_LABEL.equals(type)) {
            return "Tip: to add several .d folders at once, pick the folder that contains them — "
                    + "you'll then tick which .d to include.";
        }
        return "Select one or more spectrum files (mzML, mzXML, MGF or raw).";
    }

    /**
     * Give a dialog the app's window icon and no header graphic, so it doesn't show the default
     * "?" icon. JavaFX dialogs don't inherit the owner's icon, so copy it once the window exists.
     */
    private void useAppIcon(Dialog<?> dlg) {
        dlg.getDialogPane().setGraphic(null);
        if (owner instanceof Stage os && !os.getIcons().isEmpty()) {
            dlg.setOnShown(e -> {
                if (dlg.getDialogPane().getScene().getWindow() instanceof Stage s) {
                    s.getIcons().setAll(os.getIcons());
                }
            });
        }
    }

    /** Native multi-file picker for {@code exts}; the selection <em>replaces</em> the field (one type per run). */
    private void addFiles(String desc, String[] exts) {
        FileChooser chooser = new FileChooser();
        FxUtils.applyExtFilter(chooser, desc, exts);
        File dir = FxUtils.startDir(field.getText(), key);
        if (dir != null) {
            chooser.setInitialDirectory(dir);
        }
        List<File> files = chooser.showOpenMultipleDialog(owner);
        if (files == null || files.isEmpty()) {
            return;
        }
        List<String> paths = new ArrayList<>();
        for (File f : files) {
            paths.add(f.getAbsolutePath());
        }
        // A spectrum run takes one type at a time; catch a mixed multi-selection here (the run also
        // validates). Only for timsTOF-enabled fields — Train may combine MGF and Lance.
        if (allowDotD && TimsTof.hasMixedSpectrumTypes(paths)) {
            warn("Please select spectrum files of a single type (all mzML, all mzXML, all MGF, or all raw). "
                    + "Casanovo runs one input type at a time.");
            return;
        }
        setPaths(paths);
        FxUtils.rememberBrowseDir(key, files.get(0));
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
