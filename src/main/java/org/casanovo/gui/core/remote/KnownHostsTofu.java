package org.casanovo.gui.core.remote;

import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.common.KeyType;
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.Base64;
import java.util.function.Predicate;

/**
 * Trust-on-first-use host-key verification. Behaves exactly like {@link OpenSSHKnownHosts} for a host that
 * is already listed &mdash; a matching key verifies, a <em>changed</em> key is rejected (the default
 * {@link #hostKeyChangedAction}, deliberately left in place) &mdash; but an <em>unknown</em> host is not
 * silently accepted: the supplied {@code accept} predicate is asked (the UI shows the fingerprint), and only
 * if it returns {@code true} is a proper {@code known_hosts} line appended and the connection allowed.
 */
public final class KnownHostsTofu extends OpenSSHKnownHosts {

    private final Predicate<String> accept;

    /**
     * @param knownHosts the {@code known_hosts} file (created on first accept if it does not exist)
     * @param accept     asked to approve an unknown host; receives {@code "<host> <keyType> SHA256:<fp>"}
     */
    public KnownHostsTofu(File knownHosts, Predicate<String> accept) throws IOException {
        super(knownHosts);
        this.accept = accept;
    }

    @Override
    protected boolean hostKeyUnverifiableAction(String hostname, PublicKey key) {
        if (accept == null) {
            return false;
        }
        KeyType type = KeyType.fromKey(key);
        if (type == KeyType.UNKNOWN) {
            return false;
        }
        String prompt = hostname + " " + type + " SHA256:" + sha256Fingerprint(key);
        if (!accept.test(prompt)) {
            return false;
        }
        try {
            // write(KnownHostEntry) appends to the same file passed to the constructor; make sure its
            // parent directory exists first (a brand-new ~/.ssh/known_hosts is the common case).
            File parent = khFile == null ? null : khFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            write(new HostEntry(null, hostname, type, key));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** OpenSSH-style {@code SHA256:} fingerprint (unpadded base64 of the SHA-256 of the SSH wire key). */
    private static String sha256Fingerprint(PublicKey key) {
        try {
            byte[] wire = new Buffer.PlainBuffer().putPublicKey(key).getCompactData();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(wire);
            return Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            return "?"; // SHA-256 is always present; keep the prompt usable regardless
        }
    }
}
