package org.casanovo.gui.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the device-check decision table, which is what stands between a user and an opaque
 * PyTorch traceback partway through a run. Everything here is a pure function of a
 * {@link DeviceProbe.Report}, so no Python, GPU or display is needed.
 */
class DeviceProbeTest {

    // Every case whose outcome depends on the platform passes it explicitly: 'gpu' and 'mps'
    // resolve differently per platform (Metal on Apple Silicon, CUDA elsewhere), so leaving that
    // to the host would assert something different depending on where the suite runs. The cpu,
    // auto and unsupported-accelerator branches do not consult the platform and use the plain
    // two-argument overload.

    /** Platform line separator, so the marked probe output below needs no escapes. */
    private static final String NL = System.lineSeparator();

    /**
     * Parse probe output written the way {@code device_probe.py} writes it: every field line
     * carries {@link DeviceProbe#MARKER}. Tests state the fields; this adds the marker.
     */
    private static DeviceProbe.Report probe(String fields) {
        StringBuilder marked = new StringBuilder();
        fields.lines().filter(line -> !line.isBlank())
                .forEach(line -> marked.append(DeviceProbe.MARKER).append(line).append(NL));
        return DeviceProbe.parse(marked.toString());
    }

    /** A CPU-only wheel: no CUDA and no Metal compiled in. */
    private static DeviceProbe.Report cpuOnly() {
        return new DeviceProbe.Report("2.13.0+cpu", null, false, null, null,
                List.of(), false, false, null);
    }

    /** A CUDA wheel built for {@code archList}, seeing a device of architecture {@code deviceArch}. */
    private static DeviceProbe.Report cuda(String torch, String build, String deviceArch, String... archList) {
        boolean visible = deviceArch != null;
        return new DeviceProbe.Report(torch, build, visible,
                visible ? "NVIDIA RTX 5000 Ada Generation" : null, deviceArch,
                List.of(archList), false, false, null);
    }

    // ---------------------------------------------------------------- CPU never blocks

    @Test
    @DisplayName("Selecting CPU is always allowed, even on a wheel with no accelerator at all")
    void cpuAlwaysRuns() {
        DeviceProbe.Verdict v = DeviceProbe.validate("cpu", cpuOnly());
        assertEquals(DeviceProbe.Status.OK, v.status());
    }

    @Test
    @DisplayName("A missing or failed probe warns but never blocks a run")
    void unknownEnvironmentDoesNotBlock() {
        assertEquals(DeviceProbe.Status.WARN, DeviceProbe.validate("gpu", null, false).status());

        DeviceProbe.Report failed = new DeviceProbe.Report(null, null, false, null, null,
                List.of(), false, false, "cannot import torch: No module named 'torch'");
        DeviceProbe.Verdict v = DeviceProbe.validate("gpu", failed, false);
        assertEquals(DeviceProbe.Status.WARN, v.status());
        assertTrue(v.detail().contains("No module named"), "the reason should reach the user");
    }

    // ---------------------------------------------------------------- the reviewer's two failures

    @Test
    @DisplayName("GPU selected on a CPU-only wheel is blocked before Casanovo starts")
    void gpuOnCpuOnlyBuildIsBlocked() {
        DeviceProbe.Verdict v = DeviceProbe.validate("gpu", cpuOnly(), false);
        assertEquals(DeviceProbe.Status.BLOCK, v.status());
        assertTrue(v.offerCpu(), "falling back to the CPU would fix this");
        assertTrue(v.detail().contains("CPU-only build"));
    }

    @Test
    @DisplayName("A CUDA wheel too old for the installed GPU is blocked, not left to fail at run time")
    void cudaWheelWithoutKernelsForThisGpuIsBlocked() {
        // The pre-fix installer pinned every recent driver to cu121, whose newest wheel (torch
        // 2.5.1) stops at sm_90 — so a Blackwell card would fail with "no kernel image is
        // available for execution on the device" only once the run was already under way.
        DeviceProbe.Report r = cuda("2.5.1+cu121", "12.1", "sm_120",
                "sm_50", "sm_60", "sm_70", "sm_75", "sm_80", "sm_86", "sm_90");
        DeviceProbe.Verdict v = DeviceProbe.validate("gpu", r, false);
        assertEquals(DeviceProbe.Status.BLOCK, v.status());
        assertTrue(v.detail().contains("sm_120"));
        assertTrue(v.detail().contains("no kernel image"));
    }

