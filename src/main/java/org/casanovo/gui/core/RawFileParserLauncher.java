package org.casanovo.gui.core;

import java.io.File;
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
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads (on first use) a self-contained, per-OS build of
 * <a href="https://github.com/compomics/ThermoRawFileParser">ThermoRawFileParser</a> from the
 * latest {@code compomics/ThermoRawFileParser} GitHub release into
 * {@code ~/.casanovo-gui/rawfileparser/<platform>}, and converts a Thermo {@code .raw} file to
 * indexed {@code .mzML} as a subprocess:
 *
 * <pre>ThermoRawFileParser -i=spectra.raw -b=spectra.mzML -f=2</pre>
 *
 * <p>Since v2.0.0 every release ships fully self-contained .NET 8 builds for Windows, Linux and
 * macOS (Intel + Apple Silicon) — no Mono runtime is required on any platform. Resolution always
 * follows GitHub's <em>latest</em> release, so a future release with a higher version number is
 * picked up automatically with no code change. (A same-numbered stable retag of a pre-release —
 * e.g. {@code v2.0.0} replacing {@code v.2.0.0-dev} — is the one case not auto-flagged as newer,
 * since the artifact is functionally identical; clear the cache or use the Settings override to
 * switch.) Mirrors {@link PdvLauncher}/{@link PepMapLauncher}'s download / cache / version model,
 * adapted for a native per-OS executable instead of a portable jar.</p>
 */
public final class RawFileParserLauncher {

    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/compomics/ThermoRawFileParser/releases/latest";

    /** Marker file written beside the extracted executable, recording the installed release. */
    private static final String VERSION_FILE = "VERSION.txt";

    private RawFileParserLauncher() {
    }

    /** Where ThermoRawFileParser is downloaded/cached: {@code ~/.casanovo-gui/rawfileparser}. */
    public static Path rawParserDir() {
        return Paths.get(System.getProperty("user.home"), ".casanovo-gui", "rawfileparser");
    }

    /** This platform's cache-subdir tag (e.g. {@code "win"}, {@code "osx-arm64"}). */
    private static String platformTag() {
        if (Os.isWindows()) {
            return "win";
        }
        if (Os.isMac()) {
            return Os.isAarch64() ? "osx-arm64" : "osx";
        }
        return "linux";
    }

    /**
     * Release-asset filename suffixes to try for this OS/arch, in preference order. Apple Silicon
     * prefers the native {@code -osx-arm64.zip} build but falls back to the Intel {@code -osx.zip}
     * build (which runs under Rosetta 2) when a release omits the arm64 asset — the build workflow
     * does not always produce it, so this keeps future releases usable on Apple Silicon.
     */
    private static List<String> candidateSuffixes() {
        if (Os.isWindows()) {
            return List.of("-win.zip");
        }
        if (Os.isMac()) {
            return Os.isAarch64() ? List.of("-osx-arm64.zip", "-osx.zip") : List.of("-osx.zip");
        }
        return List.of("-linux.zip");
    }

    /** Entry-point executable name inside an extracted release for this OS. */
    private static String exeName() {
        return Os.isWindows() ? "ThermoRawFileParser.exe" : "ThermoRawFileParser";
    }

    /** Where this platform's build is extracted: {@code ~/.casanovo-gui/rawfileparser/<platform>}. */
    private static Path platformDir() {
        return rawParserDir().resolve(platformTag());
    }

    /**
     * The cached executable for this platform, or {@code null} if not yet installed. Searches
     * recursively under {@link #platformDir()} so it tolerates both a flat zip layout (exe at the
     * root, as current releases ship) and a future nested layout (exe under a subfolder).
     */
    public static Path findInstalledExe() {
        return findExeUnder(platformDir());
    }

