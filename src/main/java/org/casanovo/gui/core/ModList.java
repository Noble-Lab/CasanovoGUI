package org.casanovo.gui.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parsing for the {@code allowed_fixed_mods} / {@code allowed_var_mods} lists, whose entries pair
 * an amino acid with a modified token from the residues vocabulary — {@code "M:M[Oxidation]"} for a
 * residue modification, {@code "nterm:[Acetyl]-"} for a terminal one.
 *
 * <p>Kept out of the editor widget so the string handling is testable without a JavaFX toolkit.</p>
 */
public final class ModList {

    private static final Pattern NTERM = Pattern.compile("\\[.+]-");
    private static final Pattern CTERM = Pattern.compile("-\\[.+]");

    private ModList() {
    }

    /**
     * The tokens of a residues vocabulary that can appear in a mod list: those carrying a
     * modification and whose amino-acid prefix can be derived — which {@link #entryFor} decides, an
     * unmodified token having no prefix to strip. {@link CasanovoConfig#residueTokens} already
     * collapses repeated lines, so a copy-pasted duplicate doesn't become two identical checkboxes.
     */
    public static List<String> modTokens(String residuesText) {
        List<String> out = new ArrayList<>();
        for (String token : CasanovoConfig.residueTokens(residuesText)) {
            String entry = entryFor(token);
            // Only offer a token the resulting entry can actually carry: a token holding a colon or a
            // comma of its own is legal in the vocabulary but unreadable in a mod list, so a checkbox
            // for it would write a value that fails the run the moment it is used.
            if (entry != null && isPair(entry)) {
                out.add(token);
            }
        }
        return out;
    }