    @Test
    @DisplayName("A wheel that never names this GPU's architecture still runs it, within a generation")
    void cubinsAreBinaryCompatibleWithinAMajorGeneration() {
        // Measured on the development machine: the cu121 wheel (torch 2.5.1) reports exactly
        // this arch list and never mentions sm_89, yet it runs perfectly on the RTX 5000 Ada
        // installed there. Blocking it would be a false positive on real, working hardware.
        DeviceProbe.Report ada = cuda("2.5.1+cu121", "12.1", "sm_89",
                "sm_50", "sm_60", "sm_61", "sm_70", "sm_75", "sm_80", "sm_86", "sm_90");
        assertEquals(DeviceProbe.Status.OK, DeviceProbe.validate("gpu", ada, false).status());
        assertEquals(DeviceProbe.Status.OK, DeviceProbe.validate("auto", ada).status());
    }

    @Test
    @DisplayName("Compatibility does not run backwards within a generation")
    void olderCardsCannotRunNewerCubins() {
        // sm_86 hardware cannot execute an sm_89 cubin, even though both are Ampere-era 8.x.
        DeviceProbe.Report r = cuda("2.13.0+cu130", "13.0", "sm_86", "sm_89", "sm_90");
        assertEquals(DeviceProbe.Status.BLOCK, DeviceProbe.validate("gpu", r, false).status());
    }

    @Test
    @DisplayName("A driver-matched CUDA wheel runs")
    void matchedCudaWheelRuns() {
        DeviceProbe.Report r = cuda("2.13.0+cu130", "13.0", "sm_89",
                "sm_75", "sm_80", "sm_86", "sm_89", "sm_90", "sm_120");
        DeviceProbe.Verdict v = DeviceProbe.validate("gpu", r, false);
        assertEquals(DeviceProbe.Status.OK, v.status());
        assertTrue(v.summary().contains("RTX 5000 Ada"));
    }

    @Test
    @DisplayName("PTX for an older architecture counts as support, since the driver can JIT it")
    void ptxForwardCompatibilityIsAccepted() {
        DeviceProbe.Report r = cuda("2.9.0+cu128", "12.8", "sm_120", "sm_90", "compute_90");
        assertEquals(DeviceProbe.Status.OK, DeviceProbe.validate("gpu", r, false).status());
    }

    @Test
    @DisplayName("An unknown architecture is not blocked — incomplete information must not stop a run")
    void incompleteArchInformationDoesNotBlock() {
        DeviceProbe.Report noArchList = cuda("2.13.0+cu130", "13.0", "sm_89");
        assertEquals(DeviceProbe.Status.OK, DeviceProbe.validate("gpu", noArchList, false).status());

        DeviceProbe.Report noCapability = cuda("2.13.0+cu130", "13.0", null, "sm_90");
        // No device visible at all is a different (and real) failure.
        assertEquals(DeviceProbe.Status.BLOCK, DeviceProbe.validate("gpu", noCapability, false).status());
    }

    @Test
    @DisplayName("A CUDA wheel with no visible device is blocked with an actionable message")
    void cudaBuildWithNoVisibleDeviceIsBlocked() {
        DeviceProbe.Report r = cuda("2.13.0+cu130", "13.0", null, "sm_90");
        DeviceProbe.Verdict v = DeviceProbe.validate("gpu", r, false);
        assertEquals(DeviceProbe.Status.BLOCK, v.status());
        assertTrue(v.offerCpu());
    }

    // ---------------------------------------------------------------- auto and mps

