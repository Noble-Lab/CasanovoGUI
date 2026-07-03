package org.casanovo.gui.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.casanovo.gui.core.UpdateChecker;
import org.casanovo.gui.core.UpdateChecker.Target;
import org.casanovo.gui.core.UpdateChecker.UpdateInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Slim notification bar parked at the top of the window. Hidden (and taking no
 * layout space) by default; {@link #show} reveals one row per available update.
 *
 * <p>Each row offers a primary action, <b>Skip this version</b> (persist the version
 * so the auto-check stops advertising it) and <b>Dismiss</b> (hide the row for this
 * session). The primary action is <b>View</b> for the GUI/Casanovo rows (opens the
 * release page in the browser) and <b>Update…</b> for the PDV/pepmap/rawparser rows
 * (opens the Settings dialog where the one-click upgrade lives). The Casanovo row
 * additionally offers <b>Update Casanovo</b> when the GUI manages the install and can
 * upgrade it in place.</p>
 */
public class UpdateBanner extends VBox {

    // A muted "warning" strip that reads as informational, not alarming. Colours come from the
    // AtlantaFX warning tokens so the banner (and its link text) adapt to the active light/dark theme
    // instead of being a fixed bright-amber band with theme-accent links.
    private static final String ROW_STYLE =
            "-fx-background-color: -color-warning-subtle;"
                    + "-fx-border-color: -color-warning-muted;"
                    + "-fx-border-width: 0 0 1 0;";
    private static final String TEXT_STYLE = "-fx-text-fill: -color-warning-fg;";

    /** Rows currently shown, keyed by target so they can be removed individually. */
    private final Map<Target, Node> rows = new LinkedHashMap<>();

    public UpdateBanner() {
        setFillWidth(true);
        hideBanner();
    }

    /**
     * Rebuild the banner from {@code updates}. Only entries with an update
     * available are shown; previously-skipped versions are hidden unless
     * {@code includeSkipped} is true (manual checks pass true so an explicit
     * "Check for updates" always surfaces the result).
     *
     * @param updates        candidate updates (both targets may be present)
     * @param includeSkipped show versions the user previously chose to skip
     * @param onView         invoked for a row's primary action ("View" / "Update…")
     * @param onReleaseNotes invoked when the user clicks "Release notes" (PDV/pepmap/rawparser rows)
     * @param onSelfUpdate   invoked when the user clicks "Update Casanovo"
     * @param canSelfUpdate  whether a given update can be applied in-app
     */
    public void show(List<UpdateInfo> updates,
                     boolean includeSkipped,
                     Consumer<UpdateInfo> onView,
                     Consumer<UpdateInfo> onReleaseNotes,
                     Consumer<UpdateInfo> onSelfUpdate,
                     Predicate<UpdateInfo> canSelfUpdate) {
        getChildren().clear();
        rows.clear();
        for (UpdateInfo info : updates) {
            if (!info.updateAvailable) {
                continue;
            }
            if (!includeSkipped && UpdateChecker.isSkipped(info.target, info.latestVersion)) {
                continue;
            }
            Node row = buildRow(info, onView, onReleaseNotes, onSelfUpdate, canSelfUpdate);
            rows.put(info.target, row);
            getChildren().add(row);
        }
        refreshVisibility();
    }

    private Node buildRow(UpdateInfo info,
                          Consumer<UpdateInfo> onView,
                          Consumer<UpdateInfo> onReleaseNotes,
                          Consumer<UpdateInfo> onSelfUpdate,
                          Predicate<UpdateInfo> canSelfUpdate) {
        String released = info.releaseDate == null ? "" : " (released " + info.releaseDate + ")";
        Label message = new Label(info.displayName + " " + info.latestVersion + released
                + " is available — you have " + info.currentVersion + ".");
        message.setStyle(TEXT_STYLE);
        message.setWrapText(true);
        HBox.setHgrow(message, Priority.ALWAYS);
        message.setMaxWidth(Double.MAX_VALUE);

        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6, 8, 6, 12));
        row.setStyle(ROW_STYLE);
        row.getChildren().add(message);

        if (canSelfUpdate != null && canSelfUpdate.test(info) && onSelfUpdate != null) {
            Button update = new Button("Update " + info.displayName);
            update.getStyleClass().add("accent");
            update.setOnAction(e -> onSelfUpdate.accept(info));
            row.getChildren().add(update);
        }

        // PDV/pepmap/rawparser "View" opens the Settings dialog (where the one-click upgrade lives),
        // so label it "Update…"; the GUI/Casanovo rows' "View" opens the release page and keeps "View".
        boolean opensUpgradeSettings = info.target == Target.PDV
                || info.target == Target.PEPMAP
                || info.target == Target.RAWPARSER;
        Hyperlink view = new Hyperlink(opensUpgradeSettings ? "Update…" : "View");
        view.setOnAction(e -> {
            if (onView != null) {
                onView.accept(info);
            }
        });

        Hyperlink skip = new Hyperlink("Skip this version");
        skip.setOnAction(e -> {
            UpdateChecker.skip(info.target, info.latestVersion);
            removeTarget(info.target);
        });

        Hyperlink dismiss = new Hyperlink("Dismiss");
        dismiss.setOnAction(e -> removeTarget(info.target));

        // Keep the links readable on the warning surface (the default accent colour can be low-contrast).
        view.setStyle(TEXT_STYLE);
        skip.setStyle(TEXT_STYLE);
        dismiss.setStyle(TEXT_STYLE);

        row.getChildren().add(view);
        // Tool rows send the primary action to Settings; also offer a direct "Release notes" link to
        // the release page, so the user can see what changed before updating.
        if (opensUpgradeSettings && onReleaseNotes != null && info.pageUrl != null) {
            Hyperlink notes = new Hyperlink("Release notes");
            notes.setOnAction(e -> onReleaseNotes.accept(info));
            notes.setStyle(TEXT_STYLE);
            row.getChildren().add(notes);
        }
        row.getChildren().addAll(skip, dismiss);
        return row;
    }

    /** Remove the row for one target (e.g. after a successful in-app update). */
    public void removeTarget(Target target) {
        Node row = rows.remove(target);
        if (row != null) {
            getChildren().remove(row);
        }
        refreshVisibility();
    }

    /** Hide the whole banner and drop all rows. */
    public void hideBanner() {
        getChildren().clear();
        rows.clear();
        refreshVisibility();
    }

    /** Visible (and laid out) only while it has at least one row. */
    private void refreshVisibility() {
        boolean hasRows = !getChildren().isEmpty();
        setVisible(hasRows);
        setManaged(hasRows);
    }
}
