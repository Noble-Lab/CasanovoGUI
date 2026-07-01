package org.casanovo.gui.core;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Looks up basic protein information from UniProt for a protein identifier that is in UniProt
 * format (an accession like {@code P02768}, an entry name like {@code ALBU_HUMAN}, or the FASTA
 * pipe form {@code sp|P02768|ALBU_HUMAN}).
 *
 * <p>Uses UniProt's REST API with a <em>field-selected</em> TSV response via the search endpoint,
 * which handles both accessions and entry names in one call and returns ~1&nbsp;KB instead of the
 * ~250&nbsp;KB of a full JSON entry (≈3× faster end-to-end in practice). Results — both hits and
 * genuine "not found" misses — are cached in memory, so re-hovering a protein is instant. Lookups
 * run on a daemon thread; the caller marshals the callback onto the UI thread.</p>
 *
 * <p>Only the JDK {@link HttpClient} and a tiny TSV split are used — no JSON dependency.</p>
 */
public final class UniProtClient {

    /** Official UniProt accession pattern. */
    private static final Pattern ACCESSION = Pattern.compile(
            "[OPQ][0-9][A-Z0-9]{3}[0-9]|[A-NR-Z][0-9]([A-Z][A-Z0-9]{2}[0-9]){1,2}");
    /** UniProt entry-name pattern, e.g. {@code ALBU_HUMAN}, {@code 1433B_HUMAN}. */
    private static final Pattern ENTRY_NAME = Pattern.compile("[A-Z0-9]{1,10}_[A-Z0-9]{1,5}");

    private static final String SEARCH = "https://rest.uniprot.org/uniprotkb/search";
    private static final String FIELDS =
            "accession,id,protein_name,gene_primary,organism_name,length,cc_function";

    // Pin HTTP/1.1: rest.uniprot.org trips the JDK HttpClient's HTTP/2 handling, which throws
    // "EOF reached while reading" on the response. (GitHub/PyPI, used elsewhere, don't hit this.)
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final ConcurrentHashMap<String, Entry> HITS = new ConcurrentHashMap<>();
    private static final Set<String> MISSES = ConcurrentHashMap.newKeySet();

    private UniProtClient() {
    }

    /** A parsed UniProt identifier: exactly one of {@code accession} / {@code entryName} is non-null. */
    public record Ref(String accession, String entryName) {
    }

    /** The subset of UniProt fields shown in the hover tooltip. Strings are never null (empty when absent). */
    public record Entry(String accession, String entryName, String proteinName, String gene,
                        String organism, int length, String function) {
    }