    @Test
    @DisplayName("auto falls back to the CPU, warning when a GPU build is installed but unusable")
    void autoFallsBackToCpu() {
        assertEquals(DeviceProbe.Status.OK, DeviceProbe.validate("auto", cpuOnly()).status());
        assertEquals(DeviceProbe.Status.OK, DeviceProbe.validate("", cpuOnly()).status());
        assertEquals(DeviceProbe.Status.OK, DeviceProbe.validate(null, cpuOnly()).status());

        DeviceProbe.Verdict v = DeviceProbe.validate("auto", cuda("2.13.0+cu130", "13.0", null, "sm_90"));
        assertEquals(DeviceProbe.Status.WARN, v.status(), "usable, but the user should know it is slow");
    }

    @Test
    @DisplayName("mps is blocked when the wheel or the machine cannot provide it")
    void mpsRequiresBothBuildAndDevice() {
        DeviceProbe.Report notBuilt = new DeviceProbe.Report("2.13.0", null, false, null, null,
                List.of(), false, false, null);
        assertEquals(DeviceProbe.Status.BLOCK, DeviceProbe.validate("mps", notBuilt, true).status());

        DeviceProbe.Report builtNotAvailable = new DeviceProbe.Report("2.13.0", null, false, null, null,
                List.of(), true, false, null);
        assertEquals(DeviceProbe.Status.BLOCK,
                DeviceProbe.validate("mps", builtNotAvailable, true).status());

        DeviceProbe.Report usable = new DeviceProbe.Report("2.13.0", null, false, null, null,
                List.of(), true, true, null);
        assertEquals(DeviceProbe.Status.OK, DeviceProbe.validate("mps", usable, true).status());
    }

    @Test
    @DisplayName("On Apple Silicon, 'gpu' is the Metal GPU — Lightning maps it to MPS there")
    void gpuOnAppleSiliconMeansMps() {
        // Lightning's gpu backend resolves to MPS on Apple Silicon, so an arm64 wheel with the
        // Metal backend can serve 'gpu'. Blocking it as "no GPU support" would refuse a run that
        // works, and the reinstall it advises cannot produce a CUDA build for this hardware.
        DeviceProbe.Report appleWheel = new DeviceProbe.Report("2.13.0", null, false, null, null,
                List.of(), true, true, null);
        DeviceProbe.Verdict v = DeviceProbe.validate("gpu", appleWheel, true);
        assertEquals(DeviceProbe.Status.OK, v.status());
        assertTrue(v.summary().contains("MPS"), "the user should be told which GPU it will use");
    }

    @Test
    @DisplayName("On Apple Silicon, 'gpu' on a Rosetta (x86) wheel is still blocked, and reinstall helps")
    void gpuOnAppleSiliconWithoutMetalIsBlocked() {
        DeviceProbe.Report rosetta = new DeviceProbe.Report("2.13.0", null, false, null, null,
                List.of(), false, false, null);
        DeviceProbe.Verdict v = DeviceProbe.validate("gpu", rosetta, true);
        assertEquals(DeviceProbe.Status.BLOCK, v.status());
        assertTrue(v.offerReinstall(), "a native arm64 build would provide the Metal backend");
    }

    @Test
    @DisplayName("An Intel Mac is told the truth too: macOS has no CUDA build to reinstall")
    void gpuOnIntelMacOffersNoReinstall() {
        // PyTorch stopped shipping macOS CUDA wheels years ago, so advising a multi-gigabyte
        // reinstall would send an Intel Mac user around the same loop Apple Silicon was spared.
        for (String acc : new String[]{"gpu", "cuda"}) {
            DeviceProbe.Verdict v = DeviceProbe.validate(acc, cpuOnly(), false, true);
            assertEquals(DeviceProbe.Status.BLOCK, v.status(), acc);
            assertFalse(v.offerReinstall(), acc + ": no macOS CUDA build exists to install");
            assertTrue(v.offerCpu(), acc + ": the CPU is still a way forward");
            assertTrue(v.summary().contains("Mac"), acc);
        }
    }

