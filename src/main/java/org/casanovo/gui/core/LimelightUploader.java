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
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * Converts a Casanovo mzTab result to Limelight XML and uploads it to a
 * <a href="https://limelight-ms.readthedocs.io/">Limelight</a> instance, using two downloaded
 * jars. This class only provisions the jars (download + cache) and builds the command lines;
 * execution goes through {@code LimelightController}'s own process runner so the jar output
 * streams to the app console.
 *
 * <ul>
 *   <li><b>Converter</b> — {@code casanovoToLimelightXML.jar} from the latest
 *       {@code yeastrc/limelight-import-casanovo} release.</li>
 *   <li><b>Importer</b> — {@code limelightSubmitImport.jar} from the latest
 *       {@code yeastrc/limelight-core} release.</li>
 * </ul>
 *
 * <p>Both assets have stable names, so each is fetched via GitHub's {@code releases/latest/download}
 * redirect (no API call). The jars are re-downloaded on <em>every</em> call and cached under
 * {@code ~/.casanovo-gui/limelight}; a failed download falls back to the cached copy so a flaky
 * network never blocks an upload.</p>
 */
public final class LimelightUploader {

    private static final String CONVERTER_URL =
            "https://github.com/yeastrc/limelight-import-casanovo/releases/latest/download/casanovoToLimelightXML.jar";
    private static final String CONVERTER_JAR = "casanovoToLimelightXML.jar";
    private static final String IMPORTER_URL =
            "https://github.com/yeastrc/limelight-core/releases/latest/download/limelightSubmitImport.jar";
    private static final String IMPORTER_JAR = "limelightSubmitImport.jar";
    /** Tag automatically attached to every upload, so Limelight searches are filterable. */
    private static final String SEARCH_TAG = "CasanovoGUI";

    private LimelightUploader() {
    }

    /** Where the Limelight jars are downloaded/cached: {@code ~/.casanovo-gui/limelight}. */
    public static Path limelightDir() {
        return Paths.get(System.getProperty("user.home"), ".casanovo-gui", "limelight");
    }

    // ---------------------------------------------------------------- jar provisioning

    /**
     * Return the converter jar, always fetching the latest GitHub release. The asset name is
     * stable, so the {@code latest/download} redirect resolves to the newest release. A failed
     * download falls back to the cached jar (so a flaky network never blocks an upload); only a
     * missing jar with no network propagates.
     */
    public static Path ensureConverterJar(Consumer<String> log, DoubleConsumer progress)
            throws IOException, InterruptedException {
        Files.createDirectories(limelightDir());
        Path jar = limelightDir().resolve(CONVERTER_JAR);
        boolean haveCache = Files.isRegularFile(jar);
        try {
            log.accept("[limelight] Fetching the latest Casanovo→Limelight converter from "
                    + CONVERTER_URL + " …");
            download(CONVERTER_URL, jar, progress);
            log.accept("[limelight] Converter ready: " + jar);
            return jar;
        } catch (IOException | InterruptedException e) {
            if (haveCache) {
                log.accept("[limelight] Could not refresh the converter (" + e.getMessage()
                        + "); using the cached copy.");
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return jar;
            }
            throw e;
        }
    }

    /**
     * Return the importer jar, always fetching the latest {@code yeastrc/limelight-core} release.
     * Same stable-asset / cache-fallback behaviour as {@link #ensureConverterJar}.
     */
    public static Path ensureImporterJar(Consumer<String> log, DoubleConsumer progress)
            throws IOException, InterruptedException {
        Files.createDirectories(limelightDir());
        Path jar = limelightDir().resolve(IMPORTER_JAR);
        boolean haveCache = Files.isRegularFile(jar);
        try {
            log.accept("[limelight] Fetching the latest Limelight importer from "
                    + IMPORTER_URL + " …");
            download(IMPORTER_URL, jar, progress);
            log.accept("[limelight] Importer ready: " + jar);
            return jar;
        } catch (IOException | InterruptedException e) {
            if (haveCache) {
                log.accept("[limelight] Could not refresh the importer (" + e.getMessage()
                        + "); using the cached copy.");
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return jar;
            }
            throw e;
        }
    }

    /** Strip trailing slashes from a URL (the user enters the full {@code .../limelight} form). */
    public static String normalizeUrl(String url) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    // ---------------------------------------------------------------- command construction

    /** {@code java -jar casanovoToLimelightXML.jar -m <mzTab> -c <config> -o <outXml> -v} */
    public static List<String> convertCommand(String javaExe, Path converterJar,
                                              File mzTab, File config, File outXml) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe);
        cmd.add("-jar");
        cmd.add(converterJar.toString());
        cmd.add("-m");
        cmd.add(mzTab.getAbsolutePath());
        cmd.add("-c");
        cmd.add(config.getAbsolutePath());
        cmd.add("-o");
        cmd.add(outXml.getAbsolutePath());
        cmd.add("-v");
        return cmd;
    }

    /**
     * Build the importer command. Each token is a separate list element, so spaces in the
     * description need no shell quoting. When {@code scanFiles} is empty, {@code --no-scan-files}
     * is used instead of {@code --scan-file}.
     */
    public static List<String> uploadCommand(String javaExe, Path importerJar, String webAppUrl,
                                             String submitKey, String projectId, File xml,
                                             String description, List<File> scanFiles) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe);
        cmd.add("-jar");
        cmd.add(importerJar.toString());
        cmd.add("--retry-count-limit=5");
        cmd.add("--limelight-web-app-url=" + normalizeUrl(webAppUrl));
        cmd.add("--user-submit-import-key=" + submitKey);
        cmd.add("--project-id=" + projectId);
        cmd.add("--limelight-xml-file=" + xml.getAbsolutePath());
        if (description != null && !description.trim().isEmpty()) {
            cmd.add("--search-description=" + description.trim());
        }
        cmd.add("--search-tag=" + SEARCH_TAG);
        if (scanFiles == null || scanFiles.isEmpty()) {
            cmd.add("--no-scan-files");
        } else {
            for (File f : scanFiles) {
                cmd.add("--scan-file=" + f.getAbsolutePath());
            }
        }
        return cmd;
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Download {@code url} to {@code target} via a {@code .part} temp file that is moved into
     * place only on success — so a partial/failed download never truncates an existing cached
     * jar (which the offline fallback in the {@code ensure*} methods relies on).
     */
    private static void download(String url, Path target, DoubleConsumer progress)
            throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<InputStream> resp = http.send(
                HttpRequest.newBuilder().uri(URI.create(url))
                        .timeout(Duration.ofMinutes(15)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("Download failed (HTTP " + resp.statusCode() + "): " + url);
        }
        long total = resp.headers().firstValueAsLong("content-length").orElse(-1L);
        if (progress != null) {
            progress.accept(total > 0 ? 0.0 : -1.0);
        }
        Path part = target.resolveSibling(target.getFileName().toString() + ".part");
        try (InputStream in = resp.body();
             OutputStream out = Files.newOutputStream(part,
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
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
