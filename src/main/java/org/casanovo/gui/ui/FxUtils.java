package org.casanovo.gui.ui;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared JavaFX helpers: a small form-grid builder and file/directory chooser
 * buttons, so the command panes stay focused on which fields they expose.
 */
final class FxUtils {

    private FxUtils() {
    }

    /** Builds a three-column form: label | field (grows) | optional trailing button. */
    static final class FormGrid {
        private final GridPane grid = new GridPane();
        private int row = 0;
        private Label lastLabel;
        private Node lastField;

        FormGrid() {
            grid.setHgap(8);
            grid.setVgap(6);
            grid.setPadding(new Insets(12));
            ColumnConstraints c0 = new ColumnConstraints();
            c0.setHalignment(HPos.RIGHT);
            c0.setMinWidth(Region.USE_PREF_SIZE); // keep the label column at its content width — no "…" truncation
            ColumnConstraints c1 = new ColumnConstraints();
            c1.setHgrow(Priority.ALWAYS);
            c1.setFillWidth(true);
            ColumnConstraints c2 = new ColumnConstraints();
            c2.setMinWidth(Region.USE_PREF_SIZE); // keep the "Browse" button column at its content width — no "…"
            grid.getColumnConstraints().addAll(c0, c1, c2);
        }

        FormGrid addRow(String label, Node field) {
            return addRow(label, field, null);
        }

        FormGrid addRow(String label, Node field, Node trailing) {
            Label lbl = new Label(label);
            grid.add(lbl, 0, row);
            grid.add(field, 1, row);
            GridPane.setHgrow(field, Priority.ALWAYS);
            if (trailing != null) {
                grid.add(trailing, 2, row);
            }
            lastLabel = lbl;
            lastField = field;
            row++;
            return this;
        }

        /** Add an arbitrary node spanning all three columns (full dialog width). */
        FormGrid addFullWidth(Node node) {
            grid.add(node, 0, row, 3, 1);
            row++;
            return this;
        }

        /** Add full-width italic helper text. */
        FormGrid addNote(String text) {
            Label note = new Label(text);
            note.setWrapText(true);
            // Use the theme-aware muted style class (adapts to light/dark) rather
            // than the Modena-only -fx-text-inner-color, which AtlantaFX dark
            // themes leave dark -> invisible on a dark form background.
            note.getStyleClass().addAll("text-muted", "form-note");
            note.setStyle("-fx-font-style: italic;");
            // Indent under the field column (not the right-aligned label column) so each note
            // sits beneath the field it describes, with the field's width to wrap into.
            grid.add(note, 1, row, 2, 1);
            row++;
            return this;
        }

        /** Mark the field required, shown as an accent "Required. {hint}" placeholder inside it. */
        FormGrid required(String hint) {
            return promptMark("Required. " + hint, "prompt-required");
        }

        /** Mark the field optional, shown as a muted "Optional. {hint}" placeholder inside it. */
        FormGrid optional(String hint) {
            return promptMark("Optional. " + hint, "prompt-optional");
        }

        /** Set the field's placeholder text and a style class that colours it (see app.css). */
        private FormGrid promptMark(String text, String styleClass) {
            TextField tf = innerTextField(lastField);
            if (tf != null) {
                tf.setPromptText(text);
                tf.getStyleClass().add(styleClass);
            }
            return this;
        }

        /**
         * The text field a placeholder can live in: a plain TextField, or the inner field of a
         * composite (MultiFileField's StackPane). Null for controls with no placeholder to show
         * (a selected ComboBox, a CheckBox).
         */
        private static TextField innerTextField(Node node) {
            if (node instanceof TextField) {
                return (TextField) node;
            }
            if (node instanceof Pane) {
                for (Node child : ((Pane) node).getChildren()) {
                    if (child instanceof TextField) {
                        return (TextField) child;
                    }
                }
            }
            return null;
        }

        /**
         * Install a help tooltip on the most-recently-added row's label and field, so
         * hovering either one shows the description. Call immediately after {@link #addRow}.
         */
        FormGrid tooltip(String text) {
            Tooltip tip = helpTooltip(text);
            installTooltipDeep(lastLabel, tip);
            installTooltipDeep(lastField, tip);
            return this;
        }

        /**
         * Install {@code tip} on {@code node} and, when it is a plain container (the label's
         * HBox or MultiFileField's StackPane), its direct children too — a tooltip on the
         * wrapper alone doesn't fire when hovering the inner control.
         */
        private static void installTooltipDeep(Node node, Tooltip tip) {
            installTooltipOne(node, tip);
            if (node instanceof Pane) {
                for (Node child : ((Pane) node).getChildren()) {
                    installTooltipOne(child, tip);
                }
            }
        }

