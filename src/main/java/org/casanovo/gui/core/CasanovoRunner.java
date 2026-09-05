package org.casanovo.gui.core;

import java.io.File;
import java.util.function.BiConsumer;

/**
 * Executes a {@link CasanovoCommand} in a background thread, streaming the
 * merged stdout/stderr to a listener and reporting completion.
 *
 * <p>Output is delivered as (text, transient) pairs. A <em>transient</em> chunk
 * (terminated by a bare carriage return {@code \r}) is a progress refresh that
 * should overwrite the previous one; a non-transient chunk (terminated by
 * {@code \n} or {@code \r\n}) is a committed line. This lets the UI render
 * tqdm/Lightning progress bars as a single updating line instead of thousands
 * of separate lines.</p>
 *
 * <p>Only one process may run at a time. Callbacks are invoked from the
 * background thread; UI code must marshal them onto its toolkit thread.</p>
 */
public class CasanovoRunner {

    private final ProcessSession session = new ProcessSession();

    /** True while a process is currently executing (or about to). */
    public boolean isRunning() {
        return session.isRunning();
    }

    /**
     * Launch the command asynchronously.
     *
     * @param command    the command to run
     * @param settings   execution settings (executable path / conda env)
     * @param workingDir working directory for the process, or {@code null}
     * @param onOutput   receives (text, isTransient); isTransient marks a progress refresh
     * @param onFinished receives (exitCode, throwable); throwable non-null only on
     *                   start/interrupt failure (exitCode -1 in that case)
     */
    public void start(CasanovoCommand command,
                      Settings settings,
                      File workingDir,
                      BiConsumer<String, Boolean> onOutput,
                      BiConsumer<Integer, Throwable> onFinished) {
        session.start(command.toProcessCommand(settings), workingDir,
                // Per-platform subprocess environment: the Windows-only guard against the hard
                // access-violation crash (0xC0000005) from the Intel MKL/OpenMP clash, the
                // Apple Silicon MPS CPU fallback, and — for a CPU run — hiding every GPU so
                // nothing in the stack can bind one (see Os.applyNativeEnv).
                pb -> Os.applyNativeEnv(pb, command.getAccelerator()),
                "Casanovo", "Check the executable path and Conda settings.",
                onOutput, onFinished);
    }

    /** Forcibly terminate the running process, if any. */
    public void cancel() {
        session.cancel();
    }
}
