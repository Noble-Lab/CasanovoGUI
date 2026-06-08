package org.casanovo.gui.core;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Locale;

/**
 * Locates Casanovo's cached default model weights so the GUI can show which
 * weights would be used when the {@code --model} field is left blank.
 *
 * <p>Casanovo caches weights under a per-OS directory:
 * {@code ~/.cache/casanovo} (Linux, or {@code $XDG_CACHE_HOME/casanovo}),
 * {@code ~/Library/Caches/casanovo} (macOS), and
 * {@code %LOCALAPPDATA%\\casanovo} (Windows).</p>
 */
public final class CasanovoWeights {

    private CasanovoWeights() {
    }

    /** Casanovo's weights cache directory for this OS, or {@code null} if undeterminable. */
    public static Path cacheDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            if (local != null && !local.isEmpty()) {
                return Paths.get(local, "casanovo");
            }
            return home == null ? null : Paths.get(home, "AppData", "Local", "casanovo");
        } else if (os.contains("mac") || os.contains("darwin")) {
            return home == null ? null : Paths.get(home, "Library", "Caches", "casanovo");
        } else {
            String xdg = System.getenv("XDG_CACHE_HOME");
            if (xdg != null && !xdg.isEmpty()) {
                return Paths.get(xdg, "casanovo");
            }
            return home == null ? null : Paths.get(home, ".cache", "casanovo");
        }
    }

    /**
     * The most recently modified cached {@code *.ckpt} weights file, or
     * {@code null} if none is cached yet.
     */
    public static File findCachedDefault() {
        Path dir = cacheDir();
        if (dir == null || !Files.isDirectory(dir)) {
            return null;
        }
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ckpt"))
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .map(Path::toFile)
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
