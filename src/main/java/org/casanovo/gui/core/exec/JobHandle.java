package org.casanovo.gui.core.exec;

/** A running (or just-finished) Casanovo job: something to poll for liveness and to cancel. */
public interface JobHandle {

    /** Request cancellation; safe to call even after the job has already finished. */
    void cancel();

    /** True while the job is executing (or about to &mdash; see the backend's start-race handling). */
    boolean isRunning();
}
