package org.casanovo.gui.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.Locale;

/**
 * Reusable visualization of a peptide's per-amino-acid confidence scores: each residue is a cell
 * tinted on a red&rarr;yellow&rarr;green scale by its score, with a bar whose height is the score,
 * plus a gradient legend and a hover readout. Everything is drawn on a single {@link Canvas}, so it
 * stays fast for long peptides. Pass data with {@link #setData}; standalone and panel-agnostic.
 */
public class AaScoreChart extends BorderPane {

    private static final double CELL_W = 30;
    private static final double LETTER_H = 28;
    private static final double BAR_H = 92;
    private static final double PAD = 14;
    private static final double AXIS_W = 30;
    private static final Font LETTER_FONT = Font.font("Arial", FontWeight.BOLD, 14);
    private static final Font SMALL_FONT = Font.font("Arial", 12);

    private final Canvas canvas = new Canvas();
    private final Label title = new Label();
    private final Label subtitle = new Label();
    private final Label hover = new Label(" ");

    private String sequence = "";
    private double[] scores = new double[0];

    public AaScoreChart() {
        // Follow the app theme: -color-bg-default is an AtlantaFX variable (light or dark).
        // Arial throughout the window; child labels inherit this family.
        setStyle("-fx-background-color: -color-bg-default; -fx-font-family: 'Arial';");
        title.setStyle("-fx-font-weight: bold;");
        subtitle.setStyle("-fx-font-size: 11px; -fx-opacity: 0.8;");
        subtitle.setVisible(false);
        subtitle.setManaged(false);
        VBox head = new VBox(2, title, subtitle);
        head.setAlignment(Pos.CENTER);
        head.setPadding(new Insets(0, 0, 2, 0));
        setTop(head);

        // Hold the canvas in a region kept at least as wide as the viewport, so a short peptide's
        // chart stays centered while a long one still scrolls horizontally.
        StackPane holder = new StackPane(canvas);
        holder.setStyle("-fx-background-color: transparent;");
        ScrollPane sp = new ScrollPane(holder);
        sp.setFitToHeight(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        sp.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        sp.viewportBoundsProperty().addListener((o, a, b) -> holder.setMinWidth(b.getWidth()));
        setCenter(sp);

        Region grad = new Region();
        grad.setMinSize(120, 12);
        grad.setPrefSize(120, 12);
        grad.setStyle("-fx-background-color: linear-gradient(to right, "
                + "hsb(0,70%,90%), hsb(60,70%,90%), hsb(120,70%,90%)); -fx-background-radius: 2;");
        Label lowLabel = new Label("low");
        Label highLabel = new Label("high");
        HBox legend = new HBox(6, lowLabel, grad, highLabel);
        legend.setAlignment(Pos.CENTER);

        hover.setStyle("-fx-font-family: 'Arial';");
        VBox bottom = new VBox(2, legend, hover);
        bottom.setAlignment(Pos.CENTER);
        bottom.setPadding(new Insets(2, 0, 0, 0));
        setBottom(bottom);

        setPadding(new Insets(4));
        canvas.setOnMouseMoved(e -> updateHover(e.getX()));
        canvas.setOnMouseExited(e -> hover.setText(" "));
    }

    /** Show {@code scores} aligned 1:1 to {@code sequence}; any extra of either is ignored. */
    public void setData(String sequence, double[] scores) {
        setData(sequence, scores, null);
    }

    /** As {@link #setData(String, double[])} but with an optional context line under the title. */
    public void setData(String sequence, double[] scores, String subtitleText) {
        boolean hasSub = subtitleText != null && !subtitleText.isEmpty();
        subtitle.setVisible(false); // the peptide score is folded into the title, after the mean
        subtitle.setManaged(false);
        this.sequence = sequence == null ? "" : sequence;
        this.scores = scores == null ? new double[0] : scores;
        int n = residues();
        double sum = 0;
        int m = 0;
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(this.scores[i])) {
                sum += this.scores[i];
                m++;
            }
        }
        // First row shows only the mean and peptide score; the sequence is shown by the residue
        // circles below (and in the window title bar).
        String mean = (m > 0) ? String.format(Locale.US, "mean amino acid score %.3f", sum / m) : "";
        String score = hasSub ? subtitleText.replaceFirst("(?i)peptide score:?\\s*", "peptide score ") : "";
        String headline = mean.isEmpty() ? score : (score.isEmpty() ? mean : mean + ", " + score);
        title.setText(headline);
        canvas.setWidth(AXIS_W + 2 * PAD + n * CELL_W);
        canvas.setHeight(2 * PAD + LETTER_H + 8 + BAR_H + 16);
        draw();
    }

    private int residues() {
        return Math.min(sequence.length(), scores.length);
    }

    private void draw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        int n = residues();
        double left = AXIS_W + PAD;
        double letterTop = PAD;
        double barTop = letterTop + LETTER_H + 8;
        double barBase = barTop + BAR_H;

        // The cells/bars are always colormap-bright, so dark letters read on either theme; only the
        // marks drawn on the (theme-colored) background need to flip light/dark.
        boolean dark = Themes.isDark();
        Color letterColor = Color.web("#1a1a1a");
        Color gridColor = dark ? Color.web("#3a3a3a") : Color.web("#dddddd");
        Color tickColor = dark ? Color.web("#b0b0b0") : Color.web("#999999");
        Color posColor = dark ? Color.web("#a0a0a0") : Color.web("#888888");
        Color cellBorder = dark ? Color.web("#3a3a3a") : Color.web("#cfcfcf");
        Color naColor = dark ? Color.web("#9a9a9a") : Color.web("#dddddd");
        double tintAlpha = dark ? 0.9 : 0.55;

        // y gridlines + labels at 0 / 0.5 / 1
        g.setFont(SMALL_FONT);
        g.setTextBaseline(VPos.CENTER);
        for (double t : new double[]{0, 0.5, 1.0}) {
            double y = barBase - t * BAR_H;
            g.setStroke(gridColor);
            g.strokeLine(left, y, left + n * CELL_W, y);
            g.setTextAlign(TextAlignment.RIGHT);
            g.setFill(tickColor);
            g.fillText(String.format(Locale.US, "%.1f", t), left - 4, y);
        }

        g.setTextAlign(TextAlignment.CENTER);
        for (int i = 0; i < n; i++) {
            double s = scores[i];
            double x = left + i * CELL_W;
            Color c = Double.isNaN(s) ? naColor : colorFor(s);
            // tinted residue circle
            g.setFill(c.deriveColor(0, 1, 1, tintAlpha));
            g.fillOval(x + 1, letterTop, CELL_W - 2, LETTER_H);
            g.setStroke(cellBorder);
            g.strokeOval(x + 1, letterTop, CELL_W - 2, LETTER_H);
            g.setFont(LETTER_FONT);
            g.setTextBaseline(VPos.CENTER);
            g.setFill(letterColor);
            g.fillText(String.valueOf(sequence.charAt(i)), x + CELL_W / 2, letterTop + LETTER_H / 2);
            // score bar
            if (!Double.isNaN(s)) {
                double bh = clamp01(s) * BAR_H;
                g.setFill(c);
                g.fillRect(x + 4, barBase - bh, CELL_W - 8, bh);
            }
            // position number
            g.setFont(SMALL_FONT);
            g.setTextBaseline(VPos.TOP);
            g.setFill(posColor);
            g.fillText(String.valueOf(i + 1), x + CELL_W / 2, barBase + 3);
        }
        g.setStroke(posColor);
        g.strokeLine(left, barBase, left + n * CELL_W, barBase);
    }

    private void updateHover(double mouseX) {
        int n = residues();
        double left = AXIS_W + PAD;
        int i = (int) Math.floor((mouseX - left) / CELL_W);
        if (i < 0 || i >= n) {
            hover.setText(" ");
            return;
        }
        double s = scores[i];
        hover.setText(String.format(Locale.US, "residue %d:  %c  =  %s",
                i + 1, sequence.charAt(i), Double.isNaN(s) ? "—" : String.format(Locale.US, "%.4f", s)));
    }

    private static Color colorFor(double s) {
        return Color.hsb(clamp01(s) * 120, 0.7, 0.9);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }
}