    @Test
    @DisplayName("An accelerator the GUI could not read warns — it is not silently treated as auto")
    void unknownAcceleratorWarnsRatherThanPassing() {
        // An unreadable external config is not the same as one that leaves the key unset: the
        // second means auto, the first means nobody knows, and a clean OK would be a lie.
        DeviceProbe.Report ada = cuda("2.5.1+cu121", "12.1", "sm_89", "sm_80", "sm_86", "sm_90");
        DeviceProbe.Verdict v = DeviceProbe.validate(DeviceProbe.UNKNOWN, ada, false);
        assertEquals(DeviceProbe.Status.WARN, v.status());
        assertTrue(v.detail().contains("config file"), "say which file could not be read");
        assertFalse(v.offerCpu(), "there is nothing wrong with the device to fall back from");
        assertFalse(v.offerReinstall(), "an unreadable file is not fixed by reinstalling PyTorch");

        assertEquals(DeviceProbe.Status.OK, DeviceProbe.validate(null, ada, false).status(),
                "an unset accelerator still means auto");

        // The sentinel must not be spellable in a config: a file that really says
        // "accelerator: unknown" is an accelerator this check does not cover, and is reported as
        // that rather than as a file it could not read.
        DeviceProbe.Verdict literal = DeviceProbe.validate("unknown", ada, false);
        assertTrue(literal.summary().contains("unknown"));
        assertFalse(literal.detail().contains("config file"),
                "a readable config naming an odd accelerator is not an unreadable config");
    }

    @Test
    @DisplayName("A failure always carries a reason, so it can never be mistaken for a good report")
    void everyFailureIsMarkedAsOne() {
        // error() is the only thing that marks a report failed: probe() refuses to cache such a
        // report and validate() turns it into a warning. A blank or missing reason — an
        // interrupted file read throws with no message at all — would otherwise produce an
        // all-empty "successful" report that caches as a CPU-only build for the whole session.
        assertNotNull(probe("error=").error(), "a blank reason is still a failure");
        assertNotNull(DeviceProbe.parse("").error(), "no output at all is a failure");
        assertTrue(DeviceProbe.validate("gpu", probe("error="), false).status()
                == DeviceProbe.Status.WARN, "and a failed report never blocks a run");
    }

    @Test
    @DisplayName("A GPU build with no visible device is cached briefly, never for the session")
    void gpuBuildWithoutDeviceGetsFiniteCacheLifetime() {
        // These states change without the environment changing at all — a laptop's dGPU still
        // powering up, a driver reload, CUDA_VISIBLE_DEVICES inherited from the parent — so they
        // may be remembered briefly but must not be cached for the full session.
        assertTrue(cuda("2.5.1+cu121", "12.1", null, "sm_90").gpuBuildWithoutDevice(),
                "a CUDA build with nothing visible");
        assertTrue(new DeviceProbe.Report("2.5.1+rocm6.2", null, false, null, null,
                List.of("gfx1100"), false, false, null).gpuBuildWithoutDevice(),
                "a ROCm build with nothing visible");
        assertTrue(new DeviceProbe.Report("2.13.0", null, false, null, null, List.of(),
                true, false, null).gpuBuildWithoutDevice(), "Metal built in but unavailable");

        // Everything else describes the installation itself, which only a reinstall changes.
        assertFalse(cuda("2.5.1+cu121", "12.1", "sm_89", "sm_89").gpuBuildWithoutDevice(),
                "a usable GPU");
        assertFalse(cpuOnly().gpuBuildWithoutDevice(), "a CPU-only wheel has no device to miss");
        assertFalse(new DeviceProbe.Report("2.13.0", null, false, null, null, List.of(),
                true, true, null).gpuBuildWithoutDevice(), "MPS present and available");

        DeviceProbe.Report absent = cuda("2.5.1+cu121", "12.1", null, "sm_90");
        assertTrue(DeviceProbe.cacheLifetimeNanos(absent) > 0);
        assertTrue(DeviceProbe.cacheLifetimeNanos(absent) < Long.MAX_VALUE,
                "a missing device must be retried after a finite interval");
        assertEquals(Long.MAX_VALUE, DeviceProbe.cacheLifetimeNanos(cpuOnly()),
                "stable environment facts remain cached until explicit invalidation");
    }