    /**
     * Split a mod list into entries for editing: every comma separates, and so does a line break —
     * the editor offers a multi-line box for custom entries, and a newline left in the value would
     * break the quoted YAML scalar it gets written to. Blank entries are dropped and each entry is
     * trimmed, so a half-typed list is still workable in the editor.
     *
     * <p>Every comma separates because that is all Casanovo does ({@code allowed_mods.split(",")}),
     * so a comma inside a token can only ever split the entry there too — treating it as part of the
     * token would let the editor keep and re-emit a value no run can read. What this drops or tidies
     * is not thereby valid: {@link #unresolvable} judges the raw value the way Casanovo will.</p>
     */
    public static List<String> splitEntries(String text) {
        List<String> out = new ArrayList<>();
        for (String entry : text.split(",|\\R", -1)) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /**
     * The {@code aa:mod_residue} entry for a vocabulary token, or {@code null} when no prefix can be
     * derived — a bracket-leading token that is neither N- nor C-terminal (no flanking dash) names
     * no amino acid, and pairing it with itself would emit nonsense like {@code "[Oxidation]:[Oxidation]"}.
     */
    public static String entryFor(String token) {
        String prefix = prefixFor(token);
        return prefix == null ? null : prefix + ":" + token;
    }

    /**
     * The token an entry refers to: the part after the first {@code aa:}, or the whole entry when
     * there is no colon. The first colon and no other, because that is where Casanovo cuts
     * ({@code aa, mod_aa = mod.split(":")}) — a token holding a colon of its own can live in the
     * vocabulary but can never be named by a mod list, and {@link #unresolvable} says so.
     */
    public static String tokenOf(String entry) {
        int colon = entry.indexOf(':');
        return colon < 0 ? entry.trim() : entry.substring(colon + 1).trim();
    }

    /**
     * The entries of {@code csv} a run would choke on: one that isn't an {@code aa:mod_residue} pair,
     * and one naming a token the vocabulary doesn't define. Both kill a database search, verified
     * against Casanovo 5.2.1, and neither error names the setting or the entry: the first raises
     * "not enough values to unpack" where {@code _construct_mods_dict} splits the entry on ':', the
     * second "Unrecognized token" much later, when depthcharge tokenizes a modified peptide.
     *
     * <p>Judged on the raw value, split the way Casanovo splits it and with nothing tidied away:
     * {@link #splitEntries} trims each entry and drops the blank ones, and every one of those
     * kindnesses hides a real failure — Casanovo strips nothing, so {@code "M:M[Ox], N:N[De]"} makes
     * the second amino acid {@code " N"} and the modification simply never fires, and it unpacks
     * every piece, so a trailing or doubled comma raises "not enough values to unpack". An empty
     * {@code tokens} means the vocabulary couldn't be read; only the pair check applies then, since
     * reporting every entry against nothing would just be noise. A wholly blank value is not
     * reported here — it is its own, separate problem.</p>
     */
    public static List<String> unresolvable(String csv, List<String> tokens) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String entry : csv.split(",", -1)) {
            if (!isPair(entry) || (!tokens.isEmpty() && !tokens.contains(tokenOf(entry)))) {
                out.add(entry);
            }
        }
        return out;
    }

    /**
     * The reverse of {@link #unresolvable}: vocabulary tokens carrying a modification that no entry
     * in {@code csvs} references. These don't fail a run — they narrow it. Casanovo makes the same
     * check when it builds the protein database and logs "Modified residue '...' is not specified as
     * a fixed or variable modification for database search. Peptides with this modification will not
     * be considered." (db_utils.py, verified against 5.2.1), so the setting is only visible after a
     * run has started; reporting it in the dialog says it while it can still be acted on.
     *
     * <p>Casanovo's own version of this check ignores the variable-mod list entirely when
     * {@code max_mods} is 0, and then reports every variable-mod token. Not mirrored here: a blank
     * {@code max_mods} means Casanovo's default, whatever the installed version's is, so a GUI-side
     * guess about the threshold would be wrong more often than the check is useful.</p>
     */
    public static List<String> unreferencedTokens(String residuesText, String... csvs) {
        Set<String> referenced = new HashSet<>();
        for (String csv : csvs) {
            for (String entry : splitEntries(csv)) {
                referenced.add(tokenOf(entry));
            }
        }
        List<String> out = new ArrayList<>();
        for (String token : CasanovoConfig.residueTokens(residuesText)) {
            // Casanovo's condition exactly: a token is "modified" if its name contains a bracket. That
            // takes in tokens no mod list could name (no derivable amino acid), which is right — those
            // are exactly the ones that can never be searched.
            if (token.indexOf('[') >= 0 && !referenced.contains(token)) {
                out.add(token);
            }
        }
        return out;
    }

    /**
     * True when Casanovo can read {@code entry} as an {@code aa:mod_residue} pair: exactly one colon,
     * neither half empty, no comma, and no whitespace anywhere. {@code mod.split(":")} is unpacked
     * into exactly two names, so a second colon raises "too many values to unpack" and none raises
     * "not enough"; a comma is where the value was cut into entries in the first place, so one inside
     * an entry means it was never a whole entry; and since nothing is stripped, a single stray space
     * becomes part of the amino acid, which fails silently — the run finishes having never applied
     * that modification.
     *
     * <p>Judges a synthesised entry as readily as a typed one, which is what lets {@link #modTokens}
     * withhold a checkbox whose entry could never be read.</p>
     */
    private static boolean isPair(String entry) {
        int colon = entry.indexOf(':');
        return colon > 0
                && colon == entry.lastIndexOf(':')
                && colon < entry.length() - 1
                && entry.indexOf(',') < 0
                && entry.codePoints().noneMatch(ModList::isSpace);
    }

    /** Whitespace as a user can produce it, including the non-breaking space a paste can carry in. */
    private static boolean isSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static String prefixFor(String token) {
        if (NTERM.matcher(token).matches()) {
            return "nterm";
        }
        if (CTERM.matcher(token).matches()) {
            return "cterm";
        }
        int bracket = token.indexOf('[');
        return bracket > 0 ? token.substring(0, bracket) : null;
    }
}
