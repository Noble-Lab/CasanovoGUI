package org.casanovo.gui.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the installer does when {@code uv pip install --torch-backend=auto} fails.
 *
 * <p>The distinction this covers is expensive to get wrong in either direction. Treating a real
 * failure as a rejected flag sends the install down the legacy driver mapping &mdash; a second
 * multi-gigabyte download that pins the retired {@code cu121} index. Treating a rejected flag as
 * a real failure aborts an install that would have succeeded without it.</p>
 */
class TorchBackendFallbackTest {

    /** Line separator for the multi-line uv logs below. */
    private static final String SEP = Character.toString(10); // newline, no escape to mangle

    /** A runner that records what it was asked to run and returns a scripted result. */
    private static final class FakeUv extends CasanovoInstaller.Runner {
        private final List<List<String>> commands = new ArrayList<>();
        private final IOException failure;

        FakeUv(IOException failure) {
            super(null);
            this.failure = failure;
        }

        @Override
        String run(List<String> command, Path workDir) throws IOException {
            commands.add(List.copyOf(command));
            if (failure != null) {
                throw failure;
            }
            return "";
        }
    }

    private static CasanovoInstaller.Logger silentLog(Path dir) {
        return new CasanovoInstaller.Logger(dir.resolve("install.log"), null);
    }

    private static IOException uvFailure(String output) {
        return new CasanovoInstaller.CommandFailed(List.of("uv", "pip", "install"), 2, output);
    }

    // ---------------------------------------------------------------- what counts as a rejection

    @Test
    @DisplayName("Only a complaint that names the flag counts as the flag being rejected")
    void rejectionMustNameTheFlag() {
        // uv's argument parser and its preview gate both name it.
        assertTrue(CasanovoInstaller.rejectedTheFlag(
                "error: unexpected argument '--torch-backend' found"));
        assertTrue(CasanovoInstaller.rejectedTheFlag(
                "the `--torch-backend` option is only available in preview mode"));
        assertTrue(CasanovoInstaller.rejectedTheFlag("ERROR: unrecognized --torch-backend"));

        // Everything else is a real failure, however uv phrased it.
        assertFalse(CasanovoInstaller.rejectedTheFlag(null));
        assertFalse(CasanovoInstaller.rejectedTheFlag(""));
        assertFalse(CasanovoInstaller.rejectedTheFlag(
                "error: Failed to fetch: https://pypi.org/simple/casanovo/ (proxy 407)"));
        assertFalse(CasanovoInstaller.rejectedTheFlag(
                "error: No solution found when resolving dependencies for torch>=2.2"));
        assertFalse(CasanovoInstaller.rejectedTheFlag("error: failed to write to the cache: "
                + "No space left on device"));

        // The trap that survived a round: uv prints "error:" on every failure, and its own
        // driver-detection notes mention the flag — so "names the flag AND says error" is not
        // evidence of a rejection.
        assertFalse(CasanovoInstaller.rejectedTheFlag(
                "note: --torch-backend=auto selected the cu128 index; "
                        + "error: Failed to fetch https://download.pytorch.org/whl/cu128"));

        // Nor is it when the two land on different lines of a long install log: the complaint
        // has to be about the flag, in the same sentence.
        assertFalse(CasanovoInstaller.rejectedTheFlag(String.join(SEP,
                "note: --torch-backend=auto selected the cu128 index",
                "Resolved 84 packages in 1.20s",
                "error: Failed to fetch: the request timed out (preview of 3 retries)")));
        assertTrue(CasanovoInstaller.rejectedTheFlag(String.join(SEP,
                "Resolved 84 packages in 1.20s",
                "error: unexpected argument '--torch-backend' found",
                "  tip: a similar argument exists: --torch")));
    }

