package org.casanovo.gui;

import javafx.application.Application;
import org.casanovo.gui.ui.MainApp;

/**
 * Launcher for the Casanovo GUI.
 *
 * <p>This class deliberately does <b>not</b> extend {@link Application}. When a
 * JavaFX application's main class extends {@code Application} and is launched
 * from the classpath (e.g. {@code java -jar}), the JVM refuses to start with
 * "JavaFX runtime components are missing". Using a separate launcher that calls
 * {@link Application#launch} sidesteps that, so the shaded JAR runs without
 * extra module flags.</p>
 */
public final class CasanovoGuiApp {

    private CasanovoGuiApp() {
    }

    public static void main(String[] args) {
        Application.launch(MainApp.class, args);
    }
}
