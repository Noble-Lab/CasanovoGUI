package org.casanovo.gui.core.remote;

import net.schmizz.keepalive.KeepAliveProvider;
import net.schmizz.sshj.DefaultConfig;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import org.casanovo.gui.core.OutputPump;
import org.casanovo.gui.core.exec.ExecutionBackend;
import org.casanovo.gui.core.exec.JobHandle;
import org.casanovo.gui.core.exec.JobRequest;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Runs a Casanovo job on a remote host over SSH, behind the same {@link ExecutionBackend} contract as the
 * local backend so the console/progress layer is reused unchanged. The job is <em>detached</em> on the
 * server ({@code setsid} + {@code nohup}-style backgrounding writing its own {@code run.pid}/{@code run.exit}),
 * so a dropped SSH channel &mdash; a network hiccup, a laptop lid closing &mdash; never orphans or kills the
 * (often GPU-bound) run: the GUI reads {@code run.log} incrementally over a single SFTP handle (no remote
 * {@code tail} process), blocking by polling until the exit marker appears, tolerating a brief connection
 * blip before it gives up. (It does not fully re-establish a dropped connection; a heartbeat keep-alive
 * holds an idle one open instead.)
 *
 * <p>Threading mirrors {@code CasanovoRunner}: {@link #start} flips {@code active} synchronously and returns a
 * handle immediately, doing all SSH work on a daemon thread. Only one job runs at a time.</p>
 */
public final class RemoteBackend implements ExecutionBackend {

    private final RemoteSettings settings;
    private final Predicate<String> hostKeyPrompt;
    private final Supplier<char[]> passwordSupplier;
    private final Supplier<char[]> passphraseSupplier;
    private final Supplier<String> runIdSupplier;

    private volatile boolean active;
    private volatile boolean cancelled;
    private volatile SSHClient client;
    private volatile String remoteOut;      // absolute remote output dir of the running job
    private volatile String jobPid;         // the launched group's pid, for the finally to kill
    private volatile boolean launched;      // true once the detached job (+watchdog) exists on the server

    /**
     * @param settings           connection coordinates and non-secret paths
     * @param hostKeyPrompt      approves an unknown host key (UI-supplied); {@code null} rejects unknown hosts
     * @param passwordSupplier   supplies the password for {@link RemoteSettings.AuthMode#PASSWORD}; may be null
     * @param passphraseSupplier supplies a key passphrase for {@link RemoteSettings.AuthMode#KEY}; may be null
     * @param runIdSupplier      supplies a unique run stamp (the backend never reads the clock itself)
     */
    public RemoteBackend(RemoteSettings settings,
                         Predicate<String> hostKeyPrompt,
                         Supplier<char[]> passwordSupplier,
                         Supplier<char[]> passphraseSupplier,
                         Supplier<String> runIdSupplier) {
        this.settings = settings;
        this.hostKeyPrompt = hostKeyPrompt;
        this.passwordSupplier = passwordSupplier;
        this.passphraseSupplier = passphraseSupplier;
        this.runIdSupplier = runIdSupplier;
    }

    @Override
    public JobHandle start(JobRequest request,
                           BiConsumer<String, Boolean> onOutput,
                           BiConsumer<Integer, Throwable> onFinished,
                           Consumer<String> onStage) {
        if (active) {
            throw new IllegalStateException("A Casanovo job is already running.");
        }
        cancelled = false;
        active = true;
        Thread worker = new Thread(() -> runJob(request, onOutput, onFinished, onStage), "casanovo-remote");
        worker.setDaemon(true);
        worker.start();
        return new JobHandle() {
            @Override
            public void cancel() {
                RemoteBackend.this.cancel();
            }

            @Override
            public boolean isRunning() {
                return active;
            }
        };
    }

    @Override
    public String displayName() {
        return "Remote (" + settings.getUser() + "@" + settings.getHost() + ")";
    }

    /** A new client with a heartbeat keep-alive provider so idle-silent long runs aren't reaped. */
    private static SSHClient newClient() {
        DefaultConfig config = new DefaultConfig();
        config.setKeepAliveProvider(KeepAliveProvider.HEARTBEAT);
        return new SSHClient(config);
    }

    /**
     * Connect + authenticate + disconnect with the given parameters, doing no run. Returns {@code null}
     * on success or a short error message &mdash; used by the settings dialog's "Test connection" against
     * the current (possibly unsaved) fields. Prompts are invoked exactly as during a real run.
     */
    public static String testConnection(String host, int port, String user, RemoteSettings.AuthMode auth,
                                        String keyPath, String knownHosts,
                                        Predicate<String> hostKeyPrompt,
                                        Supplier<char[]> passwordSupplier,
                                        Supplier<char[]> passphraseSupplier) {
        if (host == null || host.isBlank() || user == null || user.isBlank()) {
            return "Host and user are required.";
        }
        SSHClient ssh = newClient();
        try {
            connect(ssh, host, port, knownHosts, hostKeyPrompt);
            auth(ssh, user, auth, keyPath, passwordSupplier, passphraseSupplier);
            return ssh.isAuthenticated() ? null : "Authentication failed.";
        } catch (Exception e) {
            return e.getMessage() == null ? e.toString() : e.getMessage();
        } finally {
            try {
                ssh.disconnect();
            } catch (IOException ignore) {
                // ignore
            }
            try {
                ssh.close();
            } catch (IOException ignore) {
                // ignore
            }
        }
    }

    // ------------------------------------------------------------------ connect + auth

    /** Install the TOFU known-hosts verifier, connect, and turn on the heartbeat interval. */
    private static void connect(SSHClient ssh, String host, int port, String knownHosts,
                                Predicate<String> hostKeyPrompt) throws IOException {
        Predicate<String> prompt = hostKeyPrompt == null ? s -> false : hostKeyPrompt;
        ssh.addHostKeyVerifier(new KnownHostsTofu(new File(knownHosts == null ? "" : knownHosts), prompt));
        ssh.connect(host, port <= 0 ? 22 : port);
        try {
            ssh.getConnection().getKeepAlive().setKeepAliveInterval(30);
        } catch (RuntimeException ignore) {
            // keep-alive is a nicety; a provider that rejects it must not fail the run
        }
    }

    /** Authenticate an already-connected client per {@code mode}; throws with a clear message on failure. */
    private static void auth(SSHClient ssh, String user, RemoteSettings.AuthMode mode, String keyPath,
                             Supplier<char[]> pwSupplier, Supplier<char[]> passphraseSupplier)
            throws IOException {
        switch (mode) {
            case PASSWORD -> {
                char[] pw = pwSupplier == null ? null : pwSupplier.get();
                if (pw == null) {
                    throw new IOException("Password authentication selected but no password was provided.");
                }
                ssh.authPassword(user, pw);
            }
            case KEY -> {
                if (keyPath == null || keyPath.isBlank()) {
                    throw new IOException("Key authentication selected but no key file is configured.");
                }
                char[] passphrase = passphraseSupplier == null ? null : passphraseSupplier.get();
                KeyProvider keys = (passphrase == null || passphrase.length == 0)
                        ? ssh.loadKeys(keyPath)
                        : ssh.loadKeys(keyPath, passphrase);
                ssh.authPublickey(user, keys);
            }
            case AGENT -> ssh.authPublickey(user); // default identity files + a running ssh-agent
        }
    }

    // ------------------------------------------------------------------ the job

    private void runJob(JobRequest request,
                        BiConsumer<String, Boolean> onOutput,
                        BiConsumer<Integer, Throwable> onFinished,
                        Consumer<String> onStage) {
        int exit = -1;
        Throwable error = null;
        SSHClient ssh = null;
        try {
            RemoteSettings rs = settings;
            onStage.accept("Connecting to " + rs.getUser() + "@" + rs.getHost() + " …");
            ssh = newClient();
            this.client = ssh;
            connect(ssh, rs.getHost(), rs.getPort(), rs.getKnownHostsPath(), hostKeyPrompt);
            auth(ssh, rs.getUser(), rs.getAuthMode(), rs.getKeyPath(), passwordSupplier, passphraseSupplier);
            if (cancelled) {
                return;
            }

            // Concrete remote home lets us turn a tilde'd data root into an absolute path SFTP can use.
            String home = RemoteShell.lastLine(RemoteShell.capture(ssh, "echo \"$HOME\""));
            String installDir = RemoteShell.resolveTilde(rs.getInstallDir(), home);
            String dataRoot = RemoteShell.resolveTilde(rs.getDataRoot(), home);

            onStage.accept("Preparing Casanovo on the remote host …");
            String launcher = RemoteInstaller.ensureCasanovo(ssh, installDir, line -> onOutput.accept(line, false));
            if (cancelled) {
                return;
            }
            // The venv's activate sits next to its casanovo launcher (…/.venv/bin/casanovo → …/.venv/bin/activate).
            String activate = launcher.substring(0, launcher.lastIndexOf('/')) + "/activate";

            String runDir = dataRoot + "/run-" + runIdSupplier.get();
            String remoteIn = runDir + "/in";
            String remoteOutDir = runDir + "/out";
            this.remoteOut = remoteOutDir;

            SFTPClient sftp = ssh.newSFTPClient();
            PathMap pathMap;
            List<String> remoteArgs;
            try {
                sftp.mkdirs(remoteIn);
                sftp.mkdirs(remoteOutDir);

                SftpStager stager = new SftpStager(sftp);
                pathMap = stager.stageIn(request.inputs(), remoteIn, onStage);
                // Map the local --output_dir onto the remote out dir so rewriteArgs redirects the run there.
                if (request.outputDir() != null) {
                    pathMap.put(request.outputDir().getAbsolutePath(), remoteOutDir);
                }
                remoteArgs = pathMap.rewriteArgs(request.command().getArguments());

                launchDetached(ssh, remoteOutDir, activate,
                        request.command().getSubcommand(), remoteArgs);
                // The detached group (and its watchdog) now exist: mark it launched so the finally always
                // enforces a cancel via run.cancel, even if Stop lands before we read the PID below.
                this.launched = true;

                // Wait for the detached group to record its PID before streaming; a null pid means the
                // launch itself failed (nothing to tail or kill), so report a failure and stop here.
                String pid = readPid(ssh, remoteOutDir);
                this.jobPid = pid;
                if (pid == null) {
                    onOutput.accept("Remote launch failed: the job did not start (no run.pid).", false);
                    exit = -1;
                } else if (!cancelled) {
                    exit = streamUntilExit(sftp, ssh, remoteOutDir, pid, onOutput);
                }
                if (cancelled) {
                    return;
                }

                if (exit == 0) {
                    onStage.accept("Downloading results …");
                    stager.download(remoteOutDir, request.outputDir(), onStage);
                    rewriteMzTabs(request.outputDir(), pathMap);
                }
            } finally {
                try {
                    sftp.close();
                } catch (IOException ignore) {
                    // closing the SFTP subsystem is best-effort
                }
            }
        } catch (Throwable t) {
            error = t;
        } finally {
            boolean wasCancelled = cancelled;
            active = false;
            String pid = this.jobPid;
            String out = this.remoteOut;
            // Enforce cancel here, on the worker thread, while we still hold the client exclusively (no
            // killer-thread race): trip the server-side watchdog first (it survives even if we disconnect
            // right after), then kill the group directly for immediacy, then verify.
            if (wasCancelled && launched && out != null && ssh != null && ssh.isConnected()) {
                requestRemoteCancel(ssh, out, pid, msg -> onOutput.accept(msg, false));
            }
            this.client = null;
            this.remoteOut = null;
            this.jobPid = null;
            if (ssh != null) {
                try {
                    ssh.disconnect();
                } catch (IOException ignore) {
                    // ignore
                }
                try {
                    ssh.close();
                } catch (IOException ignore) {
                    // ignore
                }
            }
            if (wasCancelled) {
                onFinished.accept(130, null);
            } else {
                onFinished.accept(error == null ? exit : -1, error);
            }
        }
    }

    /**
     * Launch the run detached and return immediately. The session-leader {@code bash} writes its own PID
     * (its process-group id, thanks to {@code setsid}) to {@code run.pid}, runs Casanovo with output to
     * {@code run.log}, then writes the exit code to {@code run.exit} via a temp-then-rename so a poller never
     * sees a half-written marker. Because the group is backgrounded in a new, controlling-terminal-less
     * session, it keeps running after this launching channel closes. {@code run.log} is created up front so
     * the SFTP reader can open it immediately, before the run has produced any output.
     *
     * <p>A server-side watchdog subshell makes cancellation self-enforcing: it waits for {@code run.cancel}
     * to appear, then TERMs the whole process group ({@code $$} in the subshell is the parent leader's PID,
     * i.e. the group id) and escalates to KILL after a grace &mdash; {@code trap '' TERM} lets it survive its
     * own broadcast. On normal completion the leader KILLs the watchdog by its specific (positive) PID.</p>
     */
    private void launchDetached(SSHClient ssh, String out, String activate,
                                String subcommand, List<String> remoteArgs) throws IOException {
        StringBuilder inner = new StringBuilder();
        inner.append("echo $$ > ").append(RemoteShell.shq(out + "/run.pid")).append('\n');
        inner.append("( trap '' TERM; while [ ! -e ").append(RemoteShell.shq(out + "/run.cancel"))
                .append(" ]; do sleep 1; done; kill -TERM -$$ 2>/dev/null; sleep 3;")
                .append(" kill -KILL -$$ 2>/dev/null ) &\n");
        inner.append("__wd=$!\n");
        inner.append("source ").append(RemoteShell.shq(activate)).append('\n');
        inner.append("casanovo ").append(subcommand);
        for (String arg : remoteArgs) {
            inner.append(' ').append(RemoteShell.shq(arg));
        }
        inner.append(" > ").append(RemoteShell.shq(out + "/run.log")).append(" 2>&1").append('\n');
        inner.append("__code=$?\n");
        inner.append("kill -KILL \"$__wd\" 2>/dev/null\n");
        // Atomic marker: never expose a half-written (empty) run.exit that would read as a false failure.
        inner.append("echo $__code > ").append(RemoteShell.shq(out + "/run.exit.tmp"))
                .append(" && mv ").append(RemoteShell.shq(out + "/run.exit.tmp"))
                .append(' ').append(RemoteShell.shq(out + "/run.exit")).append('\n');

        // touch run.log up front (same channel), then setsid: a new session (no controlling terminal) whose
        // leader PID == the process-group id we later kill.
        String launch = "touch " + RemoteShell.shq(out + "/run.log") + "\n"
                + "setsid bash -lc " + RemoteShell.shq(inner.toString())
                + " >/dev/null 2>&1 </dev/null &";
        RemoteShell.runStreamed(ssh, launch, null); // the launching shell exits at once; the group runs on
    }

    /**
     * Poll {@code run.pid} until the detached group has recorded its PGID (~5s), returning the PID or
     * {@code null} if it never appears (the launch failed).
     */
    private static String readPid(SSHClient ssh, String out) throws IOException {
        String pidPath = out + "/run.pid";
        for (int i = 0; i < 20; i++) {
            String pid = RemoteShell.lastLine(RemoteShell.capture(ssh, RemoteShell.bashLogin(
                    "cat " + RemoteShell.shq(pidPath) + " 2>/dev/null || true")));
            if (pid != null && pid.matches("\\d+")) {
                return pid;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /** True if the detached process group {@code pid} is still alive ({@code kill -0} on the negative PGID). */
    private static boolean groupAlive(SSHClient ssh, String pid) throws IOException {
        String r = RemoteShell.lastLine(RemoteShell.capture(ssh, RemoteShell.bashLogin(
                "kill -0 -" + pid + " 2>/dev/null && echo ALIVE || echo DEAD")));
        return "ALIVE".equals(r);
    }

    /**
     * Stream {@code run.log} through {@link OutputPump} via an incremental SFTP read ({@link SftpLogStream})
     * on this thread until the job ends: the stream serves new bytes as they land over a single SFTP handle
     * and blocks by polling while caught up, hitting EOF once {@code run.exit} exists (or the detached group
     * has died) and the log is fully drained. The {@code kill -0} liveness shell-out is throttled to every
     * Nth caught-up poll, so a run that dies without ever writing {@code run.exit} (killed, OOM, node
     * reboot) still breaks the wait &mdash; with a failure exit &mdash; instead of spinning forever.
     */
    private int streamUntilExit(SFTPClient sftp, SSHClient ssh, String out, String pid,
                                BiConsumer<String, Boolean> onOutput) throws IOException {
        String logPath = out + "/run.log";
        String exitPath = out + "/run.exit";
        final int[] liveTick = {0};
        final int LIVENESS_EVERY = 12;
        SftpLogStream.EndCheck end = () -> {
            if (sftp.statExistence(exitPath) != null) {
                return true;
            }
            // Throttle the liveness shell-out: only every Nth caught-up check, and only while we have a pid.
            if (pid != null && (liveTick[0]++ % LIVENESS_EVERY == 0)) {
                return !groupAlive(ssh, pid);
            }
            return false;
        };
        try (SftpLogStream in = new SftpLogStream(sftp.open(logPath), end, () -> cancelled, 400L)) {
            OutputPump.pump(in, onOutput); // returns when the job ends (or cancel flips the flag)
        }
        if (cancelled) {
            return -1;
        }
        // run.exit present => authoritative code; absent => the group died without writing it (SIGKILL/OOM).
        if (sftp.statExistence(exitPath) == null) {
            return -1;
        }
        return parseExit(RemoteShell.capture(ssh, RemoteShell.bashLogin(
                "cat " + RemoteShell.shq(exitPath))));
    }

    /** Rewrite each downloaded {@code *.mztab} in place so its {@code ms_run} locations resolve locally. */
    private static void rewriteMzTabs(File outputDir, PathMap pathMap) {
        if (outputDir == null) {
            return;
        }
        File[] files = outputDir.listFiles(
                (dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".mztab"));
        if (files == null) {
            return;
        }
        for (File f : files) {
            try {
                String content = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                String rewritten = pathMap.rewriteMzTabToLocal(content);
                if (!rewritten.equals(content)) {
                    Files.writeString(f.toPath(), rewritten, StandardCharsets.UTF_8);
                }
            } catch (IOException ignore) {
                // leave an unreadable/unwritable mzTab untouched rather than fail the whole run
            }
        }
    }

    // ------------------------------------------------------------------ cancel

    private void cancel() {
        // Raise the flag only. The worker thread (which owns the SSH client exclusively) performs the remote
        // cancel in its finally — no cross-thread use of the client, so the kill can't be lost to a race.
        cancelled = true;
    }

    /**
     * Enforce a cancel on the still-connected client. Creates run.cancel (tripping the server-side watchdog,
     * which enforces the kill even if the connection drops immediately after), then TERMs the process group
     * directly, polls for it to die, and KILLs if it outlives the grace. Best-effort and logged; the watchdog
     * is the backstop.
     */
    private static void requestRemoteCancel(SSHClient ssh, String out, String pid,
                                            Consumer<String> log) {
        try {
            RemoteShell.capture(ssh, RemoteShell.bashLogin("touch " + RemoteShell.shq(out + "/run.cancel")));
            if (pid == null) {
                // No PID captured (Stop landed right after launch): the watchdog picks up run.cancel.
                log.accept("Cancel signalled; the remote watchdog will stop the job shortly.");
                return;
            }
            RemoteShell.capture(ssh, RemoteShell.bashLogin("kill -TERM -" + pid + " 2>/dev/null || true"));
            boolean dead = false;
            for (int i = 0; i < 20; i++) {          // ~5s grace for a clean TERM shutdown
                if (!groupAlive(ssh, pid)) {
                    dead = true;
                    break;
                }
                Thread.sleep(250);
            }
            if (!dead) {
                RemoteShell.capture(ssh, RemoteShell.bashLogin("kill -KILL -" + pid + " 2>/dev/null || true"));
                Thread.sleep(500);
                dead = !groupAlive(ssh, pid);
            }
            log.accept(dead ? "Remote job stopped."
                    : "Cancel signalled; the remote watchdog will stop the job shortly.");
        } catch (IOException e) {
            log.accept("Cancel signalled to the remote host (watchdog will enforce it).");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int parseExit(String s) {
        String line = RemoteShell.lastLine(s);
        if (line == null || line.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(line);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
