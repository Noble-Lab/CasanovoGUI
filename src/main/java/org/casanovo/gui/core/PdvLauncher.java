package org.casanovo.gui.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads (on first use) a <a href="https://github.com/wenbostar/PDV">PDV</a> release
 * into {@code ~/.casanovo-gui/pdv}, locates its runnable jar, and launches PDV's DeNovo
 * viewer preloaded with the given spectra + Casanovo mzTab via PDV's {@code denovo-gui}
 * command line:
 *
 * <pre>java -jar PDV.jar denovo-gui --mztab result.mztab --spectrum a.mzML[,b.mzML]</pre>
 *
 * <p>Version policy (a "minimum version" model): the {@code denovo-gui} entry point and
 * {@code --tol-unit} were added in PDV {@value #PDV_MIN_VERSION}. The GUI follows GitHub's
 * <em>latest</em> PDV release as long as it is at least {@value #PDV_MIN_VERSION}; if the
 * latest is older (or GitHub can't be reached) it falls back to {@value #PDV_MIN_VERSION}.
 * Releases are fetched straight from {@code github.com/.../releases/download/...}.</p>
 */
public final class PdvLauncher {

    /**
     * Minimum PDV release the GUI accepts: the first version whose jar includes the
     * {@code denovo-gui} command line and {@code --tol-unit}. The GUI uses the newest
     * release at/above this; it never downloads anything older.
     */
    private static final String PDV_MIN_VERSION = "2.3.0";

    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/wenbostar/PDV/releases/latest";

    /** GitHub direct-download URL of the PDV release archive for a given version. */
    private static String downloadUrlFor(String version) {
        return "https://github.com/wenbostar/PDV/releases/download/v" + version
                + "/PDV-" + version + ".zip";
    }

    private PdvLauncher() {
    }

    /** Where PDV is downloaded/cached: {@code ~/.casanovo-gui/pdv}. */
    public static Path pdvDir() {
        return Paths.get(System.getProperty("user.home"), ".casanovo-gui", "pdv");
    }

    private static final Pattern PDV_JAR = Pattern.compile("(?i)^pdv-(\\d[\\w.\\-]*)\\.jar$");

    /**
     * Find the installed PDV jar with the <em>highest</em> version (e.g. prefer a
     * just-downloaded {@code PDV-2.4.0.jar} over a cached {@code PDV-2.3.0.jar}), or null.
     * Version-aware so an upgrade supersedes an older cached copy.
     */
    public static Path findInstalledJar() {
        Path dir = pdvDir();
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> PDV_JAR.matcher(p.getFileName().toString()).matches())
                    .max(Comparator.comparing(p -> versionOfJar(p.getFileName().toString()),
                            PdvLauncher::compareVersions))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** Version of the highest cached PDV jar (e.g. {@code "2.3.0"}), or empty if none. */
    public static Optional<String> installedVersion() {
        Path jar = findInstalledJar();
        return jar == null ? Optional.empty()
                : Optional.of(versionOfJar(jar.getFileName().toString()));
    }

    private static String versionOfJar(String jarName) {
        Matcher m = PDV_JAR.matcher(jarName);
        return m.matches() ? m.group(1) : "0";
    }

    /** Semver-ish comparison reusing {@link UpdateChecker}; negative/zero/positive like {@link Comparator}. */
    private static int compareVersions(String a, String b) {
        if (UpdateChecker.isNewer(a, b)) {
            return 1;
        }
        if (UpdateChecker.isNewer(b, a)) {
            return -1;
        }
        return 0;
    }

    /**
     * Return a usable PDV jar: the explicit override if given and present,
     * otherwise the cached download, otherwise download the pinned release.
     *
     * @param explicitJarPath an explicit PDV jar path from Settings (may be blank/null)
     * @param log             progress sink
     */
    public static Path ensurePdv(String explicitJarPath, Consumer<String> log) throws IOException, InterruptedException {
        if (explicitJarPath != null && !explicitJarPath.trim().isEmpty()) {
            Path p = Paths.get(explicitJarPath.trim());
            if (Files.isRegularFile(p)) {
                log.accept("Using configured PDV jar: " + p);
                return p;
            }
            log.accept("Configured PDV jar not found, falling back to download: " + explicitJarPath);
        }
        Path installed = findInstalledJar();
        if (installed != null) {
            log.accept("Using cached PDV: " + installed);
            return installed;
        }
        return downloadPdv(log);
    }

    /**
     * Download the pinned PDV release archive directly from its GitHub release URL,
     * unzip it into {@link #pdvDir()}, and return its runnable jar. No GitHub API call
     * is made — the asset is fetched straight from
     * {@code https://github.com/wenbostar/PDV/releases/download/...}.
     */
    public static Path downloadPdv(Consumer<String> log) throws IOException, InterruptedException {
        return downloadPdv(log, null);
    }

    /**
     * Like {@link #downloadPdv(Consumer)}, but reports download progress: {@code progress}
     * receives a fraction in {@code [0, 1]} as bytes arrive, or {@code -1.0} once when the
     * total size is unknown (indeterminate). May be {@code null}.
     */
    public static Path downloadPdv(Consumer<String> log, java.util.function.DoubleConsumer progress)
            throws IOException, InterruptedException {
        Path dir = pdvDir();
        Files.createDirectories(dir);

        String version = resolvedVersion();
        String url = downloadUrlFor(version);
        log.accept("Downloading PDV " + version + " from " + url + " ...");
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        String fileName = url.substring(url.lastIndexOf('/') + 1);
        Path archive = dir.resolve(fileName);
        HttpResponse<InputStream> resp = http.send(
                HttpRequest.newBuilder().uri(URI.create(url))
                        .timeout(Duration.ofMinutes(15)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("Failed to download PDV " + version + " (HTTP " + resp.statusCode()
                    + ") from " + url + "\nCheck that release v" + version
                    + " exists with the asset " + fileName + ".");
        }

        long total = resp.headers().firstValueAsLong("content-length").orElse(-1L);
        if (progress != null) {
            progress.accept(total > 0 ? 0.0 : -1.0);
        }
        try (InputStream in = resp.body();
             OutputStream out = Files.newOutputStream(archive,
                     StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[1 << 16];
            long read = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                read += n;
                if (progress != null && total > 0) {
                    progress.accept((double) read / total);
                }
            }
        }

        log.accept("Extracting PDV...");
        unzip(archive, dir);
        Path jar = findInstalledJar();
        if (jar == null) {
            throw new IOException("PDV downloaded but no runnable jar was found under " + dir);
        }
        log.accept("PDV ready: " + jar);
        return jar;
    }

    /**
     * The PDV version "Open in PDV" would download now: GitHub's latest release if it
     * meets the {@value #PDV_MIN_VERSION} floor, else the floor. Empty only when GitHub
     * can't be reached, so the caller can show "couldn't check". Does a network request,
     * so call this OFF the UI thread.
     */
    public static Optional<String> latestUsableVersion() {
        return fetchLatestVersion().map(v -> meetsFloor(v) ? v : PDV_MIN_VERSION);
    }

    /** Version to actually download: {@link #latestUsableVersion()} or the floor when offline. */
    private static String resolvedVersion() {
        return latestUsableVersion().orElse(PDV_MIN_VERSION);
    }

    /** True when {@code version} is at or above the {@value #PDV_MIN_VERSION} floor. */
    private static boolean meetsFloor(String version) {
        return !UpdateChecker.isNewer(PDV_MIN_VERSION, version);
    }

    /** GitHub's latest PDV release version (leading {@code v} stripped), or empty on failure. */
    private static Optional<String> fetchLatestVersion() {
        try {
            HttpClient http = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder().uri(URI.create(LATEST_RELEASE_API))
                            .header("Accept", "application/vnd.github+json")
                            .timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return Optional.empty();
            }
            Matcher m = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"").matcher(resp.body());
            return m.find() ? Optional.of(m.group(1).trim()) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Launch PDV's DeNovo viewer preloaded with the spectra + mzTab. Uses the
     * same Java runtime that is running this application.
     */
    public static Process launchDenovo(Path pdvJar, List<java.io.File> spectra, java.io.File mzTab,
                                        double fragTol, String tolUnit, Consumer<String> log) throws IOException {
        String javaExe = javaExecutable(log);
        String unit = (tolUnit == null || tolUnit.trim().isEmpty()) ? "Da" : tolUnit.trim();
        List<String> spectraPaths = new ArrayList<>();
        for (java.io.File f : spectra) {
            spectraPaths.add(f.getAbsolutePath());
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe);
        cmd.add("-jar");
        cmd.add(pdvJar.toString());
        cmd.add("denovo-gui");
        cmd.add("--mztab");
        cmd.add(mzTab.getAbsolutePath());
        cmd.add("--spectrum");
        cmd.add(String.join(",", spectraPaths));
        cmd.add("--tol");
        cmd.add(String.valueOf(fragTol));
        cmd.add("--tol-unit");
        cmd.add(unit);

        log.accept("Launching PDV: " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        return pb.start();
    }

    /**
     * Locate a real {@code java} launcher to start PDV, robust across launch
     * modes. Order:
     * <ol>
     *   <li>{@code ProcessHandle} command if it is already a java launcher;</li>
     *   <li>if this app was started from a jpackage native launcher
     *       (e.g. {@code CasanovoGUI.exe}), the bundled {@code runtime/bin/java};</li>
     *   <li>{@code java.home/bin/java};</li>
     *   <li>bare {@code java} on PATH.</li>
     * </ol>
     */
    private static String javaExecutable(Consumer<String> log) {
        String javaName = Os.isWindows() ? "java.exe" : "java";

        String home = System.getProperty("java.home");
        log.accept("[pdv] java.home = " + home);

        String procCmd = null;
        try {
            java.util.Optional<String> cmd = ProcessHandle.current().info().command();
            if (cmd.isPresent()) {
                procCmd = cmd.get();
            }
        } catch (Throwable ignored) {
        }
        log.accept("[pdv] launcher command = " + procCmd);

        java.util.List<java.io.File> candidates = new ArrayList<>();
        // 1. The launcher itself, if it is already a java binary.
        if (procCmd != null) {
            String lc = procCmd.toLowerCase();
            if (lc.endsWith("java.exe") || lc.endsWith(java.io.File.separator + "java") || lc.equals("java")) {
                candidates.add(new java.io.File(procCmd));
            }
            // 2. jpackage app-image layouts relative to the native launcher, which differ per OS:
            //      Windows : <app>\CasanovoGUI.exe       -> <app>\runtime\bin\java.exe
            //      Linux   : <app>/bin/CasanovoGUI       -> <app>/lib/runtime/bin/java
            //      macOS   : <app>.app/Contents/MacOS/.. -> <app>.app/Contents/runtime/Contents/Home/bin/java
            java.io.File dir = new java.io.File(procCmd).getParentFile();
            if (dir != null) {
                candidates.add(new java.io.File(dir, "runtime/bin/" + javaName));   // Windows: beside the exe
                candidates.add(new java.io.File(dir, javaName));
                java.io.File up = dir.getParentFile();
                if (up != null) {
                    candidates.add(new java.io.File(up, "runtime/bin/" + javaName));
                    candidates.add(new java.io.File(up, "lib/runtime/bin/" + javaName));            // Linux app-image
                    candidates.add(new java.io.File(up, "runtime/Contents/Home/bin/" + javaName));  // macOS .app
                }
            }
        }
        // 3. The current runtime (java.home) -- the bundled jpackage runtime on every OS, once
        //    the build copies the stripped launcher back into its bin. Works for Win/Linux/macOS.
        if (home != null && !home.isEmpty()) {
            candidates.add(new java.io.File(home, "bin/" + javaName));
            java.io.File hp = new java.io.File(home).getParentFile();
            if (hp != null) {
                candidates.add(new java.io.File(hp, "bin/" + javaName));
                candidates.add(new java.io.File(hp, "runtime/bin/" + javaName));
            }
        }
        // 4. JAVA_HOME environment variable.
        String envHome = System.getenv("JAVA_HOME");
        if (envHome != null && !envHome.isEmpty()) {
            candidates.add(new java.io.File(envHome, "bin/" + javaName));
        }

        for (java.io.File c : candidates) {
            if (c.isFile()) {
                log.accept("[pdv] using java: " + c.getAbsolutePath());
                return c.getAbsolutePath();
            }
        }
        log.accept("[pdv] no bundled java found; falling back to '" + javaName + "' on PATH.");
        return javaName;
    }

    private static void unzip(Path zip, Path destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                Path out = destDir.resolve(entry.getName()).normalize();
                if (!out.startsWith(destDir)) {
                    throw new IOException("Unsafe zip entry: " + entry.getName());
                }
                Files.createDirectories(out.getParent());
                try (OutputStream os = Files.newOutputStream(out,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    zis.transferTo(os);
                }
                zis.closeEntry();
            }
        }
    }
}
