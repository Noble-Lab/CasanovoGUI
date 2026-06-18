# Implementation Plan — "Upload to Limelight"

A new feature for Casanovo GUI that converts a de novo search result to Limelight XML
and uploads it to a [Limelight](https://limelight-ms.readthedocs.io/) instance, using two
downloaded jars run as child processes whose output streams into the app console.

Status: **planned** (design approved; not yet implemented).
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
- **Source — per Limelight instance** (so the importer matches the server version):
  `<web-app-url>/static/limelightSubmitImport/limelightSubmitImport.jar`
  - The web-app URL **includes a `/limelight` path**, e.g. `https://limelight.yeastrc.org/limelight`.
  - So the jar URL = `<web-app-url>` + `/static/limelightSubmitImport/limelightSubmitImport.jar`.
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
  - `--search-short-label`, `--search-description`, `--search-tag` are **optional** (work but
    undocumented). We add `--search-tag=CasanovoGUI` automatically.
  - The **"API key" is the *user submit import key*** — obtained per-project from Limelight's
    *Command Line Import Information → Show Key*. Field label/help text should say this.

### 1.3 Sources

- Limelight upload guide: https://limelight-ms.readthedocs.io/en/latest/using-limelight/data-upload-guide.html
- Import API: https://github.com/yeastrc/limelight-import-api
- Casanovo converter: https://github.com/yeastrc/limelight-import-casanovo

---

## 2. Approved design decisions

1. **Scope:** enabled for the **De novo** tab and **Evaluate** tab (both emit a `casanovo
   sequence` mzTab). Not db-search/train/configure.
2. **Importer jar is fetched from the user's instance URL** (version-matched). Converter jar
   is fetched from GitHub latest. Both cached under `~/.casanovo-gui/limelight/`, with a
   refresh action.
3. **Persisted settings:** instance URL, submit-import key, and **last** project ID
   (project ID is prefilled but editable per upload). Stored via `Preferences` like the rest
   of the app.
4. **Optional dialog fields:** search short label (default = mzTab base name) and description.
5. **Config source:** the config file the last run used; if the run had none on disk,
   auto-generate the version-correct default via `core/ConfigCache`.
6. **Scan files:** filter the last run's spectra to `.mzML`/`.mzXML`; one `--scan-file=` each,
   or `--no-scan-files` if none qualify.
7. **UI:** a modal `LimelightDialog` (like `SettingsDialog`); an **"Upload to Limelight"**
   button in the run bar next to "Open in PDV".
8. **Security:** the submit key is stored in plaintext `Preferences` (consistent with the
   app), masked in the UI. Documented as a known limitation.

---

## 3. Architecture & reuse

Reuses existing patterns; **adds no new Maven dependencies** (JDK `HttpClient` +
`ProcessBuilder` only).

| Existing asset | How it's reused |
|---|---|
| `core/PdvLauncher` (download/cache jar; locate `java`) | Template for `LimelightUploader`; its `javaExecutable()` is extracted into a shared helper |
| `core/CasanovoRunner` (async process + transient/committed streaming) | Generalized to run an arbitrary `List<String>`; both jar steps stream to the console with live progress and a working **Stop** button |
| `core/ConfigCache` | Generates the default config when a run had none |
| `core/Settings` | New persisted fields follow the `coloredConsole` pattern |
| `ui/SettingsDialog` | Template for the modal `LimelightDialog` |
| MainApp last-run tracking (mzTab + spectra for PDV) | Extended to also remember the config file used; reused to source upload inputs |

> Note: running external jars requires a real `java` launcher. The packaging scripts already
> copy `java` back into the bundled jpackage runtime for PDV; Limelight reuses that. No
> packaging change needed.

---

## 4. File-by-file changes

### 4.1 `core/Settings.java` (modify)

Add three fields following the existing pattern (`KEY_*` constant → field → `load()` →
`save()` → getter/setter):

```java
private static final String KEY_LL_URL        = "limelightWebAppUrl";
private static final String KEY_LL_KEY         = "limelightSubmitImportKey";
private static final String KEY_LL_PROJECT_ID  = "limelightLastProjectId";

private String limelightUrl;
private String limelightKey;
private String limelightProjectId;

// load():
limelightUrl       = prefs.get(KEY_LL_URL, "");
limelightKey       = prefs.get(KEY_LL_KEY, "");
limelightProjectId = prefs.get(KEY_LL_PROJECT_ID, "");

// save():
prefs.put(KEY_LL_URL, nullToEmpty(limelightUrl));
prefs.put(KEY_LL_KEY, nullToEmpty(limelightKey));
prefs.put(KEY_LL_PROJECT_ID, nullToEmpty(limelightProjectId));
```
Plus trimmed getters and setters. (Note the 8192-char `Preferences` value cap is irrelevant
for these.)

### 4.2 `core/JavaLauncher.java` (new) — or fold into `core/Os`

Extract `PdvLauncher.javaExecutable(Consumer<String> log)` verbatim into a shared static
helper and have **both** `PdvLauncher` and `LimelightUploader` call it.

```java
public final class JavaLauncher {
    /** Locate a real `java` launcher across plain, jar, and jpackage app-image layouts. */
    public static String find(Consumer<String> log) { /* moved from PdvLauncher */ }
}
```
Refactor `PdvLauncher.launchDenovo` to call `JavaLauncher.find(...)`. No behavior change.

### 4.3 `core/LimelightUploader.java` (new)

Modeled on `PdvLauncher`. Responsibilities: ensure both jars (download+cache), build the two
command lines, expose the cache dir. **No process execution here** — execution goes through
the generalized `CasanovoRunner` so output streams to the console with the Stop button.

```java
public final class LimelightUploader {

    private static final String CONVERTER_URL =
        "https://github.com/yeastrc/limelight-import-casanovo/releases/latest/download/casanovoToLimelightXML.jar";
    private static final String CONVERTER_JAR = "casanovoToLimelightXML.jar";
    private static final String IMPORTER_JAR  = "limelightSubmitImport.jar";

    /** ~/.casanovo-gui/limelight */
    public static Path limelightDir() { ... }

    // ---- jar provisioning (download with progress; cache; reuse) ----

    /** Cached converter jar, downloading the latest from GitHub if absent or refresh=true. */
    public static Path ensureConverterJar(boolean refresh, Consumer<String> log,
                                          DoubleConsumer progress) throws IOException, InterruptedException;

    /**
     * Cached importer jar for the given instance. Derives the URL from webAppUrl, downloads
     * if absent / refresh / the recorded source URL differs (instance changed). The source
     * URL is recorded in a sibling file so a different instance triggers a re-download.
     */
    public static Path ensureImporterJar(String webAppUrl, boolean refresh, Consumer<String> log,
                                         DoubleConsumer progress) throws IOException, InterruptedException;

    /** webAppUrl (trailing slash trimmed) + "/static/limelightSubmitImport/limelightSubmitImport.jar" */
    public static String importerUrlFor(String webAppUrl);

    // ---- command construction ----

    /** java -jar <converter> -m <mzTab> -c <config> -o <outXml> -v */
    public static List<String> convertCommand(String javaExe, Path converterJar,
                                              File mzTab, File config, File outXml);

    /** The full importer command; scanFiles empty => --no-scan-files. */
    public static List<String> uploadCommand(String javaExe, Path importerJar, String webAppUrl,
                                             String submitKey, String projectId, File xml,
                                             String shortLabel, String description, List<File> scanFiles);

    /** Optional: read Implementation-Version from a jar manifest for display. */
    public static Optional<String> jarVersion(Path jar);
}
```

Download/unzip helpers mirror `PdvLauncher` (the converter is a bare jar — **no unzip**;
just stream to file). Cache layout:
```
~/.casanovo-gui/limelight/
  casanovoToLimelightXML.jar
  limelightSubmitImport.jar
  limelightSubmitImport.source     # the instance URL the importer was fetched from
```

**`uploadCommand` rules:**
- Always: `--retry-count-limit=5`, `--limelight-web-app-url`, `--user-submit-import-key`,
  `--project-id`, `--limelight-xml-file`, `--search-tag=CasanovoGUI`.
- Add `--search-short-label=<label>` / `--search-description=<desc>` only when non-blank.
- `scanFiles` non-empty → one `--scan-file=<path>` per file; empty → `--no-scan-files`.
- Each token is a **separate list element** → spaces in label/description need no quoting.

### 4.4 `core/CasanovoRunner.java` (modify) — generalize

Add a raw-command entry point; the existing Casanovo entry delegates to it. Keep the
transient-`\r`/committed-line streaming and the single-process guard.

```java
/** Existing API — unchanged signature; now delegates. */
public synchronized void start(CasanovoCommand command, Settings settings, File workingDir,
        BiConsumer<String,Boolean> onOutput, BiConsumer<Integer,Throwable> onFinished) {
    start(command.toProcessCommand(settings), workingDir, onOutput, onFinished);
}

/** New: run any prebuilt command with the same streaming + cancel semantics. */
public synchronized void start(List<String> osCommand, File workingDir,
        BiConsumer<String,Boolean> onOutput, BiConsumer<Integer,Throwable> onFinished) { ... }
```
`Os.applyNativeEnv` stays applied (harmless for these jars). The `isRunning()` /
`cancel()` machinery now covers Limelight steps too, so **Stop** works during upload.

### 4.5 `ui/LimelightDialog.java` (new)

Modal dialog modeled on `SettingsDialog` (`initOwner(stage)`, returns true on "Upload").

Fields (prefilled from `Settings`):
- **Limelight web-app URL** — e.g. `https://limelight.yeastrc.org/limelight`. Help text:
  "include the `/limelight` path".
- **Submit import key** — `PasswordField` (masked). Help: "from your project's Command Line
  Import Information → Show Key".
- **Project ID** — prefilled with last value, editable.
- **Search short label** (optional) — default = mzTab base name.
- **Description** (optional).
- **Read-only summary** of detected inputs: mzTab path, config source ("from run" /
  "auto-generated default"), and the scan files that will be attached (or "no scan files").

Validation before enabling "Upload": URL non-blank and `http(s)://`; key non-blank; project
ID non-blank (and numeric). On "Upload", write the three persisted settings (`settings.save()`)
and return the collected values to `MainApp`.

### 4.6 `ui/MainApp.java` (modify) — button, gating, orchestration

1. **Button:** add `Button limelightButton = new Button("Upload to Limelight")` to the run
   bar next to `pdvButton`; tooltip explains it converts + uploads the last result.
2. **Last-run tracking:** where the app currently records the last successful run's mzTab +
   spectra (for PDV), also record **the config file path used** (the generated temp config or
   the explicit `--config`, or `null`). Add a flag/record of whether the last run was a
   `sequence` run.
3. **Enablement:** enable `limelightButton` only when there is a last successful `sequence`
   run with an existing mzTab; disable during any busy/running state.
4. **Handler `onUploadToLimelight()`** (outline):
   ```
   if (!hasUploadableResult()) { warn; return; }
   LimelightDialog dlg = new LimelightDialog(stage, settings, detectedInputsSummary);
   if (!dlg.showAndCollect()) return;            // persists url/key/projectId

   mzTab     = lastMzTab;                          // re-check exists
   scanFiles = lastSpectra.filter(mzML|mzXML);
   outXml    = sibling(mzTab, base + ".limelight.xml");
   setRunningState(true);

   background:
     java = JavaLauncher.find(console::appendLine);
     converter = LimelightUploader.ensureConverterJar(false, sink, progress);
     importer  = LimelightUploader.ensureImporterJar(url, false, sink, progress);
     config    = resolveConfig();                  // last config, else ConfigCache → temp file
     onFx -> runner.start(convertCommand, workDir, onOutput, (code, err) -> {
                if (code == 0 && outXml.exists())
                    runner.start(uploadCommand, workDir, onOutput, finalFinished);
                else
                    reportFailure("Conversion failed", code, err);
             });
   ```
   Chaining is safe: the runner's `onFinished` fires after `active` is cleared, so starting
   step 2 from inside it does not trip the single-process guard. All UI/runner kicks marshal
   to the FX thread.
5. **`resolveConfig()`:** if `lastConfigFile != null && exists` → use it; else
   `ConfigCache.warm(settings)` then `cachedBase(settings)`, written to a temp file (reuse
   `CasanovoConfig.writeTempConfig`); if that fails, `new CasanovoConfig().toYaml()` fallback.
   Runs on the background thread (it may spawn `casanovo configure`).
6. **Busy-state consolidation:** fold Limelight into the existing `setBusy`/
   `updateRunningState` so Run/Install/Upload are mutually exclusive and Stop cancels the
   active step.
7. **Settings dialog (optional):** add a "Limelight tools" row with a "Re-download converter"
   action calling `ensureConverterJar(refresh=true, ...)`, mirroring the PDV upgrade button.

---

## 5. Runtime flow (happy path)

1. User runs a de novo search → mzTab produced; app records mzTab + spectra + config path.
2. User clicks **Upload to Limelight** → modal dialog (prefilled) → fills key/URL/project →
   **Upload**.
3. Background: locate `java`; ensure converter jar (download if first use); ensure importer
   jar from the instance URL; resolve config.
4. Console streams **Step 1** (`casanovoToLimelightXML.jar … -v`) → writes
   `<result>.limelight.xml`.
5. On exit 0, console streams **Step 2** (`limelightSubmitImport.jar …`) → uploads XML +
   scan files (or `--no-scan-files`).
6. On success: status "Uploaded to Limelight"; the XML is left on disk for inspection.

---

## 6. Edge cases & error handling

- **No internet / jar download fails** → clear console error; abort before running.
- **mzTab missing** (deleted/moved since the run) → re-validate at click time; disable or warn.
- **Conversion exits non-zero** → do **not** upload; surface stderr; keep the XML.
- **Upload auth/permission failure** → importer's message streams to the console; non-zero
  exit reported in the status bar.
- **No mzML/mzXML inputs** (e.g. MGF run) → `--no-scan-files`.
- **Multiple spectrum files** → multiple `--scan-file=` args.
- **Instance URL formatting** → trim trailing slash before deriving the jar URL; pass the URL
  verbatim to `--limelight-web-app-url` (user enters the full `…/limelight` form, per docs).
- **Instance changed** since last cache → importer re-downloaded (recorded `.source` differs).
- **Spaces** in label/description → fine (separate `ProcessBuilder` args; the command-preview
  display string should quote them, execution needs nothing).
- **Stop pressed mid-pipeline** → `runner.cancel()` kills the current step; the chain stops
  (don't start step 2 if step 1 was cancelled — treat cancel/exit 130 as failure).

---

## 7. Testing plan

Manual (no automated test harness exists in this project):

1. **End-to-end, mzML input:** de novo run on an `.mzML` → Upload → verify both steps stream,
   XML is written, and the search appears in Limelight with the `CasanovoGUI` tag + scan file.
2. **MGF input:** confirm `--no-scan-files` is used and upload still succeeds.
3. **Auto-config:** run without GUI parameters / `--config`; confirm `ConfigCache` generates a
   config and conversion succeeds.
4. **Multiple inputs:** run on 2+ mzML files; confirm repeated `--scan-file=` args.
5. **Persistence:** restart the app; confirm URL/key/project ID prefill (key masked).
6. **Custom instance:** point the URL at a non-yeastrc instance; confirm the importer jar is
   fetched from that instance (`.source` recorded) and re-fetched when the URL changes.
7. **Offline:** disconnect; confirm a clean error rather than a hang/stack trace.
8. **Stop:** cancel during step 1; confirm step 2 does not start.
9. **Gating:** confirm the button is disabled with no result, during a run, and for
   db-search/train/configure results.

---

## 8. Open questions / future enhancements

- Display the cached converter/importer versions (from jar manifest) in the Settings dialog.
- Optional "validate key/URL" pre-flight before running the full pipeline.
- Optional secure storage for the submit key (OS keychain) instead of plaintext `Preferences`
  — deferred to stay consistent with the app's current approach and zero-dependency stance.
- Allow uploading a previously generated mzTab via a file picker (currently only the last run).

---

## 9. Summary of new/changed files

```
NEW  src/main/java/org/casanovo/gui/core/LimelightUploader.java
NEW  src/main/java/org/casanovo/gui/core/JavaLauncher.java          (extracted from PdvLauncher)
NEW  src/main/java/org/casanovo/gui/ui/LimelightDialog.java
MOD  src/main/java/org/casanovo/gui/core/Settings.java               (+3 persisted fields)
MOD  src/main/java/org/casanovo/gui/core/CasanovoRunner.java         (raw List<String> overload)
MOD  src/main/java/org/casanovo/gui/core/PdvLauncher.java            (use JavaLauncher.find)
MOD  src/main/java/org/casanovo/gui/ui/MainApp.java                  (button, gating, orchestration,
                                                                      last-run config tracking)
MOD  src/main/java/org/casanovo/gui/ui/SettingsDialog.java           (optional: re-download converter)
```
No `pom.xml` / packaging changes required.
