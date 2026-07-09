package org.casanovo.gui.core.remote;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps LOCAL absolute paths to REMOTE POSIX paths as inputs are staged, then rewrites:
 * <ol>
 *   <li>the command arguments <em>local &rarr; remote</em> before the run (exact-equality on absolute
 *       tokens, so a spectra/FASTA/config/model path becomes its uploaded location and
 *       {@code --output_dir} becomes the remote output dir), and</li>
 *   <li>the downloaded mzTab's {@code ms_run[k]-location} URIs <em>remote &rarr; local</em>, so the View
 *       tab + PDV resolve the returned results against the local staged files.</li>
 * </ol>
 * All command paths the GUI emits are already absolute (see {@code CommonOptions.appendArgs}), so exact
 * string equality is a safe, quoting- and separator-proof way to match them.
 */
public final class PathMap {

    /** Local absolute path &rarr; remote POSIX path, in insertion order. */
    private final Map<String, String> localToRemote = new LinkedHashMap<>();

    /** Record that local file/dir {@code local} was uploaded to {@code remotePosix}. */
    public void put(File local, String remotePosix) {
        localToRemote.put(local.getAbsolutePath(), remotePosix);
    }

    /** Record a mapping for a path that is not a File on disk (e.g. the remote {@code --output_dir}). */
    public void put(String localAbsolute, String remotePosix) {
        localToRemote.put(localAbsolute, remotePosix);
    }

    /** The remote path a local path was mapped to, or {@code null} if it was never staged. */
    public String remoteFor(String localAbsolute) {
        return localToRemote.get(localAbsolute);
    }

    /**
     * Rewrite each command token that maps to a staged input to its remote path. Matches on the raw token
     * first (the common case: the GUI emits absolute paths), then on {@code new File(token).getAbsolutePath()}
     * so a typed relative or forward-slash path that was staged by its absolute path is still rewritten.
     */
    public List<String> rewriteArgs(List<String> args) {
        List<String> out = new ArrayList<>(args.size());
        for (String a : args) {
            String remote = localToRemote.get(a);
            if (remote == null) {
                remote = localToRemote.get(new File(a).getAbsolutePath());
            }
            out.add(remote != null ? remote : a);
        }
        return out;
    }

    /**
     * Rewrite a downloaded mzTab so each {@code ms_run[k]-location file://<remote>} points at the LOCAL
     * staged file. Replaces both the remote {@code file://} URI and the bare remote path with the local
     * equivalents, longest remote path first so nested paths can't be partially matched.
     */
    public String rewriteMzTabToLocal(String mzTab) {
        String out = mzTab;
        // Longest remote path first: a run dir is a prefix of the files under it, so replacing the files
        // before the dir avoids a shorter match clobbering a longer one.
        List<Map.Entry<String, String>> entries = new ArrayList<>(localToRemote.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue().length(), a.getValue().length()));
        for (Map.Entry<String, String> e : entries) {
            String localUri = new File(e.getKey()).toURI().toString(); // file:/D:/… (parsed by MzTabScores)
            String remotePosix = e.getValue();
            out = out.replace("file://" + remotePosix, localUri); // file:///tmp/… -> local URI
            out = out.replace(remotePosix, e.getKey());            // fallback: bare remote path -> local path
        }
        return out;
    }

    public boolean isEmpty() {
        return localToRemote.isEmpty();
    }
}
