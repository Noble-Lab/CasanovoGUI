package org.casanovo.gui.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Export a JavaFX node as a high-resolution PNG. The node is re-rendered at {@code scale}× via
 * {@link Node#snapshot}, so vector content (charts, text) stays crisp at any resolution — unlike an
 * OS screen grab, which is capped at the display DPI. Must be called on the JavaFX application thread.
 */
final class ImageExport {

    private ImageExport() {
    }

    /** Best-effort background color of the scene {@code node} lives in (the active theme's root
        background), or {@code fallback} if it can't be determined — so exports match light/dark themes. */
    static Color sceneBackground(Node node, Color fallback) {
        if (node == null || node.getScene() == null) {
            return fallback;
        }
        Parent root = node.getScene().getRoot();
        if (root instanceof Region region && region.getBackground() != null) {
            for (BackgroundFill bf : region.getBackground().getFills()) {
                if (bf.getFill() instanceof Color c) {
                    return c;
                }
            }
        }
        return node.getScene().getFill() instanceof Color c ? c : fallback;
    }

    /** Render {@code node} to a PNG at {@code dpi} (relative to the 96-DPI logical reference, so the
        render scale is dpi/96) and tag the file with that DPI so it imports at the right physical size. */
    static void writeHiResPng(Node node, int dpi, Color fill, File out) throws IOException {
        double scale = dpi / 96.0;
        SnapshotParameters sp = new SnapshotParameters();
        if (fill != null) {
            sp.setFill(fill);
        }
        sp.setTransform(Transform.scale(scale, scale));
        WritableImage img = node.snapshot(sp, null);
        int w = (int) Math.round(img.getWidth());
        int h = (int) Math.round(img.getHeight());
        BufferedImage bimg = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader pr = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                bimg.setRGB(x, y, pr.getArgb(x, y));
            }
        }
        writePng(bimg, dpi, out);
    }

    /** Like {@link #writeHiResPng} but wraps the rendered content in a thin border and a soft drop
        shadow on a transparent canvas — a "windowed" look at full DPI without the actual OS title bar. */
    static void writeHiResPngFramed(Node node, int dpi, Color contentFill, boolean shadow, File out) throws IOException {
        writePng(renderFramed(node, dpi, contentFill, shadow), dpi, out);
    }

    /** Like {@link #writeHiResPngFramed(Node, int, Color, boolean, File)} but stacks several nodes
        vertically inside one frame — exports a chosen subset of panels as a single image. */
    static void writeHiResPngFramed(List<Node> nodes, int dpi, Color contentFill, boolean shadow, File out)
            throws IOException {
        writePng(renderFramed(nodes, dpi, contentFill, shadow), dpi, out);
    }

    /** Snapshot {@code node} at {@code dpi} wrapped in a thin border (and optional drop shadow) on a
        transparent canvas, as an ARGB image. Must run on the JavaFX application thread. */
    static BufferedImage renderFramed(Node node, int dpi, Color contentFill, boolean shadow) {
        return renderFramed(List.of(node), dpi, contentFill, shadow);
    }

    /** A node that can render itself to a crisp image at a given scale (e.g. a Canvas-based view, which a
        plain scaled snapshot would blur). Honored by {@link #renderFramed(List, int, Color, boolean)}. */
    interface HiResExportable {
        WritableImage renderHiRes(double scale, Color fill);
    }

    /** Snapshot {@code node} at {@code scale}× — vector content (text, shapes) stays crisp. */
    static WritableImage snapshotScaled(Node node, double scale, Color fill) {
        SnapshotParameters sp = new SnapshotParameters();
        if (fill != null) {
            sp.setFill(fill);
        }
        sp.setTransform(Transform.scale(scale, scale));
        return node.snapshot(sp, null);
    }

    /** Snapshot each of {@code nodes} at {@code dpi}, stack them vertically (each at its own size, so a
        subset isn't padded with window-fill space), and wrap in a thin border (and optional drop shadow)
        on a transparent canvas. Must run on the JavaFX application thread. */
    static BufferedImage renderFramed(List<Node> nodes, int dpi, Color contentFill, boolean shadow) {
        double scale = dpi / 96.0;
        VBox stack = new VBox();
        if (contentFill != null) {
            stack.setBackground(new javafx.scene.layout.Background(new BackgroundFill(
                    contentFill, javafx.scene.layout.CornerRadii.EMPTY, Insets.EMPTY)));
        }
        for (Node node : nodes) {
            // A node may render itself at high resolution (e.g. a Canvas-based chart, whose raster would
            // otherwise blur when scaled up); others are snapshotted vector-crisp at the DPI scale.
            WritableImage img = (node instanceof HiResExportable h)
                    ? h.renderHiRes(scale, contentFill)
                    : snapshotScaled(node, scale, contentFill);
            stack.getChildren().add(new javafx.scene.image.ImageView(img));
        }

        javafx.scene.layout.StackPane card = new javafx.scene.layout.StackPane(stack);
        int border = Math.max(1, (int) Math.round(scale));
        card.setStyle("-fx-border-color: #c8c8c8; -fx-border-width: " + border + ";");
        double margin;
        if (shadow) {
            javafx.scene.effect.DropShadow ds = new javafx.scene.effect.DropShadow();
            ds.setRadius(14 * scale);
            ds.setOffsetY(5 * scale);
            ds.setColor(Color.rgb(0, 0, 0, 0.35));
            card.setEffect(ds);
            margin = 28 * scale;
        } else {
            margin = 8 * scale;
        }
        javafx.scene.layout.StackPane root = new javafx.scene.layout.StackPane(card);
        root.setPadding(new Insets(margin));
        root.setStyle("-fx-background-color: transparent;");
        javafx.scene.Scene scene = new javafx.scene.Scene(root);
        scene.setFill(Color.TRANSPARENT);
        root.applyCss();
        root.layout();

        SnapshotParameters fp = new SnapshotParameters();
        fp.setFill(Color.TRANSPARENT);
        return toBufferedImage(root.snapshot(fp, null));
    }

    /** Copy a JavaFX {@link WritableImage} into an ARGB {@link BufferedImage}. */
    private static BufferedImage toBufferedImage(WritableImage img) {
        int w = (int) Math.round(img.getWidth());
        int h = (int) Math.round(img.getHeight());
        BufferedImage bimg = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader pr = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                bimg.setRGB(x, y, pr.getArgb(x, y));
            }
        }
        return bimg;
    }

    /** Write a PNG, embedding {@code dpi} as the pHYs resolution via the standard ImageIO metadata. */
    static void writePng(BufferedImage img, int dpi, File out) throws IOException {
        javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        try (javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
            javax.imageio.metadata.IIOMetadata meta =
                    writer.getDefaultImageMetadata(new javax.imageio.ImageTypeSpecifier(img), param);
            String fmt = "javax_imageio_png_1.0";
            int ppm = (int) Math.round(dpi / 0.0254); // PNG pHYs is pixels per metre
            javax.imageio.metadata.IIOMetadataNode phys = new javax.imageio.metadata.IIOMetadataNode("pHYs");
            phys.setAttribute("pixelsPerUnitXAxis", Integer.toString(ppm));
            phys.setAttribute("pixelsPerUnitYAxis", Integer.toString(ppm));
            phys.setAttribute("unitSpecifier", "meter");
            javax.imageio.metadata.IIOMetadataNode root = new javax.imageio.metadata.IIOMetadataNode(fmt);
            root.appendChild(phys);
            meta.mergeTree(fmt, root);
            writer.write(meta, new javax.imageio.IIOImage(img, null, meta), param);
        } finally {
            writer.dispose();
        }
    }

    /** DPI, drop-shadow, and (optional) per-component selection chosen in the export dialog. {@code
        components[i]} matches the i-th label passed to {@link #promptExportOptions}; empty if none. */
    record ExportOptions(int dpi, boolean shadow, boolean[] components) {
    }

    /** Show the shared export options dialog (DPI + drop shadow, plus a checkbox per {@code componentLabels}
        under the DPI row when non-empty). At least one component must stay checked. Empty if cancelled. */
    static Optional<ExportOptions> promptExportOptions(Window owner, List<String> componentLabels) {
        Spinner<Integer> dpi = new Spinner<>(72, 1200, 300, 50);
        dpi.setEditable(true);
        dpi.setPrefWidth(110);
        HBox dpiRow = new HBox(8, new Label("Resolution:"), dpi, new Label("DPI"));
        dpiRow.setAlignment(Pos.CENTER_LEFT);

        CheckBox shadowCheck = new CheckBox("Drop shadow");
        shadowCheck.setSelected(true);

        Label note = new Label("Renders the content at the chosen DPI with a thin border. "
                + "300 DPI is typical for publication figures; 600 is sharper and larger.");
        note.setWrapText(true);
        note.setMaxWidth(470);
        note.setStyle("-fx-opacity: 0.7; -fx-font-size: 11px;");

        VBox content = new VBox(10, dpiRow);

        List<CheckBox> compChecks = new ArrayList<>();
        Label compWarn = new Label("Select at least one component to export.");
        compWarn.setStyle("-fx-text-fill: #C0392B; -fx-font-size: 11px;");
        if (!componentLabels.isEmpty()) {
            Label compTitle = new Label("Include (at least one):");
            compTitle.setStyle("-fx-font-weight: bold;");
            VBox compBox = new VBox(4, compTitle);
            for (String label : componentLabels) {
                CheckBox cb = new CheckBox(label);
                cb.setSelected(true);
                compChecks.add(cb);
                compBox.getChildren().add(cb);
            }
            compBox.getChildren().add(compWarn);
            content.getChildren().add(compBox);
        }

        content.getChildren().addAll(shadowCheck, note);
        content.setPadding(new Insets(12));

        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Export image");
        if (owner != null) {
            dlg.initOwner(owner);
        }
        ButtonType exportType = new ButtonType("Export", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(exportType, ButtonType.CANCEL);
        dlg.getDialogPane().setContent(content);

        if (!compChecks.isEmpty()) {
            Node exportButton = dlg.getDialogPane().lookupButton(exportType);
            Runnable update = () -> {
                boolean any = compChecks.stream().anyMatch(CheckBox::isSelected);
                exportButton.setDisable(!any); // can't export nothing
                compWarn.setVisible(!any);
                compWarn.setManaged(!any);
            };
            for (CheckBox cb : compChecks) {
                cb.selectedProperty().addListener((o, a, b) -> update.run());
            }
            update.run();
        }

        Optional<ButtonType> result = dlg.showAndWait();
        if (result.isEmpty() || result.get() != exportType) {
            return Optional.empty();
        }
        boolean[] components = new boolean[compChecks.size()];
        for (int i = 0; i < compChecks.size(); i++) {
            components[i] = compChecks.get(i).isSelected();
        }
        return Optional.of(new ExportOptions(dpi.getValue(), shadowCheck.isSelected(), components));
    }

    /** Prompt for a destination, then write {@code targets} (stacked vertically) as a high-res framed PNG
        at {@code opts}. The nullable {@code beforeSnapshot}/{@code afterSnapshot} hooks hide/restore
        transient UI during the snapshot; status messages go to the nullable {@code status}. FX thread only. */
    static void exportFramed(Window owner, List<Node> targets, String defaultFileName, ExportOptions opts,
                             Runnable beforeSnapshot, Runnable afterSnapshot, Consumer<String> status) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Export image");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG image", "*.png"));
        fc.setInitialFileName(defaultFileName);
        File chosen = fc.showSaveDialog(owner);
        if (chosen == null) {
            return;
        }
        File out = chosen.getName().toLowerCase(Locale.ROOT).endsWith(".png")
                ? chosen : new File(chosen.getParentFile(), chosen.getName() + ".png");
        try {
            if (beforeSnapshot != null) {
                beforeSnapshot.run();
            }
            try {
                Color fill = sceneBackground(targets.isEmpty() ? null : targets.get(0), Color.WHITE);
                writeHiResPngFramed(targets, opts.dpi(), fill, opts.shadow(), out);
            } finally {
                if (afterSnapshot != null) {
                    afterSnapshot.run();
                }
            }
            if (status != null) {
                status.accept("Saved image (" + opts.dpi() + " DPI): " + out.getName());
            }
        } catch (IOException ex) {
            if (status != null) {
                status.accept("Could not save image: " + ex.getMessage());
            }
        }
    }
}
