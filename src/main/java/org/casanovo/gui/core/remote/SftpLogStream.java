package org.casanovo.gui.core.remote;

import net.schmizz.sshj.sftp.RemoteFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.function.BooleanSupplier;

/**
 * An {@link InputStream} over a growing remote file, read incrementally through a single SFTP handle.
 * Each {@link #read(byte[], int, int)} serves whatever bytes have appeared past the last-read offset
 * (a fresh {@code fstat} per call sees the growth); when the reader has caught up it <em>blocks by
 * polling</em> &mdash; sleeping {@code pollMillis} between size checks &mdash; until either more bytes
 * land or the {@link EndCheck} reports the job has ended, at which point any final flush is drained and
 * the stream returns EOF exactly once. Feeding this stream to {@link org.casanovo.gui.core.OutputPump}
 * makes the remote console render identically to the local backend, with no {@code tail} process, no
 * second channel, and no extra pump thread. Transient {@code IOException}s (a brief connection blip)
 * are tolerated for a few consecutive strikes before being rethrown.
 */
final class SftpLogStream extends InputStream {

    /** How the stream learns the producing job has ended (e.g. an exit-marker file exists). */
    @FunctionalInterface
    interface EndCheck {
        boolean jobEnded() throws IOException;
    }

    private static final int MAX_STRIKES = 12;

    private final RemoteFile file;
    private final EndCheck endCheck;
    private final BooleanSupplier cancelled;
    private final long pollMillis;

    private long offset = 0;
    private int failStrikes = 0;

    /**
     * @param file       an open handle on the remote log file (closed by {@link #close()})
     * @param endCheck   reports whether the producing job has ended
     * @param cancelled  when true, the stream returns EOF on the next read instead of waiting further
     * @param pollMillis sleep between polls while caught up (or after a transient failure)
     */
    SftpLogStream(RemoteFile file, EndCheck endCheck, BooleanSupplier cancelled, long pollMillis) {
        this.file = file;
        this.endCheck = endCheck;
        this.cancelled = cancelled;
        this.pollMillis = pollMillis;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        while (true) {
            if (cancelled.getAsBoolean()) {
                return -1;
            }
            long size;
            try {
                size = file.length();
                failStrikes = 0;
            } catch (IOException e) {
                if (++failStrikes >= MAX_STRIKES) {
                    throw e;
                }
                sleep();
                continue;
            }
            if (size > offset) {
                int want = (int) Math.min(len, size - offset);
                int n;
                try {
                    n = file.read(offset, b, off, want);
                    failStrikes = 0;
                } catch (IOException e) {
                    if (++failStrikes >= MAX_STRIKES) {
                        throw e;
                    }
                    sleep();
                    continue;
                }
                if (n > 0) {
                    offset += n;
                    return n;
                }
                // n <= 0 despite size > offset (a truncation race): fall through to the caught-up branch.
            }
            boolean ended;
            try {
                ended = endCheck.jobEnded();
                failStrikes = 0;
            } catch (IOException e) {
                if (++failStrikes >= MAX_STRIKES) {
                    throw e;
                }
                ended = false;
            }
            if (ended) {
                long finalSize;
                try {
                    finalSize = file.length();
                } catch (IOException e) {
                    finalSize = offset;
                }
                if (finalSize > offset) {
                    continue; // a final flush landed between the read and the end check; drain it first
                }
                return -1;    // real EOF: the job has ended and the log is fully read
            }
            sleep();
        }
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n == -1 ? -1 : (one[0] & 0xff);
    }

    @Override
    public void close() {
        try {
            file.close();
        } catch (IOException ignore) {
            // closing the remote handle is best-effort
        }
    }

    private void sleep() throws InterruptedIOException {
        try {
            Thread.sleep(pollMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException();
        }
    }
}