    @Test
    @DisplayName("The echoed command line is not evidence — it names the flag whatever went wrong")
    void theCommandLineIsNotTheComplaint() {
        // This is the trap the classifier was written for: CommandFailed's *message* repeats the
        // command, which always contains --torch-backend=auto, so classifying on the message
        // matched every failure and every install went down the cu121 fallback.
        IOException networkFailure = uvFailure("error: Failed to fetch https://pypi.org/simple/");
        assertTrue(networkFailure.getMessage().contains("uv pip install"),
                "the message echoes the command, as the installer log needs it to");
        assertFalse(CasanovoInstaller.rejectedTheFlag(
                ((CasanovoInstaller.CommandFailed) networkFailure).output()));
    }

    @Test
    @DisplayName("A command that asks for input gets EOF instead of hanging the installer")
    void promptingCommandDoesNotHang(@TempDir Path dir) {
        // uv can ask for an index credential or a keyring password. A GUI has no console to
        // answer at, so without a closed stdin the read never returns: `installing` stays true
        // and every guarded action is refused for the rest of the session.
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            CasanovoInstaller.Runner runner = new CasanovoInstaller.Runner(silentLog(dir));
            assertThrows(IOException.class, () ->
                    // WSL can retain its process working directory briefly after Bash exits;
                    // using the parent prevents that transient handle from blocking JUnit's
                    // immediate deletion of the directory it owns.
                    runner.run(List.of("bash", "-c", "read answer"), dir.getParent()),
                    "reading EOF must fail the command, not block it");
        });
    }

    // ---------------------------------------------------------------- what the installer then does

    @Test
    @DisplayName("A uv without the flag installs nothing here — the caller applies its own recipe")
    void unsupportedFlagLeavesTheInstallToTheCaller(@TempDir Path dir) throws Exception {
        FakeUv uv = new FakeUv(null);
        boolean matched = CasanovoInstaller.pipInstallMatchingTorch(dir.resolve("uv"), dir, uv,
                silentLog(dir), List.of("casanovo"), false);

        assertFalse(matched);
        assertTrue(uv.commands.isEmpty(),
                "installing here would put Casanovo in before the driver-matched torch");
    }

    @Test
    @DisplayName("With the flag supported, uv is asked to pick the backend in one resolution")
    void supportedFlagInstallsWithIt(@TempDir Path dir) throws Exception {
        FakeUv uv = new FakeUv(null);
        boolean matched = CasanovoInstaller.pipInstallMatchingTorch(dir.resolve("uv"), dir, uv,
                silentLog(dir), List.of("casanovo"), true);

        assertTrue(matched);
        assertEquals(1, uv.commands.size());
        List<String> command = uv.commands.get(0);
        assertEquals(List.of("pip", "install", "--torch-backend=auto", "casanovo"),
                command.subList(1, command.size()), "flag before the package, one resolution");
    }

    @Test
    @DisplayName("A rejected flag falls back quietly; the caller installs a matched stack instead")
    void rejectedFlagFallsBack(@TempDir Path dir) throws Exception {
        FakeUv uv = new FakeUv(uvFailure("error: unexpected argument '--torch-backend' found"));
        boolean matched = CasanovoInstaller.pipInstallMatchingTorch(dir.resolve("uv"), dir, uv,
                silentLog(dir), List.of("casanovo"), true);

        assertFalse(matched, "the caller must take over, driver-matched torch first");
        assertEquals(1, uv.commands.size(), "no second attempt is made here");
    }

    @Test
    @DisplayName("Any other failure surfaces instead of triggering a second full install")
    void realFailureIsNotSwallowed(@TempDir Path dir) {
        FakeUv uv = new FakeUv(uvFailure("error: Failed to fetch: connection timed out"));

        IOException thrown = assertThrows(IOException.class, () ->
                CasanovoInstaller.pipInstallMatchingTorch(dir.resolve("uv"), dir, uv,
                        silentLog(dir), List.of("casanovo"), true));
        assertTrue(thrown.getMessage().contains("connection timed out"),
                "the user must see the real reason, not a torch-backend warning");
    }
}
