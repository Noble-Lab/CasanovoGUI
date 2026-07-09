package org.casanovo.gui.ui;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.casanovo.gui.core.remote.RemoteSettings;

/**
 * Settings for running Casanovo on a remote server over SSH. Collects the connection + auth details and
 * the remote install/data locations, persisting them to {@link RemoteSettings} on Save. No secret (a key
 * passphrase or a password) is stored here &mdash; those are prompted per run. A "Test connection" button
 * runs a caller-supplied probe off the FX thread, so this dialog stays decoupled from the SSH backend.
 *
 * <p>Returns {@code true} from {@link #showAndApply()} when the user saved.</p>
 */
public class RemoteSettingsDialog {

    /** Probe a connection with the current (unsaved) field values; return {@code null} on success or an error message. */
    @FunctionalInterface
    public interface ConnectionTester {
        String test(String host, int port, String user, RemoteSettings.AuthMode auth, String keyPath, String knownHosts);
    }

    private final Window owner;
    private final RemoteSettings settings;
    private final ConnectionTester tester;

    private final CheckBox enableCheck = new CheckBox("Run Casanovo on the remote server");
    private final TextField hostField = FxUtils.wideField();
    private final TextField portField = new TextField();
    private final TextField userField = FxUtils.wideField();
    private final ComboBox<RemoteSettings.AuthMode> authCombo = new ComboBox<>();
    private final TextField keyField = FxUtils.wideField();
    private final TextField knownHostsField = FxUtils.wideField();
    private final TextField installDirField = FxUtils.wideField();
    private final TextField dataRootField = FxUtils.wideField();
    private final Label testResult = new Label();
    private Node keyRowLabel;
    private Button keyBrowseButton;

    public RemoteSettingsDialog(Window owner, RemoteSettings settings, ConnectionTester tester) {
        this.owner = owner;
        this.settings = settings;
        this.tester = tester;
    }