    /** First file named {@link #exeName()} anywhere under {@code root}, or {@code null}. */
    private static Path findExeUnder(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return null;
        }
        String want = exeName();
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(want))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** Version of the cached install (from its {@value #VERSION_FILE} marker), or empty if none. */
    public static Optional<String> installedVersion() {
        if (findInstalledExe() == null) {
            return Optional.empty();
        }
        Path marker = platformDir().resolve(VERSION_FILE);
        try {
            return Files.isRegularFile(marker)
                    ? Optional.of(Files.readString(marker, StandardCharsets.UTF_8).trim())
                    : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Version of an explicitly configured (Settings-override) executable path. Unlike PDV/pepmap
     * jars, the executable's filename carries no version, and a user-provided install has no
     * {@value #VERSION_FILE} marker — so this is always empty; callers fall back to a
     * version-less "using the configured executable" status, same as PDV does when a jar
     * filename doesn't parse.
     */
    public static Optional<String> versionOfExePath(String path) {
        return Optional.empty();
    }

    /**
     * Resolve a usable ThermoRawFileParser executable: the explicit Settings override if present,
     * otherwise the cached download, otherwise download the latest release for this platform.
     */
    public static Path ensureRawFileParser(String explicitPath, Consumer<String> log)
            throws IOException, InterruptedException {
        if (explicitPath != null && !explicitPath.trim().isEmpty()) {
            Path p = Paths.get(explicitPath.trim());
            if (Files.isRegularFile(p)) {
                log.accept("Using configured ThermoRawFileParser: " + p);
                return p;
            }
            log.accept("Configured ThermoRawFileParser not found, falling back to download: " + explicitPath);
        }
        Path installed = findInstalledExe();
        if (installed != null) {
            log.accept("Using cached ThermoRawFileParser: " + installed);
            return installed;
        }
        return downloadAndExtract(log, null);
    }

    /**
     * Download the latest release's platform-matching zip asset and extract it, returning the
     * entry-point executable. The download is staged in a temp directory and only swapped into
     * {@link #platformDir()} on success, so a partial download never corrupts an existing install
     * and an upgrade never leaves stale files from the previous build behind. {@code progress}
     * (nullable) receives a fraction in {@code [0,1]} as bytes arrive, or {@code -1.0} once when
     * the size is unknown.
     */
    public static Path downloadAndExtract(Consumer<String> log, DoubleConsumer progress)
            throws IOException, InterruptedException {
        Path parent = rawParserDir();
        Files.createDirectories(parent);

        Release rel = fetchLatestRelease();
        if (rel == null || rel.assetUrl == null) {
            throw new IOException("Could not find a ThermoRawFileParser release asset for this platform ("
                    + String.join(" or ", candidateSuffixes()) + "). Set a local ThermoRawFileParser "
                    + "executable in Settings, or check https://github.com/compomics/ThermoRawFileParser/releases.");
        }
        String ver = (rel.version == null || rel.version.isEmpty()) ? "0" : rel.version;
        log.accept("Downloading ThermoRawFileParser " + ver + " from " + rel.assetUrl + " ...");

        Path tmpDir = Files.createTempDirectory(parent, platformTag() + "-dl");
        try {
            Path archive = tmpDir.resolve("thermorawfileparser.zip");
            HttpClient http = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
            HttpResponse<InputStream> resp = http.send(
                    HttpRequest.newBuilder().uri(URI.create(rel.assetUrl))
                            .timeout(Duration.ofMinutes(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IOException("Failed to download ThermoRawFileParser (HTTP " + resp.statusCode()
                        + ") from " + rel.assetUrl);
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

            log.accept("Extracting ThermoRawFileParser...");
            Path extractDir = tmpDir.resolve("x");
            Files.createDirectories(extractDir);
            unzip(archive, extractDir);
            Path exe = findExeUnder(extractDir);
            if (exe == null) {
                throw new IOException("ThermoRawFileParser downloaded but " + exeName()
                        + " was not found in the release archive.");
            }
            // Record the version inside the extracted tree so it travels with the atomic swap below.
            Files.writeString(extractDir.resolve(VERSION_FILE), ver, StandardCharsets.UTF_8);

            // Swap the freshly-extracted tree into place: remove any previous install (avoids stale
            // files from an earlier self-contained build), then move the new tree atomically.
            Path dir = platformDir();
            deleteRecursively(dir);
            Files.createDirectories(dir.getParent());
            try {
                Files.move(extractDir, dir, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(extractDir, dir);
            }

            Path finalExe = findInstalledExe();
            if (finalExe == null) {
                throw new IOException("ThermoRawFileParser install failed: " + exeName()
                        + " missing after extraction under " + dir);
            }
            if (!Os.isWindows() && !finalExe.toFile().setExecutable(true)) {
                log.accept("Warning: could not mark " + finalExe
                        + " executable; conversion may fail with a permission error.");
            }
            log.accept("ThermoRawFileParser ready: " + finalExe);
            return finalExe;
        } finally {
            deleteRecursively(tmpDir);
        }
    }

    /** GitHub's latest ThermoRawFileParser version usable on this platform, or empty when offline /
        when no matching asset exists (so callers do not advertise an impossible download). */
    public static Optional<String> latestUsableVersion() {
        Release rel = fetchLatestRelease();
        return (rel == null || rel.version == null || rel.assetUrl == null)
                ? Optional.empty() : Optional.of(rel.version);
    }

    private static final class Release {
        String version;
        String assetUrl;
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
            String body = resp.body();
            Release r = new Release();
            Matcher tag = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
            if (tag.find()) {
                r.version = cleanVersion(tag.group(1).trim());
            }
            // Pick the first candidate suffix that has a matching asset URL (native build preferred).
            for (String suffix : candidateSuffixes()) {
                Matcher asset = Pattern.compile(
                        "\"browser_download_url\"\\s*:\\s*\"([^\"]+" + Pattern.quote(suffix) + ")\"")
                        .matcher(body);
                if (asset.find()) {
                    r.assetUrl = asset.group(1).trim();
                    break;
                }
            }
            return r;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Strip a GitHub tag's leading "v"/"V" and an immediately-following ".", e.g. {@code "v.2.0.0-dev"} -> {@code "2.0.0-dev"}. */
    private static String cleanVersion(String tag) {
        return tag.replaceFirst("^[vV]\\.?", "");
    }

    /**
     * Build and start the conversion subprocess (merged stdout/stderr):
     * {@code <exe> -i=<raw> -b=<outFile> -f=2} (indexed mzML; Thermo's native peak picking stays
     * on, producing centroided spectra). The caller streams the process output and waits for it
     * to exit; a non-zero exit or a missing {@code outFile} means failure.
     */
    public static Process convertToMzml(Path exe, File rawFile, File outFile, Consumer<String> log)
            throws IOException {
        List<String> cmd = List.of(
                exe.toString(),
                "-i=" + rawFile.getAbsolutePath(),
                "-b=" + outFile.getAbsolutePath(),
                "-f=2");
        log.accept("Converting: " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        return pb.start();
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

    /** Best-effort recursive delete (deepest entries first); never throws. */
    private static void deleteRecursively(Path p) {
        if (p == null || !Files.exists(p)) {
            return;
        }
        try (var walk = Files.walk(p)) {
            walk.sorted(Comparator.reverseOrder()).forEach(q -> {
                try {
                    Files.deleteIfExists(q);
                } catch (IOException ignored) {
                    // best-effort
                }
            });
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
