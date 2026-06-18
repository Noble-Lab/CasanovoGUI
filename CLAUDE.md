# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A **JavaFX desktop front-end that drives the [Casanovo](https://github.com/Noble-Lab/casanovo)
Python CLI** (de novo peptide sequencing from mass spectrometry data) as a child process. The
GUI reimplements **no** mass-spec logic — it builds command lines, runs them, streams their
output to a console, and manages the surrounding tooling (installing Casanovo + its Python/GPU
runtime, generating config, launching result viewers/uploaders).

## Build & run

There is **no Maven wrapper** and **no automated test suite** — verification is a compile plus
manual runs. **Requires JDK 23+** (the pom targets `release 23`; JavaFX 25 needs ≥23; CI uses
JDK 25). Maven must be on `PATH`.

```bash
mvn javafx:run                       # primary dev run (handles the JavaFX module path + natives)
mvn -DskipTests clean package        # fat JAR for the host platform -> target/casanovo-gui-<ver>.jar
mvn -Pportable clean package         # fat JAR bundling JavaFX natives for Win + Linux + macOS-ARM
bash packaging/package.sh [--installer]   # native app-image / .deb+.tar.gz / .dmg (run ON the target OS)
pwsh ./packaging/package.ps1              # Windows app-image
```

- An IntelliJ run config exists at `.run/CasanovoGuiApp.run.xml` (runs `CasanovoGuiApp` with
  `--enable-native-access=ALL-UNNAMED`).
- The "main class" `CasanovoGuiApp` deliberately does **not** extend `Application` — that is what
  lets `java -jar` work without extra module flags. Don't "fix" this.
- To compile-check without a full JavaFX/Maven setup, the `core` package is pure JDK (no JavaFX)
  and compiles standalone: `javac -d /tmp/out src/main/java/org/casanovo/gui/core/*.java`.
- Dependencies are deliberately minimal: JavaFX, AtlantaFX, RichTextFX. **RichTextFX is pinned
  to 0.11.7** — do not downgrade (0.11.5 crashes on JavaFX 25; see the comment in `pom.xml`).

## Architecture

Two packages with a strict dependency direction (`ui` → `core`, never the reverse):

- **`core/`** — pure JDK, no JavaFX: process execution, config model, installer, update checks,
  external-tool launchers. Headless and independently compilable.
- **`ui/`** — JavaFX. `MainApp` is the central orchestrator (a `TabPane`, a console, a single
  `CasanovoRunner`); everything else is panes/dialogs/helpers.

Big-picture pieces that span multiple files:

- **Tab-per-subcommand.** Each `CommandPane` subclass (`SequencePane`, `DbSearchPane`,
  `EvalPane`, `TrainPane`, `ConfigurePane`) builds a `CasanovoCommand` (subcommand + args).
  `CasanovoCommand.toProcessCommand(Settings)` produces the OS command, optionally wrapping it in
  `conda run`.

- **Subprocess execution + live output.** `CasanovoRunner` runs **one process at a time** on a
  background thread and streams merged stdout/stderr as `(text, isTransient)` pairs: a bare `\r`
  chunk is a *transient* progress refresh (overwrites in place), `\n` is a committed line. This
  is what makes tqdm/Lightning progress bars render as a single updating line. It has two
  `start` overloads — `(CasanovoCommand, Settings, …)` and a raw `(List<String>, …)` used for the
  external Limelight jars. Callbacks fire on the background thread; UI code must
  `Platform.runLater`.

- **Console abstraction.** `ConsoleOutput` is implemented by `ConsoleView` (plain `TextArea`) and
  `RichConsoleView` (color-coded RichTextFX); the user swaps them live via View → "Colored
  console output". Both coalesce appends per FX pulse and keep one transient progress line.

- **Self-contained Python install.** `CasanovoInstaller` downloads `uv`, which fetches a pinned
  Python interpreter and installs Casanovo into `~/.casanovo-gui` (nothing system-wide). It
  detects an NVIDIA driver to pick CUDA-vs-CPU PyTorch wheels, and **pins `pyarrow<17`** — a too-
  new PyArrow vs Casanovo's pinned pylance crashes the interpreter with a hard access violation
  (exit `0xC0000005`). This pin recurs at install/update/repair time and a startup self-check
  (`hasPyArrowMismatch` / `maybeCheckPyArrow`) offers a one-click repair. `PyVenv` reads installed
  package versions from `dist-info` metadata **without launching Python** (fast, offline, and
  avoids the Windows crash).

- **Version-proof config.** Rather than emit its own full YAML, the GUI overlays only the
  user-changed parameters onto the output of `casanovo configure` (cached per installed version
  by `ConfigCache` under `~/.casanovo-gui/config-cache`). See `CasanovoConfig.overlayOnto` +
  `ConfigField`. This keeps generated configs valid when a Casanovo release adds new options.

- **Launching external Java tools.** `PdvLauncher` (PDV spectrum viewer) and `LimelightUploader`
  (mzTab → Limelight XML conversion + upload) both download/cache jars under `~/.casanovo-gui/`
  and run them via `JavaLauncher.find()`, which locates a real `java` across plain / jar /
  jpackage app-image layouts. The Limelight feature is documented in detail in
  **`docs/limelight-upload-plan.md`** (kept current as an as-built record) — read it before
  changing that flow.

- **Updates & settings.** `UpdateChecker` checks the GUI (GitHub) and Casanovo (PyPI) versions
  using only the JDK `HttpClient` + regex (no JSON dependency). `Settings` and `UpdateChecker`
  persist via `java.util.prefs.Preferences` (plaintext — relevant if storing secrets). To add a
  persisted setting, follow the existing pattern in `Settings` (`KEY_*` constant → field →
  `load()` → `save()` → getter/setter).

## Cross-cutting conventions & gotchas

- **Native env on every subprocess.** All `ProcessBuilder`s must call `Os.applyNativeEnv(pb)`:
  `PYTHONIOENCODING=utf-8` (UTF-8 output) and `FORCE_COLOR=1` (makes Lightning's Rich bar stream
  live; the ANSI it adds is stripped in `MainApp.onOutput`); on Windows also
  `KMP_DUPLICATE_LIB_OK`/`MKL_THREADING_LAYER` to avoid the MKL/OpenMP `0xC0000005` crash.
  Output-capturing helper paths (e.g. `ConfigCache`, the installer's command runner) additionally
  set `NO_COLOR=1`.

- **Concurrency / busy-state model.** Only one subprocess runs at a time. "Is something running"
  is the combination of `runner.isRunning()` and the `installing` flag. Long network tasks
  (install, update, PyArrow repair, PDV, Limelight upload) use `setBusy()` and have **no Stop
  button**; actual Casanovo runs use `updateRunningState()` and enable Stop. Match the existing
  pattern when adding a new long-running action.

- **jpackage runtime needs `java`.** jpackage strips the `java` launcher from the bundled runtime;
  `packaging/package.sh` copies it back so `PdvLauncher`/`LimelightUploader` can spawn
  `java -jar`. Don't remove that step.

- **Result reuse for viewers/uploaders.** After a successful `sequence` run, `MainApp` records the
  produced mzTab, its input spectra, the config used, and whether it was a `sequence` run; "Open
  in PDV" and "Upload to Limelight" reuse these (the Limelight button is gated to `sequence`
  results).

- **Git/CI.** `git commit`/`push` only when asked. Releases are driven by pushing a `v*` tag
  (`.github/workflows/build.yml` builds the portable JAR + per-OS native packages, one OS per
  runner since jpackage can't cross-build).
