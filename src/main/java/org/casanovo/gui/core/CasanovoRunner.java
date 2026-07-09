package org.casanovo.gui.core;

import java.io.File;
import java.io.IOException;
import java.util.List;
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

    private volatile Process process;
    private volatile Thread worker;
    private volatile boolean cancelled;
    /**
     * Set true synchronously inside {@link #start} (before the worker is even
     * spawned), cleared in the worker's {@code finally}. {@link #isRunning}
     * reflects this flag so callers see a coherent "busy" state immediately
     * after {@code start} returns, closing the race in which {@link #process}
     * is still {@code null} because the worker has not yet called
     * {@code ProcessBuilder.start()}.
     */
    private volatile boolean active;

    /** True while a process is currently executing (or about to). */
    public boolean isRunning() {
        return active;
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
    public synchronized void start(CasanovoCommand command,
                                   Settings settings,
                                   File workingDir,
                                   BiConsumer<String, Boolean> onOutput,
                                   BiConsumer<Integer, Throwable> onFinished) {
        if (active) {
            throw new IllegalStateException("A Casanovo process is already running.");
        }
        cancelled = false;
        active = true;
        final List<String> osCommand = command.toProcessCommand(settings);

        worker = new Thread(() -> {
            int exitCode = -1;
            Throwable error = null;
            try {
                ProcessBuilder pb = new ProcessBuilder(osCommand);
                pb.redirectErrorStream(true);
                // Windows-only: prevent the hard access-violation crash (0xC0000005) from the
                // Intel MKL/OpenMP clash. No-op on Linux/macOS (see Os.applyNativeEnv).
                Os.applyNativeEnv(pb);
                if (workingDir != null && workingDir.isDirectory()) {
                    pb.directory(workingDir);
                }
                process = pb.start();
                OutputPump.pump(process.getInputStream(), onOutput);
                exitCode = process.waitFor();
            } catch (IOException e) {
                error = new IOException("Failed to start Casanovo. Check the executable path "
                        + "and Conda settings.\n" + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                error = e;
            } finally {
                Process finished = process;
                process = null;
                active = false;
                if (cancelled) {
                    onFinished.accept(130, null);
                } else {
                    onFinished.accept(error == null ? exitCode : -1, error);
                }
                if (finished != null && finished.isAlive()) {
                    finished.destroy();
                }
            }
        }, "casanovo-runner");
        worker.setDaemon(true);
        worker.start();
    }

    /** Forcibly terminate the running process, if any. */
    public synchronized void cancel() {
        cancelled = true;
        Process p = process;
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
    }
}