        private static void installTooltipOne(Node node, Tooltip tip) {
            if (node == null) {
                return;
            }
            // Keep a control that already declares its own tooltip (e.g. the overlaid
            // "view/edit selected files" hyperlink on a multi-file field).
            if (node instanceof Control && ((Control) node).getTooltip() != null) {
                return;
            }
            Tooltip.install(node, tip);
        }

        GridPane getGrid() {
            return grid;
        }
    }

    /** A tooltip tuned for form help text: wraps, shows promptly, and stays up long enough to read. */
    static Tooltip helpTooltip(String text) {
        Tooltip tip = new Tooltip(text);
        tip.setWrapText(true);
        tip.setMaxWidth(360);
        tip.setShowDelay(Duration.millis(400));
        tip.setShowDuration(Duration.seconds(30));
        return tip;
    }

    static TextField wideField() {
        TextField tf = new TextField();
        tf.setPrefColumnCount(34);
        return tf;
    }

    /**
     * A "Browse" button that opens an <em>existing</em> file chooser (Open) and
     * writes the picked path into {@code target}. Use this for input files only;
     * for output files the user creates, use {@link #saveFileButton}.
     */
    static Button fileButton(Window owner, TextField target, boolean multiple,
                             String filterDesc, String... extensions) {
        Button b = new Button("Browse");
        b.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            applyExtFilter(chooser, filterDesc, extensions);
            File dir = initialDir(target.getText());
            if (dir != null) {
                chooser.setInitialDirectory(dir);
            }
            if (multiple) {
                List<File> files = chooser.showOpenMultipleDialog(owner);
                if (files != null && !files.isEmpty()) {
                    List<String> paths = new ArrayList<>();
                    for (File f : files) {
                        paths.add(f.getAbsolutePath());
                    }
                    target.setText(String.join(File.pathSeparator, paths));
                }
            } else {
                File f = chooser.showOpenDialog(owner);
                if (f != null) {
                    target.setText(f.getAbsolutePath());
                }
            }
        });
        return b;
    }

    /**
     * A "Browse" button that opens a <em>Save as</em> file chooser, so the user
     * can pick a path for a file that does not exist yet. The chosen path is
     * written into {@code target}. Use this for output files the user creates
     * (e.g. {@code casanovo configure --output ...}).
     */
    static Button saveFileButton(Window owner, TextField target,
                                 String filterDesc, String... extensions) {
        Button b = new Button("Browse");
        b.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            applyExtFilter(chooser, filterDesc, extensions);
            // Seed the chooser with whatever the user already typed.
            String current = target.getText() == null ? "" : target.getText().trim();
            if (!current.isEmpty()) {
                File f = new File(current);
                File parent = f.getParentFile();
                if (parent != null && parent.isDirectory()) {
                    chooser.setInitialDirectory(parent);
                }
                String name = f.getName();
                if (!name.isEmpty()) {
                    chooser.setInitialFileName(name);
                }
            } else if (extensions != null && extensions.length > 0) {
                // Suggest a sensible default name based on the first extension.
                String first = extensions[0]; // e.g. "*.yaml"
                int dot = first.lastIndexOf('.');
                String ext = dot >= 0 ? first.substring(dot) : "";
                chooser.setInitialFileName("config" + ext);
            }
            File f = chooser.showSaveDialog(owner);
            if (f != null) {
                target.setText(f.getAbsolutePath());
            }
        });
        return b;
    }

    static void applyExtFilter(FileChooser chooser, String filterDesc, String... extensions) {
        if (filterDesc != null && extensions != null && extensions.length > 0) {
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter(filterDesc, extensions));
        }
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("All files", "*.*"));
    }

    /** A "Browse" button that selects a directory into {@code target}. */
    static Button dirButton(Window owner, TextField target) {
        Button b = new Button("Browse");
        b.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            File dir = initialDir(target.getText());
            if (dir != null) {
                chooser.setInitialDirectory(dir.isDirectory() ? dir : dir.getParentFile());
            }
            File f = chooser.showDialog(owner);
            if (f != null) {
                target.setText(f.getAbsolutePath());
            }
        });
        return b;
    }

    static File initialDir(String current) {
        if (current == null || current.trim().isEmpty()) {
            return null;
        }
        String first = current.trim();
        int idx = first.indexOf(File.pathSeparatorChar);
        if (idx >= 0) {
            first = first.substring(0, idx);
        }
        File f = new File(first);
        File parent = f.getParentFile();
        if (f.isDirectory()) {
            return f;
        }
        return (parent != null && parent.isDirectory()) ? parent : null;
    }
}
