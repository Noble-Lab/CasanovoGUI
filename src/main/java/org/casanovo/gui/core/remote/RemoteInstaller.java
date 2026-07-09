package org.casanovo.gui.core.remote;

import net.schmizz.sshj.SSHClient;

import java.io.IOException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Ensures a GUI-managed Casanovo virtual environment exists on the remote host, mirroring the local
 * {@link org.casanovo.gui.core.CasanovoInstaller} venv+pip flow but driven entirely over SSH. The venv lives
 * under {@link RemoteSettings#getInstallDir()} (default {@code ~/.casanovo-gui}) so it survives reboots and
 * is reused across runs; only its absence triggers an install. All commands run under {@code bash -lc} so a
 * leading {@code ~} and the user's login {@code PATH} resolve on the server.
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
     * @throws IOException if no suitable Python is found or an install step fails
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

        // ---- Pick a Python >= 3.10 (Casanovo supports 3.10-3.13). ----
        String python = RemoteShell.lastLine(RemoteShell.capture(ssh, RemoteShell.bashLogin(
                "for p in python3.13 python3.12 python3.11 python3.10; do command -v $p && break; done")));
        if (python == null || !python.contains("python")) {
            throw new IOException("No suitable python3 (>=3.10) found on the remote host. "
                    + "Install Python 3.10-3.13 or load a module that provides it, then retry.");
        }
        log.accept("Using remote Python: " + python);

        // Forward committed lines to the log (skip bare-\r progress refreshes so pip bars don't flood).
        BiConsumer<String, Boolean> toLog = (line, isTransient) -> {
            if (!isTransient) {
                log.accept(line);
            }
        };

        // ---- Create the venv. ----
        log.accept("Creating virtual environment ...");
        int code = RemoteShell.runStreamed(ssh, RemoteShell.bashLogin(
                        "mkdir -p " + RemoteShell.shq(installDir)
                                + " && " + RemoteShell.shq(python) + " -m venv " + RemoteShell.shq(venvDir)),
                toLog);
        if (code != 0) {
            throw new IOException("Failed to create the remote virtual environment (exit " + code + ").");
        }

        // ---- Install Casanovo (this can take several minutes). ----
        log.accept("Installing Casanovo (this can take several minutes) ...");
        code = RemoteShell.runStreamed(ssh, RemoteShell.bashLogin(
                        RemoteShell.shq(venvDir + "/bin/pip") + " install --upgrade pip casanovo"),
                toLog);
        if (code != 0) {
            throw new IOException("Failed to install Casanovo on the remote host (exit " + code + ").");
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
}
