package org.casanovo.gui.core.remote;

import org.casanovo.gui.core.CasanovoInstaller;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the generated remote-install shell against a stub {@code uv}.
 *
 * <p>This step is shell logic with traps a syntax check cannot see: the script runs under
 * {@code set -e}, where a failing command inside a pipeline's subshell aborts the group, and it
 * has to survive a host whose {@code TMPDIR} is unwritable. Both were real bugs; both look fine
 * in {@code sh -n} and in review.</p>
 */
class RemoteInstallScriptTest {

    /** Line separator for the multi-line uv logs below. */
    private static final String SEP = Character.toString(10); // newline, no escape to mangle

    private static final String INDEX = "https://download.pytorch.org/whl/cu121";

    /** {@code uv} as a shell function: records its arguments, and fails the flagged install on cue. */
    private static final String STUB_UV = """
            uvstub() {
              printf '%s\\n' "$*" >> "$CALLS"
              case "$*" in
                *"${FAIL_MATCH:---torch-backend}"*)
                  if [ -n "${FAIL_OUTPUT:-}" ]; then printf '%s\\n' "$FAIL_OUTPUT"; fi
                  return "${FAIL_CODE:-0}"
                  ;;
              esac
              return 0
            }
            if [ -n "${SILENCE_ECHO:-}" ]; then echo() { : ; }; fi
            UV=uvstub
            """;

    private record Result(int exitCode, String output, List<String> uvCalls) {
    }

    private static Result run(Path dir, String torchBackend, Map<String, String> env)
            throws IOException, InterruptedException {
        Path calls = dir.resolve("calls.log");
        Path venv = dir.resolve("venv");
        Files.createDirectories(venv.resolve("bin"));
        // WSL maps a Windows process working directory to /mnt/<drive>. Use the stable parent of
        // JUnit's per-test directory as that bridge: making `dir` itself the working directory
        // can leave it briefly locked when JUnit tries to delete it after Bash exits.
        Path bashWorkDir = dir.getParent();
        String bashDir = bashWorkDir.relativize(dir).toString().replace('\\', '/');

        List<String> script = new ArrayList<>();
        script.add("set -e");
        // ProcessBuilder environment additions do not cross the Windows-to-WSL boundary unless
        // WSLENV names each one. Put the controlled test variables in the script instead; this
        // also behaves identically under Git Bash and on Unix CI runners.
        script.add("export CALLS=" + RemoteShell.shq(bashDir + "/calls.log"));
        for (Map.Entry<String, String> entry : env.entrySet()) {
            script.add("export " + entry.getKey() + "=" + RemoteShell.shq(entry.getValue()));
        }
        script.add(STUB_UV);
        // Keep every path Bash sees relative to its working directory. On Windows the available
        // `bash` may be WSL, where C:/... is not a valid Linux path; relative paths also work
        // unchanged under Git Bash and on Unix CI runners.
        script.add("VENV=" + RemoteShell.shq(bashDir + "/venv"));
        script.add("TB=" + RemoteShell.shq(torchBackend));
        script.addAll(RemoteInstaller.casanovoInstallLines(INDEX));
        Path file = dir.resolve("install.sh");
        Files.writeString(file, String.join("\n", script) + "\n", StandardCharsets.UTF_8);

        // bash, because RemoteShell runs the real thing as `bash -lc`: verifying it under a
        // different shell would leave the production one unchecked.
        ProcessBuilder pb = new ProcessBuilder(BASH, bashDir + "/install.sh");
        pb.directory(bashWorkDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "the stub install should finish promptly");

        List<String> uvCalls = Files.exists(calls) ? Files.readAllLines(calls) : List.of();
        return new Result(p.exitValue(), out, uvCalls);
    }

    private static Map<String, String> failing(String output, int code) {
        Map<String, String> env = new HashMap<>();
        env.put("FAIL_OUTPUT", output);
        env.put("FAIL_CODE", Integer.toString(code));
        return env;
    }

    /** Candidates tried by {@link #findWorkingBash()}, in order, for the failure message. */
    private static final List<String> BASH_CANDIDATES = bashCandidates();

    /** The first candidate that actually runs, or {@code null} when none does. */
    private static final String BASH = findWorkingBash();

