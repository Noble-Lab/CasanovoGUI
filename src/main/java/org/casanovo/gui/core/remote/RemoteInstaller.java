package org.casanovo.gui.core.remote;

import net.schmizz.sshj.SSHClient;
import org.casanovo.gui.core.CasanovoInstaller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Ensures a GUI-managed Casanovo virtual environment exists on the remote host, driven entirely over SSH.
 * The venv lives under {@link RemoteSettings#getInstallDir()} (default {@code ~/.casanovo-gui}) so it survives
 * reboots and is reused across runs; only its absence triggers an install.
 *
 * <p>The environment is built with <a href="https://docs.astral.sh/uv/">uv</a>, a single self-contained
 * static binary. uv creates the venv itself &mdash; it never calls {@code python -m venv}, so the
 * Debian/Ubuntu split where {@code ensurepip}/{@code python3-venv} is a separate (often missing) package
 * cannot break setup &mdash; and it can fetch a managed CPython when the host has no suitable interpreter.
 * A system or per-user uv is reused if present; otherwise uv is installed (no sudo) under the GUI's own
 * install dir. All commands run under {@code bash -lc} so a leading {@code ~} and the login {@code PATH}
 * resolve on the server.</p>
 *
 * <p>This mirrors the local {@link CasanovoInstaller} recipe (same uv, {@link CasanovoInstaller#PYTHON_VERSION}
 * and {@link CasanovoInstaller#PYARROW_PIN} &mdash; the pin prevents the pylance/PyArrow ABI crash). The two
 * can't share execution (local runs a {@code ProcessBuilder} on the local filesystem; this drives shell
 * commands over SSH), but the recipe and the pinned <em>policy</em> are kept in sync: the Python version, the
 * PyArrow pin, and the driver&rarr;CUDA wheel selection ({@link CasanovoInstaller#cudaTorchIndexUrl(String)})
 * all come from {@link CasanovoInstaller}. Like the local installer it reads the remote {@code nvidia-smi}
 * driver version and installs the matched {@code torch}/{@code torchvision}/{@code torchaudio} trio before
 * Casanovo on a GPU host.</p>
 */
public final class RemoteInstaller {

    private RemoteInstaller() {
    }

    /**
     * Return the remote {@code casanovo} launcher path, creating the managed venv first if it is missing.
     *
     * @param ssh        a connected, authenticated client
     * @param installDir the resolved, absolute remote install dir (no {@code ~}; shell-quoted on use)
     * @param log        receives human-readable progress lines
     * @return the remote path of the {@code casanovo} launcher (an absolute path under {@code installDir})
     * @throws IOException if the environment could not be set up
     */
    public static String ensureCasanovo(SSHClient ssh, String installDir, Consumer<String> log)
            throws IOException {
        String venvDir = installDir + "/.venv";
        String launcher = venvDir + "/bin/casanovo";

        // ---- Reuse an existing venv (probe with `test -x` under a login shell for the user's PATH). ----
        String existing = RemoteShell.capture(ssh, RemoteShell.bashLogin(
                "test -x " + RemoteShell.shq(launcher) + " && echo EXISTS || true"));
        if (existing.contains("EXISTS")) {
            log.accept("Reusing existing Casanovo venv at " + venvDir);
            return launcher;
        }
        log.accept("No managed Casanovo venv found; creating one at " + venvDir + " ...");

        // Forward committed lines to the log (skip bare-\r progress refreshes so download bars don't flood).
        BiConsumer<String, Boolean> toLog = (line, isTransient) -> {
            if (!isTransient) {
                log.accept(line);
            }
        };

        // Match the local installer: detect the remote NVIDIA driver and pick the SAME matched CUDA PyTorch
        // wheel index (shared selection), so a GPU box with an older driver gets the right cuXXX build rather
        // than PyPI's default. The version comparison stays in Java (shared, tested), not reimplemented in shell.
        String driver = detectNvidiaDriver(ssh);
        String torchIndexUrl = CasanovoInstaller.cudaTorchIndexUrl(driver);
        if (torchIndexUrl != null) {
            log.accept("NVIDIA driver " + driver + " detected -> installing matched CUDA PyTorch ("
                    + torchIndexUrl + ").");
        } else {
            log.accept("No CUDA-capable NVIDIA driver detected -> using the default PyTorch.");
        }

        // ---- Build the environment with uv (see the class javadoc for why uv, not `python -m venv`). ----
        log.accept("Setting up Casanovo with uv (this can take several minutes) ...");
        List<String> lines = new ArrayList<>();
        lines.add("set -e");
        lines.add("INSTALL=" + RemoteShell.shq(installDir));
        lines.add("VENV=" + RemoteShell.shq(venvDir));
        lines.add("TOOLBIN=\"$INSTALL/bin\"");
        lines.add("mkdir -p \"$INSTALL\" \"$TOOLBIN\"");
        // Locate uv: PATH, our managed copy, or the usual per-user install dirs.
        lines.add("UV=\"$(command -v uv 2>/dev/null || true)\"");
        lines.add("if [ -z \"$UV\" ]; then for c in \"$TOOLBIN/uv\" \"$HOME/.local/bin/uv\" \"$HOME/.cargo/bin/uv\";"
                + " do if [ -x \"$c\" ]; then UV=\"$c\"; break; fi; done; fi");
        // Install uv (static binary, no admin rights) under our install dir if it is still missing.
        lines.add("if [ -z \"$UV\" ]; then");
        lines.add("  echo 'Installing uv (standalone, no admin rights needed) ...'");
        // UV_NO_MODIFY_PATH=1: the installer would otherwise edit the user's shell rc to add uv to PATH; we
        // locate uv by explicit path, so don't touch the remote user's dotfiles.
        lines.add("  if command -v curl >/dev/null 2>&1; then curl -LsSf https://astral.sh/uv/install.sh"
                + " | env UV_INSTALL_DIR=\"$TOOLBIN\" UV_NO_MODIFY_PATH=1 sh;");
        lines.add("  elif command -v wget >/dev/null 2>&1; then wget -qO- https://astral.sh/uv/install.sh"
                + " | env UV_INSTALL_DIR=\"$TOOLBIN\" UV_NO_MODIFY_PATH=1 sh;");
        lines.add("  else echo 'ERROR: need curl or wget to install uv on the remote host' >&2; exit 4; fi");
        lines.add("  for c in \"$TOOLBIN/uv\" \"$HOME/.local/bin/uv\" \"$HOME/.cargo/bin/uv\";"
                + " do if [ -x \"$c\" ]; then UV=\"$c\"; break; fi; done");
        lines.add("fi");
        lines.add("[ -n \"$UV\" ] || { echo 'ERROR: uv is not available and could not be installed' >&2; exit 4; }");
        lines.add("echo \"Using uv: $UV\"");
        // uv fetches a managed CPython (no reliance on a system python), matching the local recipe.
        lines.add("\"$UV\" venv --clear --python " + RemoteShell.shq(CasanovoInstaller.PYTHON_VERSION) + " \"$VENV\"");
        // Matched CUDA PyTorch trio BEFORE Casanovo, so Casanovo keeps the GPU build (mirrors the local flow).
        if (torchIndexUrl != null) {
            lines.add("\"$UV\" pip install --python \"$VENV/bin/python\" torch torchvision torchaudio"
                    + " --index-url " + RemoteShell.shq(torchIndexUrl));
        }
        // Install Casanovo AND the PyArrow pin in one resolution so it is atomic: the casanovo launcher only
        // appears if the pin applied too. (Same pin as local; a too-new PyArrow crashes Casanovo on import.
        // Installing them separately could leave a launcher present but mis-pinned, which the reuse check
        // above would then wrongly accept.)
        lines.add("\"$UV\" pip install --python \"$VENV/bin/python\" casanovo "
                + RemoteShell.shq(CasanovoInstaller.PYARROW_PIN));
        lines.add("test -x " + RemoteShell.shq(launcher)
                + " || { echo 'ERROR: casanovo not found after install' >&2; exit 5; }");
        int code = RemoteShell.runStreamed(ssh, RemoteShell.bashLogin(String.join("\n", lines)), toLog);
        if (code != 0) {
            throw new IOException("Failed to set up Casanovo on the remote host (exit " + code + "). "
                    + "uv needs curl or wget to self-install and the host needs internet access — "
                    + "check the remote host and retry.");
        }

        // ---- Best-effort sanity check (non-fatal, mirrors the local installer). ----
        try {
            RemoteShell.runStreamed(ssh, RemoteShell.bashLogin(
                    RemoteShell.shq(launcher) + " version"), toLog);
        } catch (IOException ve) {
            log.accept("[warn] Version check did not complete cleanly: " + ve.getMessage());
        }

        log.accept("Casanovo ready at " + launcher);
        return launcher;
    }

    /**
     * The remote NVIDIA driver version via {@code nvidia-smi}, or {@code null} when there is no usable GPU
     * (no {@code nvidia-smi}, or its output isn't a version). Fed to
     * {@link CasanovoInstaller#cudaTorchIndexUrl(String)} to pick a matched CUDA wheel index.
     */
    private static String detectNvidiaDriver(SSHClient ssh) {
        try {
            String v = RemoteShell.lastLine(RemoteShell.capture(ssh, RemoteShell.bashLogin(
                    "nvidia-smi --query-gpu=driver_version --format=csv,noheader 2>/dev/null | head -n1 || true")));
            // Accept only a real version string; a login banner or empty output means no usable driver.
            return (v != null && v.trim().matches("\\d+(\\.\\d+)*")) ? v.trim() : null;
        } catch (Exception e) {
            // Best-effort probe: no nvidia-smi / not a GPU host, or a transient SSH hiccup — fall back to
            // the default PyTorch rather than aborting the whole install.
            return null;
        }
    }
}
