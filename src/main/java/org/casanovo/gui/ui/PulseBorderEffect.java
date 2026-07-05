package org.casanovo.gui.ui;

import java.util.Locale;
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeType;
import javafx.util.Duration;

/**
 * Option 1: a soft accent glow around the console that gently "breathes" (fades in
 * and out) while a run is in progress, then returns to a plain edge. It does not
 * distinguish success from error — the border simply stops when the run ends.
 *
 * <p>A single rectangle stroke sized to the console carries the accent colour and a
 * soft {@code DropShadow} glow (both theme tokens in {@code console.css}); a
 * {@link Timeline} breathes the overlay's opacity between {@link #DIM} and 1.0 to
 * produce the pulse.</p>
 */
public class PulseBorderEffect implements ConsoleBorderEffect {

    /** Opacity floor of the breathing pulse (1.0 is the peak). Kept high so the pulse reads as a
        calm, ambient cue rather than an attention magnet over a multi-minute run. */
    private static final double DIM = 0.5;
    /** Half-cycle time (dim → bright); the full breath is twice this. */
    private static final double HALF_CYCLE_SEC = 1.4;

    private final Pane overlay = new Pane();
    private final Rectangle edge = new Rectangle();
    private Timeline timeline;
    private State state = State.IDLE;
    private boolean motionEnabled = true;

    public PulseBorderEffect() {
        overlay.getStyleClass().add("console-pulse");
        overlay.setPickOnBounds(false);
        overlay.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE); // let the ConsoleFrame stretch it to fill
        // The overlay lives outside the console view, so carry its colours (console.css) here.
        // Theme tokens still resolve from the AtlantaFX user-agent stylesheet via the scene graph.
        java.net.URL css = getClass().getResource("/org/casanovo/gui/console.css");
        if (css != null) {
            overlay.getStylesheets().add(css.toExternalForm());
        }

        edge.setFill(Color.TRANSPARENT);
        edge.setStrokeType(StrokeType.INSIDE); // keep the stroke inside the console bounds
        edge.setManaged(false);                // fixed at (0,0), sized in resize()
        edge.getStyleClass().add("edge");
        overlay.getChildren().add(edge);

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

    private void applyState() {
        overlay.setVisible(state != State.IDLE); // running pulses; success/error settle to a solid edge
        overlay.getStyleClass().removeAll("state-idle", "state-running", "state-success", "state-error");
        overlay.getStyleClass().add("state-" + state.name().toLowerCase(Locale.ROOT));
        if (state == State.RUNNING && motionEnabled) {
            startPulse();
        } else {
            stopPulse(); // static edge: running with motion off, or a settled success/error edge
        }
    }

    private void resize() {
        double w = overlay.getWidth();
        double h = overlay.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        edge.setWidth(w);
        edge.setHeight(h);
    }

    private void startPulse() {
        if (timeline == null) {
            timeline = new Timeline(
                    new KeyFrame(Duration.ZERO,
                            new KeyValue(overlay.opacityProperty(), DIM, Interpolator.EASE_BOTH)),
                    new KeyFrame(Duration.seconds(HALF_CYCLE_SEC),
                            new KeyValue(overlay.opacityProperty(), 1.0, Interpolator.EASE_BOTH)));
            timeline.setAutoReverse(true);
            timeline.setCycleCount(Animation.INDEFINITE);
        }
        resize();
        timeline.playFromStart();
    }

    private void stopPulse() {
        if (timeline != null) {
            timeline.stop();
        }
        overlay.setOpacity(1.0);
    }

    @Override
    public void dispose() {
        stopPulse();
    }
}
