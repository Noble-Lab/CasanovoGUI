package org.casanovo.gui.core.exec;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Runs a {@link JobRequest} somewhere &mdash; locally today, over SSH (or a scheduler) later &mdash;
 * behind one contract so the run pipeline never branches on transport. Implementations start a job on
 * a background thread and stream output through the SAME {@code (text, isTransient)} contract as
 * {@code CasanovoRunner}, so the console/progress layer is reused unchanged. Callbacks are invoked
 * from a background thread; UI code must marshal them onto its toolkit thread.
 */
public interface ExecutionBackend {

    /**
     * Start the job asynchronously.
     *
     * @param request    the job (command + settings + working dir, plus inputs/outputDir to stage)
     * @param onOutput   receives {@code (text, isTransient)}; isTransient marks a progress refresh
     * @param onFinished receives {@code (exitCode, throwable)}; throwable non-null only on
     *                   start/interrupt failure (exitCode {@code -1}); cancellation reports exit {@code 130}
     * @param onStage    coarse staging progress (e.g. "Uploading 3/12…"); never called for local runs
     * @return a handle to poll/cancel the job
     */
    JobHandle start(JobRequest request,
                    BiConsumer<String, Boolean> onOutput,
                    BiConsumer<Integer, Throwable> onFinished,
                    Consumer<String> onStage);

    /** Short label for the UI, e.g. {@code "Local"} or {@code "Remote (user@host)"}. */
    String displayName();
}
