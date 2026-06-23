package org.casanovo.gui.core;

import java.io.File;
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
import java.util.function.DoubleConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads (on first use) the <a href="https://github.com/wenbostar/pepmap">pepmap</a> jar
 * from the latest {@code wenbostar/pepmap} release into {@code ~/.casanovo-gui/pepmap}, and
 * builds the command to run pepmap's peptide-to-protein mapping (compomics FM-index) as a
 * subprocess:
 *
 * <pre>java [-Xmx&lt;N&gt;g] -jar pepmap.jar -i peptides.txt -d db.fasta -o outDir [-i2l -mm N -x F -l N -c N]</pre>
 *
 * <p>Mirrors {@link PdvLauncher}'s download / cache / version model. The latest release's
 * {@code .jar} asset is used, so the exact asset name does not matter.</p>
 */
public final class PepMapLauncher {

    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/wenbostar/pepmap/releases/latest";

    private static final Pattern PEPMAP_JAR = Pattern.compile("(?i)^pepmap-(\\d[\\w.\\-]*)\\.jar$");

    private PepMapLauncher() {
    }

    /** Where pepmap is downloaded/cached: {@code ~/.casanovo-gui/pepmap}. */
    public static Path pepmapDir() {
        return Paths.get(System.getProperty("user.home"), ".casanovo-gui", "pepmap");
    }

    /** Highest-version cached {@code pepmap-<ver>.jar}, or null. */
    public static Path findInstalledJar() {
        Path dir = pepmapDir();
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> PEPMAP_JAR.matcher(p.getFileName().toString()).matches())
                    .max(Comparator.comparing(p -> versionOfJar(p.getFileName().toString()),
                            PepMapLauncher::compareVersions))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** Version of the highest cached pepmap jar, or empty if none. */
    public static Optional<String> installedVersion() {
        Path jar = findInstalledJar();
        return jar == null ? Optional.empty() : Optional.of(versionOfJar(jar.getFileName().toString()));
    }

    private static String versionOfJar(String name) {
        Matcher m = PEPMAP_JAR.matcher(name);
        return m.matches() ? m.group(1) : "0";
    }

