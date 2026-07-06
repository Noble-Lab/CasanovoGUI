package org.casanovo.gui.ui;

import java.io.File;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Remembers, per input field, the folder last used in that field's Browse dialog, so reopening the
 * chooser starts where the user last was — even when the field is empty or in a fresh session.
 *
 * <p>Persisted in {@link Preferences} under one node, keyed by a short, stable field id (e.g.
 * {@code "spectra"}, {@code "fasta"}, {@code "output"}). The logic lives inside the shared chooser
 * factories in {@link FxUtils} ({@code fileButton} / {@code dirButton}) and {@link MultiFileField},
 * so any field built through them gets this behaviour just by passing a key — no new field ever has
 * to re-implement it.</p>
 */
final class BrowseMemory {

    private static final Preferences PREFS = Preferences.userRoot().node("org/casanovo/gui/browse");

    private BrowseMemory() {
    }

    /** The folder last used for {@code key}, or {@code null} if none is stored or it no longer exists. */
    static File dirFor(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        String path = PREFS.get(key, "");
        if (path.isEmpty()) {
            return null;
        }
        File dir = new File(path);
        return dir.isDirectory() ? dir : null; // ignore a since-deleted folder
    }

    /** Remember {@code file}'s folder (or {@code file} itself when it is a directory) as last-used for {@code key}. */
    static void remember(String key, File file) {
        if (key == null || key.isEmpty() || file == null) {
            return;
        }
        File dir = file.isDirectory() ? file : file.getParentFile();
        if (dir != null && dir.isDirectory()) {
            PREFS.put(key, dir.getAbsolutePath());
            try {
                PREFS.flush();
            } catch (BackingStoreException ignored) {
                // best-effort persistence; the value still applies for this session
            }
        }
    }
}
