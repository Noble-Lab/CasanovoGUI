package org.casanovo.gui.core;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * One subprocess, run in a background thread with its merged output streamed through
 * {@link OutputPump} and its completion reported once.
 *
 * <p>Shared by {@link CasanovoRunner} and {@link GlissadeRunner}: both need the same lifecycle
 * (start, stream, wait, cancel, report exactly once) and differ only in what they launch, how they
 * dress the environment and what they are called in an error message. Keeping one copy is what
 * stops a fix landing in one runner and not the other.</p>
 *
 * <p>Callbacks are invoked from the background thread; UI code must marshal them onto its toolkit
 * thread. Only one process may run at a time per instance.</p>
 */
class ProcessSession {

    private volatile Process process;
    private volatile boolean cancelled;
    /**
     * Set true synchronously inside {@link #start} (before the worker is even spawned), cleared in
     * the worker's {@code finally}. {@link #isRunning} reflects this flag so callers see a coherent
     * "busy" state immediately after {@code start} returns, closing the race in which
     * {@link #process} is still {@code null} because the worker has not yet called
     * {@code ProcessBuilder.start()}.
     */
    private volatile boolean active;

    /** True while a process is currently executing (or about to). */
    boolean isRunning() {
        return active;
    }

    /**
     * Launch {@code command} asynchronously.
     *
     * @param command     the OS command line
     * @param workingDir  working directory for the process, or {@code null}
     * @param environment applied to the {@link ProcessBuilder} before it starts
     * @param label       how the program is named in error messages, e.g. {@code "glissade"}
     * @param startHint   extra sentence appended to a start failure, or {@code ""}
     * @param onOutput    receives (text, isTransient); isTransient marks a progress refresh
     * @param onFinished  receives (exitCode, throwable); 130 after {@link #cancel()}, and exit code
     *                    -1 with a non-null throwable when the process could not start
     */
    synchronized void start(List<String> command,
                            File workingDir,
                            Consumer<ProcessBuilder> environment,
                            String label,
                            String startHint,
                            BiConsumer<String, Boolean> onOutput,
                            BiConsumer<Integer, Throwable> onFinished) {
        if (active) {
            throw new IllegalStateException("A " + label + " process is already running.");
        }
        cancelled = false;
        active = true;

        Thread worker = new Thread(() -> {
            int exitCode = -1;
            Throwable error = null;
            boolean started = false;
            try {
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                environment.accept(pb);
                if (workingDir != null && workingDir.isDirectory()) {
                    pb.directory(workingDir);
                }
                process = pb.start();
                started = true;
                // Cancel can be pressed before the process exists — start() returns as soon as the
                // worker is spawned. Without this the cancel would be silently dropped and the run
                // would continue to completion with the UI already saying "stopped".
                if (cancelled) {
                    process.destroy();
                }
                OutputPump.pump(process.getInputStream(), onOutput);
                exitCode = process.waitFor();
            } catch (IOException e) {
                if (started) {
                    // The pipe broke after a successful launch: reporting this as a start failure
                    // would send the user to check an executable path that plainly worked.
                    error = new IOException("Lost " + label + "'s output while it was running.\n"
                            + e.getMessage(), e);
                    exitCode = awaitQuietly();
                } else {
                    error = new IOException("Failed to start " + label + "."
                            + (startHint.isEmpty() ? "" : " " + startHint) + "\n" + e.getMessage(), e);
                }
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
        }, label.toLowerCase(java.util.Locale.ROOT) + "-runner");
        worker.setDaemon(true);
        worker.start();
    }

    /** The exit code of a process whose output stream failed, or -1 if it cannot be collected. */
    private int awaitQuietly() {
        Process p = process;
        if (p == null) {
            return -1;
        }
        try {
            return p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /** Terminate the running process, if any. */
    synchronized void cancel() {
        cancelled = true;
        Process p = process;
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(3, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
    }
}