    @Test
    @DisplayName("A ROCm build is not a CPU-only build, and no reinstall turns it into CUDA")
    void rocmBuildWarnsInsteadOfBlocking() {
        // torch.version.cuda is None on a ROCm wheel, so it used to read as "CPU-only" and 'gpu'
        // was blocked with a multi-gigabyte reinstall offer that could not have helped.
        DeviceProbe.Report rocm = new DeviceProbe.Report("2.5.1+rocm6.2", null, true,
                "AMD Radeon Pro W7900", "sm_110", List.of("gfx1100"), false, false, null);
        assertFalse(rocm.isCpuOnlyBuild());
        DeviceProbe.Verdict v = DeviceProbe.validate("gpu", rocm, false);
        assertEquals(DeviceProbe.Status.WARN, v.status(), "unverifiable is not unusable");
        assertFalse(v.offerReinstall());
        assertTrue(v.summary().contains("ROCm"));

        // With no device visible there is nothing to run on, so that one does block.
        DeviceProbe.Report noDevice = new DeviceProbe.Report("2.5.1+rocm6.2", null, false,
                null, null, List.of(), false, false, null);
        DeviceProbe.Verdict blocked = DeviceProbe.validate("gpu", noDevice, false);
        assertEquals(DeviceProbe.Status.BLOCK, blocked.status());
        assertFalse(blocked.offerReinstall());
    }

    @Test
    @DisplayName("A CUDA device PyTorch cannot name is described, never printed as \"null\"")
    void unnamedDeviceIsNotNull() {
        // device_probe.py emits cuda_available independently of cuda_name, so a failed
        // get_device_name(0) leaves the name unset while the device is still usable.
        DeviceProbe.Report unnamed = new DeviceProbe.Report("2.5.1+cu121", "12.1", true,
                null, "sm_89", List.of("sm_89"), false, false, null);
        assertFalse(unnamed.summary().contains("null"), unnamed.summary());
        for (String acc : new String[]{"gpu", "auto"}) {
            DeviceProbe.Verdict v = DeviceProbe.validate(acc, unnamed, false);
            assertEquals(DeviceProbe.Status.OK, v.status(), acc);
            assertFalse(v.summary().contains("null"), v.summary());
        }
    }

    @Test
    @DisplayName("An unnamed device is described in the kernel-mismatch dialog too, never as \"null\"")
    void unnamedDeviceInTheMismatchMessage() {
        // device_probe.py guards get_device_name and get_device_capability separately, so the
        // capability can arrive while the name does not — and this message goes into a modal
        // that also offers a multi-gigabyte reinstall.
        DeviceProbe.Report unnamed = new DeviceProbe.Report("2.5.1+cu121", "12.1", true,
                null, "sm_120", List.of("sm_80", "sm_86", "sm_90"), false, false, null);
        DeviceProbe.Verdict v = DeviceProbe.validate("gpu", unnamed, false);

        assertEquals(DeviceProbe.Status.BLOCK, v.status());
        assertFalse(v.detail().contains("null"), v.detail());
        assertTrue(v.detail().contains("sm_120"), "it still says which architecture is missing");
    }

    @Test
    @DisplayName("An arch list naming no CUDA architecture is incomplete information, not a mismatch")
    void unrecognisedArchListDoesNotBlock() {
        // get_arch_list() does not always return sm_/compute_ entries — a HIP build lists gfx
        // targets — and a list we cannot interpret says nothing about kernel coverage. Treating
        // it as proof of a mismatch would block a GPU that works.
        DeviceProbe.Report odd = new DeviceProbe.Report("2.5.1", "12.1", true,
                "Some accelerator", "sm_110", List.of("gfx900", "gfx1100"), false, false, null);
        assertEquals(DeviceProbe.Status.OK, DeviceProbe.validate("gpu", odd, false).status());
        assertEquals(DeviceProbe.Status.OK, DeviceProbe.validate("auto", odd).status());
    }

