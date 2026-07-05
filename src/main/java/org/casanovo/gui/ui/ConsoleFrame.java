package org.casanovo.gui.ui;

import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * Wraps the console view and stacks a {@link ConsoleBorderEffect} over it, so the
 * console shows a dynamic border that follows the run lifecycle. The wrapper is a
 * {@link Region}, so it can stand in for the raw console view anywhere in the layout.
 *
 * <p>To switch the border style, construct with a different {@link ConsoleBorderEffect}:
 * {@link PulseBorderEffect} (Option 1, the current default) or {@link CometBorderEffect}
 * (Option 4). Nothing else in the app needs to change.</p>
 */
public class ConsoleFrame extends StackPane {

    /**
     * Master switch for the dynamic console border. Disabled for now; the effect classes
     * ({@link PulseBorderEffect} / {@link CometBorderEffect}) and the {@code console.css}
     * rules stay in place — flip this to {@code true} to re-enable (and pick the effect in
     * the no-arg constructor below).
     */
    private static final boolean BORDER_ENABLED = false;

    /** The border effect, or {@code null} when the border is disabled. */
    private final ConsoleBorderEffect effect;

    public ConsoleFrame(Region content) {
        this(content, BORDER_ENABLED ? new PulseBorderEffect() : null);
    }

    public ConsoleFrame(Region content, ConsoleBorderEffect effect) {
        this.effect = effect;
        if (effect != null) {
            Node overlay = effect.getOverlay();
            overlay.setMouseTransparent(true); // clicks and text selection pass through to the console
            getChildren().addAll(content, overlay); // content at index 0, overlay on top at index 1
        } else {
            getChildren().add(content); // border disabled — just the console, no overlay
        }
    }

    /**
     * Replace the wrapped console view (e.g. when toggling the colored console)
     * without disturbing the border overlay stacked above it.
     */
    public void setContent(Region content) {
        getChildren().set(0, content);
    }

    /** Drive the border through the run lifecycle (no-op when the border is disabled). */
    public void setState(ConsoleBorderEffect.State state) {
        if (effect != null) {
            effect.setState(state);
        }
    }

    /** Allow or suppress the border's running motion (no-op when the border is disabled). */
    public void setMotionEnabled(boolean enabled) {
        if (effect != null) {
            effect.setMotionEnabled(enabled);
        }
    }
}
