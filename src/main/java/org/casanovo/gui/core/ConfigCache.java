package org.casanovo.gui.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;

/**
 * Caches the base Casanovo configuration — the output of {@code casanovo configure} —
 * per installed Casanovo version under {@code ~/.casanovo-gui/config-cache}.
 *
 * <p>The base carries every option the installed Casanovo expects, so overlaying the
 * GUI's parameters onto it (see {@link CasanovoConfig#overlayOnto}) yields a config
 * that stays valid across Casanovo releases that add new options — the GUI never has
 * to track the schema. {@code casanovo configure} is run at most once per version
 * (cached), so per-run config preparation stays instant.</p>
 */
public final class ConfigCache {

    private ConfigCache() {
    }

    private static Path cacheDir() {
        return CasanovoInstaller.defaultInstallRoot().resolve("config-cache");
    }

    /** Installed Casanovo version for {@code settings}, or empty if it can't be read. */
    private static Optional<String> installedVersion(Settings settings) {
        return PyVenv.venvRootForExecutable(settings.getCasanovoExecutable())
                .flatMap(root -> PyVenv.packageVersion(root, "casanovo"));
    }

    private static Path baseFile(String version) {
        return cacheDir().resolve("casanovo-" + sanitize(version) + ".yaml");
    }

    /**
     * The cached base config for the installed version, or empty if it isn't cached
     * yet or the version can't be determined. Fast (just a file read) — safe to call
     * on the UI thread.
     */
    public static Optional<String> cachedBase(Settings settings) {
        Optional<String> version = installedVersion(settings);
        if (version.isEmpty()) {
            return Optional.empty();
        }
        Path file = baseFile(version.get());
        try {
            if (Files.isRegularFile(file)) {
                return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // treat as not cached
        }
        return Optional.empty();
    }

    /**
     * Ensure the base config is cached for the installed version, generating it once
     * via {@code casanovo configure} if needed. Spawns Casanovo, so call this OFF the
     * UI thread. Returns true if a cached base is available afterwards. A failure is
     * non-fatal: callers fall back to {@link CasanovoConfig#toYaml()}.
     */
    public static boolean warm(Settings settings) {
        Optional<String> version = installedVersion(settings);
        if (version.isEmpty()) {
            return false; // unknown version (Conda / PATH install) — caller falls back
        }
        Path file = baseFile(version.get());
        if (Files.isRegularFile(file)) {
            return true;
        }
        Path work = null;
        try {
            // `casanovo configure` crashes on an absolute -o path (its check_dir_file_exists
            // globs a non-relative pattern), so run it inside a throwaway working directory
            // with a RELATIVE output name, then move the result into the cache.
            work = Files.createTempDirectory("casanovo-cfg-");
            String relName = "base.yaml";
            List<String> cmd = new CasanovoCommand("configure", List.of("-o", relName))
                    .toProcessCommand(settings);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(work.toFile());
            pb.redirectErrorStream(true);
            // Same Windows OpenMP safeguards the GUI uses when launching Casanovo.
            pb.environment().putIfAbsent("KMP_DUPLICATE_LIB_OK", "TRUE");
            pb.environment().putIfAbsent("MKL_THREADING_LAYER", "SEQUENTIAL");
            Process p = pb.start();
            drain(p);
            int code = p.waitFor();
            Path out = work.resolve(relName);
            if (code == 0 && Files.isRegularFile(out) && Files.size(out) > 0) {
                Files.createDirectories(cacheDir());
                Files.move(out, file, StandardCopyOption.REPLACE_EXISTING);
                return true;
            }
        } catch (IOException e) {
            // non-fatal — fall back to the self-generated config
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (work != null) {
                deleteRecursively(work);
            }
        }
        return false;
    }

    private static void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    /** Drain (and discard) the process output so it can't block on a full pipe. */
    private static void drain(Process p) throws IOException {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            while (r.readLine() != null) {
                // `casanovo configure` is quiet; we only care that the file was written
            }
        }
    }

    private static String sanitize(String version) {
        return version.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
