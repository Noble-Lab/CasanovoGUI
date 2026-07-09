package org.casanovo.gui.core.remote;

import java.io.File;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Persisted configuration for the remote (SSH) execution backend, on its own Preferences node
 * ({@code org/casanovo/gui/remote}) so it stays isolated from the rest of the app. <strong>Secrets are
 * never stored</strong>: a key passphrase or a password is prompted per session and held only in memory
 * by the caller. Persisted here are only the connection coordinates and non-secret paths.
 */
public final class RemoteSettings {

    /** How the GUI authenticates to the SSH server. */
    public enum AuthMode {
        /** A running {@code ssh-agent} holds the key. */
        AGENT,
        /** An OpenSSH private-key file (passphrase, if any, prompted per session). */
        KEY,
        /** A password, prompted per session and never persisted. */
        PASSWORD
    }

    private final Preferences prefs = Preferences.userRoot().node("org/casanovo/gui/remote");

    public boolean isEnabled() {
        return prefs.getBoolean("enabled", false);
    }

    public void setEnabled(boolean v) {
        prefs.putBoolean("enabled", v);
    }

    public String getHost() {
        return prefs.get("host", "");
    }

    public void setHost(String v) {
        prefs.put("host", v == null ? "" : v.trim());
    }

    public int getPort() {
        return prefs.getInt("port", 22);
    }

    public void setPort(int v) {
        prefs.putInt("port", v <= 0 ? 22 : v);
    }

    public String getUser() {
        return prefs.get("user", "");
    }

    public void setUser(String v) {
        prefs.put("user", v == null ? "" : v.trim());
    }

    public AuthMode getAuthMode() {
        try {
            return AuthMode.valueOf(prefs.get("authMode", AuthMode.AGENT.name()));
        } catch (IllegalArgumentException e) {
            return AuthMode.AGENT;
        }
    }

    public void setAuthMode(AuthMode m) {
        prefs.put("authMode", (m == null ? AuthMode.AGENT : m).name());
    }

    /** OpenSSH private-key file, used when {@link #getAuthMode()} is {@link AuthMode#KEY}. */
    public String getKeyPath() {
        return prefs.get("keyPath", "");
    }

    public void setKeyPath(String v) {
        prefs.put("keyPath", v == null ? "" : v.trim());
    }

    /** {@code known_hosts} file for host-key verification (defaults to {@code ~/.ssh/known_hosts}). */
    public String getKnownHostsPath() {
        return prefs.get("knownHosts", defaultKnownHosts());
    }

    public void setKnownHostsPath(String v) {
        prefs.put("knownHosts", blankToDefault(v, defaultKnownHosts()));
    }

    /** Persistent remote dir for the GUI-managed Casanovo venv (survives reboot; reused across runs). */
    public String getInstallDir() {
        return prefs.get("installDir", "~/.casanovo-gui");
    }

    public void setInstallDir(String v) {
        prefs.put("installDir", blankToDefault(v, "~/.casanovo-gui"));
    }

    /** Per-run data workspace root on the server (ephemeral {@code /tmp}). */
    public String getDataRoot() {
        return prefs.get("dataRoot", "/tmp/casanovo-gui");
    }

    public void setDataRoot(String v) {
        prefs.put("dataRoot", blankToDefault(v, "/tmp/casanovo-gui"));
    }

    /** Persist any changes best-effort. */
    public void flush() {
        try {
            prefs.flush();
        } catch (BackingStoreException ignored) {
            // best-effort; values still apply for this session
        }
    }

    /** True once host + user (and, for key auth, a key file) are set — the minimum to attempt a connection. */
    public boolean isConfigured() {
        if (getHost().isEmpty() || getUser().isEmpty()) {
            return false;
        }
        return getAuthMode() != AuthMode.KEY || !getKeyPath().isBlank();
    }

    private static String defaultKnownHosts() {
        String home = System.getProperty("user.home");
        return home == null ? "" : home + File.separator + ".ssh" + File.separator + "known_hosts";
    }

    private static String blankToDefault(String v, String def) {
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }
}