    /** Version of a configured pepmap jar path's filename (e.g. {@code .../pepmap-2.0.0.jar} -> {@code "2.0.0"}), or empty. */
    public static Optional<String> versionOfJarPath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return Optional.empty();
        }
        Matcher m = PEPMAP_JAR.matcher(Paths.get(path.trim()).getFileName().toString());
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }

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
     * Resolve a usable pepmap jar: the explicit Settings override if present, otherwise the
     * cached download, otherwise download the latest release's jar.
     */
    public static Path ensurePepmap(String explicitJarPath, Consumer<String> log)
            throws IOException, InterruptedException {
        if (explicitJarPath != null && !explicitJarPath.trim().isEmpty()) {
            Path p = Paths.get(explicitJarPath.trim());
            if (Files.isRegularFile(p)) {
                log.accept("Using configured pepmap jar: " + p);
                return p;
            }
            log.accept("Configured pepmap jar not found, falling back to download: " + explicitJarPath);
        }
        Path installed = findInstalledJar();
        if (installed != null) {
            log.accept("Using cached pepmap: " + installed);
            return installed;
        }
        return downloadPepmap(log, null);
    }

    /**
     * Download the latest {@code wenbostar/pepmap} release's {@code .jar} asset into
     * {@link #pepmapDir()} as {@code pepmap-<tag>.jar}. {@code progress} (nullable) receives a
     * fraction in {@code [0,1]} as bytes arrive, or {@code -1.0} once when the size is unknown.
     */
    public static Path downloadPepmap(Consumer<String> log, DoubleConsumer progress)
            throws IOException, InterruptedException {
        Path dir = pepmapDir();
        Files.createDirectories(dir);

        Release rel = fetchLatestRelease();
        if (rel == null || rel.jarUrl == null) {
            throw new IOException("Could not find a pepmap jar in the latest wenbostar/pepmap release. "
                    + "Set a local pepmap jar in Settings, or publish a release with a .jar asset.");
        }
        String ver = (rel.version == null || rel.version.isEmpty()) ? "0" : rel.version;
        Path jar = dir.resolve("pepmap-" + ver + ".jar");
        log.accept("Downloading pepmap " + ver + " from " + rel.jarUrl + " ...");

        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<InputStream> resp = http.send(
                HttpRequest.newBuilder().uri(URI.create(rel.jarUrl))
                        .timeout(Duration.ofMinutes(15)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("Failed to download pepmap (HTTP " + resp.statusCode() + ") from " + rel.jarUrl);
        }

        long total = resp.headers().firstValueAsLong("content-length").orElse(-1L);
        if (progress != null) {
            progress.accept(total > 0 ? 0.0 : -1.0);
        }
        try (InputStream in = resp.body();
             OutputStream out = Files.newOutputStream(jar,
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
        log.accept("pepmap ready: " + jar);
        return jar;
    }

    /** Latest pepmap version on GitHub (leading {@code v} stripped), or empty when offline. */
    public static Optional<String> latestUsableVersion() {
        Release rel = fetchLatestRelease();
        return (rel == null || rel.version == null) ? Optional.empty() : Optional.of(rel.version);
    }

    private static final class Release {
        String version;
        String jarUrl;
    }

    private static Release fetchLatestRelease() {
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
                return null;
            }
            Release r = new Release();
            Matcher tag = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"").matcher(resp.body());
            if (tag.find()) {
                r.version = tag.group(1).trim();
            }
            Matcher asset = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.jar)\"")
                    .matcher(resp.body());
            if (asset.find()) {
                r.jarUrl = asset.group(1).trim();
            }
            return r;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Mapping options, mirroring pepmap's CLI flags. */
    public static final class Options {
        public boolean i2l = false;     // -i2l
        public int minLength = 0;       // -l
        public int mismatches = 0;      // -mm
        public double xShare = 0.0;     // -x
        public int cpus = 0;            // -c  (0 = all available)
        public int maxMemGb = 4;        // -Xmx<N>g
    }

    /**
     * Build and start the pepmap mapping subprocess (merged stdout/stderr). pepmap writes
     * {@code peptide_map.txt} and {@code peptide_map_detail.txt} into {@code outDir}; the caller
     * consumes the process output and reads those files when it exits.
     */
    public static Process runMapping(Path jar, File peptideFile, File fasta, File outDir,
                                     Options opts, Consumer<String> log) throws IOException {
        return runMapping(jar, peptideFile, fasta, outDir, opts, log, null);
    }

    /**
     * Same as {@link #runMapping(Path, File, File, File, Options, Consumer)}, but {@code commandSink}
     * (nullable) is handed the exact command line just before launch so the caller can record it.
     */
    public static Process runMapping(Path jar, File peptideFile, File fasta, File outDir,
                                     Options opts, Consumer<String> log,
                                     Consumer<List<String>> commandSink) throws IOException {
        String javaExe = JavaLauncher.find(log);
        List<String> cmd = buildCommand(javaExe, jar, peptideFile, fasta, outDir, opts);
        if (commandSink != null) {
            commandSink.accept(cmd);
        }

        log.accept("Running pepmap: " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        return pb.start();
    }

    /**
     * Build the exact pepmap command line (the same argument list {@link #runMapping} runs), so
     * callers can record it (e.g. in a run log) without launching the process.
     */
    private static List<String> buildCommand(String javaExe, Path jar, File peptideFile, File fasta,
                                             File outDir, Options opts) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe);
        if (opts.maxMemGb > 0) {
            cmd.add("-Xmx" + opts.maxMemGb + "g");
        }
        cmd.add("-jar");
        cmd.add(jar.toString());
        cmd.add("-i");
        cmd.add(peptideFile.getAbsolutePath());
        cmd.add("-d");
        cmd.add(fasta.getAbsolutePath());
        cmd.add("-o");
        cmd.add(outDir.getAbsolutePath());
        if (opts.i2l) {
            cmd.add("-i2l");
        }
        if (opts.mismatches > 0) {
            cmd.add("-mm");
            cmd.add(String.valueOf(opts.mismatches));
        }
        if (opts.xShare > 0) {
            cmd.add("-x");
            cmd.add(String.valueOf(opts.xShare));
        }
        if (opts.minLength > 0) {
            cmd.add("-l");
            cmd.add(String.valueOf(opts.minLength));
        }
        if (opts.cpus > 0) {
            cmd.add("-c");
            cmd.add(String.valueOf(opts.cpus));
        }
        return cmd;
    }
}