    @Test
    @DisplayName("An Intel Mac asking for mps is not sent off to try the GPU it also lacks")
    void mpsOnIntelMacDoesNotSuggestCuda() {
        DeviceProbe.Verdict v = DeviceProbe.validate("mps", cpuOnly(), false, true);
        assertEquals(DeviceProbe.Status.BLOCK, v.status());
        assertFalse(v.detail().contains("NVIDIA"),
                "'gpu' would only be blocked in turn: macOS has no CUDA build either");
        assertFalse(v.offerReinstall());
    }

    @Test
    @DisplayName("Asking for CUDA by name on a Mac is a wrong choice, not a reinstallable one")
    void cudaOnAppleSiliconOffersNoReinstall() {
        DeviceProbe.Report appleWheel = new DeviceProbe.Report("2.13.0", null, false, null, null,
                List.of(), true, true, null);
        DeviceProbe.Verdict v = DeviceProbe.validate("cuda", appleWheel, true);
        assertEquals(DeviceProbe.Status.BLOCK, v.status());
        assertFalse(v.offerReinstall(), "no build of PyTorch provides CUDA on Apple Silicon");
        assertTrue(v.offerCpu(), "the CPU is still a way forward");
    }

    @Test
    @DisplayName("Off Apple Silicon, mps is a wrong choice — not a broken install to reinstall over")
    void mpsOffAppleSiliconIsNotAnInstallationProblem() {
        // A Windows/Linux machine has no Metal backend in any PyTorch build, so advising a
        // multi-gigabyte reinstall (or blaming Rosetta) would be actively misleading.
        DeviceProbe.Verdict v = DeviceProbe.validate("mps", cuda("2.5.1+cu121", "12.1", "sm_89",
                "sm_80", "sm_86", "sm_90"), false);
        assertEquals(DeviceProbe.Status.BLOCK, v.status());
        assertTrue(v.summary().contains("Apple Silicon"));
        assertFalse(v.detail().contains("Rosetta"), "Rosetta is meaningless off a Mac");
        assertTrue(v.offerCpu(), "the CPU is still a way forward");
        assertFalse(v.offerReinstall(), "no reinstall can add a Metal backend to a PC");
    }

    @Test
    @DisplayName("Reinstalling is offered exactly when it could fix the problem")
    void reinstallIsOfferedOnlyWhenItWouldHelp() {
        // A CPU-only wheel with a GPU selected, and a CUDA wheel too old for the card, are both
        // states a driver-matched reinstall repairs.
        assertTrue(DeviceProbe.validate("gpu", cpuOnly(), false).offerReinstall());
        assertTrue(DeviceProbe.validate("gpu", cuda("2.5.1+cu121", "12.1", "sm_120",
                "sm_80", "sm_86", "sm_90"), false).offerReinstall());
        // Hardware that simply lacks the device is not.
        for (String acc : new String[]{"tpu", "ipu", "hpu"}) {
            assertFalse(DeviceProbe.validate(acc, cpuOnly()).offerReinstall(),
                    acc + " cannot be installed into existence");
        }
    }

    @Test
    @DisplayName("Accelerators this check does not cover are passed through with a warning")
    void uncheckedAcceleratorsWarnButRun() {
        // Casanovo hands the accelerator to Lightning, which supports backends this probe knows
        // nothing about — and the Parameters dialog offers all three. Refusing them would make a
        // configuration that used to work unreachable from the GUI.
        for (String acc : new String[]{"tpu", "ipu", "hpu"}) {
            DeviceProbe.Verdict v = DeviceProbe.validate(acc, cpuOnly());
            assertEquals(DeviceProbe.Status.WARN, v.status(), acc + " must not be refused");
            assertTrue(v.summary().contains(acc));
            assertFalse(v.offerCpu(), acc + ": nothing to fall back from — the run proceeds");
            assertFalse(v.offerReinstall(), acc + " cannot be installed into existence");
        }
    }

