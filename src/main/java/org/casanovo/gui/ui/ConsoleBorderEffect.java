package org.casanovo.gui.ui;

import javafx.scene.Node;

/**
 * A dynamic border drawn around the console panel that reacts to the run
 * lifecycle. An implementation renders whatever look it likes into an overlay
 * node ({@link #getOverlay()}) that {@link ConsoleFrame} stacks on top of the
 * console; {@link #setState(State)} is called as a run progresses.
 *
 * <p>This is the seam for switching the console's border style: build a different
 * implementation in {@link ConsoleFrame}. Implementations: {@link PulseBorderEffect}
 * (Option 1, a glow that breathes while running) and {@link CometBorderEffect}
 * (Option 4, a glow that orbits the border then settles to a solid green/red edge).</p>
 */
public interface ConsoleBorderEffect {

    /** The run lifecycle states the border reacts to. */
    enum State { IDLE, RUNNING, SUCCESS, ERROR }

    /** The overlay node to stack over the console. Never {@code null}. */
    Node getOverlay();

    /** React to a lifecycle change. Called on the JavaFX application thread. */
    void setState(State state);

    /**
     * Enable or suppress the border's continuous running motion. When {@code false}
     * (the user's "Show running animation" toggle is off — also the pragmatic
     * reduced-motion lever, since JavaFX CSS has no {@code prefers-reduced-motion}),
     * the border still shows its static state edge but does not animate. Default: no-op.
     */
    default void setMotionEnabled(boolean enabled) {
    }

    /** Release any timers/resources. Optional; the default does nothing. */
    default void dispose() {
    }
}
