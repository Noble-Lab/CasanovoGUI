package org.casanovo.gui.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks for newer versions of the GUI and the tools it drives, and remembers
 * the user's update preferences (auto-check on/off, last-check timestamp,
 * per-target skipped version):
 *
 * <ul>
 *   <li><b>Casanovo GUI</b> — this application. Its own version comes from the
 *       Maven-filtered {@code project.properties} resource; the latest is the
 *       newest GitHub release of {@code Noble-Lab/CasanovoGUI}.</li>
 *   <li><b>Casanovo</b> — the Python tool the GUI drives. The <em>installed</em>
 *       version is read from the package's {@code dist-info} metadata inside the
 *       configured venv (no Python process is launched — fast, offline, and it
 *       sidesteps the Windows OpenMP crash that can take down a real
 *       {@code casanovo} invocation). If that can't be resolved (e.g. a Conda or
 *       PATH install), it falls back to running {@code casanovo version}. The
 *       <em>latest</em> version comes from PyPI, with GitHub releases as a
 *       fallback.</li>
 *   <li><b>PDV</b> and <b>pepmap</b> — the optional jars used by "Open in PDV"
 *       and the View tab's mapping. The current version is the configured jar's
 *       (or the cached auto-download's); the latest comes from each tool's GitHub
 *       releases. These are reported for information only — the actual upgrade is
 *       applied from the Settings dialog. Skipped entirely when the tool isn't
 *       installed or configured.</li>
 * </ul>
 *
 * <p>Uses only the JDK 11+ {@link HttpClient} and a small regex parser so we
 * don't pull in a JSON dependency for a handful of flat fields.</p>
 */
public final class UpdateChecker {

    // ---- endpoints ---------------------------------------------------------

    private static final String GUI_RELEASES_API =
            "https://api.github.com/repos/Noble-Lab/CasanovoGUI/releases/latest";
    private static final String GUI_RELEASES_PAGE =
            "https://github.com/Noble-Lab/CasanovoGUI/releases";

    private static final String CASANOVO_PYPI_JSON =
            "https://pypi.org/pypi/casanovo/json";
    private static final String CASANOVO_GH_RELEASES_API =
            "https://api.github.com/repos/Noble-Lab/casanovo/releases/latest";
    private static final String CASANOVO_RELEASES_PAGE =
            "https://github.com/Noble-Lab/casanovo/releases";

    private static final String PDV_RELEASES_PAGE =
            "https://github.com/wenbostar/PDV/releases";
    private static final String PEPMAP_RELEASES_PAGE =
            "https://github.com/wenbostar/pepmap/releases";
    private static final String PDV_GH_RELEASES_API =
            "https://api.github.com/repos/wenbostar/PDV/releases/latest";
    private static final String PEPMAP_GH_RELEASES_API =
            "https://api.github.com/repos/wenbostar/pepmap/releases/latest";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** Skip the startup auto-check if a successful check ran within this window. */
    private static final long AUTO_CHECK_THROTTLE_MS = 12L * 60L * 60L * 1000L; // 12h

    /** Hard cap on the {@code casanovo version} fallback so startup never hangs. */
    private static final long VERSION_CMD_TIMEOUT_SEC = 25L;

    // Preferences (all under one node so they can be wiped together).
    private static final String PREFS_NODE        = "org/casanovo/gui/UpdateChecker";
    private static final String KEY_AUTO_CHECK    = "autoCheck";
    private static final String KEY_LAST_CHECK_MS = "lastCheckMs";
    private static final String KEY_SKIPPED_GUI    = "skippedGuiTag";
    private static final String KEY_SKIPPED_CASA   = "skippedCasanovoVersion";
    private static final String KEY_SKIPPED_PDV    = "skippedPdvVersion";
    private static final String KEY_SKIPPED_PEPMAP = "skippedPepmapVersion";

    private UpdateChecker() {}

    // ---- model -------------------------------------------------------------

    /** Which product an {@link UpdateInfo} describes. */
    public enum Target { GUI, CASANOVO, PDV, PEPMAP }

    /** Outcome for a single product: current vs latest, and whether newer exists. */
    public static final class UpdateInfo {
        public final Target target;
        public final String displayName;     // "Casanovo GUI" / "Casanovo"
        public final String currentVersion;
        public final String latestVersion;
        public final String releaseDate;     // YYYY-MM-DD of the latest release, or null
        public final String pageUrl;         // where "View" should point a browser
        public final boolean updateAvailable;

        public UpdateInfo(Target target, String displayName, String currentVersion,
                          String latestVersion, String releaseDate, String pageUrl,
                          boolean updateAvailable) {
            this.target = target;
            this.displayName = displayName;
            this.currentVersion = currentVersion;
            this.latestVersion = latestVersion;
            this.releaseDate = releaseDate;
            this.pageUrl = pageUrl;
            this.updateAvailable = updateAvailable;
        }
    }

    /**
     * Result of {@link #checkAll}: the targets we could evaluate, plus a flag
     * noting whether at least one lookup failed with a network/HTTP error (so a
     * manual check can distinguish "up to date" from "couldn't reach the net").
     */
    public static final class CheckOutcome {
        public final List<UpdateInfo> infos;
        public final boolean networkError;

        CheckOutcome(List<UpdateInfo> infos, boolean networkError) {
            this.infos = infos;
            this.networkError = networkError;
        }
    }

    // ---- versions ----------------------------------------------------------

    /**
     * This GUI's version, read from the filtered {@code project.properties}
     * resource (Maven substitutes {@code ${project.version}} at build time).
     * Falls back to {@code "0.0.0"} if the resource is missing or unfiltered
     * (e.g. an IDE run without resource filtering).
     */
    public static String guiVersion() {
        try (InputStream in = UpdateChecker.class
                .getResourceAsStream("/org/casanovo/gui/project.properties")) {
            if (in == null) return "0.0.0";
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("version", "0.0.0").trim();
            // Unsubstituted placeholder => treat as unknown.
            return v.startsWith("${") ? "0.0.0" : v;
        } catch (IOException e) {
            return "0.0.0";
        }
    }

    /**
     * The installed Casanovo version, or empty if it can't be determined.
     * Tries the venv {@code dist-info} metadata first (no Python launched);
     * falls back to {@code casanovo version}.
     */
    public static Optional<String> installedCasanovoVersion(Settings settings) {
        Optional<String> fromMeta = versionFromVenv(settings.getCasanovoExecutable());
        if (fromMeta.isPresent()) return fromMeta;
        return versionFromCasanovoCommand(settings);
    }

    /**
     * Read the installed Casanovo version from its {@code dist-info} metadata in
     * the venv that owns {@code exePath} (no Python launched). Returns empty for a
     * bare {@code casanovo} (PATH) name or when the package can't be found.
     */
    private static Optional<String> versionFromVenv(String exePath) {
        return PyVenv.venvRootForExecutable(exePath)
                .flatMap(root -> PyVenv.packageVersion(root, "casanovo"));
    }

    /**
     * Last resort: run {@code casanovo version} (honouring Conda settings) and
     * parse the {@code Casanovo Version: X.Y.Z} line. Guarded with the Windows
     * OpenMP env vars and a hard timeout so it can't hang or crash startup.
     */
    private static Optional<String> versionFromCasanovoCommand(Settings settings) {
        try {
            List<String> osCmd = new CasanovoCommand("version", List.of())
                    .toProcessCommand(settings);
            ProcessBuilder pb = new ProcessBuilder(osCmd);
            pb.redirectErrorStream(true);
            Os.applyNativeEnv(pb); // Windows-only MKL/OpenMP safeguard; no-op elsewhere
            Process proc = pb.start();
            Pattern verLine = Pattern.compile("Casanovo Version:\\s*([0-9][0-9A-Za-z.\\-]*)");
            String found = null;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    Matcher m = verLine.matcher(line);
                    if (m.find()) {
                        found = m.group(1);
                    }
                }
            }
            if (!proc.waitFor(VERSION_CMD_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return Optional.empty();
            }
            return Optional.ofNullable(found);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    // ---- remote lookups ----------------------------------------------------

    /** GitHub release of {@code Noble-Lab/CasanovoGUI}, or empty if none yet (404). */
    private static Optional<ReleaseInfo> fetchLatestGuiRelease()
            throws IOException, InterruptedException {
        HttpResponse<String> resp = get(GUI_RELEASES_API, "application/vnd.github+json");
        if (resp.statusCode() == 404) {
            return Optional.empty(); // repo exists but has no releases yet — not an error
        }
        if (resp.statusCode() != 200) {
            throw new IOException("GitHub responded HTTP " + resp.statusCode());
        }
        String body = resp.body();
        String tag = extractStringField(body, "tag_name");
        String url = extractStringField(body, "html_url");
        String date = toDate(extractStringField(body, "published_at"));
        if (tag == null || tag.isEmpty()) return Optional.empty();
        return Optional.of(new ReleaseInfo(tag, url == null ? GUI_RELEASES_PAGE : url, date));
    }

    /** Latest Casanovo version + release date: PyPI first, GitHub releases as a fallback. */
    private static Optional<Latest> fetchLatestCasanovo()
            throws IOException, InterruptedException {
        try {
            HttpResponse<String> resp = get(CASANOVO_PYPI_JSON, "application/json");
            if (resp.statusCode() == 200) {
                // PyPI's JSON leads with the "info" object, whose first "version"
                // field is the latest release — the release history below uses
                // version strings as keys, not "version": "..." pairs. The first
                // "upload_time_iso_8601" belongs to that latest release's files.
                String body = resp.body();
                String v = extractStringField(body, "version");
                if (v != null && !v.isEmpty()) {
                    // The top-level "urls" array holds the latest version's files; scope the
                    // date search to it, since the "releases" map above lists every version's
                    // (older) upload times first.
                    int urlsIdx = body.indexOf("\"urls\"");
                    String dateScope = urlsIdx >= 0 ? body.substring(urlsIdx) : body;
                    String date = toDate(extractStringField(dateScope, "upload_time_iso_8601"));
                    return Optional.of(new Latest(v, date));
                }
            }
        } catch (IOException | InterruptedException pypiFailure) {
            if (pypiFailure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw pypiFailure;
            }
            // else fall through to the GitHub fallback
        }
        HttpResponse<String> gh = get(CASANOVO_GH_RELEASES_API, "application/vnd.github+json");
        if (gh.statusCode() != 200) {
            throw new IOException("Casanovo version lookup failed (GitHub HTTP "
                    + gh.statusCode() + ")");
        }
        String tag = extractStringField(gh.body(), "tag_name");
        if (tag == null || tag.isEmpty()) return Optional.empty();
        return Optional.of(new Latest(tag, toDate(extractStringField(gh.body(), "published_at"))));
    }

    private static HttpResponse<String> get(String url, String accept)
            throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", accept)
                .header("User-Agent", "CasanovoGUI/" + guiVersion())
                .GET()
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** Minimal GitHub release fields we care about. */
    private static final class ReleaseInfo {
        final String tagName;
        final String htmlUrl;
        final String publishedDate; // YYYY-MM-DD, or null
        ReleaseInfo(String tagName, String htmlUrl, String publishedDate) {
            this.tagName = tagName;
            this.htmlUrl = htmlUrl;
            this.publishedDate = publishedDate;
        }
    }

    /** A latest-version lookup result carrying an optional release date. */
    private static final class Latest {
        final String version;
        final String date; // YYYY-MM-DD, or null
        Latest(String version, String date) {
            this.version = version;
            this.date = date;
        }
    }

    /** First 10 chars (YYYY-MM-DD) of an ISO-8601 timestamp, or null if unusable. */
    private static String toDate(String isoTimestamp) {
        if (isoTimestamp == null || isoTimestamp.length() < 10) return null;
        return isoTimestamp.substring(0, 10);
    }

    /** Best-effort {@code published_at} date (YYYY-MM-DD) for a GitHub releases/latest API URL. */
    private static String fetchGithubReleaseDate(String apiUrl) {
        try {
            HttpResponse<String> resp = get(apiUrl, "application/vnd.github+json");
            if (resp.statusCode() != 200) return null;
            return toDate(extractStringField(resp.body(), "published_at"));
        } catch (Exception e) {
            return null;
        }
    }

    // ---- orchestration -----------------------------------------------------

    /**
     * Evaluate both targets. Records the check attempt (for throttling), then
     * looks up GUI and Casanovo versions independently so one failure doesn't
     * block the other. Network/HTTP failures are captured in
     * {@link CheckOutcome#networkError} rather than thrown.
     */
    public static CheckOutcome checkAll(Settings settings) {
        recordCheckAttempt();
        List<UpdateInfo> infos = new ArrayList<>();
        boolean networkError = false;

        // --- Casanovo GUI (self) ---
        try {
            Optional<ReleaseInfo> rel = fetchLatestGuiRelease();
            if (rel.isPresent()) {
                String current = guiVersion();
                boolean newer = isNewer(rel.get().tagName, current);
                infos.add(new UpdateInfo(Target.GUI, "Casanovo GUI", current,
                        stripLeadingV(rel.get().tagName), rel.get().publishedDate,
                        rel.get().htmlUrl, newer));
            }
        } catch (Exception e) {
            networkError = true;
        }

        // --- Casanovo (Python tool) ---
        Optional<String> installed = installedCasanovoVersion(settings);
        if (installed.isPresent()) {
            try {
                Optional<Latest> latest = fetchLatestCasanovo();
                if (latest.isPresent()) {
                    boolean newer = isNewer(latest.get().version, installed.get());
                    infos.add(new UpdateInfo(Target.CASANOVO, "Casanovo", installed.get(),
                            stripLeadingV(latest.get().version), latest.get().date,
                            CASANOVO_RELEASES_PAGE, newer));
                }
            } catch (Exception e) {
                networkError = true;
            }
        }

        // --- PDV and pepmap (bundled jars; updates are applied from the Settings dialog) ---
        addJarTarget(infos, Target.PDV, "PDV", settings.getPdvJar(),
                PdvLauncher::latestUsableVersion, PdvLauncher::installedVersion,
                PdvLauncher::versionOfJarPath, PDV_RELEASES_PAGE, PDV_GH_RELEASES_API);
        addJarTarget(infos, Target.PEPMAP, "pepmap", settings.getPepmapJar(),
                PepMapLauncher::latestUsableVersion, PepMapLauncher::installedVersion,
                PepMapLauncher::versionOfJarPath, PEPMAP_RELEASES_PAGE, PEPMAP_GH_RELEASES_API);

        return new CheckOutcome(infos, networkError);
    }

    /**
     * Evaluate a jar-backed tool (PDV / pepmap). The <em>current</em> version is the
     * configured jar's when one is set, otherwise the cached auto-download; if neither
     * exists the tool isn't in use and is skipped. The <em>latest</em> comes from the
     * launcher's public-release lookup. Failures are swallowed (no info added) rather
     * than flagged as a network error, since these tools are optional.
     */
    private static void addJarTarget(List<UpdateInfo> infos, Target target, String name,
                                     String configuredJar,
                                     Supplier<Optional<String>> latestFn,
                                     Supplier<Optional<String>> installedFn,
                                     Function<String, Optional<String>> jarVersionFn,
                                     String releasesPage, String releaseApi) {
        try {
            String jar = configuredJar == null ? "" : configuredJar.trim();
            Optional<String> current = jar.isEmpty() ? installedFn.get() : jarVersionFn.apply(jar);
            if (current.isEmpty()) {
                return; // not installed or configured — nothing to compare
            }
            Optional<String> latest = latestFn.get();
            if (latest.isEmpty()) {
                return; // offline or no public release — can't compare
            }
            boolean newer = isNewer(latest.get(), current.get());
            String date = fetchGithubReleaseDate(releaseApi);
            infos.add(new UpdateInfo(target, name, current.get(),
                    stripLeadingV(latest.get()), date, releasesPage, newer));
        } catch (Exception ignored) {
            // optional tool; leave it out of the report on any failure
        }
    }

    // ---- version comparison ------------------------------------------------

    /**
     * Compare two semver-ish strings ("1.2.3", "v1.2.3", "1.2"). Returns
     * {@code true} iff {@code latest} is strictly newer than {@code current}.
     * Trailing non-numeric segments (e.g. {@code "-rc1"}) are stripped before
     * comparison — good enough for the X.Y.Z lines both products use.
     */
    public static boolean isNewer(String latest, String current) {
        int[] a = parseSemver(latest);
        int[] b = parseSemver(current);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int ai = i < a.length ? a[i] : 0;
            int bi = i < b.length ? b[i] : 0;
            if (ai != bi) return ai > bi;
        }
        return false;
    }

    private static int[] parseSemver(String v) {
        if (v == null) return new int[0];
        String s = stripLeadingV(v.trim());
        int dash = s.indexOf('-');
        if (dash >= 0) s = s.substring(0, dash);
        String[] parts = s.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].replaceAll("\\D.*$", ""));
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    private static String stripLeadingV(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.startsWith("v") || t.startsWith("V")) return t.substring(1);
        return t;
    }

    /**
     * Extract the first {@code "key": "value"} from a JSON payload. Sufficient
     * for the few flat fields we read; intentionally not a general parser.
     */
    private static String extractStringField(String json, String key) {
        Pattern pat = Pattern.compile(
                "\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        Matcher m = pat.matcher(json);
        if (!m.find()) return null;
        return m.group(1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\/", "/")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }

    // ---- preferences -------------------------------------------------------

    private static Preferences prefs() {
        return Preferences.userRoot().node(PREFS_NODE);
    }

    public static boolean isAutoCheckEnabled() {
        return prefs().getBoolean(KEY_AUTO_CHECK, true);
    }

    public static void setAutoCheckEnabled(boolean enabled) {
        prefs().putBoolean(KEY_AUTO_CHECK, enabled);
    }

    public static long lastCheckMillis() {
        return prefs().getLong(KEY_LAST_CHECK_MS, 0L);
    }

    public static void recordCheckAttempt() {
        prefs().putLong(KEY_LAST_CHECK_MS, System.currentTimeMillis());
    }

    /**
     * True when an auto-check should run on startup: the user hasn't disabled it
     * AND the 12h throttle window has elapsed since the last attempt.
     */
    public static boolean shouldAutoCheckOnStartup() {
        if (!isAutoCheckEnabled()) return false;
        return System.currentTimeMillis() - lastCheckMillis() >= AUTO_CHECK_THROTTLE_MS;
    }

    private static String skipKey(Target target) {
        switch (target) {
            case GUI:    return KEY_SKIPPED_GUI;
            case PDV:    return KEY_SKIPPED_PDV;
            case PEPMAP: return KEY_SKIPPED_PEPMAP;
            case CASANOVO:
            default:     return KEY_SKIPPED_CASA;
        }
    }

    public static boolean isSkipped(Target target, String version) {
        if (version == null) return false;
        return version.equals(prefs().get(skipKey(target), ""));
    }

    public static void skip(Target target, String version) {
        if (version == null) return;
        prefs().put(skipKey(target), version);
    }
}