    // ---------------------------------------------------------------- probe output parsing

    @Test
    @DisplayName("Probe output is parsed into a report")
    void parsesProbeOutput() {
        DeviceProbe.Report r = probe("""
                torch=2.13.0+cu130
                cuda_build=13.0
                cuda_available=True
                cuda_name=NVIDIA RTX 5000 Ada Generation
                cuda_arch=sm_89
                arch_list=sm_75;sm_80;sm_86;sm_90;sm_120
                mps_built=False
                mps_available=False
                """);
        assertNull(r.error());
        assertEquals("2.13.0+cu130", r.torchVersion());
        assertEquals("13.0", r.cudaBuild());
        assertTrue(r.cudaAvailable());
        assertEquals("sm_89", r.cudaArch());
        assertEquals(5, r.archList().size());
        assertFalse(r.isCpuOnlyBuild());
    }

    @Test
    @DisplayName("A CPU-only wheel is recognised as such from its probe output")
    void parsesCpuOnlyBuild() {
        // Exactly what a `+cpu` wheel reports — the state in which PyTorch raises
        // "Cannot access accelerator device when none is available."
        DeviceProbe.Report r = probe("""
                torch=2.13.0+cpu
                cuda_build=
                cuda_available=False
                mps_built=False
                mps_available=False
                """);
        assertNull(r.error());
        assertNull(r.cudaBuild());
        assertTrue(r.isCpuOnlyBuild());
    }

    @Test
    @DisplayName("A probe that could not run is reported as an error, not as 'no devices'")
    void parsesFailure() {
        DeviceProbe.Report r = probe("error=cannot import torch: No module named 'torch'");
        assertNotNull(r.error());
        assertTrue(r.error().contains("No module named"));

        assertNotNull(DeviceProbe.parse("").error(), "empty output is a failure, not a CPU machine");
    }

    @Test
    @DisplayName("An unmarked key=value line belongs to something else, not to the probe")
    void unmarkedLinesAreNotProbeFields() {
        // The probe merges stderr into stdout, and `conda run` shares that stream with the
        // environment's own banners and warnings. Reading unmarked lines let a plugin's
        // "error=..." fail a healthy probe outright, and a stray "cuda_available=true" invent a
        // device that would have suppressed a real block.
        DeviceProbe.Report r = DeviceProbe.parse(
                "error=a conda plugin could not load" + NL
                        + "cuda_available=true" + NL
                        + DeviceProbe.MARKER + "torch=2.13.0+cpu" + NL
                        + DeviceProbe.MARKER + "cuda_available=False" + NL);
        assertNull(r.error(), "the marked torch line says the probe itself ran");
        assertEquals("2.13.0+cpu", r.torchVersion());
        assertFalse(r.cudaAvailable(), "the unmarked line must not invent a device");
    }

    @Test
    @DisplayName("Output with no marked field at all is a failed probe, not a CPU machine")
    void unmarkedOutputAloneIsAFailure() {
        assertNotNull(DeviceProbe.parse("torch=2.13.0+cpu").error(),
                "an unmarked line is not the probe reporting a build");
    }

    @Test
    @DisplayName("The environment report names the build type, which is what bug reports need")
    void environmentReportDescribesTheBuild() {
        assertTrue(DeviceProbe.environmentReport(cpuOnly()).contains("CPU-only"));
        assertTrue(DeviceProbe.environmentReport(
                cuda("2.13.0+cu130", "13.0", "sm_89", "sm_89")).contains("CUDA 13.0"));
        String rocm = DeviceProbe.environmentReport(new DeviceProbe.Report(
                "2.5.1+rocm6.2", null, false, null, null, List.of("gfx1100"),
                false, false, null));
        assertTrue(rocm.contains("ROCm/HIP"));
        assertTrue(rocm.contains("ROCm device      : none visible"));
        assertTrue(DeviceProbe.environmentReport(null).contains("not available"));
    }

