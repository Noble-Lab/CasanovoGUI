package org.casanovo.gui.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlissadeRunnerTest {

    /** Stands in for a long glissade fit, which prints nothing for minutes. */
    public static final class SilentProcess {
        public static void main(String[] args) throws InterruptedException {
            Thread.sleep(30_000);
        }
    }

    /** Stands in for glissade refusing an input: it prints a line and exits <em>zero</em>. */
    public static final class UnsupportedFormatProcess {
        public static void main(String[] args) {
            System.out.println("Unsupported de novo file format");
        }
    }

    @Test
    @DisplayName("The command line is glissade's: -n first, then four positionals in order")
    void commandLayout(@TempDir Path dir) {
        File denovo = dir.resolve("run.mztab").toFile();
        File psms = dir.resolve("percolator.target.psms.txt").toFile();
        File peptides = dir.resolve("percolator.target.peptides.txt").toFile();
        File fasta = dir.resolve("human.fasta").toFile();
        List<String> cmd = GlissadeRunner.command(Path.of("glissade"), denovo, psms, peptides,
                fasta, 250);
        assertEquals(List.of("glissade", "-n", "250",
                denovo.getAbsolutePath(), psms.getAbsolutePath(),
                peptides.getAbsolutePath(), fasta.getAbsolutePath()), cmd);
    }

    @Test
    @DisplayName("A non-positive bootstrap count leaves -n off, so glissade uses its own default")
    void bootstrapsOmitted(@TempDir Path dir) {
        List<String> cmd = GlissadeRunner.command(Path.of("glissade"),
                dir.resolve("a").toFile(), dir.resolve("b").toFile(),
                dir.resolve("c").toFile(), dir.resolve("d").toFile(), 0);
        assertFalse(cmd.contains("-n"));
        assertEquals(5, cmd.size());
    }

    @Test
    @Timeout(30)
    @DisplayName("Cancel reports 130, not the process's own exit code")
    void cancelReports130() throws Exception {
        GlissadeRunner runner = new GlissadeRunner();
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger exit = new AtomicInteger(Integer.MIN_VALUE);
        // No working directory: on Windows a killed process keeps a handle on its CWD long enough
        // to defeat @TempDir's cleanup, which has nothing to do with what this test asserts.
        runner.start(javaCommand(SilentProcess.class), null,
                (text, isTransient) -> { },
                (code, err) -> {
                    exit.set(code);
                    done.countDown();
                });
        assertTrue(runner.isRunning());
        runner.cancel(); // immediately: Stop can be pressed before the process even exists
        assertTrue(done.await(20, TimeUnit.SECONDS), "the runner never reported completion");
        assertEquals(130, exit.get());
        assertFalse(runner.isRunning());
    }

    @Test
    @Timeout(30)
    @DisplayName("A refused input exits 0 with output — which is why exit code alone cannot mean success")
    void unsupportedFormatExitsZero(@TempDir Path dir) throws Exception {
        GlissadeRunner runner = new GlissadeRunner();
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger exit = new AtomicInteger(Integer.MIN_VALUE);
        StringBuilder out = new StringBuilder();
        runner.start(javaCommand(UnsupportedFormatProcess.class), dir.toFile(),
                (text, isTransient) -> out.append(text),
                (code, err) -> {
                    exit.set(code);
                    done.countDown();
                });
        assertTrue(done.await(20, TimeUnit.SECONDS));
        assertEquals(0, exit.get());
        assertTrue(out.toString().contains("Unsupported de novo file format"));
        assertFalse(dir.resolve(GlissadeDiscoveries.OUTPUT_FILE).toFile().isFile(),
                "no output file, despite exit 0");
    }

    private static List<String> javaCommand(Class<?> mainClass) {
        Path java = Path.of(System.getProperty("java.home"), "bin",
                Os.isWindows() ? "java.exe" : "java");
        return List.of(java.toString(), "-cp", System.getProperty("java.class.path"),
                mainClass.getName());
    }
}
