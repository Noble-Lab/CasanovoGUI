package org.casanovo.gui.core;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Fetching and unpacking, shared by {@link CasanovoInstaller} and {@link GlissadeInstaller}.
 *
 * <p>Both installers acquire {@code uv} the same way and then look for an executable inside what
 * they unpacked. Held in one place so a new uv platform triple, a redirect or TLS fix, or a
 * zip-entry edge case is fixed once rather than in whichever copy the next maintainer happens to
 * open.</p>
 */
final class Downloads {

    private Downloads() {
    }

    /** The {@code uv} release archive for this platform. */
    static String uvDownloadUrl() {
        String base = "https://github.com/astral-sh/uv/releases/latest/download/";
        if (Os.isWindows()) {
            return base + "uv-x86_64-pc-windows-msvc.zip";
        }
        if (Os.isMac()) {
            String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            return base + ((arch.contains("aarch64") || arch.contains("arm"))
                    ? "uv-aarch64-apple-darwin.tar.gz"
                    : "uv-x86_64-apple-darwin.tar.gz");
        }
        return base + "uv-x86_64-unknown-linux-gnu.tar.gz";
    }

    /** The archive name {@link #uvDownloadUrl()} lands in: uv ships a zip on Windows, a tarball elsewhere. */
    static String uvArchiveName() {
        return Os.isWindows() ? "uv.zip" : "uv.tar.gz";
    }

    /**
     * Download {@code url} to {@code target}, writing to a {@code .part} file first so an
     * interrupted download can never be mistaken for a complete one.
     */
    static void download(String url, Path target) throws IOException, InterruptedException {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();
        HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(part,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            Files.deleteIfExists(part);
            throw new IOException("Download failed (HTTP " + resp.statusCode() + "): " + url);
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Extract {@code zip} into {@code destDir}, refusing any entry that escapes it. */
    static void unzip(Path zip, Path destDir) throws IOException {
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

    /** The executable named {@code name} directly in {@code root}, else the first one below it. */
    static Optional<Path> findExecutable(Path root, String name) throws IOException {
        Path direct = root.resolve(name);
        if (Files.isRegularFile(direct)) {
            return Optional.of(direct);
        }
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase(name))
                    .findFirst();
        }
    }
}
