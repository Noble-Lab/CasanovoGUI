package org.casanovo.gui.core.remote;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import org.casanovo.gui.core.OutputPump;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

/**
 * Shell-quoting and one-shot exec helpers shared by {@link RemoteInstaller} and {@link RemoteBackend}.
 * Everything the GUI sends over SSH is assembled here so quoting is done in exactly one place: remote
 * paths and arguments are single-quoted with {@link #shq(String)} (POSIX-safe, embedded quotes escaped),
 * and a script is wrapped for a login shell with {@link #bashLogin(String)} (stderr folded into stdout so
 * a single stream is enough for {@link OutputPump}).
 */
final class RemoteShell {

    private RemoteShell() {
    }

    /** Single-quote {@code s} for POSIX {@code sh}, escaping embedded single quotes as {@code '\''}. */
    static String shq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** Rewrite a leading {@code ~} to the concrete remote home, giving an absolute path SFTP can use. */
    static String resolveTilde(String p, String home) {
        if (p == null || p.isEmpty() || home == null || home.isEmpty()) {
            return p;
        }
        if (p.equals("~")) {
            return home;
        }
        if (p.startsWith("~/")) {
            return home + "/" + p.substring(2);
        }
        return p;
    }

    /** Wrap {@code script} as {@code bash -lc '<script>'}, folding stderr into stdout for one stream. */
    static String bashLogin(String script) {
        return "bash -lc " + shq("exec 2>&1\n" + script);
    }

    /**
     * The last non-blank line of {@code s}, trimmed, or {@code null} if there is none. A login shell can
     * print a {@code .bash_profile} banner <em>before</em> the command's own output, so the value we want
     * (an exit code, a resolved path, {@code $HOME}, a PID) is the final line, not the first.
     */
    static String lastLine(String s) {
        if (s == null) {
            return null;
        }
        String[] lines = s.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty()) {
                return line;
            }
        }
        return null;
    }

    /**
     * Run {@code command}, streaming its (merged) output through {@link OutputPump} to {@code onOutput}
     * (may be {@code null} to discard), and return its exit status ({@code -1} if the server reported none).
     */
    static int runStreamed(SSHClient ssh, String command, BiConsumer<String, Boolean> onOutput)
            throws IOException {
        try (Session session = ssh.startSession()) {
            Session.Command cmd = session.exec(command);
            if (onOutput != null) {
                try {
                    OutputPump.pump(cmd.getInputStream(), onOutput);
                } catch (IOException ignore) {
                    // channel closed mid-stream; fall through to collect the exit status
                }
            }
            try {
                cmd.join();
            } catch (IOException ignore) {
                // best-effort; the exit status below still reflects what the server sent
            }
            Integer status = cmd.getExitStatus();
            return status == null ? -1 : status;
        }
    }

    /** Run {@code command} and return its merged stdout as a trimmed String. */
    static String capture(SSHClient ssh, String command) throws IOException {
        try (Session session = ssh.startSession()) {
            Session.Command cmd = session.exec(command);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = cmd.getInputStream()) {
                in.transferTo(out);
            }
            try {
                cmd.join();
            } catch (IOException ignore) {
                // best-effort capture
            }
            return out.toString(StandardCharsets.UTF_8).trim();
        }
    }
}
