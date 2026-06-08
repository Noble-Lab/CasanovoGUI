package org.casanovo.gui.ui;

import javafx.application.Application;

import java.util.Arrays;
import java.util.List;

/**
 * Applies an <a href="https://github.com/mkpaz/atlantafx">AtlantaFX</a> theme as
 * the JavaFX user-agent stylesheet, giving the app a modern flat look.
 *
 * <p>AtlantaFX is referenced reflectively so this class compiles and runs even
 * if the {@code atlantafx-base} jar is absent: in that case theming is simply a
 * no-op and JavaFX keeps its default Modena look. When the dependency is on the
 * classpath (it is declared in the POM), the selected theme is applied.</p>
 */
public final class Themes {

    /** Theme simple class names from {@code atlantafx.base.theme}. */
    public static final List<String> THEME_NAMES = Arrays.asList(
            "PrimerLight", "PrimerDark",
            "NordLight", "NordDark",
            "CupertinoLight", "CupertinoDark",
            "Dracula");

    public static final String DEFAULT = "PrimerLight";

    private Themes() {
    }

    /** True if AtlantaFX is available on the classpath. */
    public static boolean isAvailable() {
        try {
            Class.forName("atlantafx.base.theme.PrimerLight");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Apply the AtlantaFX theme with the given simple class name.
     *
     * @return true if the theme was applied; false if AtlantaFX/theme is unavailable.
     */
    public static boolean apply(String name) {
        String themeName = (name == null || name.trim().isEmpty()) ? DEFAULT : name.trim();
        try {
            Class<?> cls = Class.forName("atlantafx.base.theme." + themeName);
            Object theme = cls.getDeclaredConstructor().newInstance();
            Object css = cls.getMethod("getUserAgentStylesheet").invoke(theme);
            Application.setUserAgentStylesheet(String.valueOf(css));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
