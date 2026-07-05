package org.casanovo.gui.ui;

import java.util.Locale;
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeType;
import javafx.util.Duration;

/**
 * Option 4: a bright "comet" glow travels around the console border while a run is
 * in progress, then settles to a solid green (success) or red (error) edge.
 *
 * <p>The border is three stacked rectangle strokes sized to the console: a faint
 * full-outline <em>track</em> so the edge stays visible, a bright <em>comet</em>
 * dash whose {@code strokeDashOffset} is animated around the whole perimeter, and a
 * white-hot <em>core</em> bound to follow the comet. Colours and the glow come from
 * theme tokens in {@code console.css}; only the motion (a {@link Timeline}) and the
 * dash geometry live here. The comet travels at a roughly constant pixel speed, so
 * it looks the same on a tall or short console, and re-fits itself on resize.</p>
 */
public class CometBorderEffect implements ConsoleBorderEffect {

    /** Comet speed in pixels/second, clamped to a sane per-lap time below. */
    private static final double PX_PER_SEC = 700;
    private static final double MIN_SEC = 1.5;
    private static final double MAX_SEC = 3.5;
    /** Length of the bright glowing streak, capped to a fraction of the perimeter on small consoles. */
    private static final double COMET_LEN = 150;
    /** Fixed base lap time for the phase timeline; the real speed is set via the timeline's rate. */
    private static final double BASE_SEC = 3.0;

    private final Pane overlay = new Pane();
    private final Rectangle track = new Rectangle();
    private final Rectangle comet = new Rectangle();
    private final Rectangle core = new Rectangle();
    /** Normalised position of the comet around the perimeter (0..1) — the sole animated value. */
    private final DoubleProperty phase = new SimpleDoubleProperty(0);
    private double perimeter;
    private Timeline timeline;
    private State state = State.IDLE;
    private boolean motionEnabled = true;

    public CometBorderEffect() {
        overlay.getStyleClass().add("console-comet");
        overlay.setPickOnBounds(false);
        overlay.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE); // let the ConsoleFrame stretch it to fill
        // The overlay lives outside the console view, so carry the comet's colours (console.css)
        // here rather than relying on the console's own stylesheet. Theme tokens still resolve
        // from the AtlantaFX user-agent stylesheet via the scene graph.
        java.net.URL css = getClass().getResource("/org/casanovo/gui/console.css");
        if (css != null) {
            overlay.getStylesheets().add(css.toExternalForm());
        }

        for (Rectangle r : new Rectangle[]{track, comet, core}) {
            r.setFill(Color.TRANSPARENT);
            r.setStrokeType(StrokeType.INSIDE); // keep the stroke inside the console bounds
            r.setManaged(false);                // fixed at (0,0), sized in resize(); Pane must not lay it out
        }
        track.getStyleClass().add("track");
        comet.getStyleClass().add("comet");
        core.getStyleClass().add("core");
        core.strokeDashOffsetProperty().bind(comet.strokeDashOffsetProperty());
        phase.addListener((o, a, b) -> updateOffset()); // map phase → absolute dash offset each tick

        overlay.getChildren().addAll(track, comet, core);
        overlay.widthProperty().addListener((o, a, b) -> resize());
        overlay.heightProperty().addListener((o, a, b) -> resize());
        applyState();
    }

    @Override
    public Node getOverlay() {
        return overlay;
    }

    @Override
    public void setState(State next) {
        if (next == state) {
            return;
        }
        state = next;
        applyState();
    }

    @Override
    public void setMotionEnabled(boolean enabled) {
        if (enabled == motionEnabled) {
            return;
        }
        motionEnabled = enabled;
        applyState();
    }

    /** Reflect {@link #state} in the style class, node visibility and the animation. */
    private void applyState() {
        overlay.setVisible(state != State.IDLE);
        // With motion off, the moving comet/core are hidden; the faint accent track still
        // marks the running edge, and success/error still settle to their solid edge.
        boolean animate = state == State.RUNNING && motionEnabled;
        comet.setVisible(animate);
        core.setVisible(animate);
        overlay.getStyleClass().removeAll("state-idle", "state-running", "state-success", "state-error");
        overlay.getStyleClass().add("state-" + state.name().toLowerCase(Locale.ROOT));
        if (animate) {
            startComet();
        } else {
            stopComet();
        }
    }

    /**
     * Resize the strokes to the console and recompute the dash geometry + speed <em>without</em>
     * rebuilding the animation, so a live window resize doesn't restart (visibly jump) the comet.
     */
    private void resize() {
        double w = overlay.getWidth();
        double h = overlay.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        for (Rectangle r : new Rectangle[]{track, comet, core}) {
            r.setWidth(w);
            r.setHeight(h);
        }
        perimeter = 2 * (w + h);
        double cometLen = Math.min(COMET_LEN, perimeter * 0.35);
        comet.getStrokeDashArray().setAll(cometLen, perimeter - cometLen);
        core.getStrokeDashArray().setAll(cometLen, perimeter - cometLen);
        updateOffset(); // keep the comet at the same fractional position across the resize
        applySpeed();   // re-fit the speed to the new perimeter, without restarting
    }

    /** Map the normalised {@link #phase} to an absolute dash offset for the current perimeter. */
    private void updateOffset() {
        comet.setStrokeDashOffset(-phase.get() * perimeter);
    }

    /** Set the timeline rate so the comet keeps a roughly constant pixel speed as the console resizes. */
    private void applySpeed() {
        if (timeline == null) {
            return;
        }
        double seconds = Math.max(MIN_SEC, Math.min(MAX_SEC, perimeter / PX_PER_SEC));
        timeline.setRate(BASE_SEC / seconds);
    }

    /**
     * Build the single, never-rebuilt animation: {@link #phase} runs 0→1 (one lap) forever. Because
     * the dash array sums to exactly one perimeter, the 1→0 wrap is visually seamless.
     */
    private void ensureTimeline() {
        if (timeline != null) {
            return;
        }
        timeline = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(phase, 0, Interpolator.LINEAR)),
                new KeyFrame(Duration.seconds(BASE_SEC), new KeyValue(phase, 1, Interpolator.LINEAR)));
        timeline.setCycleCount(Animation.INDEFINITE);
    }

    private void startComet() {
        ensureTimeline();
        resize(); // fit geometry + speed to the current size before playing
        timeline.play();
    }

    private void stopComet() {
        if (timeline != null) {
            timeline.stop();
        }
        phase.set(0); // resets the dash offset to 0 via the phase listener
    }

    @Override
    public void dispose() {
        stopComet();
    }
}