    /** Show modally; on Save, persist the fields to {@link RemoteSettings} and return true. */
    public boolean showAndApply() {
        enableCheck.setSelected(settings.isEnabled());
        hostField.setText(settings.getHost());
        portField.setText(String.valueOf(settings.getPort()));
        portField.setPrefColumnCount(5);
        userField.setText(settings.getUser());
        authCombo.getItems().setAll(RemoteSettings.AuthMode.values());
        authCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(RemoteSettings.AuthMode m) {
                return authLabel(m);
            }

            @Override
            public RemoteSettings.AuthMode fromString(String s) {
                return null;
            }
        });
        authCombo.getSelectionModel().select(settings.getAuthMode());
        keyField.setText(settings.getKeyPath());
        knownHostsField.setText(settings.getKnownHostsPath());
        installDirField.setText(settings.getInstallDir());
        dataRootField.setText(settings.getDataRoot());

        FxUtils.FormGrid form = new FxUtils.FormGrid();
        form.addRow("", enableCheck);
        form.addRow("Host:", hostField).tooltip("SSH server hostname, e.g. gpu.example.edu.");
        form.addRow("Port:", portField).optional("Default 22");
        form.addRow("User:", userField);
        form.addRow("Authentication:", authCombo)
                .tooltip("Agent/default keys, a key file, or a password. A passphrase or password is "
                        + "prompted each session and never stored.");
        keyBrowseButton = FxUtils.fileButton(owner, keyField, "sshKey", false, null);
        form.addRow("Private key file:", keyField, keyBrowseButton);
        keyRowLabel = form.lastLabel();
        form.addRow("known_hosts:", knownHostsField, FxUtils.fileButton(owner, knownHostsField, "knownHosts", false, null))
                .tooltip("Verifies the server's host key; a new host prompts for confirmation before connecting.");
        form.addRow("Remote install dir:", installDirField)
                .tooltip("Persistent remote dir for the GUI-managed Casanovo venv (reused across runs).");
        form.addRow("Remote data dir:", dataRootField)
                .tooltip("Per-run inputs/outputs are staged under here (e.g. /tmp), then results are downloaded back.");

        Button testButton = new Button("Test connection");
        testResult.getStyleClass().add("text-muted");
        testButton.setOnAction(e -> runTest(testButton));
        HBox testRow = new HBox(10, testButton, testResult);
        testRow.setAlignment(Pos.CENTER_LEFT);
        form.addRow("", testRow);

        authCombo.valueProperty().addListener((o, a, b) -> updateKeyRowVisibility());
        updateKeyRowVisibility();

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Remote execution");
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setResizable(true);
        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);
        // Scroll the form when the dialog is capped shorter than its content (see useOwnerIconAndCenter),
        // so a small monitor can never hide a row or the Save/Cancel bar.
        ScrollPane formScroll = new ScrollPane(form.getGrid());
        formScroll.setFitToWidth(true);
        formScroll.setStyle("-fx-background-color: transparent;");
        dialog.getDialogPane().setContent(formScroll);
        dialog.getDialogPane().setMinWidth(560);
        useOwnerIconAndCenter(dialog);

        dialog.getDialogPane().lookupButton(saveType).addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            if (enableCheck.isSelected()
                    && (hostField.getText().trim().isEmpty() || userField.getText().trim().isEmpty())) {
                ev.consume();
                Alert a = new Alert(Alert.AlertType.WARNING,
                        "Host and User are required to enable remote execution.", ButtonType.OK);
                a.initOwner(dialog.getDialogPane().getScene().getWindow());
                a.showAndWait();
            } else if (enableCheck.isSelected()
                    && authCombo.getValue() == RemoteSettings.AuthMode.KEY
                    && keyField.getText().trim().isEmpty()) {
                ev.consume();
                Alert a = new Alert(Alert.AlertType.WARNING,
                        "A private key file is required for key authentication.", ButtonType.OK);
                a.initOwner(dialog.getDialogPane().getScene().getWindow());
                a.showAndWait();
            }
        });

        boolean saved = dialog.showAndWait().orElse(ButtonType.CANCEL) == saveType;
        if (saved) {
            apply();
        }
        return saved;
    }

    private void updateKeyRowVisibility() {
        boolean usingKey = authCombo.getValue() == RemoteSettings.AuthMode.KEY;
        for (Node n : new Node[]{keyRowLabel, keyField, keyBrowseButton}) {
            if (n != null) {
                n.setVisible(usingKey);
                n.setManaged(usingKey);
            }
        }
    }

    /** Human-readable label for an auth mode (the combo would otherwise show the raw enum name). */
    private static String authLabel(RemoteSettings.AuthMode m) {
        if (m == null) {
            return "";
        }
        return switch (m) {
            case AGENT -> "SSH agent / default keys";
            case KEY -> "Key file";
            case PASSWORD -> "Password";
        };
    }

    private void runTest(Button testButton) {
        if (tester == null) {
            testResult.setText("(connection test unavailable)");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            port = 22;
        }
        final int p = port;
        final String host = hostField.getText().trim();
        final String user = userField.getText().trim();
        final RemoteSettings.AuthMode mode = authCombo.getValue();
        final String key = keyField.getText().trim();
        final String kh = knownHostsField.getText().trim();
        testButton.setDisable(true);
        testResult.setText("Connecting…");
        Thread t = new Thread(() -> {
            String err;
            try {
                err = tester.test(host, p, user, mode, key, kh);
            } catch (RuntimeException ex) {
                err = ex.getMessage();
            }
            final String msg = err;
            Platform.runLater(() -> {
                testButton.setDisable(false);
                testResult.setText(msg == null ? "✓ Connected" : "✗ " + msg);
            });
        }, "remote-conn-test");
        t.setDaemon(true);
        t.start();
    }

    private void apply() {
        settings.setEnabled(enableCheck.isSelected());
        settings.setHost(hostField.getText());
        try {
            settings.setPort(Integer.parseInt(portField.getText().trim()));
        } catch (NumberFormatException e) {
            settings.setPort(22);
        }
        settings.setUser(userField.getText());
        settings.setAuthMode(authCombo.getValue());
        settings.setKeyPath(keyField.getText());
        settings.setKnownHostsPath(knownHostsField.getText());
        settings.setInstallDir(installDirField.getText());
        settings.setDataRoot(dataRootField.getText());
        settings.flush();
    }

    /** Give the dialog the app icon and center it on the owner (mirrors the other dialogs). */
    private void useOwnerIconAndCenter(Dialog<?> dialog) {
        dialog.setOnShown(e -> {
            if (dialog.getDialogPane().getScene().getWindow() instanceof Stage s) {
                if (owner instanceof Stage os && !os.getIcons().isEmpty()) {
                    s.getIcons().setAll(os.getIcons());
                }
                Platform.runLater(() -> {
                    // Grow the window to fit the whole form plus the Save/Cancel bar; a resizable
                    // JavaFX dialog can otherwise open too short and clip the button row at the bottom.
                    s.sizeToScene();
                    // ...but never taller than the monitor: cap to 90% of the screen's usable height.
                    // The ScrollPane wrapping the form then shows a vertical scrollbar for the overflow.
                    double maxHeight = screenFor(s).getVisualBounds().getHeight() * 0.90;
                    if (s.getHeight() > maxHeight) {
                        s.setHeight(maxHeight);
                    }
                    // Floor the window at its settled height so it can be enlarged but never dragged
                    // shorter than the point where the form (and Save/Cancel bar) stay fully visible.
                    s.setMinHeight(s.getHeight());
                    if (owner != null) {
                        s.setX(owner.getX() + (owner.getWidth() - s.getWidth()) / 2);
                        s.setY(owner.getY() + (owner.getHeight() - s.getHeight()) / 2);
                    }
                });
            }
        });
    }

    /** The screen the dialog (or its owner) sits on, so the height cap uses the right monitor. */
    private Screen screenFor(Stage dialogStage) {
        double x = owner != null ? owner.getX() : dialogStage.getX();
        double y = owner != null ? owner.getY() : dialogStage.getY();
        double w = owner != null ? owner.getWidth() : dialogStage.getWidth();
        double h = owner != null ? owner.getHeight() : dialogStage.getHeight();
        var screens = Screen.getScreensForRectangle(x, y, w, h);
        return screens.isEmpty() ? Screen.getPrimary() : screens.get(0);
    }
}