    @Test
    @DisplayName("The environment report names the machine, not just its instruction set")
    void environmentReportDescribesTheHardware() {
        // os.arch says "amd64" on every 64-bit x86 machine whoever built the chip, so the model
        // and the memory sizes are what make one bug report distinguishable from another.
        String report = DeviceProbe.environmentReport(cpuOnly());
        assertTrue(report.contains("CPU              : "), report);
        assertTrue(report.contains("System memory    : "), report);
        // Both lookups are platform-specific and may legitimately fail. What must hold whatever
        // this machine answers is that a failure reads as "unknown": a report about to be pasted
        // into an issue may not carry a "null", an empty value or a stray newline.
        DeviceProbe.environmentFields(cpuOnly()).forEach((label, value) -> {
            assertNotNull(value, label);
            assertFalse(value.isBlank(), label);
            assertFalse(value.contains("null"), label + " = " + value);
            assertEquals(value.strip(), value, label + " is padded or wrapped");
        });
        assertEquals("24.0 GB", DeviceProbe.gigabytes(24L << 30));
    }

    @Test
    @DisplayName("A value spanning lines keeps the report aligned")
    void multiLineValuesIndentToTheValueColumn() {
        // The value most likely to span lines is the one a bug report is filed about: a failed
        // probe carries the tail of the interpreter's traceback.
        java.util.LinkedHashMap<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("PyTorch", "not available (python exited with code 1\n"
                + "Traceback (most recent call last):\n  ImportError)");
        fields.put("CPU cores", "20");
        String[] lines = DeviceProbe.formatFields(fields).split("\n");
        assertEquals("PyTorch   : not available (python exited with code 1", lines[0]);
        assertEquals("            Traceback (most recent call last):", lines[1]);
        assertEquals("              ImportError)", lines[2]);
        assertEquals("CPU cores : 20", lines[3]);
    }

    @Test
    @DisplayName("GPU memory is reported when the probe measured it, and omitted when it did not")
    void environmentReportShowsDeviceMemory() {
        DeviceProbe.Report measured = new DeviceProbe.Report("2.13.0+cu130", "13.0", true,
                "NVIDIA RTX 5000 Ada Generation", "sm_89", 32L << 30,
                List.of("sm_89"), false, false, null);
        assertTrue(DeviceProbe.environmentReport(measured).contains("GPU memory       : 32.0 GB"));
        // An older PyTorch without get_device_properties reports 0: leave the line out rather
        // than claim the card has none.
        assertFalse(DeviceProbe.environmentReport(
                cuda("2.13.0+cu130", "13.0", "sm_89", "sm_89")).contains("GPU memory"));
    }

    @Test
    @DisplayName("Every line's colon sits in one column, including a row the caller adds")
    void reportLinesAlignOnTheLongestLabel() {
        // The dialog prepends its own "CasanovoGUI" row. With the width written into each line
        // that row sat a column out of step; measuring the labels present is what fixes it.
        java.util.LinkedHashMap<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("CasanovoGUI", "1.3.1");
        fields.putAll(DeviceProbe.environmentFields(
                cuda("2.13.0+cu130", "13.0", "sm_89", "sm_89")));
        String text = DeviceProbe.formatFields(fields);
        assertEquals(1, text.lines().map(line -> line.indexOf(" : ")).distinct().count(),
                "colons drift between rows:\n" + text);
        assertTrue(text.startsWith("CasanovoGUI      : 1.3.1\n"), text);
    }

    @Test
    @DisplayName("Device memory is parsed from the probe's byte count")
    void parsesDeviceMemory() {
        assertEquals(34359738368L, probe("""
                torch=2.13.0+cu130
                cuda_build=13.0
                cuda_available=True
                cuda_mem=34359738368
                """).cudaMemoryBytes());
        // A PyTorch that could not answer emits no line at all; one that answers strangely is
        // not worth failing a report over.
        assertEquals(0L, probe("torch=2.13.0+cpu").cudaMemoryBytes());
        assertEquals(0L, probe("torch=2.13.0+cu130\ncuda_mem=lots").cudaMemoryBytes());
    }
}
