package org.casanovo.gui.core;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Runs glissade in a background thread, streaming its merged output and reporting completion.
 *
 * <p>Independent of {@link CasanovoCommand} and {@link Settings}: glissade is not a Casanovo
 * sub-command and does not go through the Run-Casanovo bar. It follows the same output contract as
 * {@link CasanovoRunner} &mdash; {@code (text, isTransient)} chunks from {@link OutputPump} &mdash;
 * so the shared console renders it identically.</p>
 *
 * <p>The working directory is load-bearing, not incidental: glissade writes
 * {@code glissade_discoveries.tsv} into its process CWD and offers no output-directory flag, so
 * choosing the directory is the only way to choose where the result lands.</p>
 */
public class GlissadeRunner {

    private final ProcessSession session = new ProcessSession();

    /**
     * The command line, as a pure function of its inputs so it can be asserted in a test and
     * echoed to the console before launch. glissade takes four positionals in a fixed order and
     * one option; there is no output-directory, threshold or format flag.
     */
    public static List<String> command(Path exe, File denovo, File psms, File peptides,
                                       File fasta, int bootstraps) {
        List<String> cmd = new ArrayList<>();
        cmd.add(exe.toString());
        if (bootstraps > 0) {
            cmd.add("-n");
            cmd.add(Integer.toString(bootstraps));
        }
        cmd.add(denovo.getAbsolutePath());
        cmd.add(psms.getAbsolutePath());
        cmd.add(peptides.getAbsolutePath());
        cmd.add(fasta.getAbsolutePath());
        return cmd;
    }

    /** True while a glissade process is running (or about to be). */
    public boolean isRunning() {
        return session.isRunning();
    }

    /**
     * Launch {@code command} asynchronously with {@code workDir} as its working directory.
     *
     * @param onOutput   receives (text, isTransient) from the merged stdout/stderr
     * @param onFinished receives (exitCode, throwable); 130 after {@link #cancel()}, and exit
     *                   code -1 with a non-null throwable when the process could not start
     */
    public void start(List<String> command, File workDir,
                      BiConsumer<String, Boolean> onOutput,
                      BiConsumer<Integer, Throwable> onFinished) {
        session.start(command, workDir, GlissadeRunner::applyEnv, "glissade", "",
                onOutput, onFinished);
    }

    private static void applyEnv(ProcessBuilder pb) {
        Os.applyNativeEnv(pb);
        // applyNativeEnv sets FORCE_COLOR (for the run console's live Rich progress), but the FDR
        // console does not strip ANSI and parsePi0 reads these lines verbatim — a colour escape
        // next to the number would make it unparseable. NO_COLOR overrides FORCE_COLOR, giving
        // glissade plain text.
        pb.environment().put("NO_COLOR", "1");
    }

    /** Terminate the running process, if any. */
    public void cancel() {
        session.cancel();
    }
}
