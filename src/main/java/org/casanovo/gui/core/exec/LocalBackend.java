package org.casanovo.gui.core.exec;

import org.casanovo.gui.core.CasanovoRunner;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Runs Casanovo as a local child process &mdash; the original behavior &mdash; by wrapping
 * {@link CasanovoRunner}. {@code inputs}, {@code outputDir} and {@code onStage} are ignored because
 * every path is already local; the run is otherwise identical to before the backend seam existed.
 */
public final class LocalBackend implements ExecutionBackend {

    private final CasanovoRunner runner = new CasanovoRunner();

    @Override
    public JobHandle start(JobRequest request,
                           BiConsumer<String, Boolean> onOutput,
                           BiConsumer<Integer, Throwable> onFinished,
                           Consumer<String> onStage) {
        runner.start(request.command(), request.settings(), request.workingDir(), onOutput, onFinished);
        return new JobHandle() {
            @Override
            public void cancel() {
                runner.cancel();
            }

            @Override
            public boolean isRunning() {
                return runner.isRunning();
            }
        };
    }

    @Override
    public String displayName() {
        return "Local";
    }
}
