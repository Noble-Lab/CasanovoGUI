package org.casanovo.gui.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the legacy driver &rarr; CUDA wheel mapping, which is now the fallback used only when
 * the available uv predates {@code --torch-backend}. Its limits are exactly why it was demoted:
 * it tops out at {@code cu121}, a retired index whose newest wheel is torch 2.5.1.
 */
class CasanovoInstallerTest {

    /** A subprocess fixture that produces no output and would otherwise keep readLine blocked. */
    public static final class SilentProcess {
        public static void main(String[] args) throws InterruptedException {
            Thread.sleep(30_000);
        }
    }

    /** Keeps talking forever, proving that output cannot reset a hard wall-clock deadline. */
    public static final class PeriodicProcess {
        public static void main(String[] args) throws InterruptedException {
            while (true) {
                System.out.println("still running");
                Thread.sleep(100);
            }
        }
    }

    /** Quiet but healthy: represents a very slow download that eventually completes. */
    public static final class DelayedProcess {
        public static void main(String[] args) throws InterruptedException {
            Thread.sleep(2_500);
        }
    }

    /** Exits successfully after one line; the test sink delays processing that line past drain timeout. */
    public static final class OneLineProcess {
        public static void main(String[] args) {
            System.out.println("finished");
        }
    }

    private static List<String> javaCommand(Class<?> mainClass) {
        Path java = Path.of(System.getProperty("java.home"), "bin",
                Os.isWindows() ? "java.exe" : "java");
        return List.of(java.toString(), "-cp", System.getProperty("java.class.path"),
                mainClass.getName());
    }

    @Test
    @DisplayName("No driver means no CUDA wheel index")
    void noDriverMeansCpu() {
        assertNull(CasanovoInstaller.cudaTorchIndexUrl(null));
    }

    @Test
    @DisplayName("Driver thresholds select the documented indexes")
    void driverThresholds() {
        assertNull(CasanovoInstaller.cudaTorchIndexUrl("470.00"), "too old for either CUDA build");
        assertTrue(CasanovoInstaller.cudaTorchIndexUrl("522.06").endsWith("cu118"));
        assertTrue(CasanovoInstaller.cudaTorchIndexUrl("530.99").endsWith("cu118"));
        assertTrue(CasanovoInstaller.cudaTorchIndexUrl("531.14").endsWith("cu121"));
        assertTrue(CasanovoInstaller.cudaTorchIndexUrl("560.35").endsWith("cu121"));
    }

    @Test
    @DisplayName("Every modern driver collapses to cu121 — the reason this mapping is only a fallback")
    void modernDriversAllCollapseToTheRetiredIndex() {
        // A 2026 driver on a current GPU still lands on cu121 (newest wheel: torch 2.5.1),
        // which carries no kernels for architectures released since. uv's --torch-backend=auto
        // is what resolves this properly; see CasanovoInstaller.pipInstallMatchingTorch.
        assertEquals(CasanovoInstaller.cudaTorchIndexUrl("531.14"),
                CasanovoInstaller.cudaTorchIndexUrl("595.95"));
    }

    @Test
    @DisplayName("CUDA and ROCm signatures are both GPU-enabled PyTorch builds")
    void gpuTorchBuildRecognitionIncludesRocm() {
        assertTrue(CasanovoInstaller.hasGpuTorchBuild("torch=2.8.0+cu130"));
        assertTrue(CasanovoInstaller.hasGpuTorchBuild("torch=2.8.0+ROCm6.4"));
        assertFalse(CasanovoInstaller.hasGpuTorchBuild("torch=2.8.0+cpu"));
        assertFalse(CasanovoInstaller.hasGpuTorchBuild(null));
    }

    @Test
    @Timeout(10)
    @DisplayName("A silent child is killed while its output stream is still open")
    void commandOutputReadHasADeadline(@TempDir Path dir) {
        List<String> command = javaCommand(SilentProcess.class);
        CasanovoInstaller.Runner runner = new CasanovoInstaller.Runner(
                new CasanovoInstaller.Logger(null, null), 1);

        CasanovoInstaller.CommandFailed failure = assertThrows(
                CasanovoInstaller.CommandFailed.class, () -> runner.run(command, dir));

        assertTrue(failure.output().contains("no output for 1 s"), failure.output());
    }

    @Test
    @Timeout(10)
    @DisplayName("Periodic output cannot extend a hard total deadline")
    void hardDeadlineCannotBeResetByOutput(@TempDir Path dir) {
        CasanovoInstaller.Runner runner = new CasanovoInstaller.Runner(
                new CasanovoInstaller.Logger(null, null), 0, 1, null);

        CasanovoInstaller.CommandFailed failure = assertThrows(
                CasanovoInstaller.CommandFailed.class,
                () -> runner.run(javaCommand(PeriodicProcess.class), dir));

        assertTrue(failure.output().contains("exceeded its 1 s deadline"), failure.output());
    }

    @Test
    @Timeout(10)
    @DisplayName("A slow silent installer can continue after the user chooses to wait")
    void softStallWarningCanContinue(@TempDir Path dir) throws Exception {
        AtomicInteger warnings = new AtomicInteger();
        CasanovoInstaller.Runner runner = new CasanovoInstaller.Runner(
                new CasanovoInstaller.Logger(null, null), 1, 0,
                (command, silent, elapsed) -> {
                    warnings.incrementAndGet();
                    return true;
                });

        runner.run(javaCommand(DelayedProcess.class), dir);

        assertTrue(warnings.get() >= 1, "the quiet command should ask before continuing");
    }

    @Test
    @Timeout(15)
    @DisplayName("Closing a slow output reader after successful exit does not turn success into failure")
    void deliberateReaderCloseIsNotAReadFailure(@TempDir Path dir) throws Exception {
        CasanovoInstaller.Logger logger = new CasanovoInstaller.Logger(null, line -> {
            if (line.equals("finished")) {
                try {
                    Thread.sleep(5_200); // Runner's normal drain join is five seconds
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        CasanovoInstaller.Runner runner = new CasanovoInstaller.Runner(logger, 0, 0, null);

        String output = runner.run(javaCommand(OneLineProcess.class), dir);

        assertTrue(output.contains("finished"), output);
    }

    @Test
    @DisplayName("A logger without a file supports quiet subprocess probes")
    void quietLoggerDoesNotRequireAFile() {
        List<String> lines = new ArrayList<>();
        CasanovoInstaller.Logger logger = new CasanovoInstaller.Logger(null, lines::add);

        logger.info("probe output");

        assertEquals(List.of("probe output"), lines);
    }
}
