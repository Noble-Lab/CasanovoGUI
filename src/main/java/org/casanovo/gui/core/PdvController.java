package org.casanovo.gui.core;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Drives one or more running PDV windows over their loopback HTTP control port (the
 * {@code denovo-gui --port} server PDV exposes). A panel registers each PDV it launches against the
 * mzTab it is showing, then calls {@link #select(File, String)} when the user clicks a peptide; the
 * controller forwards a debounced {@code /select?ref=<spectra_ref>} to that mzTab's PDV so it
 * locates the PSM and shows its annotated spectrum.
 *
 * <p>Shared and reusable: any panel (Mapping today, others later) can register an instance and call
 * {@code select}. All work is off the JavaFX thread; failures are swallowed so a missing or
 * still-loading PDV never disrupts the UI.</p>
 */
public final class PdvController {

    /** One launched PDV: its process, its loopback control port, and whether it has reported ready. */
    private static final class Instance {
        final Process process;
        final int port;
        volatile boolean ready;

        Instance(Process process, int port) {
            this.process = process;
            this.port = port;
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /** mzTab absolute path -> the PDV instance showing it. */
    private final Map<String, Instance> instances = new ConcurrentHashMap<>();

    /** Single-thread debouncer: rapid clicks collapse to one select (the latest wins). */
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "pdv-select");
                t.setDaemon(true);
                return t;
            });
    private ScheduledFuture<?> pending;

    /** Debounce window for collapsing a burst of row clicks into a single select. */
    private static final long DEBOUNCE_MS = 60;

    /**
     * Reserve a free loopback TCP port to hand to {@code denovo-gui --port}. There is a tiny race
     * between closing the socket and PDV binding it, which is acceptable for a local desktop tool.
     *
     * @return a free port, or -1 if one could not be obtained (caller should then skip {@code --port})
     */
    public static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Register a freshly launched PDV instance for {@code mzTab} and begin polling its {@code /ready}
     * endpoint in the background. Replaces any previous instance registered for the same mzTab.
     *
     * @param mzTab   the result file this PDV was opened with
     * @param process the PDV process
     * @param port    the loopback control port passed to {@code denovo-gui --port}
     */
    public void register(File mzTab, Process process, int port) {
        if (mzTab == null || process == null || port <= 0) {
            return;
        }
        Instance inst = new Instance(process, port);
        instances.put(mzTab.getAbsolutePath(), inst);
        pollReady(inst);
    }

    /**
     * Open PDV for {@code mzTab} on a separate daemon thread (ensure the jar, pick a free control
     * port, launch, register) and return immediately. Reuses a live window already open for this
     * result instead of opening a duplicate. Every failure is logged and swallowed, so it can never
     * disrupt the caller (e.g. a mapping run started alongside it).
     *
     * @param mzTab          the result the window should show
     * @param spectra        the spectrum file(s) PDV should load
     * @param fragTol        fragment ion tolerance for annotation
     * @param tolUnit        {@code "Da"} or {@code "ppm"}
     * @param hidePsmTable   open directly into a spectrum-only view (no PSM table)
     * @param pdvJarOverride explicit PDV jar from Settings, or null/blank to use the cached download
     * @param log            progress/error sink (e.g. the window's console)
     */
    public void launchAndRegister(File mzTab, List<File> spectra, double fragTol, String tolUnit,
                                  boolean hidePsmTable, String pdvJarOverride, Consumer<String> log) {
        if (mzTab == null || spectra == null || spectra.isEmpty()) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                if (hasInstance(mzTab)) {
                    log.accept("[pdv] reusing the PDV window already open for this result.");
                    return;
                }
                Path jar = PdvLauncher.ensurePdv(pdvJarOverride, log);
                int port = freePort();
                Process proc = PdvLauncher.launchDenovo(jar, spectra, mzTab, fragTol, tolUnit, port, hidePsmTable, log);
                if (port > 0) {
                    register(mzTab, proc, port);
                }
            } catch (Exception e) {
                log.accept("[pdv] could not open PDV: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }, "pdv-open");
        t.setDaemon(true);
        t.start();
    }

    /** @return true if a live PDV instance is registered for {@code mzTab}. */
    public boolean hasInstance(File mzTab) {
        if (mzTab == null) {
            return false;
        }
        Instance inst = instances.get(mzTab.getAbsolutePath());
        return inst != null && inst.process.isAlive();
    }

    /**
     * Ask the PDV showing {@code mzTab} to select the PSM with the given verbatim mzTab
     * {@code spectra_ref} and render its spectrum. Debounced and asynchronous: a burst of clicks
     * sends only the last selection, and nothing is sent if no live, ready instance exists.
     *
     * @param mzTab      the result whose PDV should be driven
     * @param spectraRef the verbatim {@code spectra_ref} of the peptide's best PSM
     */
    public synchronized void select(File mzTab, String spectraRef) {
        if (mzTab == null || spectraRef == null || spectraRef.isEmpty()) {
            return;
        }
        if (pending != null) {
            pending.cancel(false);
        }
        String path = mzTab.getAbsolutePath();
        pending = scheduler.schedule(() -> doSelect(path, spectraRef), DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private void doSelect(String mzTabPath, String spectraRef) {
        Instance inst = instances.get(mzTabPath);
        if (inst == null || !inst.process.isAlive() || !inst.ready) {
            return;
        }
        try {
            String url = "http://127.0.0.1:" + inst.port + "/select?ref="
                    + URLEncoder.encode(spectraRef, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // A select failure (PDV closed, busy, etc.) must never disrupt the UI.
        }
    }

    /** Poll {@code /ready} until the instance reports loaded, the process dies, or a 5-minute cap. */
    private void pollReady(Instance inst) {
        Thread t = new Thread(() -> {
            long deadline = System.currentTimeMillis() + Duration.ofMinutes(5).toMillis();
            while (System.currentTimeMillis() < deadline && inst.process.isAlive()) {
                try {
                    HttpRequest req = HttpRequest.newBuilder(
                                    URI.create("http://127.0.0.1:" + inst.port + "/ready"))
                            .timeout(Duration.ofSeconds(3))
                            .GET()
                            .build();
                    HttpResponse<Void> r = http.send(req, HttpResponse.BodyHandlers.discarding());
                    if (r.statusCode() == 200) {
                        inst.ready = true;
                        return;
                    }
                } catch (Exception ignored) {
                    // Not up yet; keep polling.
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "pdv-ready-poll");
        t.setDaemon(true);
        t.start();
    }
}
