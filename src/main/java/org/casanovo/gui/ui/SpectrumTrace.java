package org.casanovo.gui.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.animation.AnimationTimer;
import javafx.scene.Group;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

/**
 * A "Casanovo is running" indicator styled as a live MS/MS spectrum. A soft,
 * glowing beam sweeps left→right across a stick spectrum; each fragment peak
 * flares as the beam passes, and an arrow is drawn from the previous peak to the
 * one just reached with an amino-acid letter at its midpoint — mirroring how a
 * de&nbsp;novo sequencer reads one residue from each peak-to-peak mass gap.
 * Purely decorative: the peaks are fixed, fake data; the widget only signals
 * activity.
 *
 * <p>The spectrum fills the node's current size (recomputed in
 * {@link #layoutChildren()}), so it covers whatever region hosts it, and paints an
 * opaque, theme-matched background so it can sit as an overlay. Call
 * {@link #start()} when work begins and {@link #stop()} when it ends; both are
 * idempotent and must be called on the JavaFX Application Thread.</p>
 */
public final class SpectrumTrace extends Pane {

    /** Number of fragment peaks in the fake spectrum. */
    private static final int PEAK_COUNT = 11;
    /** Residue letters emitted as the beam passes peaks (de novo flourish). */
    private static final String RESIDUES = "AGSPVTCLINDEQKMHFRYW";

    private static final Color ACCENT      = Color.web("#4f8cff");
    private static final Color PEAK_TOP    = Color.web("#9cc0ff");
    private static final Color PEAK_BOTTOM = Color.web("#2a4a82");
    private static final Color BASELINE    = Color.rgb(128, 128, 128, 0.35);

    private static final double PAD_X = 26;
    private static final double PAD_TOP = 18;
    private static final double PAD_BOTTOM = 16;
    private static final double SWEEP_PERIOD = 2.0;   // seconds for one left→right pass
    private static final double GLOW_DECAY = 2.6;     // glow units lost per second (≈0.4s fade)
    private static final double SEG_LIFE = 1.6;       // seconds an arrow+letter annotation lives
    private static final double MIN_FRAME = 1.0 / 30;  // throttle the animation to ~30 fps

    // Per-peak m/z positions (0..1, ascending) and intensities (0..1); reshuffled each sweep.
    private final double[] mz = new double[PEAK_COUNT];
    private final double[] intensity = new double[PEAK_COUNT];
    private final java.util.Random rng = new java.util.Random();

    private final Line baseline = new Line();
    private final Rectangle beam = new Rectangle();
    private final Line[] peaks = new Line[PEAK_COUNT];
    private final DropShadow[] peakGlow = new DropShadow[PEAK_COUNT];
    private final double[] glow = new double[PEAK_COUNT]; // 0..1 current flare per peak
    private final List<Annotation> annotations = new ArrayList<>(); // arrow + letter between peaks

    private double progress;       // sweep position, 0..1
    private long lastNanos;
    private int residueIdx;
    private Color letterColor;
    private Color haloColor;

