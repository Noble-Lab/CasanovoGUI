package org.casanovo.gui.core;

import com.sun.management.OperatingSystemMXBean;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Asks the installed PyTorch what compute devices it can actually use, and checks that
 * answer against the accelerator the user selected <em>before</em> a run starts.
 *
 * <p>Casanovo's device selection happens deep inside PyTorch Lightning, so a mismatch
 * between the selected accelerator and the installed PyTorch build surfaces only as a
 * raw traceback partway through a run &mdash; for example
 * {@code "Cannot access accelerator device when none is available."}, which PyTorch raises
 * (from {@code at::accelerator::getAccelerator}) whenever the installed wheel has no
 * accelerator backend compiled in at all. Two failures are especially opaque:</p>
 *
 * <ul>
 *   <li><b>A CPU-only wheel with a GPU selected.</b> {@code torch.version.cuda} is
 *       {@code None}, so there is no CUDA support to fall back on, however good the GPU is.</li>
 *   <li><b>A CUDA wheel too old for the GPU.</b> The driver is detected, {@code torch.cuda}
 *       reports a device, and the run still dies with "no kernel image is available for
 *       execution on the device" because the wheel carries no kernels for that
 *       architecture. Comparing {@code get_arch_list()} against the device's compute
 *       capability catches this up front.</li>
 * </ul>
 *
 * <p>{@link #validate} is a pure function of a {@link Report} so the decision table can be
 * unit-tested without launching Python; {@link #probe} is the side-effecting part and
 * caches per (interpreter, installed torch version) so repeat runs cost nothing.</p>
 */
public final class DeviceProbe {

    /** How long to wait for the probe interpreter before giving up and reporting "unknown". */
    private static final long PROBE_TIMEOUT_SECONDS = 60;

    private DeviceProbe() {
    }

    /**
     * What the installed PyTorch reports about this machine.
     *
     * @param torchVersion   {@code torch.__version__}, e.g. {@code 2.13.0+cu130}
     * @param cudaBuild      {@code torch.version.cuda} (e.g. {@code "13.0"}); {@code null} on a
     *                       CPU-only wheel, which is the distinction that matters most here
     * @param cudaAvailable  {@code torch.cuda.is_available()}
     * @param cudaDeviceName name of CUDA device 0, or {@code null}
     * @param cudaArch       device 0's compute capability as {@code sm_XY}, or {@code null}
     * @param cudaMemoryBytes device 0's total memory in bytes, or 0 when the probe could not say
     * @param archList       architectures the wheel was built for ({@code torch.cuda.get_arch_list()})
     * @param mpsBuilt       whether the wheel has the Metal backend compiled in
     * @param mpsAvailable   {@code torch.backends.mps.is_available()}
     * @param error          why the probe could not run, or {@code null} on success
     */
    public record Report(String torchVersion,
                         String cudaBuild,
                         boolean cudaAvailable,
                         String cudaDeviceName,
                         String cudaArch,
                         long cudaMemoryBytes,
                         List<String> archList,
                         boolean mpsBuilt,
                         boolean mpsAvailable,
                         String error) {

        /** Without a device-memory figure: every report that has no CUDA device to measure. */
        public Report(String torchVersion, String cudaBuild, boolean cudaAvailable,
                      String cudaDeviceName, String cudaArch, List<String> archList,
                      boolean mpsBuilt, boolean mpsAvailable, String error) {
            this(torchVersion, cudaBuild, cudaAvailable, cudaDeviceName, cudaArch, 0L,
                    archList, mpsBuilt, mpsAvailable, error);
        }

        /** A wheel with no accelerator backend compiled in — the state that makes PyTorch's
         * "Cannot access accelerator device when none is available." reachable. */
        public boolean isCpuOnlyBuild() {
            return (cudaBuild == null || cudaBuild.isBlank()) && !mpsBuilt && !isRocmBuild();
        }

        /**
         * Whether this is an AMD ROCm/HIP wheel, recognised by the {@code +rocm} local version
         * every official ROCm build carries ({@code torch.version.cuda} is {@code None} there,
         * so it would otherwise read as a CPU-only build). A ROCm PyTorch compiled from source
         * without that suffix is indistinguishable from CPU-only here, which is the honest
         * limit of what the probe can tell: it reads {@code torch.version.cuda} only.
         */
        public boolean isRocmBuild() {
            return torchVersion != null && torchVersion.toLowerCase(Locale.ROOT).contains("rocm");
        }

        /**
         * CUDA device 0's name, or a stand-in: {@code device_probe.py} reports availability and
         * the name from separate guarded lookups, so a usable device can arrive unnamed.
         */
        String deviceName() {
            return (cudaDeviceName == null || cudaDeviceName.isBlank())
                    ? "an unnamed GPU" : cudaDeviceName;
        }

        /** How to name CUDA device 0 in a sentence, with its build, never printing "null". */
        String deviceDescription() {
            String name = deviceName();
            if (cudaBuild == null || cudaBuild.isBlank()) {
                return isRocmBuild() ? name + " (ROCm)" : name;
            }
            return name + " (CUDA " + cudaBuild + ")";
        }

        /**
         * A GPU build with no device to run on. Unlike everything else this report describes,
         * that can change while the environment stays exactly as it is: a laptop's discrete GPU
         * still powering up after resume, a driver being reloaded, {@code CUDA_VISIBLE_DEVICES}
         * set in the parent environment. It is therefore cached only briefly: enough to avoid a
         * cold torch import on every Run, but not long enough to require an application restart
         * after the device becomes available.
         */
        boolean gpuBuildWithoutDevice() {
            return (cudaBuild != null && !cudaBuild.isBlank() && !cudaAvailable)
                    || (isRocmBuild() && !cudaAvailable)
                    || (mpsBuilt && !mpsAvailable);
        }

        /** A one-line human summary for logs and the environment report. */
        public String summary() {
            if (error != null) {
                return "PyTorch device probe failed: " + error;
            }
            StringBuilder sb = new StringBuilder("PyTorch ").append(torchVersion);
            if (cudaBuild != null && !cudaBuild.isBlank()) {
                sb.append(" (CUDA ").append(cudaBuild).append(')');
                sb.append(cudaAvailable
                        ? " — GPU: " + deviceDescription() + (cudaArch == null ? "" : " [" + cudaArch + "]")
                        : " — no CUDA device visible");
            } else if (isRocmBuild()) {
                sb.append(" (ROCm/HIP)").append(cudaAvailable
                        ? " — GPU: " + deviceDescription()
                        : " — no ROCm device visible");
            } else if (mpsBuilt) {
                sb.append(" (Metal/MPS)").append(mpsAvailable ? " — MPS available" : " — MPS unavailable");
            } else {
                sb.append(" — CPU-only build");
            }
            return sb.toString();
        }
    }

    /**
     * The accelerator a run will use could not be determined &mdash; an external config file the
     * GUI could not read. Distinct from {@code null}/blank, which means "not set" and therefore
     * Casanovo's own default of {@code auto}: that one is knowable, this one is not, and passing
     * it off as {@code auto} would report a clean verdict for a device nobody checked.
     *
     * <p>Deliberately not a value a YAML file could plausibly hold, so a config that literally
     * says {@code accelerator: unknown} is still rejected as the unsupported accelerator it
     * is.</p>
     */
    public static final String UNKNOWN = "<unreadable config>";

    /** Whether a run may proceed, and how loudly to say so. */
    public enum Status {
        /** The selected accelerator is usable. */
        OK,
        /** Usable, but something is worth telling the user (logged, never modal). */
        WARN,
        /** The run would fail; stop before launching Casanovo. */
        BLOCK
    }

    /**
     * The outcome of checking a selected accelerator against a {@link Report}.
     *
     * @param status         whether to proceed
     * @param summary        short headline, suitable for a dialog title or status line
     * @param detail         what to do about it; empty when there is nothing to do
     * @param offerCpu       whether falling back to {@code accelerator: cpu} would fix it
     * @param offerReinstall whether reinstalling the managed environment could fix it. False
     *                       when the hardware simply cannot provide the device &mdash; asking
     *                       for MPS on a PC, say &mdash; where a reinstall would download
     *                       gigabytes and change nothing.
     */
    public record Verdict(Status status, String summary, String detail,
                          boolean offerCpu, boolean offerReinstall) {

        /** Most verdicts a CPU fallback can rescue are also fixable by reinstalling. */
        Verdict(Status status, String summary, String detail, boolean offerCpu) {
            this(status, summary, detail, offerCpu, offerCpu);
        }
    }

    // ------------------------------------------------------------------ decision table

    /**
     * Check the selected {@code accelerator} against what PyTorch reports. Pure and total:
     * a {@code null} or failed report yields {@link Status#WARN}, never a block, because an
     * unavailable probe must not stop a run that would otherwise have worked.
     *
     * @param accelerator the Casanovo {@code accelerator} setting ({@code auto}, {@code cpu},
     *                    {@code gpu}, {@code mps}, ...); blank is treated as {@code auto}
     * @param report      what {@link #probe} found, or {@code null} if it could not run
     */
    public static Verdict validate(String accelerator, Report report) {
        return validate(accelerator, report, Os.isMac() && Os.isAarch64(), Os.isMac());
    }

    /**
     * As {@link #validate(String, Report)}, with the platform supplied explicitly so the
     * decision table can be tested for every platform from any one of them.
     *
     * @param appleSilicon whether this machine could host the Metal (MPS) backend at all
     */
    static Verdict validate(String accelerator, Report report, boolean appleSilicon) {
        return validate(accelerator, report, appleSilicon, appleSilicon);
    }

    /**
     * As {@link #validate(String, Report, boolean)}, distinguishing an Intel Mac from a PC.
     *
     * @param mac whether this is a Mac of any kind. PyTorch ships no CUDA build for macOS at
     *            all, so on an Intel Mac a GPU request is a wrong choice rather than a broken
     *            install &mdash; the same reason Apple Silicon must not be offered a reinstall.
     */
    static Verdict validate(String accelerator, Report report, boolean appleSilicon, boolean mac) {
        String acc = (accelerator == null || accelerator.isBlank())
                ? "auto" : accelerator.trim().toLowerCase(Locale.ROOT);

        if (report == null || report.error() != null) {
            String why = (report == null) ? "the probe did not run" : report.error();
            return new Verdict(Status.WARN,
                    "Could not check the compute device.",
                    "CasanovoGUI could not ask PyTorch which devices are available (" + why
                            + "). The run will proceed; if it fails with a device error, "
                            + "select 'cpu' in Parameters → Accelerator.",
                    false);
        }

        return switch (acc) {
            // CPU always works, and the launcher additionally hides any CUDA device from the
            // subprocess (see Os.applyNativeEnv), so nothing in the stack can bind one.
            case "cpu" -> new Verdict(Status.OK, "Casanovo will run on the CPU.", "", false);
            // Lightning resolves 'gpu' per platform: the Metal (MPS) backend on Apple Silicon,
            // CUDA everywhere else. Checking it against CUDA on a Mac would refuse a run that
            // works and advise a reinstall that cannot produce a CUDA build for the hardware.
            case "gpu" -> appleSilicon ? validateMps(report, true, true) : validateCuda(report, mac);
            case "cuda" -> validateCuda(report, mac);
            // The GUI could not read the accelerator out of an external config, so there is
            // nothing to check it against. Say so and let the run proceed, as with a failed probe.
            case UNKNOWN -> new Verdict(Status.WARN,
                    "Could not read the accelerator from the config file.",
                    "CasanovoGUI could not read the supplied config file to see which accelerator "
                            + "it selects, so the device check was skipped. The run will proceed; if "
                            + "it fails with a device error, check the accelerator in that file.",
                    false);
            case "mps" -> validateMps(report, appleSilicon, mac);
            case "auto" -> validateAuto(report);
            // Casanovo passes the accelerator straight to Lightning, which supports backends
            // this probe knows nothing about (tpu, ipu, hpu — all offered in the Parameters
            // dialog). Not being able to check one is not a reason to refuse it: warn, and let
            // the run proceed, exactly as for a probe that could not run at all.
            default -> new Verdict(Status.WARN,
                    "CasanovoGUI cannot check the '" + acc + "' accelerator.",
                    "This build's device check covers 'cpu', 'gpu'/'cuda' and 'mps'; '" + acc
                            + "' is passed straight to PyTorch Lightning unchecked. The run will "
                            + "proceed; if it fails with a device error, select 'cpu' or 'auto' in "
                            + "Parameters → Accelerator.",
                    false, false);
        };
    }

    private static Verdict validateCuda(Report r, boolean mac) {
        if (r.isRocmBuild()) {
            // torch.cuda maps onto HIP on a ROCm build, so Lightning's 'gpu' does reach the AMD
            // card — this probe just cannot verify it (no CUDA build string, gfx architectures
            // it cannot compare). Unverifiable is not unusable: warn rather than block.
            return new Verdict(r.cudaAvailable() ? Status.WARN : Status.BLOCK,
                    r.cudaAvailable()
                            ? "CasanovoGUI cannot verify this AMD (ROCm) GPU."
                            : "No ROCm device is visible to PyTorch.",
                    "PyTorch " + r.torchVersion() + " is a ROCm/HIP build, which CasanovoGUI does "
                            + "not check. " + (r.cudaAvailable()
                                    ? "PyTorch reports a usable device, so the run will proceed; if "
                                            + "it fails with a device error, select 'cpu' in "
                                            + "Parameters → Accelerator."
                                    : "torch.cuda.is_available() is false, so there is no device to "
                                            + "run on — select 'cpu' in Parameters → Accelerator."),
                    !r.cudaAvailable(), false);
        }
        if (r.isCpuOnlyBuild() || r.cudaBuild() == null || r.cudaBuild().isBlank()) {
            if (mac) {
                // Nothing to reinstall: CUDA is NVIDIA's backend and PyTorch ships no macOS CUDA
                // build for any Mac, so this is a wrong choice rather than a broken install.
                return new Verdict(Status.BLOCK,
                        "CUDA is not available on a Mac.",
                        "CUDA is NVIDIA's backend, and no build of PyTorch provides it on macOS — "
                                + "Intel Macs included — so no reinstall can supply one. Select 'cpu' "
                                + "in Parameters → Accelerator; on an Apple Silicon Mac, 'gpu' uses "
                                + "the Metal GPU instead.",
                        true, false);
            }
            return new Verdict(Status.BLOCK,
                    "The installed PyTorch has no GPU support.",
                    "PyTorch " + r.torchVersion() + " is a CPU-only build — it was compiled without "
                            + "CUDA, so it cannot use a GPU no matter which one this machine has. "
                            + "Reinstall Casanovo from Settings to get a driver-matched GPU build, "
                            + "or select 'cpu' in Parameters → Accelerator.",
                    true);
        }
        if (!r.cudaAvailable()) {
            return new Verdict(Status.BLOCK,
                    "No usable CUDA device is visible to PyTorch.",
                    "PyTorch " + r.torchVersion() + " was built for CUDA " + r.cudaBuild()
                            + ", but torch.cuda.is_available() is false — usually a driver that is "
                            + "older than the build requires, a GPU switched off in a hybrid-graphics "
                            + "laptop, or CUDA_VISIBLE_DEVICES excluding every device. "
                            + "Select 'cpu' in Parameters → Accelerator to run without a GPU, or "
                            + "press Run again once the GPU is available — this answer is checked "
                            + "afresh every time, never remembered.",
                    true);
        }
        String mismatch = archMismatch(r);
        if (mismatch != null) {
            return new Verdict(Status.BLOCK, "This PyTorch build is too old for this GPU.", mismatch, true);
        }
        return new Verdict(Status.OK, "Casanovo will run on " + r.deviceDescription() + ".", "", false);
    }

    private static Verdict validateMps(Report r, boolean appleSilicon, boolean mac) {
        // Metal exists only on Apple Silicon, so on anything else this is a wrong choice rather
        // than a broken installation: no reinstall can produce the backend. An Intel Mac has
        // neither Metal nor CUDA, so pointing it at 'gpu' would only send the user round a loop.
        if (!appleSilicon) {
            return new Verdict(Status.BLOCK,
                    "MPS is available only on Apple Silicon Macs.",
                    "The 'mps' accelerator is Apple's Metal backend, which exists only on Apple "
                            + "Silicon hardware — no build of PyTorch provides it on this machine. "
                            + (mac
                                    ? "This Mac has neither Metal nor CUDA, so select 'cpu' in "
                                            + "Parameters → Accelerator."
                                    : "Select 'cpu', or 'gpu' if this computer has an NVIDIA GPU, "
                                            + "in Parameters → Accelerator."),
                    true, false);
        }
        if (!r.mpsBuilt()) {
            return new Verdict(Status.BLOCK,
                    "The installed PyTorch has no MPS (Metal) support.",
                    "PyTorch " + r.torchVersion() + " was compiled without the Metal backend. On an "
                            + "Apple Silicon Mac this is usually an x86 (Rosetta) PyTorch. Reinstalling "
                            + "should replace it with a native arm64 build; or select 'cpu' in "
                            + "Parameters → Accelerator.",
                    true, true);
        }
        if (!r.mpsAvailable()) {
            return new Verdict(Status.BLOCK,
                    "MPS is not available on this machine.",
                    "PyTorch has the Metal backend compiled in, but torch.backends.mps.is_available() "
                            + "is false, so the GPU cannot be used. Select 'cpu' in "
                            + "Parameters → Accelerator.",
                    true, false);
        }
        return new Verdict(Status.OK, "Casanovo will run on the Apple Silicon GPU (MPS).", "", false);
    }

    private static Verdict validateAuto(Report r) {
        if (r.cudaAvailable()) {
            String mismatch = archMismatch(r);
            if (mismatch != null) {
                return new Verdict(Status.BLOCK, "This PyTorch build is too old for this GPU.", mismatch, true);
            }
            return new Verdict(Status.OK, "Casanovo will run on " + r.deviceDescription() + ".",
                    "", false);
        }
        if (r.mpsAvailable()) {
            return new Verdict(Status.OK, "Casanovo will select the Apple Silicon GPU or the CPU.", "", false);
        }
        if (!r.isCpuOnlyBuild()) {
            return new Verdict(Status.WARN,
                    "Casanovo will run on the CPU.",
                    "A GPU build of PyTorch is installed, but no GPU device is visible, so 'auto' "
                            + "falls back to the CPU. Expect a long run on a large dataset.",
                    false);
        }
        return new Verdict(Status.OK, "Casanovo will run on the CPU.", "", false);
    }

    /**
     * Explain why the wheel cannot generate code for the installed GPU, or {@code null} when it
     * can (or when there is not enough information to be sure). A wheel runs on a device if it
     * carries that device's {@code sm_XY} kernels, or PTX ({@code compute_XY}) for an
     * architecture no newer than the device, which the driver can JIT-compile forward.
     */
    private static String archMismatch(Report r) {
        Integer device = archValue(r.cudaArch());
        if (device == null || r.archList() == null || r.archList().isEmpty()) {
            return null; // don't block on incomplete information
        }
        boolean namesCudaArch = false;
        for (String arch : r.archList()) {
            Integer value = archValue(arch);
            if (value == null) {
                continue;
            }
            namesCudaArch |= arch.startsWith("sm_") || arch.startsWith("compute_");
            // Cubins are binary-compatible upward within a major generation but never across
            // one: an sm_86 (Ampere) kernel runs on an sm_89 (Ada) card, which is why a cu121
            // wheel listing sm_50..sm_90 is fine on Ada even though it never mentions sm_89 —
            // but nothing in that list runs on an sm_120 (Blackwell) card.
            if (arch.startsWith("sm_") && value / 10 == device / 10 && value <= device) {
                return null;
            }
            // PTX, in contrast, the driver can JIT for any newer architecture, majors included.
            if (arch.startsWith("compute_") && value <= device) {
                return null;
            }
        }
        if (!namesCudaArch) {
            // Nothing in the list is a CUDA architecture — a ROCm/HIP wheel lists gfx targets —
            // so it says nothing about kernel coverage. Same rule as a missing list: don't block.
            return null;
        }
        return "PyTorch " + r.torchVersion() + " was built for " + String.join(", ", r.archList())
                + ", which does not include " + r.cudaArch() + " — the architecture of "
                + r.deviceName() + ". Running on the GPU would fail with \"no kernel image is "
                + "available for execution on the device\". Reinstall Casanovo from Settings to get "
                + "a PyTorch matched to this driver, or select 'cpu' in Parameters → Accelerator.";
    }

    /** {@code sm_90} / {@code compute_120} -&gt; {@code 90} / {@code 120}; {@code null} if unparseable. */
    private static Integer archValue(String arch) {
        if (arch == null) {
            return null;
        }
        String digits = arch.replaceAll("\\D+", "");
        if (digits.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ probing

    /** Re-check a missing device occasionally: it may appear after resume or a driver reload. */
    private static final long MISSING_DEVICE_CACHE_NANOS = TimeUnit.MINUTES.toNanos(5);

    /** A successful probe plus its expiry; stable environment facts use {@link Long#MAX_VALUE}. */
    private record CacheEntry(Report report, long expiresAtNanos) {
    }

    /** Cached successful reports, keyed by {@link #cacheKey}. */
    private static final Map<String, CacheEntry> CACHE = new LinkedHashMap<>();

    /** Bumped by {@link #invalidate}; guarded by {@code CACHE}. Lets a probe that was already
     * running when the environment was reinstalled discard its now-stale result. */
    private static int cacheGeneration;

    /** One lock per cache key, so concurrent callers share a probe instead of each launching an
     * interpreter (Help &rarr; Environment Report during a pre-run check). A {@link ReentrantLock}
     * rather than a monitor because the waiter must stay cancellable: a monitor ignores
     * interrupts, so Stop could not reach a check queued behind someone else's slow probe.
     * The map itself is guarded by {@code CACHE}. */
    private static final Map<String, ProbeLock> IN_FLIGHT = new LinkedHashMap<>();

    /**
     * A per-key lock plus a count of the threads that asked for it. The count decides when the
     * entry can go: testing the lock itself would race with a caller that has taken the object
     * from the map but not yet locked it, and dropping the entry under that caller lets the next
     * one create a second lock — and launch a second interpreter for the same environment.
     */
    private static final class ProbeLock {
        final ReentrantLock lock = new ReentrantLock();
        int interested; // guarded by CACHE
    }

    /** Give up an interest in {@code key}'s lock, removing it once nobody holds one. */
    private static void releaseProbeLock(String key, ProbeLock probeLock) {
        synchronized (CACHE) {
            if (--probeLock.interested <= 0) {
                IN_FLIGHT.remove(key, probeLock);
            }
        }
    }

    /** Return a live cached report, dropping a transient entry once its retry interval elapsed. */
    private static Report cachedReport(String key, long nowNanos) {
        CacheEntry entry = CACHE.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAtNanos() != Long.MAX_VALUE
                && nowNanos >= entry.expiresAtNanos()) {
            CACHE.remove(key);
            return null;
        }
        return entry.report();
    }

    /** Cache lifetime used by tests and by {@link #probe}; failures are never passed here. */
    static long cacheLifetimeNanos(Report report) {
        return report.gpuBuildWithoutDevice() ? MISSING_DEVICE_CACHE_NANOS : Long.MAX_VALUE;
    }

    /**
     * Ask the Python environment behind {@code settings} what devices it has. Successful
     * reports are cached per (interpreter, installed torch version). Stable environment facts
     * remain cached until invalidation; a GPU build with no visible device expires after a short
     * retry interval. Never throws: failures come back
     * as a {@link Report} carrying {@link Report#error()}, and are <em>not</em> cached &mdash; a
     * probe can fail for reasons that pass on the next attempt (antivirus holding the temp
     * script, a momentary Conda hiccup), and caching those would report a broken device check
     * for the rest of the session.
     */
    public static Report probe(Settings settings) {
        List<String> python = pythonCommand(settings);
        if (python.isEmpty()) {
            return failed("no Python interpreter could be located for the configured Casanovo");
        }
        String key = cacheKey(settings, python);
        synchronized (CACHE) {
            Report cached = cachedReport(key, System.nanoTime());
            if (cached != null) {
                return cached;
            }
        }
        // Deliberately probed without the CACHE lock held: the subprocess takes about a second
        // and can take much longer, while invalidate() is called from the installer's own thread
        // the moment an install finishes — holding that lock across the launch would block it
        // there. A per-key lock still serialises callers asking about the same environment, so
        // the second one waits for the first probe's result instead of launching its own.
        ProbeLock inFlight;
        synchronized (CACHE) {
            inFlight = IN_FLIGHT.computeIfAbsent(key, k -> new ProbeLock());
            inFlight.interested++;
        }
        Report fresh;
        try {
            inFlight.lock.lockInterruptibly();
        } catch (InterruptedException e) {
            releaseProbeLock(key, inFlight);
            Thread.currentThread().interrupt();
            return failed("interrupted");
        }
        int generation;
        try {
            synchronized (CACHE) {
                Report now = cachedReport(key, System.nanoTime()); // filled while we waited
                if (now != null) {
                    return now;
                }
                // Read here, not before the wait: an invalidate that happened while this thread
                // queued concerns the probe it waited for, not the one about to start — taking
                // the older value would throw away a result describing the current environment.
                generation = cacheGeneration;
            }
            fresh = runProbe(python);
            synchronized (CACHE) {
                // Published before the lock is released, or a waiter woken by that release would
                // re-check an empty cache and launch a second interpreter — the duplication this
                // whole mechanism exists to prevent.
                //
                // Not cached if the environment was reinstalled while we were probing: this
                // report describes the environment that install replaced, so it must not be
                // handed to the next run. It is still returned to this caller, whose check began
                // before the reinstall; the install guards keep those two from overlapping.
                if (fresh.error() == null && generation == cacheGeneration) {
                    long lifetime = cacheLifetimeNanos(fresh);
                    long expires = lifetime == Long.MAX_VALUE
                            ? Long.MAX_VALUE : System.nanoTime() + lifetime;
                    CACHE.put(key, new CacheEntry(fresh, expires));
                }
            }
        } finally {
            inFlight.lock.unlock();
            // Dropped here rather than in invalidate(): removing a lock another thread is holding
            // or waiting for would let it run a second interpreter for the same environment,
            // which is the duplication this map exists to prevent.
            releaseProbeLock(key, inFlight);
        }
        return fresh;
    }

    /** Drop cached reports so the next {@link #probe} re-runs — call after an install or update. */
    public static void invalidate() {
        synchronized (CACHE) {
            CACHE.clear();
            // IN_FLIGHT is deliberately untouched: its entries are removed by the threads that
            // used them, and a probe running right now still needs its lock to exclude the next
            // caller — even though the answer it produces will no longer be cached.
            cacheGeneration++;
        }
    }

    /**
     * The command that runs Python in the same environment as the configured Casanovo:
     * {@code conda run -n <env> python} for a Conda install, otherwise the interpreter beside
     * the executable in its venv. Empty when Casanovo is a bare {@code PATH} name, whose
     * interpreter we cannot locate without launching it.
     */
    private static List<String> pythonCommand(Settings settings) {
        if (settings.isUseConda() && !settings.getCondaEnv().isEmpty()) {
            return List.of(settings.getCondaExecutable(), "run", "--no-capture-output",
                    "-n", settings.getCondaEnv(), "python");
        }
        Optional<Path> venvRoot = PyVenv.venvRootForExecutable(settings.getCasanovoExecutable());
        if (venvRoot.isEmpty()) {
            return List.of();
        }
        Path python = Os.isWindows()
                ? venvRoot.get().resolve("Scripts").resolve("python.exe")
                : venvRoot.get().resolve("bin").resolve("python");
        return Files.isRegularFile(python) ? List.of(python.toString()) : List.of();
    }

    /**
     * Cache identity: the interpreter plus the installed torch version, so a reinstall invalidates.
     *
     * <p>The version is read from the venv that owns the configured executable, which a Conda or
     * bare-{@code PATH} install does not have — those key on the interpreter alone, so a torch
     * upgraded outside the GUI is not noticed until the cache is dropped (saving Settings, an
     * install or update, or a restart).</p>
     */
    private static String cacheKey(Settings settings, List<String> python) {
        String torch = PyVenv.venvRootForExecutable(settings.getCasanovoExecutable())
                .flatMap(root -> PyVenv.packageVersion(root, "torch"))
                .orElse("?");
        return String.join(" ", python) + "|torch=" + torch;
    }

    /** Classpath location of the probe script (also run verbatim by the CI smoke test). */
    private static final String PROBE_RESOURCE = "/org/casanovo/gui/device_probe.py";

    /**
     * The probe script, read from the classpath. Kept as a resource rather than a string
     * constant so the CI smoke test can execute the very same file on each platform instead of
     * a copy that could drift from it.
     */
    private static String probeScript() throws IOException {
        try (InputStream in = DeviceProbe.class.getResourceAsStream(PROBE_RESOURCE)) {
            if (in == null) {
                throw new IOException("missing resource " + PROBE_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }


    private static Report runProbe(List<String> python) {
        Path script = null;
        Path output = null;
        Process process = null;
        try {
            script = Files.createTempFile("casanovo-gui-device-probe", ".py");
            script.toFile().deleteOnExit();
            Files.writeString(script, probeScript(), StandardCharsets.UTF_8);
            output = Files.createTempFile("casanovo-gui-device-probe", ".txt");
            output.toFile().deleteOnExit();

            List<String> command = new ArrayList<>(python);
            command.add(script.toString());
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            // Collected through a file rather than the pipe: reading the pipe to EOF here would
            // block until the process exits, so the timeout below could never fire on a probe
            // that hangs (a stalled `conda run`, a wedged torch import on a broken driver).
            pb.redirectOutput(output.toFile());
            // A GUI has no terminal from which to answer a conda/channel prompt. Give every
            // probe immediate EOF instead of letting an unexpected prompt consume the full
            // timeout on every run (failed probes are deliberately not cached).
            pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File(
                    Os.isWindows() ? "NUL" : "/dev/null")));
            // Same subprocess guards Casanovo itself gets: on Windows the MKL/OpenMP workaround
            // matters here too, since importing torch is exactly what triggers that crash.
            Os.applyNativeEnv(pb);
            pb.environment().putIfAbsent("NO_COLOR", "1");
            // Belt and braces with the script's own flush=True: whatever the interpreter printed
            // on its way down is the only clue a hard crash leaves behind.
            pb.environment().putIfAbsent("PYTHONUNBUFFERED", "1");

            process = pb.start();
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return failed("the probe timed out after " + PROBE_TIMEOUT_SECONDS + " s");
            }
            if (process.exitValue() != 0) {
                // The output file holds whatever the interpreter managed to say — a Windows fault
                // code's accompanying text, a partial traceback — which is the only actionable
                // part of a crash like the PyArrow/pylance access violation (0xC0000005).
                return failed("python exited with code " + process.exitValue() + tail(output));
            }
            // Decoded from bytes rather than Files.readString: stderr is merged into this file
            // and native libraries under torch write locale-encoded bytes to it, which a strict
            // UTF-8 decode would reject — turning a healthy probe into a reported failure.
            return parse(new String(Files.readAllBytes(output), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return failed(e.toString()); // toString: some IO exceptions carry only a type
        } catch (InterruptedException e) {
            // Cancelled from the GUI: take the interpreter down with the wait, or it keeps
            // importing torch in the background long after the user pressed Stop.
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            return failed("interrupted");
        } finally {
            deleteQuietly(script);
            deleteQuietly(output);
        }
    }

    /** The last few lines of {@code file}, for a failure message; empty when unreadable. */
    private static String tail(Path file) {
        try {
            return Text.tail(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), 400);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /** Best-effort temp-file cleanup; {@code deleteOnExit} is the backstop. */
    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // temp file; deleteOnExit will catch it
        }
    }

    /**
     * The prefix {@code device_probe.py} puts on every field line, and the only lines this
     * parser reads. Must match {@code MARKER} in that script (and the CI smoke test, which
     * parses the same output).
     */
    static final String MARKER = "casanovo-probe:";

    /** Parse the probe's {@code key=value} lines. Package-private so tests can exercise it directly. */
    static Report parse(String output) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            // Only the script's own marked lines: stdout and stderr are merged here, and a
            // `conda run` interpreter shares the stream with the environment's banners and
            // warnings, so an unmarked "key=value" belongs to something else. Reading those let
            // an unrelated "error=" fail a healthy probe and an unrelated "cuda_available=true"
            // invent a device that would suppress a real block.
            if (!line.startsWith(MARKER)) {
                continue;
            }
            String field = line.substring(MARKER.length());
            int eq = field.indexOf('=');
            if (eq > 0) {
                values.put(field.substring(0, eq).trim(), field.substring(eq + 1).trim());
            }
        }
        if (!values.containsKey("torch")) {
            // The script emits error= only when torch cannot be imported, and stops there, so a
            // report without a torch line is a failed probe whatever else it says.
            return failed(values.getOrDefault("error", "the probe produced no output"));
        }
        String cudaBuild = emptyToNull(values.get("cuda_build"));
        List<String> archList = new ArrayList<>();
        String arches = values.get("arch_list");
        if (arches != null && !arches.isBlank()) {
            for (String a : arches.split(";")) {
                if (!a.isBlank()) {
                    archList.add(a.trim());
                }
            }
        }
        return new Report(
                values.get("torch"),
                cudaBuild,
                Boolean.parseBoolean(values.getOrDefault("cuda_available", "false")),
                emptyToNull(values.get("cuda_name")),
                emptyToNull(values.get("cuda_arch")),
                parseBytes(values.get("cuda_mem")),
                List.copyOf(archList),
                Boolean.parseBoolean(values.getOrDefault("mps_built", "false")),
                Boolean.parseBoolean(values.getOrDefault("mps_available", "false")),
                null);
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /** A byte count the probe emitted, or 0 when it emitted none this PyTorch could produce. */
    private static long parseBytes(String s) {
        try {
            return (s == null || s.isBlank()) ? 0L : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static Report failed(String why) {
        // error() is what marks a report as failed — probe() refuses to cache such a report and
        // validate() turns it into a warning. Some exceptions carry no message at all (an
        // interrupted file operation throws ClosedByInterruptException with getMessage() == null),
        // and passing that through would produce a "successful" all-empty report that then gets
        // cached and blocks every GPU run for the rest of the session.
        return new Report(null, null, false, null, null, List.of(), false, false,
                (why == null || why.isBlank()) ? "the probe failed without a message" : why);
    }

    // ------------------------------------------------------------------ reporting

    /**
     * The whole environment report as one aligned block, so a user filing a bug can paste the
     * machine's actual configuration rather than describing it. The dialog composes the two
     * halves itself &mdash; {@link #environmentFields} to lay the rows out, {@link #formatFields}
     * for what its Copy button puts on the clipboard &mdash; because it prepends a row of its own.
     */
    public static String environmentReport(Report r) {
        return formatFields(environmentFields(r));
    }

    /**
     * The same report as label &rarr; value pairs in display order, for a caller that lays the
     * rows out itself (the dialog puts them in a grid, in the application's own font) rather
     * than showing the aligned text {@link #formatFields} produces for the clipboard.
     */
    public static Map<String, String> environmentFields(Report r) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("Operating system", System.getProperty("os.name")
                + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ")");
        fields.put("CPU", cpuModel());
        fields.put("CPU cores", String.valueOf(Runtime.getRuntime().availableProcessors()));
        fields.put("System memory", systemMemory());
        if (r == null || r.error() != null) {
            fields.put("PyTorch", "not available ("
                    + (r == null ? "probe did not run" : r.error()) + ")");
            return fields;
        }
        fields.put("PyTorch", r.torchVersion());
        fields.put("PyTorch build", r.cudaBuild() != null ? "CUDA " + r.cudaBuild()
                : r.isRocmBuild() ? "ROCm/HIP"
                : r.mpsBuilt() ? "Metal (MPS)" : "CPU-only");
        if (r.cudaBuild() != null || r.isRocmBuild()) {
            fields.put(r.isRocmBuild() ? "ROCm device" : "CUDA device",
                    r.cudaAvailable()
                            ? r.deviceDescription() + (r.cudaArch() == null ? "" : " (" + r.cudaArch() + ")")
                            : "none visible");
            if (r.cudaMemoryBytes() > 0) {
                fields.put("GPU memory", gigabytes(r.cudaMemoryBytes()));
            }
            if (!r.archList().isEmpty()) {
                fields.put("Built for", String.join(", ", r.archList()));
            }
        }
        if (r.mpsBuilt()) {
            fields.put("MPS available", String.valueOf(r.mpsAvailable()));
        }
        return fields;
    }

    /**
     * Fields as {@code label : value} lines, every colon in one column. The width is measured
     * from the labels present rather than written into each line, so a caller that adds a row of
     * its own (the dialog prepends the GUI's version) cannot leave it a column out of step.
     */
    public static String formatFields(Map<String, String> fields) {
        int width = fields.keySet().stream().mapToInt(String::length).max().orElse(0);
        String continuation = " ".repeat(width + 3); // where the values start: label, " : ", value
        StringBuilder sb = new StringBuilder();
        fields.forEach((label, value) -> sb.append(String.format(Locale.ROOT, "%-" + width + "s", label))
                .append(" : ")
                // A value can span lines — a failed probe carries the tail of the interpreter's
                // traceback — and a continuation line at column 0 would break the alignment this
                // method exists to guarantee, on the one report that most needs to stay readable.
                .append(value == null ? "" : value.replaceAll("\\R", "\n" + continuation))
                .append('\n'));
        return sb.toString();
    }

    /** A byte count in GB, 1024-based — how both GPU vendors and the OS state memory sizes. */
    static String gigabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f GB", bytes / (double) (1L << 30));
    }

    /**
     * The CPU's marketing name — "Intel(R) Core(TM) Ultra 7 265K", say. The OS line can only give
     * {@code os.arch}, which names the instruction set ({@code amd64} on every 64-bit x86 chip,
     * Intel's included) and so tells a bug report nothing about the machine. Java exposes no
     * property for the model, so each platform is asked the way that platform answers.
     *
     * <p>Looked up once. Neither this nor {@link #systemMemory} can change while the JVM runs,
     * and the lookup forks a process on two of the three platforms &mdash; too much to repeat
     * every time a report is formatted.</p>
     */
    static String cpuModel() {
        String known = cpuModel;
        return known != null ? known : (cpuModel = lookUpCpuModel());
    }

    private static volatile String cpuModel;
    private static volatile String systemMemory;

    private static String lookUpCpuModel() {
        try {
            if (Os.isWindows()) {
                // The registry value wmic used to report; wmic itself is gone from current Windows.
                String out = runQuickly("reg", "query",
                        "HKLM\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0",
                        "/v", "ProcessorNameString");
                int type = out == null ? -1 : out.indexOf("REG_SZ");
                if (type >= 0) {
                    return firstLine(out.substring(type + "REG_SZ".length()));
                }
            } else if (Os.isMac()) {
                String out = runQuickly("/usr/sbin/sysctl", "-n", "machdep.cpu.brand_string");
                if (out != null && !out.isBlank()) {
                    return firstLine(out);
                }
            } else {
                // x86 only: an ARM board's cpuinfo has no model name, which is why every branch
                // here falls through to "unknown" rather than asserting a value. Streamed, not
                // read whole: the file repeats a block per logical CPU, and the line wanted is in
                // the first one.
                try (java.util.stream.Stream<String> lines = Files.lines(Path.of("/proc/cpuinfo"))) {
                    Optional<String> model = lines
                            .filter(line -> line.toLowerCase(Locale.ROOT).startsWith("model name")
                                    && line.indexOf(':') > 0)
                            .findFirst();
                    if (model.isPresent()) {
                        String line = model.get();
                        return line.substring(line.indexOf(':') + 1).trim();
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // A missing tool or an unreadable /proc costs one line of the report, nothing more.
        }
        return "unknown";
    }

    /** Total physical RAM, or "unknown" where the platform bean cannot supply it. Looked up once. */
    static String systemMemory() {
        String known = systemMemory;
        return known != null ? known : (systemMemory = lookUpSystemMemory());
    }

    private static String lookUpSystemMemory() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean os) {
                long bytes = os.getTotalMemorySize();
                if (bytes > 0) {
                    return gigabytes(bytes);
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // com.sun.management is an extension: absent from a trimmed runtime, and this is the
            // one line of the report that would otherwise take the whole report down with it.
        }
        return "unknown";
    }

    /** The first non-blank line of {@code text}, trimmed. */
    private static String firstLine(String text) {
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                return line.trim();
            }
        }
        return "unknown";
    }

    /** How long to wait for a host-fact lookup ({@code reg}, {@code sysctl}) before giving up. */
    private static final long HOST_LOOKUP_TIMEOUT_SECONDS = 5;

    /**
     * Run a short informational command and return its output, or {@code null} if it fails.
     *
     * <p>Collected through a file rather than a pipe, for the reason {@link #runProbe} gives:
     * reading a pipe to EOF blocks until the process exits, which would put the timeout below out
     * of reach of the very hang it is there for. This runs on the thread the environment-report
     * dialog waits on, and that thread holds the busy state the whole window depends on.</p>
     */
    private static String runQuickly(String... command) {
        Path output = null;
        Process process = null;
        try {
            output = Files.createTempFile("casanovo-gui-host", ".txt");
            output.toFile().deleteOnExit();
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.redirectOutput(output.toFile());
            // Immediate EOF: a tool that unexpectedly asks something must fail, not wait.
            pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File(
                    Os.isWindows() ? "NUL" : "/dev/null")));
            process = pb.start();
            if (!process.waitFor(HOST_LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            // Decoded in the platform's native encoding, not UTF-8: a redirected console tool
            // writes the OEM code page on Windows, which a UTF-8 decode turns into mojibake in
            // the one field a bug report is read for.
            return process.exitValue() == 0
                    ? new String(Files.readAllBytes(output), nativeCharset())
                    : null;
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            if (process != null) {
                process.destroyForcibly(); // cancelled: do not leave the tool running behind us
            }
            Thread.currentThread().interrupt();
            return null;
        } finally {
            deleteQuietly(output);
        }
    }

    /** The encoding a console tool writes in; UTF-8 where the JVM does not say. */
    private static Charset nativeCharset() {
        try {
            return Charset.forName(System.getProperty("native.encoding", "UTF-8"));
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }
}
