package org.casanovo.gui.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;

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
     * The most recently modified cached {@code *.ckpt} whose filename contains {@code modelId} (e.g.
     * {@code "orbitrap"} or {@code "timstof"} — Casanovo names them {@code casanovo_<id>_v<ver>.ckpt}),
     * or {@code null} if that model isn't cached yet.
     */
    public static File findCachedFor(String modelId) {
        Path dir = cacheDir();
        if (dir == null || !Files.isDirectory(dir) || modelId == null || modelId.isEmpty()) {
            return null;
        }
        String needle = modelId.toLowerCase(Locale.ROOT);
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .filter(f -> {
                        String n = f.getName().toLowerCase(Locale.ROOT);
                        return n.endsWith(".ckpt") && n.contains(needle);
                    })
                    .max(Comparator.comparingLong(File::lastModified))
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Cached {@code *.ckpt} files that are NOT valid zip archives — i.e. corrupt or
     * truncated downloads.
     *
     * <p>A PyTorch checkpoint is a zip archive: a complete one opens cleanly, an
     * interrupted download has no readable central directory and fails to open. That is
     * exactly the failure Casanovo reports ("PytorchStreamReader failed ... failed
     * finding central directory" / "Weights file incompatible"). Only the zip directory
     * is read, so this stays fast even for multi-hundred-MB checkpoints — and it never
     * flags a complete, valid checkpoint.</p>
     */
    public static List<File> findCorruptCheckpoints() {
        Path dir = cacheDir();
        List<File> corrupt = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return corrupt;
        }
        try (var walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ckpt"))
                    .filter(p -> !isValidZip(p.toFile()))
                    .forEach(p -> corrupt.add(p.toFile()));
        } catch (IOException ignored) {
            // best-effort
        }
        return corrupt;
    }

    private static boolean isValidZip(File f) {
        try (ZipFile z = new ZipFile(f)) {
            return true; // central directory readable => complete archive
        } catch (IOException e) {
            return false;
        }
    }
}
