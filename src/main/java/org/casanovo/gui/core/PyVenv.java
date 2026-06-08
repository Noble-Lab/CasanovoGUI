package org.casanovo.gui.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Read-only helpers for inspecting a Python virtual environment <em>without</em>
 * launching the interpreter: locate its {@code site-packages} and read an
 * installed package's version straight from its {@code dist-info} metadata.
 *
 * <p>Reading {@code dist-info} directly is fast and works offline, and on Windows
 * it sidesteps the OpenMP DLL clash that can crash a real {@code python} /
 * {@code casanovo} invocation. Shared by {@link UpdateChecker} (reads the
 * installed Casanovo version) and {@link CasanovoInstaller} (detects whether an
 * upgrade disturbed the torch stack).</p>
 */
public final class PyVenv {

    private PyVenv() {
    }

    /**
     * Derive the venv root that owns an executable such as
     * {@code <venv>/Scripts/casanovo.exe} (Windows) or {@code <venv>/bin/casanovo}
     * (Unix). Returns empty for a bare name (e.g. {@code "casanovo"} resolved via
     * {@code PATH}) or a path without the expected {@code Scripts}/{@code bin}
     * parent.
     */
    public static Optional<Path> venvRootForExecutable(String exePath) {
        if (exePath == null || exePath.isBlank()) {
            return Optional.empty();
        }
        if (!exePath.contains("/") && !exePath.contains("\\")) {
            return Optional.empty(); // bare PATH name — no venv layout to walk
        }
        try {
            Path exe = Paths.get(exePath);
            Path binDir = exe.getParent();      // Scripts (win) or bin (unix)
            if (binDir == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(binDir.getParent());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Every existing {@code site-packages} directory under {@code venvRoot},
     * covering both the Windows ({@code Lib/site-packages}) and Unix
     * ({@code lib/pythonX.Y/site-packages}) layouts.
     */
    public static List<Path> sitePackages(Path venvRoot) {
        List<Path> out = new ArrayList<>();
        if (venvRoot == null) {
            return out;
        }
        Path win = venvRoot.resolve("Lib").resolve("site-packages");
        if (Files.isDirectory(win)) {
            out.add(win);
        }
        Path lib = venvRoot.resolve("lib");
        if (Files.isDirectory(lib)) {
            try (var dirs = Files.list(lib)) {
                dirs.filter(Files::isDirectory)
                        .map(d -> d.resolve("site-packages"))
                        .filter(Files::isDirectory)
                        .forEach(out::add);
            } catch (IOException ignored) {
                // best-effort
            }
        }
        return out;
    }

    /**
     * Version of {@code pkg} as recorded in {@code <pkg>-*.dist-info/METADATA}
     * anywhere under {@code venvRoot}, or empty if it isn't installed. The local
     * build segment is preserved (e.g. {@code 2.5.1+cu121}).
     */
    public static Optional<String> packageVersion(Path venvRoot, String pkg) {
        for (Path sp : sitePackages(venvRoot)) {
            Optional<String> v = packageVersionIn(sp, pkg);
            if (v.isPresent()) {
                return v;
            }
        }
        return Optional.empty();
    }

    /** Read {@code pkg}'s version from a single {@code site-packages} directory. */
    public static Optional<String> packageVersionIn(Path sitePackages, String pkg) {
        if (sitePackages == null || !Files.isDirectory(sitePackages)) {
            return Optional.empty();
        }
        String prefix = pkg.toLowerCase(Locale.ROOT) + "-";
        try (var entries = Files.list(sitePackages)) {
            Optional<Path> distInfo = entries
                    .filter(Files::isDirectory)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.startsWith(prefix) && n.endsWith(".dist-info");
                    })
                    .findFirst();
            if (distInfo.isEmpty()) {
                return Optional.empty();
            }
            Path metadata = distInfo.get().resolve("METADATA");
            if (!Files.isRegularFile(metadata)) {
                return Optional.empty();
            }
            for (String line : Files.readAllLines(metadata, StandardCharsets.UTF_8)) {
                if (line.isEmpty()) {
                    break; // RFC822 headers end at the first blank line
                }
                if (line.regionMatches(true, 0, "Version:", 0, "Version:".length())) {
                    return Optional.of(line.substring("Version:".length()).trim());
                }
            }
        } catch (IOException ignored) {
            // fall through to empty
        }
        return Optional.empty();
    }
}
