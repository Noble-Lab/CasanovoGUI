package org.casanovo.gui.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the promise that selecting "cpu" actually prevents the run from touching a GPU.
 * Casanovo passes the accelerator to Lightning, but nothing below that stops another layer
 * from enumerating a CUDA device. Hiding every device through {@code CUDA_VISIBLE_DEVICES} is
 * what makes the choice binding.
 */
class OsEnvironmentTest {

    private static Map<String, String> envFor(String accelerator) {
        ProcessBuilder pb = new ProcessBuilder("echo");
        Os.applyNativeEnv(pb, accelerator);
        return pb.environment();
    }

    @Test
    @DisplayName("A CPU run hides every GPU from the subprocess")
    void cpuRunHidesGpus() {
        assertEquals(Os.NO_CUDA_DEVICES, envFor("cpu").get("CUDA_VISIBLE_DEVICES"));
        assertEquals(Os.NO_CUDA_DEVICES, envFor("  CPU  ").get("CUDA_VISIBLE_DEVICES"),
                "trimmed and case-insensitive");
    }

    @Test
    @DisplayName("An inherited CUDA_VISIBLE_DEVICES cannot defeat a CPU run")
    void cpuRunOverridesAnInheritedValue() {
        ProcessBuilder pb = new ProcessBuilder("echo");
        pb.environment().put("CUDA_VISIBLE_DEVICES", "0,1");
        Os.applyNativeEnv(pb, "cpu");
        assertEquals(Os.NO_CUDA_DEVICES, pb.environment().get("CUDA_VISIBLE_DEVICES"));
    }

    @Test
    @DisplayName("The hidden-GPU value survives all the way into the child process")
    void theValueReachesTheSubprocess() throws Exception {
        // Asserting on the ProcessBuilder map is not enough: Windows drops empty-valued entries
        // from a child's environment block, so the empty string this used to set arrived as
        // "unset" — every GPU visible, and an inherited value widened rather than cleared.
        ProcessBuilder pb = new ProcessBuilder(Os.isWindows()
                ? List.of("cmd", "/c",
                        "if defined CUDA_VISIBLE_DEVICES (echo [%CUDA_VISIBLE_DEVICES%])"
                                + " else (echo UNSET)")
                : List.of("sh", "-c", "echo \"[${CUDA_VISIBLE_DEVICES-UNSET}]\""));
        pb.redirectErrorStream(true);
        Os.applyNativeEnv(pb, "cpu");

        Process p = pb.start();
        String seen = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "the helper process should exit promptly");
        assertEquals("[" + Os.NO_CUDA_DEVICES + "]", seen,
                "the subprocess must actually see the value, not just the Java-side map");
    }

    @Test
    @DisplayName("Any other accelerator leaves device visibility alone")
    void nonCpuRunsAreUntouched() {
        for (String acc : new String[]{"auto", "gpu", "mps", null}) {
            ProcessBuilder pb = new ProcessBuilder("echo");
            pb.environment().remove("CUDA_VISIBLE_DEVICES");
            Os.applyNativeEnv(pb, acc);
            assertFalse(pb.environment().containsKey("CUDA_VISIBLE_DEVICES"),
                    "accelerator=" + acc + " must not hide the GPUs");
        }
    }

    @Test
    @DisplayName("The existing per-platform guards still apply on every path")
    void platformGuardsStillApply() {
        // Cleared first: these are putIfAbsent, so a value inherited from the developer's own
        // shell (e.g. PYTHONIOENCODING=utf-8:surrogateescape) would otherwise stand.
        ProcessBuilder pb = new ProcessBuilder("echo");
        for (String key : new String[]{"PYTHONIOENCODING", "FORCE_COLOR", "KMP_DUPLICATE_LIB_OK",
                "MKL_THREADING_LAYER", "PYTORCH_ENABLE_MPS_FALLBACK"}) {
            pb.environment().remove(key);
        }
        Os.applyNativeEnv(pb, "cpu");
        Map<String, String> env = pb.environment();
        assertEquals("utf-8", env.get("PYTHONIOENCODING"));
        assertEquals("1", env.get("FORCE_COLOR"));
        if (Os.isWindows()) {
            // Without these, Casanovo dies with a hard access violation (exit 0xC0000005).
            assertEquals("TRUE", env.get("KMP_DUPLICATE_LIB_OK"));
            assertEquals("SEQUENTIAL", env.get("MKL_THREADING_LAYER"));
        }
        if (Os.isMac() && Os.isAarch64()) {
            assertEquals("1", env.get("PYTORCH_ENABLE_MPS_FALLBACK"));
        }
    }

    @Test
    @DisplayName("A caller's own encoding choice is respected, unlike device visibility")
    void inheritedEncodingIsPreserved() {
        ProcessBuilder pb = new ProcessBuilder("echo");
        pb.environment().put("PYTHONIOENCODING", "utf-8:surrogateescape");
        Os.applyNativeEnv(pb, "cpu");
        assertEquals("utf-8:surrogateescape", pb.environment().get("PYTHONIOENCODING"));
    }

    @Test
    @DisplayName("The one-argument form stays exactly as it was")
    void singleArgumentFormIsUnchanged() {
        ProcessBuilder pb = new ProcessBuilder("echo");
        pb.environment().remove("CUDA_VISIBLE_DEVICES"); // inherited from this JVM on a GPU box
        Os.applyNativeEnv(pb);
        assertFalse(pb.environment().containsKey("CUDA_VISIBLE_DEVICES"));
        assertTrue(pb.environment().containsKey("PYTHONIOENCODING"));
    }
}
