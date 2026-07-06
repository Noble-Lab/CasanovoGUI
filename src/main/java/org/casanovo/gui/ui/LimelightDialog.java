package org.casanovo.gui.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Window;

/**
 * Modal dialog collecting the information needed to upload a Casanovo de novo result to Limelight:
 * the instance URL, the import key, and the target project ID — plus a required description
 * (seeded with the result file name). Fields are prefilled from caller-supplied strings (the
 * caller owns persistence); this dialog performs no I/O.
 *
 * <p>A read-only summary of the auto-detected inputs (mzTab, config source, scan files) is shown
 * so the user can confirm what will be uploaded. Returns {@code true} from
 * {@link #showAndCollect()} when the user clicks "Upload".</p>
 */
public class LimelightDialog {

    private final Window owner;
    private final String prefUrl;
    private final String prefKey;
    private final String prefProjectId;
    private final String defaultDescription;
    private final String inputsSummary;

    private final TextField urlField = FxUtils.wideField();
    private final PasswordField keyField = new PasswordField();
    private final TextField projectIdField = FxUtils.wideField();
    private final TextField descriptionField = FxUtils.wideField();

    private String url;
    private String key;
    private String projectId;
    private String description;

    public LimelightDialog(Window owner, String prefUrl, String prefKey, String prefProjectId,
                           String defaultDescription, String inputsSummary) {
        this.owner = owner;
        this.prefUrl = prefUrl;
        this.prefKey = prefKey;
        this.prefProjectId = prefProjectId;
        this.defaultDescription = defaultDescription;
        this.inputsSummary = inputsSummary;
    }

    /**
     * Show modally. On "Upload", validate, capture the field values (available via the getters),
     * and return true. Persistence is the caller's responsibility.
     */
    public boolean showAndCollect() {
        urlField.setText(prefUrl == null ? "" : prefUrl);
        keyField.setText(prefKey == null ? "" : prefKey);
        projectIdField.setText(prefProjectId == null ? "" : prefProjectId);
        descriptionField.setText(defaultDescription == null ? "" : defaultDescription);

        FxUtils.FormGrid form = new FxUtils.FormGrid();
        form.addRow("Limelight URL:", urlField)
                .addNote("Your Limelight instance, including the /limelight path, "
                        + "e.g. https://limelight.yeastrc.org/limelight");
        form.addRow("Import key:", keyField)
                .addNote("From your project's \"Command Line Import Information\" → Show Key. "
                        + "Stored locally so you don't re-enter it.");
        form.addRow("Project ID:", projectIdField)
                .addNote("The numeric ID of the Limelight project to upload into.");
        form.addRow("Description:", descriptionField)
                .addNote("Required. Shown in Limelight; defaults to the result file name.");

        Label summary = new Label(inputsSummary == null ? "" : inputsSummary);
        summary.setWrapText(true);
        summary.getStyleClass().add("text-muted");
        form.addFullWidth(summary);

        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle("Upload to Limelight");
        dialog.setHeaderText("Convert the de novo result to Limelight XML and upload it.");
        ButtonType uploadType = new ButtonType("Upload", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(uploadType, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(form.getGrid());

        dialog.getDialogPane().lookupButton(uploadType).addEventFilter(
                javafx.event.ActionEvent.ACTION, evt -> {
                    String err = validate();
                    if (err != null) {
                        Alert a = new Alert(Alert.AlertType.WARNING, err, ButtonType.OK);
                        a.setHeaderText(null);
                        if (owner != null) {
                            a.initOwner(owner);
                        }
                        a.showAndWait();
                        evt.consume();
                    }
                });

        // Center on the main window — JavaFX's default owner-centering can land off-centre; mirrors
        // SettingsDialog. Deferred one pulse so the dialog's final size is settled before centring.
        if (owner != null) {
            dialog.setOnShown(e -> {
                if (dialog.getDialogPane().getScene().getWindow() instanceof javafx.stage.Stage st) {
                    javafx.application.Platform.runLater(() -> {
                        st.setX(owner.getX() + (owner.getWidth() - st.getWidth()) / 2);
                        st.setY(owner.getY() + (owner.getHeight() - st.getHeight()) / 2);
                    });
                }
            });
        }

        ButtonType result = dialog.showAndWait().orElse(ButtonType.CANCEL);
        if (result != uploadType) {
            return false;
        }
        url = urlField.getText().trim();
        key = keyField.getText().trim();
        projectId = projectIdField.getText().trim();
        description = descriptionField.getText() == null ? "" : descriptionField.getText().trim();
        return true;
    }

    private String validate() {
        String u = urlField.getText() == null ? "" : urlField.getText().trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            return "Please enter the full Limelight URL, starting with http:// or https://";
        }
        if (keyField.getText() == null || keyField.getText().trim().isEmpty()) {
            return "Please enter your Limelight import key.";
        }
        String pid = projectIdField.getText() == null ? "" : projectIdField.getText().trim();
        if (pid.isEmpty()) {
            return "Please enter the Limelight project ID.";
        }
        try {
            Integer.parseInt(pid);
        } catch (NumberFormatException e) {
            return "The project ID must be a number.";
        }
        if (descriptionField.getText() == null || descriptionField.getText().trim().isEmpty()) {
            return "Please enter a description for the search.";
        }
        return null;
    }

    public String getUrl() {
        return url;
    }

    public String getKey() {
        return key;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getDescription() {
        return description;
    }
}
