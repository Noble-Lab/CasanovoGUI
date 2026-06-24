package org.casanovo.gui.core;

import java.util.prefs.Preferences;

/**
 * Holds and persists user-level execution settings: how to locate and launch
 * the {@code casanovo} executable.
 *
 * <p>Two execution strategies are supported for pointing at a tool installed
 * inside a Conda environment:</p>
 * <ul>
 *     <li><b>Direct</b> &mdash; invoke the executable (default {@code casanovo}) directly,
 *     relying on it being available on the {@code PATH} of the GUI process.</li>
 *     <li><b>Conda</b> &mdash; wrap the invocation in
 *     {@code conda run --no-capture-output -n <env> casanovo ...} so the command
 *     runs inside the named Conda environment without the user having to activate
 *     it first.</li>
 * </ul>
 *
 * Settings are persisted between sessions using {@link Preferences}.
 */
public class Settings {

    private static final String KEY_EXECUTABLE = "casanovoExecutable";
    private static final String KEY_USE_CONDA = "useConda";
    private static final String KEY_CONDA_ENV = "condaEnv";
    private static final String KEY_CONDA_EXECUTABLE = "condaExecutable";
    private static final String KEY_PDV_JAR = "pdvJar";
    private static final String KEY_PEPMAP_JAR = "pepmapJar";
    private static final String KEY_THEME = "theme";
    private static final String KEY_COLORED_CONSOLE = "coloredConsole";
    private static final String KEY_SHOW_RUNNING_ANIMATION = "showRunningAnimation";

    private final Preferences prefs = Preferences.userRoot().node("org/casanovo/gui");

    private String casanovoExecutable;
    private boolean useConda;
    private String condaEnv;
    private String condaExecutable;
    private String pdvJar;
    private String pepmapJar;
    private String theme;
    private boolean coloredConsole;
    private boolean showRunningAnimation;

    public Settings() {
        load();
    }

    /** Re-read persisted values, applying sensible defaults when absent. */
    public final void load() {
        casanovoExecutable = prefs.get(KEY_EXECUTABLE, "casanovo");
        useConda = prefs.getBoolean(KEY_USE_CONDA, false);
        condaEnv = prefs.get(KEY_CONDA_ENV, "casanovo_env");
        condaExecutable = prefs.get(KEY_CONDA_EXECUTABLE, "conda");
        pdvJar = prefs.get(KEY_PDV_JAR, "");
        pepmapJar = prefs.get(KEY_PEPMAP_JAR, "");
        theme = prefs.get(KEY_THEME, "PrimerLight");
        coloredConsole = prefs.getBoolean(KEY_COLORED_CONSOLE, true);
        showRunningAnimation = prefs.getBoolean(KEY_SHOW_RUNNING_ANIMATION, true);
    }

    /** Persist the current values so they survive a restart. */
    public void save() {
        prefs.put(KEY_EXECUTABLE, nullToEmpty(casanovoExecutable));
        prefs.putBoolean(KEY_USE_CONDA, useConda);
        prefs.put(KEY_CONDA_ENV, nullToEmpty(condaEnv));
        prefs.put(KEY_CONDA_EXECUTABLE, nullToEmpty(condaExecutable));
        prefs.put(KEY_PDV_JAR, nullToEmpty(pdvJar));
        prefs.put(KEY_PEPMAP_JAR, nullToEmpty(pepmapJar));
        prefs.put(KEY_THEME, nullToEmpty(theme));
        prefs.putBoolean(KEY_COLORED_CONSOLE, coloredConsole);
        prefs.putBoolean(KEY_SHOW_RUNNING_ANIMATION, showRunningAnimation);
    }

    public String getCasanovoExecutable() {
        return (casanovoExecutable == null || casanovoExecutable.trim().isEmpty())
                ? "casanovo" : casanovoExecutable.trim();
    }

    public void setCasanovoExecutable(String casanovoExecutable) {
        this.casanovoExecutable = casanovoExecutable;
    }

    public boolean isUseConda() {
        return useConda;
    }

    public void setUseConda(boolean useConda) {
        this.useConda = useConda;
    }

    public String getCondaEnv() {
        return condaEnv == null ? "" : condaEnv.trim();
    }

    public void setCondaEnv(String condaEnv) {
        this.condaEnv = condaEnv;
    }

    public String getCondaExecutable() {
        return (condaExecutable == null || condaExecutable.trim().isEmpty())
                ? "conda" : condaExecutable.trim();
    }

    public void setCondaExecutable(String condaExecutable) {
        this.condaExecutable = condaExecutable;
    }

    public String getPdvJar() {
        return pdvJar == null ? "" : pdvJar.trim();
    }

    public void setPdvJar(String pdvJar) {
        this.pdvJar = pdvJar;
    }

    public String getPepmapJar() {
        return pepmapJar == null ? "" : pepmapJar.trim();
    }

    public void setPepmapJar(String pepmapJar) {
        this.pepmapJar = pepmapJar;
    }

    public String getTheme() {
        return (theme == null || theme.trim().isEmpty()) ? "PrimerLight" : theme.trim();
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }

    public boolean isColoredConsole() {
        return coloredConsole;
    }

    public void setColoredConsole(boolean coloredConsole) {
        this.coloredConsole = coloredConsole;
    }

    public boolean isShowRunningAnimation() {
        return showRunningAnimation;
    }

    public void setShowRunningAnimation(boolean showRunningAnimation) {
        this.showRunningAnimation = showRunningAnimation;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