    /**
     * Parse a protein identifier into a UniProt {@link Ref}, or empty when it is not UniProt-format.
     * Handles the FASTA pipe form ({@code db|ACCESSION|ENTRY_NAME}), a bare accession, and a bare
     * entry name.
     */
    public static Optional<Ref> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return Optional.empty();
        }
        if (s.indexOf('|') >= 0) {
            String acc = null;
            String name = null;
            for (String part : s.split("\\|")) {
                String t = part.trim();
                if (acc == null && ACCESSION.matcher(t).matches()) {
                    acc = t;
                } else if (name == null && ENTRY_NAME.matcher(t).matches()) {
                    name = t;
                }
            }
            return (acc != null || name != null) ? Optional.of(new Ref(acc, name)) : Optional.empty();
        }
        if (ACCESSION.matcher(s).matches()) {
            return Optional.of(new Ref(s, null));
        }
        if (ENTRY_NAME.matcher(s).matches()) {
            return Optional.of(new Ref(null, s));
        }
        return Optional.empty();
    }

    /** A previously fetched entry for {@code rawId}, or {@code null} if not cached as a hit. */
    public static Entry peek(String rawId) {
        return HITS.get(rawId);
    }

    /** True when {@code rawId} was looked up and UniProt returned no entry (cached miss). */
    public static boolean knownMissing(String rawId) {
        return MISSES.contains(rawId);
    }

    /**
     * Resolve protein info for {@code rawId}, invoking {@code onDone} with the entry (or {@code null}
     * when no entry was found / the lookup failed). Returns cached results immediately on the calling
     * thread; otherwise fetches on a daemon thread and calls back from it.
     */
    public static void fetchAsync(String rawId, Consumer<Entry> onDone) {
        Entry hit = HITS.get(rawId);
        if (hit != null) {
            onDone.accept(hit);
            return;
        }
        if (MISSES.contains(rawId)) {
            onDone.accept(null);
            return;
        }
        Ref ref = parse(rawId).orElse(null);
        if (ref == null) {
            onDone.accept(null);
            return;
        }
        Thread t = new Thread(() -> {
            Entry e = doFetch(ref);
            if (e != null) {
                HITS.put(rawId, e);
            }
            // Note: a transient failure returns null but is NOT cached as a miss, so a later
            // hover retries; only a definitive "no row" response is cached (inside doFetch).
            else if (Boolean.TRUE.equals(LAST_DEFINITIVE.get())) {
                MISSES.add(rawId);
            }
            onDone.accept(e);
        }, "uniprot-lookup");
        t.setDaemon(true);
        t.start();
    }

    /** Per-thread flag: did the most recent {@link #doFetch} get a definitive 200 response (vs a network error)? */
    private static final ThreadLocal<Boolean> LAST_DEFINITIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static Entry doFetch(Ref ref) {
        LAST_DEFINITIVE.set(Boolean.FALSE);
        String term = ref.accession() != null ? "accession:" + ref.accession() : "id:" + ref.entryName();
        String url = SEARCH + "?query=" + URLEncoder.encode(term, StandardCharsets.UTF_8)
                + "&format=tsv&fields=" + FIELDS + "&size=1";
        try {
            HttpResponse<String> resp = HTTP.send(
                    HttpRequest.newBuilder().uri(URI.create(url))
                            .header("Accept", "text/plain")
                            .timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return null; // transient (rate limit / server error) — not cached as a miss
            }
            LAST_DEFINITIVE.set(Boolean.TRUE);
            return parseTsv(resp.body());
        } catch (Exception e) {
            return null; // offline / interrupted — not cached as a miss
        }
    }

    private static Entry parseTsv(String body) {
        if (body == null) {
            return null;
        }
        String[] lines = body.split("\r?\n");
        if (lines.length < 2 || lines[1].isBlank()) {
            return null; // header only -> no entry
        }
        String[] f = lines[1].split("\t", -1);
        String accession = col(f, 0);
        String entryName = col(f, 1);
        String proteinName = conciseName(col(f, 2));
        String gene = col(f, 3);
        String organism = col(f, 4);
        int length = parseIntSafe(col(f, 5));
        String function = cleanFunction(col(f, 6));
        if (accession.isEmpty() && entryName.isEmpty()) {
            return null;
        }
        return new Entry(accession, entryName, proteinName, gene, organism, length, function);
    }

    private static String col(String[] f, int i) {
        return i < f.length && f[i] != null ? f[i].trim() : "";
    }

    /** The recommended protein name without trailing parenthetical aliases/EC numbers. */
    private static String conciseName(String proteinNames) {
        if (proteinNames.isEmpty()) {
            return "";
        }
        int paren = proteinNames.indexOf(" (");
        return (paren > 0 ? proteinNames.substring(0, paren) : proteinNames).trim();
    }

    /** Strip the {@code FUNCTION:} prefix, evidence tags and PubMed refs, and truncate for a tooltip. */
    private static String cleanFunction(String raw) {
        if (raw.isEmpty()) {
            return "";
        }
        String f = raw.replaceFirst("^FUNCTION:\\s*", "")
                .replaceAll("\\{ECO:[^}]*\\}", "")
                .replaceAll("\\s*\\(PubMed:[^)]*\\)", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (f.length() > 300) {
            f = f.substring(0, 300).trim() + "…";
        }
        return f;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