    private final AnimationTimer timer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            if (lastNanos == 0) {
                lastNanos = now;
                return;
            }
            double dt = (now - lastNanos) / 1_000_000_000.0;
            if (dt < MIN_FRAME) {
                return; // skip this pulse; let dt accumulate to ~30 fps
            }
            lastNanos = now;
            tick(Math.min(0.05, dt));
        }
    };

    public SpectrumTrace() {
        // Fill whatever container hosts it (e.g. a StackPane overlay).
        setMinSize(0, 0);
        setPrefSize(USE_COMPUTED_SIZE, USE_COMPUTED_SIZE);
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        // Opaque, theme-matched background with a subtle vertical sheen.
        setStyle("-fx-background-color: linear-gradient(to bottom, derive(-color-bg-default, 7%), -color-bg-default);");

        baseline.setStroke(BASELINE);
        baseline.setStrokeWidth(1);
        getChildren().add(baseline);

        // The glowing scan beam sits behind the peaks, so the peaks read as lit-from-behind.
        beam.setMouseTransparent(true);
        beam.setManaged(false);
        // A light blur is enough — the fill is already a soft transparent→accent→transparent
        // gradient, so a large (expensive) blur radius isn't needed.
        beam.setEffect(new GaussianBlur(6));
        getChildren().add(beam);

        LinearGradient peakFill = new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, PEAK_TOP), new Stop(1, PEAK_BOTTOM));
        for (int i = 0; i < PEAK_COUNT; i++) {
            Line p = new Line();
            p.setStroke(peakFill);
            p.setStrokeWidth(2.6);
            p.setStrokeLineCap(StrokeLineCap.ROUND);
            DropShadow g = new DropShadow(0, ACCENT);
            g.setSpread(0.4);
            p.setEffect(g);
            peakGlow[i] = g;
            peaks[i] = p;
            getChildren().add(p);
        }

        randomizePeaks();
        applyTheme();
    }

    /** Begin (or restart) the sweep animation. */
    public void start() {
        applyTheme();
        randomizePeaks();
        progress = 0;
        lastNanos = 0;
        for (int i = 0; i < glow.length; i++) {
            glow[i] = 0;
        }
        clearAnnotations();
        timer.start();
    }

    /**
     * Generate a fresh set of ascending peak positions and random intensities, so the
     * spectrum looks different after each sweep. Positions stay strictly ascending (one
     * jittered peak per evenly-spaced slot) so the left→right peak-to-peak arrows hold.
     */
    private void randomizePeaks() {
        double margin = 0.04;
        double usable = 1 - 2 * margin;
        for (int i = 0; i < PEAK_COUNT; i++) {
            // One peak per even slot, jittered only within its central half, so peaks stay
            // well spread (consecutive gap ≥ half a slot) and the spectrum reads evenly.
            mz[i] = margin + (i + 0.25 + 0.5 * rng.nextDouble()) / PEAK_COUNT * usable;
            intensity[i] = 0.25 + 0.75 * rng.nextDouble();
        }
    }

    /** Halt the animation and reset to a quiet resting state. */
    public void stop() {
        timer.stop();
        progress = 0;
        for (int i = 0; i < glow.length; i++) {
            glow[i] = 0;
        }
        clearAnnotations();
        beam.setVisible(false);
        applyGlow();
    }

    /**
     * Re-read theme-dependent colours (letter fill, beam alpha). Call this when the
     * application theme changes so a running animation updates live; the background
     * already follows the theme via the {@code -color-bg-default} CSS variable.
     */
    public void applyTheme() {
        boolean dark = Themes.isDark();
        letterColor = dark ? Color.web("#e8f0ff") : Color.web("#0b2e6b");
        haloColor = dark ? Color.BLACK : Color.WHITE;
        beam.setFill(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.TRANSPARENT),
                new Stop(0.5, ACCENT.deriveColor(0, 1, 1, dark ? 0.55 : 0.35)),
                new Stop(1, Color.TRANSPARENT)));
        // Recolour any letters already drifting on screen so a mid-run theme switch is seamless.
        for (Annotation a : annotations) {
            styleLetter(a.letter);
        }
    }

    /** Apply the current theme's fill + contrasting halo to a residue letter. */
    private void styleLetter(Text letter) {
        letter.setFill(letterColor);
        DropShadow halo = new DropShadow(3, haloColor);
        halo.setSpread(0.7);
        letter.setEffect(halo);
    }

    /** Advance the simulation by {@code dt} seconds and repaint the dynamic parts. */
    private void tick(double dt) {
        double w = getWidth();
        double h = getHeight();
        double prevCx = plotX(progress, w);
        progress += dt / SWEEP_PERIOD;
        boolean wrapped = progress >= 1;
        if (wrapped) {
            progress -= 1;
            randomizePeaks();        // fresh spectrum for the next sweep
            requestLayout();         // reposition the peak sticks
            clearAnnotations();      // drop the previous sweep's arrows + letters
            java.util.Arrays.fill(glow, 0); // and any lingering peak flares
        }
        double cx = plotX(progress, w);
        double reach = Math.max(10, w * 0.018);

        // Beam.
        double beamW = Math.max(28, w * 0.05);
        beam.setVisible(true);
        beam.setX(cx - beamW / 2);
        beam.setWidth(beamW);
        beam.setY(PAD_TOP);
        beam.setHeight(Math.max(0, h - PAD_TOP - PAD_BOTTOM));

        double base = h - PAD_BOTTOM;
        double span = Math.max(0, base - PAD_TOP);
        for (int i = 0; i < peaks.length; i++) {
            double px = plotX(mz[i], w);
            // Flare when the beam is over the peak.
            if (Math.abs(px - cx) < reach) {
                glow[i] = 1;
            }
            // On reaching peak i (i ≥ 1), draw the arrow from the previous peak to this
            // one and label the gap with a residue — one residue read per mass gap.
            if (!wrapped && i >= 1 && prevCx < px && px <= cx) {
                double xPrev = plotX(mz[i - 1], w);
                double yPrev = base - intensity[i - 1] * span;
                double yCur = base - intensity[i] * span;
                spawnSegment(xPrev, yPrev, px, yCur);
            }
            glow[i] = Math.max(0, glow[i] - dt * GLOW_DECAY);
        }
        applyGlow();
        updateAnnotations(dt);
    }

    /** Emit an arrow from (x1,y1) to (x2,y2) with a residue letter at the midpoint. */
    private void spawnSegment(double x1, double y1, double x2, double y2) {
        if (annotations.size() > 30) {
            return; // safety cap; never reached in practice
        }
        Color arrowColor = ACCENT.deriveColor(0, 1, 1, 0.9);

        // Horizontal shaft level with the apex of the lower (shorter) of the two peaks —
        // a shorter peak has the larger y (nearer the baseline).
        double yLine = Math.max(y1, y2);
        Line shaft = new Line(x1, yLine, x2, yLine);
        shaft.setStroke(arrowColor);
        shaft.setStrokeWidth(1.8);
        shaft.setStrokeLineCap(StrokeLineCap.ROUND);

        // Arrowhead: a small filled triangle at the right end, pointing in the sweep direction.
        double head = 9;
        Polygon arrow = new Polygon(
                x2, yLine,
                x2 - head, yLine - head * 0.5,
                x2 - head, yLine + head * 0.5);
        arrow.setFill(arrowColor);

        Text letter = new Text(String.valueOf(RESIDUES.charAt(residueIdx % RESIDUES.length())));
        residueIdx++;
        letter.setFont(Font.font("Segoe UI", FontWeight.BOLD, 15));
        // Theme fill + contrasting halo so the letter stays legible over the spectrum.
        styleLetter(letter);
        letter.setX((x1 + x2) / 2 - 5);
        letter.setY(yLine - 6); // sit just above the arrow's midpoint

        Group g = new Group(shaft, arrow, letter);
        g.setMouseTransparent(true);
        g.setManaged(false);
        getChildren().add(g);
        annotations.add(new Annotation(g, letter));
    }

    private void updateAnnotations(double dt) {
        for (int i = annotations.size() - 1; i >= 0; i--) {
            Annotation a = annotations.get(i);
            a.age += dt;
            if (a.age >= SEG_LIFE) {
                getChildren().remove(a.node);
                annotations.remove(i);
                continue;
            }
            double f = a.age / SEG_LIFE;
            a.letter.setY(a.letter.getY() - dt * 14); // letter drifts gently upward
            a.node.setOpacity(f < 0.12 ? f / 0.12 : 1 - Math.pow((f - 0.12) / 0.88, 2)); // fade in, ease out
        }
    }

    private void clearAnnotations() {
        for (Annotation a : annotations) {
            getChildren().remove(a.node);
        }
        annotations.clear();
    }

    /** Push the current glow values onto the peaks (thickness + halo). */
    private void applyGlow() {
        for (int i = 0; i < peaks.length; i++) {
            double g = glow[i];
            peaks[i].setStrokeWidth(2.6 + g * 2.4);
            peaks[i].setOpacity(0.82 + g * 0.18);
            peakGlow[i].setRadius(g * 18);
        }
    }

    /** X of a fractional position (0..1) within the horizontal plot area. */
    private double plotX(double frac, double w) {
        return PAD_X + frac * Math.max(0, w - 2 * PAD_X);
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth();
        double h = getHeight();
        double base = h - PAD_BOTTOM;
        double span = Math.max(0, base - PAD_TOP);
        baseline.setStartX(PAD_X);
        baseline.setStartY(base);
        baseline.setEndX(w - PAD_X);
        baseline.setEndY(base);
        for (int i = 0; i < peaks.length; i++) {
            double x = plotX(mz[i], w);
            peaks[i].setStartX(x);
            peaks[i].setStartY(base);
            peaks[i].setEndX(x);
            peaks[i].setEndY(base - intensity[i] * span);
        }
    }

    /** A fading peak-to-peak arrow with its residue letter. */
    private static final class Annotation {
        final Group node;
        final Text letter;
        double age;

        Annotation(Group node, Text letter) {
            this.node = node;
            this.letter = letter;
        }
    }
}
