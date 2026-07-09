package org.casanovo.gui.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

/**
 * Streams a process/channel's merged stdout, splitting on {@code \n}, {@code \r\n} and bare
 * {@code \r}. Bare-{@code \r} chunks are emitted as transient (progress) updates; everything else as
 * committed lines &mdash; so the UI renders tqdm/Lightning progress bars as a single updating line
 * instead of thousands of separate lines. Shared by every execution backend (a local process stream
 * or a remote SSH channel) so the console behaves identically wherever Casanovo runs.
 */
public final class OutputPump {

    private OutputPump() {
    }

    /**
     * Read {@code in} to EOF, delivering {@code (text, isTransient)} chunks to {@code onOutput}; a
     * bare-{@code \r}-terminated chunk is transient (a progress refresh), all others are committed lines.
     */
    public static void pump(InputStream in, BiConsumer<String, Boolean> onOutput) throws IOException {
        try (PushbackReader r = new PushbackReader(
                new InputStreamReader(in, StandardCharsets.UTF_8), 1)) {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = r.read()) != -1) {
                char ch = (char) c;
                if (ch == '\n') {
                    onOutput.accept(sb.toString(), false);
                    sb.setLength(0);
                } else if (ch == '\r') {
                    int next = r.read();
                    if (next == '\n') {
                        onOutput.accept(sb.toString(), false); // \r\n => committed line
                    } else {
                        onOutput.accept(sb.toString(), true);   // bare \r => progress refresh
                        if (next != -1) {
                            r.unread(next);
                        }
                    }
                    sb.setLength(0);
                } else {
                    sb.append(ch);
                }
            }
            if (sb.length() > 0) {
                onOutput.accept(sb.toString(), false);
            }
        }
    }
}