    /**
     * Where to look for a bash that runs. {@code bash} on PATH comes first and is the answer
     * everywhere but a Windows CI runner, where it resolves to {@code System32ash.exe} &mdash;
     * the WSL launcher, which exits non-zero when no distribution is installed. Git for Windows
     * ships a real bash that is not necessarily on PATH, so name it explicitly rather than
     * letting the WSL stub decide that bash is unavailable.
     */
    private static List<String> bashCandidates() {
        List<String> candidates = new ArrayList<>();
        candidates.add("bash");
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            for (String base : new String[] {
                    System.getenv("ProgramFiles"), System.getenv("ProgramW6432"),
                    System.getenv("ProgramFiles(x86)"), "C:/Program Files" }) {
                if (base != null && !base.isBlank()) {
                    // Forward slashes: Windows accepts them, and they keep this free of escapes.
                    candidates.add(base + "/Git/bin/bash.exe");
                    candidates.add(base + "/Git/usr/bin/bash.exe");
                }
            }
        }
        return List.copyOf(new LinkedHashSet<>(candidates));
    }

    /** The first candidate that starts and exits zero for {@code -c "exit 0"}. */
    private static String findWorkingBash() {
        for (String candidate : BASH_CANDIDATES) {
            try {
                Process p = new ProcessBuilder(candidate, "-c", "exit 0")
                        .redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                if (p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return candidate;
                }
            } catch (IOException e) {
                // Not installed at this path; try the next candidate.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * Fails rather than skips when no bash runs. Skipping would leave the only executable check
     * on the generated script silently unrun on a green build, and a working bash is present on
     * the development machine and on all three CI runners once the WSL stub is stepped over.
     */
    private static void requireBash() {
        assertNotNull(BASH, "a working bash is required to verify the generated remote-install "
                + "script; none of " + BASH_CANDIDATES + " ran");
    }

    @Test
    @DisplayName("The remote grep and CI both look for the installer's own list of uv complaints")
    void theRejectionPhrasesHaveOnePlaceToChange() throws IOException {
        // The rule "which uv complaint means the flag was rejected" is applied in three places:
        // CasanovoInstaller.rejectedTheFlag, the grep in the script below, and the CI smoke test.
        // The script generates its alternation from the constant; CI cannot, so assert it.
        String script = String.join(SEP, RemoteInstaller.casanovoInstallLines(INDEX));
        for (String phrase : CasanovoInstaller.TORCH_BACKEND_REJECTIONS) {
            assertTrue(script.contains(phrase), "the remote script must look for: " + phrase);
        }
        Path workflow = Path.of(".github", "workflows", "smoke.yml");
        assumeTrue(Files.isRegularFile(workflow), "only runs from the repository root");
        String yaml = Files.readString(workflow, StandardCharsets.UTF_8);
        for (String phrase : CasanovoInstaller.TORCH_BACKEND_REJECTIONS) {
            assertTrue(yaml.contains(phrase),
                    "smoke.yml would pass on a rule the application no longer applies: " + phrase);
        }
    }

    @Test
    @DisplayName("A successful flagged install installs once and does not fall back")
    void successInstallsOnce(@TempDir Path dir) throws Exception {
        requireBash();
        Result r = run(dir, "--torch-backend=auto", Map.of());

        assertEquals(0, r.exitCode(), r.output());
        assertEquals(1, r.uvCalls().size(), "one resolution, not two: " + r.uvCalls());
        assertTrue(r.uvCalls().get(0).contains("--torch-backend=auto"));
        assertTrue(r.uvCalls().get(0).contains("pyarrow"),
                "the pin travels with Casanovo, so the launcher never exists mis-pinned: "
                        + r.uvCalls().get(0));
    }

    @Test
    @DisplayName("An unwritable TMPDIR does not turn a successful install into a failure")
    void unwritableTmpdirDoesNotFailTheInstall(@TempDir Path dir) throws Exception {
        requireBash();
        // The log and status files used to live in TMPDIR. On a host where that is read-only —
        // routine on hardened HPC login nodes — tee and the status write both failed, the missing
        // status was read as "uv failed", and a perfectly good install exited 6.
        Result r = run(dir, "--torch-backend=auto",
                Map.of("TMPDIR", "./no-such-dir"));

        assertEquals(0, r.exitCode(), r.output());
        assertEquals(1, r.uvCalls().size());
    }

    @Test
    @DisplayName("A rejected flag falls back: matched torch first, then Casanovo")
    void rejectedFlagFallsBackInOrder(@TempDir Path dir) throws Exception {
        requireBash();
        // Under `set -e` the status of the failing install has to survive its own subshell, or
        // this classification never runs at all.
        Result r = run(dir, "--torch-backend=auto",
                failing("error: unexpected argument '--torch-backend' found", 2));

        assertEquals(0, r.exitCode(), r.output());
        assertEquals(3, r.uvCalls().size(), r.uvCalls().toString());
        assertTrue(r.uvCalls().get(1).matches(".*\\btorch\\b.*"),
                "the driver-matched torch goes in before Casanovo: " + r.uvCalls().get(1));
        assertFalse(r.uvCalls().get(1).contains("torchvision"),
                "nothing in Casanovo's dependency closure imports torchvision");
        assertTrue(r.uvCalls().get(1).contains(INDEX));
        assertTrue(r.uvCalls().get(1).contains("--reinstall-package torch"),
                "a torch the failed attempt already installed would otherwise satisfy the "
                        + "requirement and leave the CPU wheel in place: " + r.uvCalls().get(1));
        assertTrue(r.uvCalls().get(2).contains("casanovo"));
        assertTrue(r.uvCalls().get(2).contains("pyarrow"), "still one resolution with the pin");
        assertTrue(r.uvCalls().get(2).contains("--torch-backend") == false,
                "the retry drops the flag");
    }

    @Test
    @DisplayName("Any other failure aborts instead of downloading a second stack")
    void realFailureAborts(@TempDir Path dir) throws Exception {
        requireBash();
        Result r = run(dir, "--torch-backend=auto",
                failing("error: Failed to fetch https://pypi.org/simple/: connection timed out", 2));

        assertEquals(6, r.exitCode(), r.output());
        assertEquals(1, r.uvCalls().size(), "no fallback for a network failure: " + r.uvCalls());
    }

    @Test
    @DisplayName("A note about the flag elsewhere in the log does not make a failure the flag's fault")
    void flagMentionedElsewhereIsNotARejection(@TempDir Path dir) throws Exception {
        requireBash();
        // The log of a multi-minute install can mention --torch-backend in a note and fail, much
        // later and for an unrelated reason. Matching the whole log would send this down the
        // legacy index; matching per line keeps it a failure.
        Result r = run(dir, "--torch-backend=auto", failing(String.join(SEP,
                "note: --torch-backend=auto selected the cu128 index",
                "Resolved 84 packages in 1.20s",
                "error: Failed to fetch: the request timed out"), 2));

        assertEquals(6, r.exitCode(), r.output());
        assertEquals(1, r.uvCalls().size(), "no fallback: " + r.uvCalls());
    }

    @Test
    @DisplayName("A status file that exists but says nothing is unknown, not a failed install")
    void emptyStatusIsUnknown(@TempDir Path dir) throws Exception {
        requireBash();
        // Silencing echo leaves `echo $? >"$UVST"` creating the file and writing nothing — the
        // shape a write cut short by an OOM kill or a dropped channel leaves behind. Read as ""
        // it used to mean "uv failed", so a successful install was reported as exit 6 with its
        // log deleted.
        Map<String, String> env = new HashMap<>();
        env.put("SILENCE_ECHO", "1");
        Result r = run(dir, "--torch-backend=auto", env);

        assertEquals(7, r.exitCode(), "exit 7 says the outcome could not be determined");
        assertEquals(1, r.uvCalls().size(), "and nothing is retried on a guess: " + r.uvCalls());
    }

    @Test
    @DisplayName("Without the flag there is nothing to fall back from, so a failure is fatal")
    void failureWithoutTheFlagIsFatal(@TempDir Path dir) throws Exception {
        requireBash();
        Map<String, String> env = failing("error: Failed to fetch", 2);
        env.put("FAIL_MATCH", "casanovo"); // fail the install itself, whatever flags it carries

        Result r = run(dir, "", env);

        assertEquals(5, r.exitCode(), "exit 5 says the failure was not the flag's fault");
        assertEquals(1, r.uvCalls().size(), "and nothing is retried: " + r.uvCalls());
    }
}
