package org.casanovo.gui.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads (on first use) the latest <a href="https://github.com/wenbostar/PDV">PDV</a>
 * release into {@code ~/.casanovo-gui/pdv}, locates its runnable jar, and launches
 * PDV's DeNovo viewer preloaded with the given spectra + Casanovo mzTab via PDV's
 * {@code denovo-gui} command line:
 *
 * <pre>java -jar PDV.jar denovo-gui --mztab result.mztab --spectrum a.mzML[,b.mzML]</pre>
 *
 * <p>The {@code denovo-gui} entry point is the modification added to PDV
 * ({@code PDVMainClass.launchDenovoGui}); a stock PDV release predating that
 * change will not understand it.</p>
 */
public final class PdvLauncher {

    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/wenbostar/PDV/releases/latest";

    private PdvLauncher() {
    }

    /** Where PDV is downloaded/cached: {@code ~/.casanovo-gui/pdv}. */
    public static Path pdvDir() {
        return Paths.get(System.getProperty("user.home"), ".casanovo-gui", "pdv");
    }

    /** Find an already-installed PDV jar (largest {@code PDV*.jar}), or null. */
    public static Path findInstalledJar() {
        Path dir = pdvDir();
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.startsWith("pdv") && n.endsWith(".jar");
                    })
                    .max((a, b) -> Long.compare(sizeOf(a), sizeOf(b)))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Return a usable PDV jar: the explicit override if given and present,
     * otherwise the cached download, otherwise download the latest release.
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
        return downloadLatest(log);
    }

    /** Download + unzip the latest PDV release into {@link #pdvDir()} and return its jar. */
    public static Path downloadLatest(Consumer<String> log) throws IOException, InterruptedException {
        Path dir = pdvDir();
        Files.createDirectories(dir);

        log.accept("Querying latest PDV release from GitHub...");
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<String> meta = http.send(
                HttpRequest.newBuilder().uri(URI.create(LATEST_RELEASE_API))
                        .header("Accept", "application/vnd.github+json")
                        .timeout(Duration.ofMinutes(2)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (meta.statusCode() < 200 || meta.statusCode() >= 300) {
            throw new IOException("GitHub API returned HTTP " + meta.statusCode()
                    + " when querying the latest PDV release.");
        }

        String assetUrl = pickAsset(meta.body());
        if (assetUrl == null) {
            throw new IOException("Could not find a downloadable PDV asset (.zip/.jar) in the latest release.");
        }
        log.accept("Downloading PDV asset: " + assetUrl);
        String fileName = assetUrl.substring(assetUrl.lastIndexOf('/') + 1);
        Path archive = dir.resolve(fileName);
        HttpResponse<Path> dl = http.send(
                HttpRequest.newBuilder().uri(URI.create(assetUrl)).timeout(Duration.ofMinutes(15)).GET().build(),
                HttpResponse.BodyHandlers.ofFile(archive,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
        if (dl.statusCode() < 200 || dl.statusCode() >= 300) {
            throw new IOException("Failed to download PDV (HTTP " + dl.statusCode() + ").");
        }

        if (fileName.toLowerCase().endsWith(".zip")) {
            log.accept("Extracting PDV...");
            unzip(archive, dir);
        }
        Path jar = findInstalledJar();
        if (jar == null) {
            throw new IOException("PDV downloaded but no runnable jar was found under " + dir);
        }
        log.accept("PDV ready: " + jar);
        return jar;
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
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        String javaName = win ? "java.exe" : "java";

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
            // 2. jpackage layouts relative to the launcher exe.
            java.io.File dir = new java.io.File(procCmd).getParentFile();
            if (dir != null) {
                candidates.add(new java.io.File(dir, "runtime/bin/" + javaName));     // <app>/runtime/bin
                candidates.add(new java.io.File(dir, javaName));                       // beside the exe
                java.io.File up = dir.getParentFile();
                if (up != null) {
                    candidates.add(new java.io.File(up, "runtime/bin/" + javaName));
                }
            }
        }
        // 3. The current runtime (java.home) and its parent.
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

    /** Choose a release asset URL: prefer a PDV .zip, else any .jar, else any .zip. */
    private static String pickAsset(String json) {
        List<String> urls = new ArrayList<>();
        Matcher m = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        while (m.find()) {
            urls.add(m.group(1));
        }
        for (String u : urls) {
            String low = u.toLowerCase();
            if (low.endsWith(".zip") && low.contains("pdv")) {
                return u;
            }
        }
        for (String u : urls) {
            if (u.toLowerCase().endsWith(".jar")) {
                return u;
            }
        }
        for (String u : urls) {
            if (u.toLowerCase().endsWith(".zip")) {
                return u;
            }
        }
        return null;
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

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0L;
        }
    }
}
