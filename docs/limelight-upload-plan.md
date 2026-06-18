# "Upload to Limelight" — plan & as-built record

A Casanovo GUI feature that converts a de novo search result to Limelight XML and uploads it
to a [Limelight](https://limelight-ms.readthedocs.io/) instance, using two downloaded jars run
as child processes whose output streams into the app console.

**Status: implemented.** Verified by a full `javac` compile of the whole source tree against
JavaFX + RichTextFX + AtlantaFX (the sandbox lacks JDK 23+/Maven, so it has **not** been run
end-to-end yet). This document started as the plan and is kept current as the as-built record;
where the build deviated from the original plan it is called out inline (see §4.4 and §4.6 on
**Stop**, and §4a on the **jar update mechanism**).

Target: de novo results (`casanovo sequence`, including `--evaluate`). Not db-search/train/configure.

---

## 1. Background & verified facts

The feature is a two-step external pipeline. Both steps are `java -jar` invocations of
downloaded jars — the same shape the app already uses for PDV (`core/PdvLauncher`).

### 1.1 Step 1 — convert mzTab → Limelight XML

- **Jar:** `casanovoToLimelightXML.jar` — a standalone fat jar (Java 8+), ~2.5 MB.
- **Source repo:** `https://github.com/yeastrc/limelight-import-casanovo`
- **Download URL (stable asset name → use the `latest/download` redirect):**
  `https://github.com/yeastrc/limelight-import-casanovo/releases/latest/download/casanovoToLimelightXML.jar`
  - The asset name carries **no version**, so the GitHub `releases/latest/download/<asset>`
    redirect always resolves to the newest release with **no API call** — identical to how
    `CasanovoInstaller` fetches `uv`. (Contrast: `PdvLauncher` must call the releases API
    only because PDV's asset is version-stamped.)
- **CLI:**
  ```
  java -jar casanovoToLimelightXML.jar -m <mztab> -c <casanovo config yaml> -o <output.limelight.xml> -v
  ```
  - `-m` mzTab results file, `-c` Casanovo config YAML, `-o` output XML path, `-v` verbose.
  - **Takes no scan files.** Scan files are an uploader concern only.

### 1.2 Step 2 — upload Limelight XML → Limelight server

- **Jar:** `limelightSubmitImport.jar` (Java required on the system).
- **Source — latest `yeastrc/limelight-core` release** (stable asset name → `latest/download`
  redirect, same mechanism as the converter):
  `https://github.com/yeastrc/limelight-core/releases/latest/download/limelightSubmitImport.jar`
  - The user's instance URL is **not** the jar source; it is still passed to the importer as
    `--limelight-web-app-url` (the upload target). That URL **includes a `/limelight` path**,
    e.g. `https://limelight.yeastrc.org/limelight`.
  - *History:* the importer was originally pulled per-instance from
    `<web-app-url>/static/limelightSubmitImport/limelightSubmitImport.jar`; it now comes from
    the canonical limelight-core release instead.
- **CLI:**
  ```
  java -jar limelightSubmitImport.jar \
    --retry-count-limit=5 \
    --limelight-web-app-url=<url> \
    --user-submit-import-key=<key> \
    --project-id=<id> \
    --limelight-xml-file=<output.limelight.xml> \
    [--search-short-label=<label>] [--search-description=<desc>] \
    --search-tag=CasanovoGUI \
    ( --scan-file=<mzML/mzXML> [--scan-file=...]  |  --no-scan-files )
  ```
  - **Required:** `--limelight-web-app-url`, `--project-id`, `--user-submit-import-key`,
    `--limelight-xml-file`, and a scan-file directive.
  - `--scan-file` is **repeatable**; when there are no mzML/mzXML inputs pass **`--no-scan-files`**.
  - `--search-short-label`, `--search-description`, `--search-tag` are **optional** at the CLI.
    The GUI sends `--search-description` (required in its dialog) and `--search-tag=CasanovoGUI`;
    it does **not** send `--search-short-label`.
  - The **"API key" is the *user submit import key*** — obtained per-project from Limelight's
    *Command Line Import Information → Show Key*. Field label/help text says this.

### 1.3 Sources

- Limelight upload guide: https://limelight-ms.readthedocs.io/en/latest/using-limelight/data-upload-guide.html
- Import API: https://github.com/yeastrc/limelight-import-api
- Casanovo converter: https://github.com/yeastrc/limelight-import-casanovo

---

## 2. Design decisions (as implemented)

1. **Scope:** enabled for the **De novo** tab and **Evaluate** tab (both emit a `casanovo
   sequence` mzTab). Not db-search/train/configure.
2. **Both jars are fetched from the latest GitHub release** — converter from
   `yeastrc/limelight-import-casanovo`, importer from `yeastrc/limelight-core` — via the stable
   `latest/download` redirect. Cached under `~/.casanovo-gui/limelight/` and refreshed **once
   per app session** (auto-download), plus a manual force-refresh in Settings. See §4a. The
   user's instance URL remains the upload target via `--limelight-web-app-url`. (The importer
   was originally per-instance; switched to the canonical limelight-core release.)
3. **Persisted settings:** instance URL, submit-import key, and **last** project ID
   (project ID is prefilled but editable per upload). Stored via `Preferences` like the rest
   of the app.
4. **Dialog fields:** a **required** description (defaults to the mzTab base name). There is no
   short-name field — `--search-short-label` is not sent.
5. **Config source:** the config file the last run used; if the run had none on disk,
   auto-generate the version-correct default via `core/ConfigCache`.
6. **Scan files:** filter the last run's spectra to `.mzML`/`.mzXML`; one `--scan-file=` each,
   or `--no-scan-files` if none qualify.
7. **UI:** a modal `LimelightDialog` (like `SettingsDialog`); an **"Upload to Limelight"**
   button in the run bar next to "Open in PDV", plus a File-menu item.
8. **Security:** the submit key is stored in plaintext `Preferences` (consistent with the
   app), masked in the dialog and in the console command echo. A known limitation.

---

## 3. Architecture & reuse

Reuses existing patterns; **adds no new Maven dependencies** (JDK `HttpClient` +
`ProcessBuilder` only).

| Existing asset | How it's reused |
|---|---|
| `core/PdvLauncher` (download/cache jar; locate `java`) | Template for `LimelightUploader`; its `javaExecutable()` was extracted into the shared `core/JavaLauncher` |
| `core/CasanovoRunner` (async process + transient/committed streaming) | Generalized to run an arbitrary `List<String>`; both jar steps stream to the console. The pipeline uses the app's long-task busy pattern, **not** the Stop button — see §4.4 |
| `core/ConfigCache` | Generates the default config when a run had none |
| `core/Settings` | New persisted fields follow the `coloredConsole` pattern |
| `ui/SettingsDialog` | Template for the modal `LimelightDialog`; also hosts the converter version + force-refresh |
| MainApp last-run tracking (mzTab + spectra for PDV) | Extended to also remember the config file used and whether the run was `sequence`; reused to source upload inputs |

> Note: running external jars requires a real `java` launcher. The packaging scripts already
> copy `java` back into the bundled jpackage runtime for PDV; Limelight reuses that via
> `JavaLauncher`. No packaging change needed.

---

## 4. File-by-file (as built)

### 4.1 `core/Settings.java`

Added three fields following the existing pattern (`KEY_*` constant → field → `load()` →
`save()` → getter/setter): `limelightWebAppUrl`, `limelightSubmitImportKey`,
`limelightLastProjectId`, each defaulting to `""`.

### 4.2 `core/JavaLauncher.java` (new)

`PdvLauncher.javaExecutable(Consumer<String> log)` was extracted verbatim into a shared
`JavaLauncher.find(Consumer<String> log)`; `PdvLauncher.launchDenovo` now calls it. No behavior
change. Used by both PDV and Limelight.

### 4.3 `core/LimelightUploader.java` (new)

Modeled on `PdvLauncher`. Provisions both jars (download/cache/refresh) and builds the two
command lines. **No process execution here** — execution goes through the generalized
`CasanovoRunner` so output streams to the console.

```java
public final class LimelightUploader {

    private static final String CONVERTER_URL =
        ".../limelight-import-casanovo/releases/latest/download/casanovoToLimelightXML.jar";
    private static final String IMPORTER_URL =
        ".../limelight-core/releases/latest/download/limelightSubmitImport.jar";
    private static final String CONVERTER_JAR = "casanovoToLimelightXML.jar";
    private static final String IMPORTER_JAR  = "limelightSubmitImport.jar";
    private static final String SEARCH_TAG    = "CasanovoGUI";

    // Per-app-session refresh latches (reset on restart) — see §4a.
    private static volatile boolean converterRefreshedThisSession;
    private static volatile boolean importerRefreshedThisSession;

    public static Path limelightDir();              // ~/.casanovo-gui/limelight

    /**
     * Cached converter jar. Fetches the latest GitHub release when missing, refresh=true, or
     * not yet refreshed this app session. A failed refresh falls back to the cached jar; only
     * a missing jar with no network propagates.
     */
    public static Path ensureConverterJar(boolean refresh, Consumer<String> log,
                                          DoubleConsumer progress) throws IOException, InterruptedException;

    /**
     * Cached importer jar from the latest yeastrc/limelight-core release. Re-fetches when
     * missing / refresh / not yet refreshed this session; a failed refresh falls back to the
     * cached jar (offline-safe). Structurally identical to ensureConverterJar.
     */
    public static Path ensureImporterJar(boolean refresh, Consumer<String> log,
                                         DoubleConsumer progress) throws IOException, InterruptedException;

    public static String normalizeUrl(String url);           // trims trailing slashes (--limelight-web-app-url)

    public static List<String> convertCommand(String javaExe, Path converterJar,
                                              File mzTab, File config, File outXml);
    public static List<String> uploadCommand(String javaExe, Path importerJar, String webAppUrl,
                                             String submitKey, String projectId, File xml,
                                             String description, List<File> scanFiles);

    public static Optional<String> converterVersion();       // manifest Implementation-Version of cached jar
    public static Optional<String> jarVersion(Path jar);
}
```

The download helper streams to a `.part` temp and moves it into place only on success (no
unzip — both are bare jars), so a failed/partial download never corrupts the cached jar. Cache
layout:
```
~/.casanovo-gui/limelight/
  casanovoToLimelightXML.jar
  limelightSubmitImport.jar
  *.part                           # transient download temp (moved into place on success)
```

**`uploadCommand` rules:**
- Always: `--retry-count-limit=5`, `--limelight-web-app-url`, `--user-submit-import-key`,
  `--project-id`, `--limelight-xml-file`, `--search-description=<desc>` (required by the dialog),
  `--search-tag=CasanovoGUI`. No `--search-short-label`.
- `scanFiles` non-empty → one `--scan-file=<path>` per file; empty → `--no-scan-files`.
- Each token is a **separate list element** → spaces in the description need no quoting.

### 4.4 `core/CasanovoRunner.java`

Added a raw-command entry point; the existing Casanovo entry delegates to it. Keeps the
transient-`\r`/committed-line streaming and the single-process guard:

```java
public synchronized void start(CasanovoCommand command, Settings settings, File workingDir,
        BiConsumer<String,Boolean> onOutput, BiConsumer<Integer,Throwable> onFinished) {
    start(command.toProcessCommand(settings), workingDir, onOutput, onFinished);
}
public synchronized void start(List<String> osCommand, File workingDir,
        BiConsumer<String,Boolean> onOutput, BiConsumer<Integer,Throwable> onFinished) { ... }
```
`Os.applyNativeEnv` stays applied (harmless for these jars). The generic start failure message
was made non-Casanovo-specific.

**Deviation from the plan — Stop during the pipeline.** The runner *is* generalized and its
`cancel()` works, but the Limelight pipeline deliberately uses the app's existing long-task
busy pattern (`installing` flag + `setBusy`), exactly like Install / Update / PDV — so the
**Stop button is not enabled during a Limelight upload**. Rationale: consistency with every
other network task, lower risk, and killing the importer mid-upload could leave a partial
import server-side. Wiring Stop later is easy (see §8).

### 4.5 `ui/LimelightDialog.java` (new)

Modal dialog modeled on `SettingsDialog` (`initOwner(stage)`, returns true on "Upload").
Constructor: `LimelightDialog(Window owner, Settings settings, String defaultDescription,
String inputsSummary)`.

Fields (prefilled from `Settings`):
- **Limelight URL** — e.g. `https://limelight.yeastrc.org/limelight`. Help: "include the
  `/limelight` path".
- **Import key** — `PasswordField` (masked). Help: "from your project's Command Line
  Import Information → Show Key".
- **Project ID** — prefilled with last value, editable.
- **Description** (required) — defaults to the mzTab base name.
- **Read-only summary** of detected inputs: mzTab, config source ("from run" / "auto-generated
  default"), and the scan files that will be attached (or "no scan files").

On "Upload", a validating event filter checks: URL starts with `http(s)://`, key non-blank,
project ID present and numeric, and description non-blank. It then persists URL/key/project ID
(`settings.save()`) and the collected values are read via getters.

### 4.6 `ui/MainApp.java`

1. **Button:** `limelightButton` ("Upload to Limelight") in the run bar next to `pdvButton`,
   plus a "Upload to Limelight" File-menu item; both call `onUploadToLimelight()`.
2. **Last-run tracking:** alongside the last successful run's mzTab + spectra (for PDV), it
   records the **config file used** (`pendingConfigFile`/`lastResultConfig`, from the command's
   `--config` arg, or null) and **whether the run was `sequence`** (`pendingIsSequence`/
   `lastResultIsSequence`).
3. **Enablement (`hasUploadableResult()`):** the button is enabled only when the last run was a
   `sequence` run with an existing mzTab and non-null spectra; disabled during any busy/running
   state. Folded into both `setBusy` and `updateRunningState`.
4. **Handler `onUploadToLimelight()`** (outline):
   ```
   if (!hasUploadableResult()) { info; return; }
   scanFiles = lastResultSpectra.filter(.mzML/.mzXML)
   dlg = new LimelightDialog(stage, settings, stripExtension(mzTab), inputsSummary)
   if (!dlg.showAndCollect()) return;             // persists url/key/projectId
   outXml = sibling(mzTab, base + ".limelight.xml")
   installing = true; setBusy(true);

   background thread:
     java      = JavaLauncher.find(console::appendLine)
     converter = LimelightUploader.ensureConverterJar(false, console::appendLine, null)
     importer  = LimelightUploader.ensureImporterJar(false, console::appendLine, null)
     cfg       = resolveLimelightConfig(lastResultConfig)   // last config, else ConfigCache → temp
     convertCmd / uploadCmd = LimelightUploader.{convert,upload}Command(...)
     runLater -> runLimelightPipeline(convertCmd, uploadCmd, workDir, outXml)
   ```
5. **`runLimelightPipeline()` (FX thread):** echo + run `convertCmd`; on exit 0 with the XML
   present, echo (**key masked** via `maskedUploadDisplay`) + run `uploadCmd`; report success /
   failure / non-zero exit via `finishLimelight()`. Chaining is safe — the runner clears
   `active` before invoking `onFinished`, and the callback is marshaled to the FX thread, so
   starting step 2 from inside it never trips the single-process guard.
6. **`resolveLimelightConfig()`:** if the run's config exists → use it; else
   `ConfigCache.warm(settings)` + `cachedBase(settings)` written to a temp file (with
   `CasanovoConfig.toYaml()` as the final fallback). Runs on the background thread.
7. **Busy-state:** the pipeline uses `installing` + `setBusy(true)` (same as Install / PDV), so
   Run/Install/Upload are mutually exclusive; `finishLimelight()` clears it. No Stop (§4.4).
8. **Helpers:** `onLimelightOutput` (ANSI-stripped streaming, no Casanovo-specific parsing),
   `configArgOf`, `stripExtension`, `displayCommand`, `maskedUploadDisplay`,
   `buildLimelightInputsSummary`.

### 4.7 `ui/SettingsDialog.java`

A "Limelight converter:" row shows the cached converter's manifest version (or "not downloaded
yet") and a **Download / update** button that force-refreshes it
(`ensureConverterJar(refresh=true, …)`) on a background thread.

---

## 4a. Jar update mechanism (as built)

The jars stay current via a **once-per-app-session, auto-download** policy (chosen over
per-upload checks and manual-only):

- `LimelightUploader` holds two static latches — `converterRefreshedThisSession` and
  `importerRefreshedThisSession` — that reset on each app launch.
- **First upload of a session:** both jars are fetched fresh from their GitHub `latest/download`
  URLs (converter from limelight-import-casanovo, importer from limelight-core) and the latches
  are set.
- **Later uploads in the same session:** the cached jars are reused.
- **Next launch:** latches reset, so a new converter or importer release is picked up on the
  first upload again.
- **Auto-download** is silent; the console logs the outcome, including the converter version
  read from the jar manifest (e.g. `Converter ready: … (v1.5.3)`).

Robustness:
- **Crash-safe download:** stream to `*.part`, move into place only on success — a failed
  refresh never truncates the cached jar.
- **Offline fallback:** a failed once-per-session refresh falls back to the cached jar and
  proceeds (logged) — for both jars — so a flaky network doesn't block an upload.
- **Manual force-refresh:** Settings → "Download / update" → `ensureConverterJar(refresh=true)`.

No GitHub releases-API call or version-diffing is needed: bounding to one download per jar per
session makes a conditional check unnecessary. (A future "check every upload" mode is where an
API/version-compare or conditional-GET/ETag approach would earn its keep — see §8.)

---

## 5. Runtime flow (happy path)

1. User runs a de novo search → mzTab produced; app records mzTab + spectra + config path +
   "is sequence".
2. User clicks **Upload to Limelight** → modal dialog (prefilled) → fills key/URL/project →
   **Upload**.
3. Background: locate `java`; ensure converter + importer jars (once-per-session refresh from
   their GitHub latest releases); resolve config.
4. Console streams **Step 1** (`casanovoToLimelightXML.jar … -v`) → writes
   `<result>.limelight.xml` next to the mzTab.
5. On exit 0, console streams **Step 2** (`limelightSubmitImport.jar …`, key masked in the echo)
   → uploads XML + scan files (or `--no-scan-files`).
6. On success: status "Uploaded to Limelight"; the XML is left on disk for inspection.

---

## 6. Edge cases & error handling

- **No internet / jar refresh fails** → fall back to the cached jar and proceed (logged); only a
  *missing* jar with no network aborts with a clear error.
- **mzTab missing** (deleted/moved since the run) → re-validated at click time; warns.
- **Conversion exits non-zero / produces no XML** → do **not** upload; surface output; keep XML.
- **Upload auth/permission failure** → importer's message streams to the console; non-zero exit
  reported in the status bar + alert.
- **No mzML/mzXML inputs** (e.g. MGF run) → `--no-scan-files`.
- **Multiple spectrum files** → multiple `--scan-file=` args.
- **Instance URL formatting** → trailing slash trimmed for the `--limelight-web-app-url` arg
  (user enters the full `…/limelight` form, per docs).
- **Spaces** in the description → fine (separate `ProcessBuilder` args). The console echo
  quotes them; the import key is masked.
- **No Stop during the pipeline** (§4.4): the Stop button stays disabled. Convert is fast; the
  importer manages its own retries/timeout. A convert exit of 130 is treated as a failure and
  step 2 is not started.

---

## 7. Testing plan

Manual (no automated test harness exists in this project; only a `javac` compile was possible
in the dev sandbox):

1. **End-to-end, mzML input:** de novo run on an `.mzML` → Upload → verify both steps stream,
   XML is written, and the search appears in Limelight with the `CasanovoGUI` tag + scan file.
2. **MGF input:** confirm `--no-scan-files` is used and upload still succeeds.
3. **Auto-config:** run without GUI parameters / `--config`; confirm `ConfigCache` generates a
   config and conversion succeeds.
4. **Multiple inputs:** run on 2+ mzML files; confirm repeated `--scan-file=` args.
5. **Persistence:** restart the app; confirm URL/key/project ID prefill (key masked).
6. **Custom instance:** point the URL at a non-yeastrc instance; confirm the upload targets it
   (`--limelight-web-app-url`) while the importer jar still comes from the limelight-core release.
7. **Offline with cache:** disconnect after a prior successful upload; confirm the once-per-session
   refresh falls back to the cached jars (logged) and conversion still runs. With no cached jar,
   confirm a clean error rather than a hang/stack trace.
8. **Session refresh:** confirm the jars are fetched on the first upload of a session and reused
   afterwards; restart and confirm they refresh again on the next upload.
9. **Gating:** confirm the button is disabled with no result, during a run, and for
   db-search/train/configure results.

---

## 8. Open questions / future enhancements

- Optional "validate key/URL" pre-flight before running the full pipeline.
- Optional **Stop** support during the pipeline (the runner already supports cancellation — §4.4).
- A **"check every upload"** freshness mode (conditional-GET/ETag, or GitHub releases API +
  version-compare) if once-per-session proves too lax.
- Optional secure storage for the submit key (OS keychain) instead of plaintext `Preferences`
  — deferred to stay consistent with the app's current approach and zero-dependency stance.
- Allow uploading a previously generated mzTab via a file picker (currently only the last run).

---

## 9. New/changed files

```
NEW  src/main/java/org/casanovo/gui/core/JavaLauncher.java        (extracted from PdvLauncher)
NEW  src/main/java/org/casanovo/gui/core/LimelightUploader.java
NEW  src/main/java/org/casanovo/gui/ui/LimelightDialog.java
MOD  src/main/java/org/casanovo/gui/core/Settings.java            (+3 persisted fields)
MOD  src/main/java/org/casanovo/gui/core/CasanovoRunner.java      (raw List<String> overload)
MOD  src/main/java/org/casanovo/gui/core/PdvLauncher.java         (use JavaLauncher.find)
MOD  src/main/java/org/casanovo/gui/ui/MainApp.java               (button + menu, gating,
                                                                   orchestration, last-run tracking)
MOD  src/main/java/org/casanovo/gui/ui/SettingsDialog.java        (converter version + download/update)
```
No `pom.xml` / packaging changes required.
