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
 * driver version and installs the matched {@code torch} before Casanovo on a GPU host (only
 * torch: see {@link CasanovoInstaller#cudaTorchIndexUrl(String)}'s caller for why the usual
 * three-package line is not used).</p>
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

        // Forward committed lines to the log (skip bare-\r progress refreshes so download bars don't flood).
        BiConsumer<String, Boolean> toLog = (line, isTransient) -> {
            if (!isTransient) {
                log.accept(line);
            }
        };

        // ---- Reuse an existing venv (probe with `test -x` under a login shell for the user's PATH). ----
        String existing = RemoteShell.capture(ssh, RemoteShell.bashLogin(
                "test -x " + RemoteShell.shq(launcher) + " && echo EXISTS || true"));
        if (existing.contains("EXISTS")) {
            log.accept("Reusing existing Casanovo venv at " + venvDir);
            stubRdkitDrawIfUnloadable(ssh, venvDir, toLog);
            return launcher;
        }
        log.accept("No managed Casanovo venv found; creating one at " + venvDir + " ...");

        // Match the local installer: the remote uv picks the PyTorch wheel from the remote driver itself via
        // --torch-backend=auto (see CasanovoInstaller.pipInstallMatchingTorch for why that beats choosing an
        // index ourselves). Only when the remote uv predates that flag do we fall back to our own driver ->
        // index mapping, whose version comparison stays in Java (shared, tested) rather than in shell.
        String driver = detectNvidiaDriver(ssh);
        String torchIndexUrl = CasanovoInstaller.cudaTorchIndexUrl(driver);
        if (torchIndexUrl != null) {
            log.accept("NVIDIA driver " + driver + " detected (fallback wheel index, used only if the "
                    + "remote uv is too old: " + torchIndexUrl + ").");
        } else {
            log.accept("nvidia-smi reported no CUDA-capable driver on the remote host.");
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
        // Ask the remote uv whether it understands --torch-backend. When it does, PyTorch is resolved
        // against the CUDA index matching the REMOTE driver, in the same resolution as Casanovo itself.
        lines.add("TB=\"\"; if \"$UV\" pip install --help 2>/dev/null"
                + " | grep -q -- '--torch-backend'; then TB=--torch-backend=auto; fi");
        lines.add("echo \"torch backend: ${TB:-unavailable (this uv predates --torch-backend)}\"");
        // Fallback for an older remote uv: our own driver -> wheel index mapping, installed BEFORE Casanovo
        // so that Casanovo's own resolution keeps the GPU build (the pre-flag local flow).
        if (torchIndexUrl != null) {
            lines.add("if [ -z \"$TB\" ]; then \"$UV\" pip install --python "
                    + "\"$VENV/bin/python\" torch --index-url "
                    + RemoteShell.shq(torchIndexUrl) + "; fi");
        }
        lines.addAll(casanovoInstallLines(torchIndexUrl));
        lines.add("test -x " + RemoteShell.shq(launcher)
                + " || { echo 'ERROR: casanovo not found after install' >&2; exit 5; }");
        int code = RemoteShell.runStreamed(ssh, RemoteShell.bashLogin(String.join("\n", lines)), toLog);
        if (code != 0) {
            throw new IOException("Failed to set up Casanovo on the remote host (exit " + code + "). "
                    + "uv needs curl or wget to self-install and the host needs internet access — "
                    + "check the remote host and retry.");
        }
        stubRdkitDrawIfUnloadable(ssh, venvDir, toLog);

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
     * The lines that install Casanovo with a PyTorch matched to the remote machine, and fall back
     * when that uv turns out not to accept {@code --torch-backend} after all.
     *
     * <p>Extracted and pure so a test can run it against stub binaries: this is shell logic with
     * two traps that {@code sh -n} cannot see. The install runs under {@code set +e} because the
     * whole script uses {@code set -e}, and a failing command inside a pipeline's subshell would
     * otherwise abort the group before the exit status is recorded. The log and status files live
     * beside the venv &mdash; a directory uv has just written to &mdash; rather than in
     * {@code TMPDIR}, which on a hardened host can be unwritable and would then turn a successful
     * install into a reported failure.</p>
     *
     * <p>Expects {@code $UV}, {@code $VENV} and {@code $TB} (the flag, or empty) to be set.</p>
     */
    static List<String> casanovoInstallLines(String torchIndexUrl) {
        // Casanovo AND the PyArrow pin in one resolution so it is atomic: the launcher only
        // appears if the pin applied too. (A too-new PyArrow crashes Casanovo on import;
        // installing them separately could leave a launcher present but mis-pinned.)
        // $TB is deliberately unquoted: when empty it must expand to no argument at all.
        String pin = RemoteShell.shq(CasanovoInstaller.PYARROW_PIN);
        List<String> lines = new ArrayList<>();
        lines.add("UVLOG=\"$VENV.install.log\"; UVST=\"$VENV.install.status\"; rm -f \"$UVST\"");
        // Streamed through tee, not captured: this is the multi-minute step of the install and the
        // GUI's remote log must show it progressing. The status travels in its own file because a
        // pipeline reports tee's status, not uv's.
        lines.add("set +e");
        lines.add("{ \"$UV\" pip install --python \"$VENV/bin/python\" $TB casanovo " + pin
                + "; echo $? >\"$UVST\"; } 2>&1 | tee \"$UVLOG\"");
        lines.add("set -e");
        lines.add("UVEXIT=\"$(cat \"$UVST\" 2>/dev/null || echo unknown)\"");
        // An empty or half-written status file is not a status: the write can be cut short by an
        // OOM kill during the multi-gigabyte resolution, a dropped channel, or a full disk. Left
        // as "" it would read as "uv failed" and abort an install that had in fact succeeded.
        lines.add("case \"$UVEXIT\" in ''|*[!0-9]*) UVEXIT=unknown;; esac");
        lines.add("rm -f \"$UVST\"");
        // No status at all means the recipe itself could not run (the venv's directory went away
        // mid-install). Guessing either way would be worse than saying so.
        lines.add("if [ \"$UVEXIT\" = unknown ]; then");
        lines.add("  echo 'ERROR: could not determine whether Casanovo installed' >&2");
        lines.add("  rm -f \"$UVLOG\"; exit 7");
        lines.add("fi");
        lines.add("if [ \"$UVEXIT\" != 0 ]; then");
        lines.add("  [ -n \"$TB\" ] || { rm -f \"$UVLOG\"; exit 5; }");
        // Mirrors CasanovoInstaller.rejectedTheFlag: the complaint must name the flag AND be about
        // parsing it, or the failure was something else and must not trigger a second install.
        // grep matches per line, which is the point: the flag and the complaint about it have to
        // be in the same sentence, or an unrelated failure elsewhere in a multi-minute log would
        // pair up with a note that merely mentions the flag. The alternation is generated from
        // that method's own list rather than transcribed, so the two cannot drift apart.
        String complaints = String.join("|", CasanovoInstaller.TORCH_BACKEND_REJECTIONS);
        lines.add("  if ! tr 'A-Z' 'a-z' <\"$UVLOG\" | grep -qE "
                + "'torch-backend.*(" + complaints + ")"
                + "|(" + complaints + ").*torch-backend'; then");
        lines.add("    rm -f \"$UVLOG\"; exit 6");
        lines.add("  fi");
        lines.add("  echo 'uv rejected --torch-backend; falling back to driver detection'");
        if (torchIndexUrl != null) {
            // --reinstall-package: the flagged attempt may already have installed a CPU torch
            // before it failed, and uv would consider a bare `torch` requirement satisfied by it.
            lines.add("  \"$UV\" pip install --python \"$VENV/bin/python\" --reinstall-package "
                    + "torch torch --index-url " + RemoteShell.shq(torchIndexUrl));
        }
        lines.add("  \"$UV\" pip install --python \"$VENV/bin/python\" casanovo " + pin);
        lines.add("fi");
        lines.add("rm -f \"$UVLOG\"");
        return lines;
    }

    /** The stub's module name: {@code <name>.py} and {@code <name>.pth} sit in the venv's site-packages. */
    static final String DRAW_STUB = "casanovo_gui_nodraw";

    /**
     * The Python that stands in for {@code rdkit.Chem.Draw}: a {@code sys.meta_path} finder that
     * serves an empty module for it whose attributes raise a clear
     * {@code AttributeError} should anything ever call them. Nothing in a Casanovo run does; see {@link #rdkitDrawStubLines()}.
     */
    static final String DRAW_STUB_PY = """
            \"\"\"Written by Casanovo GUI. Stands in for rdkit.Chem.Draw on a host that lacks the X11
            libraries it links against (libXrender, libXext). Casanovo never draws a molecule; the
            module is merely imported, by DepthCharge. Delete this file and the .pth beside it to
            restore the real module.\"\"\"
            import sys
            from importlib.machinery import ModuleSpec


            class _NoDraw:
                \"\"\"A sys.meta_path finder. Duck-typed rather than deriving from importlib.abc,
                which drags in typing and ~67 other modules at every interpreter start in this
                venv; meta_path only ever calls the three methods below.\"\"\"

                def find_spec(self, name, path, target=None):
                    # Not a package: then `from rdkit.Chem.Draw import X` and `import
                    # rdkit.Chem.Draw.X` fail with a normal ImportError instead of quietly
                    # producing more empty modules.
                    if name == "rdkit.Chem.Draw":
                        return ModuleSpec(name, self)
                    return None

                def create_module(self, spec):
                    return None

                def exec_module(self, module):
                    # AttributeError, not ImportError: hasattr() and inspect.getmodule() probe every
                    # module in sys.modules for __file__ and swallow only AttributeError. Lightning
                    # runs that probe on load_from_checkpoint, so anything else kills the run.
                    def disabled(attr):
                        raise AttributeError(
                            "rdkit.Chem.Draw has no attribute %r: the module is disabled in this "
                            "Casanovo environment because the host lacks the X11 libraries it "
                            "needs (see casanovo_gui_nodraw.py)" % (attr,))
                    module.__getattr__ = disabled


            sys.meta_path.insert(0, _NoDraw())
            """;

    /**
     * Keep {@code casanovo} importable on a host that cannot load {@code rdkit.Chem.Draw}. Casanovo
     * imports DepthCharge, DepthCharge imports {@code rdkit.Chem.Draw} at module level, and that
     * module links the X11 client libraries even though nothing ever draws: on a headless cloud
     * image (Amazon Linux, Ubuntu Server) the run dies at import with
     * {@code ImportError: libXrender.so.1: cannot open shared object file}. Rather than ask for
     * root to install libraries nothing uses, this puts a stub in the venv that Python loads in
     * place of the real module &mdash; and only where the real one cannot load, so a host that has
     * the libraries keeps the real module. Runs on every job, not only after an install: the venv
     * is reused across runs, and the first run on a host is exactly when this shows.
     *
     * <p>Never fatal: if the check itself breaks, the run proceeds and Casanovo's own import
     * error remains the diagnosis, as before.</p>
     */
    private static void stubRdkitDrawIfUnloadable(SSHClient ssh, String venvDir,
                                                  BiConsumer<String, Boolean> toLog) {
        List<String> lines = new ArrayList<>();
        lines.add("set -e");
        lines.add("VENV=" + RemoteShell.shq(venvDir));
        lines.addAll(rdkitDrawStubLines());
        int code;
        try {
            code = RemoteShell.runStreamed(ssh, RemoteShell.bashLogin(String.join("\n", lines)), toLog);
        } catch (IOException e) {
            // Opening a second session can fail on its own (max-sessions reached, a dropped
            // channel). That must not fail a job whose venv is ready — least of all a reused one,
            // which before this check needed no second command at all.
            toLog.accept("[warn] Could not check rdkit.Chem.Draw on the remote host ("
                    + e.getMessage() + "); continuing.", false);
            return;
        }
        // Only a real non-zero status: runStreamed returns -1 when the server sent no exit status
        // at all, which happens on a clean run against some servers and is not evidence of failure.
        if (code > 0) {
            toLog.accept("[warn] Checking rdkit.Chem.Draw (and writing its stub into the "
                    + "site-packages of " + venvDir + ") exited " + code + "; continuing. Should "
                    + "the run now fail on a missing X11 library, this is why.", false);
        }
    }

    /**
     * The lines that decide whether the stub is needed and install or remove it accordingly.
     * Pure, so a test can run them against a stub {@code ldd}.
     *
     * <p>The test is {@code ldd} on the one extension module that fails to import: the OS itself
     * says whether it can be loaded here, in an instant, and the answer is not affected by a stub
     * already being in place (it was written as a {@code .pth} in site-packages, which is how
     * Python runs it at every interpreter start). Missing libraries: write the stub, always afresh
     * so it tracks this GUI version. None missing: remove any stub, so a host that gains the
     * libraries goes back to the real module.</p>
     *
     * <p>One module is the whole story: of the 63 Python extension modules in the rdkit
     * {@code manylinux} wheel, walking their dependency graph shows exactly one that reaches the
     * X11 client libraries &mdash; {@code rdkit/Chem/Draw/rdMolDraw2D.so}, through the bundled
     * libcairo. So probing it alone is not a sample, it is the complete set.</p>
     *
     * <p>Removal needs positive evidence, since it is the direction that can break a working host:
     * either {@code ldd} ran and reported everything resolved, or rdkit is not installed at all
     * (where a leftover finder could only shadow a future install). An {@code ldd} that is missing
     * or that failed proves nothing, and the stub stays.</p>
     *
     * <p>Expects {@code $VENV} to be set and runs under {@code set -e}.</p>
     */
    static List<String> rdkitDrawStubLines() {
        List<String> lines = new ArrayList<>();
        // site-packages, found without going through rdkit: the stub has to be removable on a host
        // where rdkit is gone, which is exactly when a leftover finder would intercept a future one.
        lines.add("SITE=\"\"; for d in \"$VENV\"/lib/python*/site-packages; do"
                + " if [ -d \"$d\" ]; then SITE=\"$d\"; break; fi; done");
        lines.add("if [ -n \"$SITE\" ]; then");
        lines.add("  STUB=\"$SITE/" + DRAW_STUB + "\"");
        lines.add("  RDK=\"\"; for f in \"$SITE\"/rdkit/Chem/Draw/rdMolDraw2D*.so; do"
                + " if [ -e \"$f\" ]; then RDK=\"$f\"; break; fi; done");
        lines.add("  MISSING=\"\"; PROBED=\"\"");
        lines.add("  if [ -n \"$RDK\" ] && command -v ldd >/dev/null 2>&1; then");
        // The `if` is what keeps `set -e` out of this: a failing ldd is an answer, not an abort.
        // Its status and a "=>" line are the evidence that it really analysed the file — without
        // them an ldd that died (a noexec mount, a missing loader) would read as "nothing missing"
        // and take the branch that DELETES a stub the host still needs.
        lines.add("    if LDD=\"$(ldd \"$RDK\" 2>/dev/null)\"; then");
        lines.add("      case \"$LDD\" in *'=>'*) PROBED=yes;; esac");
        lines.add("    fi");
        lines.add("    if [ -n \"$PROBED\" ]; then");
        // awk, not grep -o: the soname is the first field of "libXrender.so.1 => not found".
        lines.add("      MISSING=\"$(printf '%s\\n' \"$LDD\" | awk '/not found/ {print $1}' | sort -u"
                + " | tr '\\n' ' ')\"; MISSING=\"${MISSING% }\"");
        lines.add("    fi");
        lines.add("  fi");
        lines.add("  if [ -n \"$MISSING\" ]; then");
        // Write through a temp file and rename: a truncated .py paired with a live .pth would make
        // every interpreter start in this venv print a traceback from site.py, install no finder,
        // and leave Casanovo dying on the original ImportError while the log claimed otherwise.
        // The .py lands first, so the .pth that activates it never points at a half-written file.
        lines.add("    cat >\"$STUB.py.tmp\" <<'CASANOVO_GUI_STUB'");
        lines.addAll(List.of(DRAW_STUB_PY.split("\n")));
        lines.add("CASANOVO_GUI_STUB");
        lines.add("    mv -f \"$STUB.py.tmp\" \"$STUB.py\"");
        lines.add("    printf 'import " + DRAW_STUB + "\\n' >\"$STUB.pth.tmp\"");
        lines.add("    mv -f \"$STUB.pth.tmp\" \"$STUB.pth\"");
        lines.add("    echo \"rdkit.Chem.Draw disabled on this host (missing $MISSING);"
                + " Casanovo does not use it.\"");
        lines.add("  elif [ -e \"$STUB.pth\" ] || [ -e \"$STUB.py\" ]; then");
        // Remove only on evidence: the probe ran and found nothing missing, or rdkit itself is gone
        // so the stub can only shadow a future install. rdkit present but unprobeable (no ldd, or
        // an ldd that failed) says nothing, and the stub stays.
        lines.add("    if [ -n \"$PROBED\" ]; then");
        lines.add("      rm -f \"$STUB.pth\" \"$STUB.py\"");
        lines.add("      echo 'rdkit.Chem.Draw loads on this host; removed the stub.'");
        lines.add("    elif [ -z \"$RDK\" ]; then");
        lines.add("      rm -f \"$STUB.pth\" \"$STUB.py\"");
        lines.add("      echo 'No rdkit in this venv; removed the leftover rdkit.Chem.Draw stub.'");
        lines.add("    else");
        lines.add("      echo 'Could not test whether rdkit.Chem.Draw loads; keeping the stub.'");
        lines.add("    fi");
        lines.add("  fi");
        lines.add("fi");
        return lines;
    }

    /**
     * The remote NVIDIA driver version via {@code nvidia-smi}, or {@code null} when there is no
     * usable GPU (no {@code nvidia-smi}, or its output isn't a version). Fed to
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
