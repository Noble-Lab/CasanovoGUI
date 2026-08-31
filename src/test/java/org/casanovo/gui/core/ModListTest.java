package org.casanovo.gui.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModListTest {

    @Test
    @DisplayName("Entries split on commas, and on the newlines a multi-line custom box can introduce")
    void splitsOnCommasAndNewlines() {
        // A newline left in the value would break the quoted YAML scalar the field is written to.
        assertEquals(List.of("M:M[Oxidation]", "N:N[Deamidated]"),
                ModList.splitEntries("M:M[Oxidation]\nN:N[Deamidated]"));
        assertEquals(List.of("M:M[Oxidation]", "N:N[Deamidated]"),
                ModList.splitEntries("M:M[Oxidation], N:N[Deamidated]"));
        assertEquals(List.of("M:M[Oxidation]", "N:N[Deamidated]"),
                ModList.splitEntries("M:M[Oxidation],\r\n  N:N[Deamidated] ,,\n"));
        assertEquals(List.of(), ModList.splitEntries("  \n , \n "));
    }

    @Test
    @DisplayName("An unbalanced bracket doesn't swallow what follows, on reopen either")
    void splitsOnNewlinesWhateverTheBracketState() {
        // A '[' left unclosed mid-edit must not merge the rest of the box into one entry: that entry
        // would carry a newline into the quoted YAML scalar and Casanovo could not parse the config.
        List<String> split = ModList.splitEntries("M:M[Oxidation\nN:N[Deamidated]");
        assertEquals(List.of("M:M[Oxidation", "N:N[Deamidated]"), split);
        // And it must still be two entries after the editor writes them back as one comma-joined
        // line: a splitter that skipped commas inside brackets would re-read them as a single entry,
        // silently merging the two the moment the dialog was reopened.
        assertEquals(split, ModList.splitEntries(String.join(",", split)));
    }

    @Test
    @DisplayName("Every comma separates, because that is all Casanovo does")
    void splitsOnEveryComma() {
        // Casanovo reads the value as allowed_mods.split(","), so a comma inside a token splits the
        // entry there too and the halves are unusable - verified against 5.2.1, which raises
        // "not enough values to unpack" on "K:K[13C(6),15N(2)]". Keeping such a token whole would let
        // the editor preserve and re-emit a value no run can read.
        assertEquals(List.of("K:K[13C(6)", "15N(2)]"),
                ModList.splitEntries("K:K[13C(6),15N(2)]"));
        assertEquals(List.of("K:K[13C(6)", "15N(2)]"),
                ModList.unresolvable("K:K[13C(6),15N(2)]", List.of("K[13C(6),15N(2)]")));
    }

    @Test
    @DisplayName("An entry Casanovo cannot unpack is reported, whitespace and empties included")
    void flagsWhatCasanovoCannotUnpack() {
        List<String> tokens = List.of("M[Oxidation]", "N[Deamidated]", "[UNIMOD:1]-");
        // A space is never stripped: the amino acid becomes " N" and the modification never fires.
        assertEquals(List.of(" N:N[Deamidated]"),
                ModList.unresolvable("M:M[Oxidation], N:N[Deamidated]", tokens));
        // split(":") is unpacked into exactly two names, so a second colon is "too many values".
        assertEquals(List.of("nterm:[UNIMOD:1]-"),
                ModList.unresolvable("nterm:[UNIMOD:1]-", tokens));
        // Empty pieces are unpacked too, so a trailing or doubled comma is "not enough values".
        assertEquals(List.of(""), ModList.unresolvable("M:M[Oxidation],", tokens));
        assertEquals(List.of(""), ModList.unresolvable("M:M[Oxidation],,N:N[Deamidated]", tokens));
        // A wholly blank value is the empty-list problem, reported on its own elsewhere.
        assertEquals(List.of(), ModList.unresolvable("   ", tokens));
    }

    @Test
    @DisplayName("A token that cannot survive an entry is not offered as a checkbox")
    void refusesToOfferAnUnusableToken() {
        // "[UNIMOD:1]-" and "K[13C(6),15N(2)]" are legal vocabulary keys, but "nterm:[UNIMOD:1]-" and
        // "K:K[13C(6),15N(2)]" are not legal entries — the extra colon and the comma are exactly where
        // Casanovo cuts. A checkbox for either would write a value that fails the run when it is used.
        assertEquals(List.of("M[Oxidation]"), ModList.modTokens(
                "\"M[Oxidation]\": 147.035400\n\"[UNIMOD:1]-\": 42.010565\n"
                        + "\"K[13C(6),15N(2)]\": 136.109162\n"));
    }

    @Test
    @DisplayName("The amino-acid prefix is derived from the token's shape")
    void derivesPrefixFromTokenShape() {
        assertEquals("M:M[Oxidation]", ModList.entryFor("M[Oxidation]"));
        assertEquals("nterm:[Acetyl]-", ModList.entryFor("[Acetyl]-"));
        assertEquals("nterm:[+25.980265]-", ModList.entryFor("[+25.980265]-"));
        assertEquals("cterm:-[Amidated]", ModList.entryFor("-[Amidated]"));
    }

    @Test
    @DisplayName("A bracket-leading token with no flanking dash names no amino acid")
    void refusesToInventAPrefix() {
        // Pairing it with itself would emit "[Oxidation]:[Oxidation]", which Casanovo cannot parse.
        assertNull(ModList.entryFor("[Oxidation]"));
    }

    @Test
    @DisplayName("tokenOf cuts at the first colon, where Casanovo cuts")
    void readsTheTokenOutOfAnEntry() {
        assertEquals("M[Oxidation]", ModList.tokenOf("M:M[Oxidation]"));
        assertEquals("[Acetyl]-", ModList.tokenOf("nterm:[Acetyl]-"));
        assertEquals("M[Oxidation]", ModList.tokenOf("M[Oxidation]"));
        // A token holding a colon of its own cannot be named by an entry at all - Casanovo unpacks
        // split(":") into two names and raises "too many values" - so cutting at the first colon is
        // what it does, and unresolvable() reports such an entry rather than quietly repairing it.
        assertEquals("[UNIMOD:1]-", ModList.tokenOf("nterm:[UNIMOD:1]-"));
        assertEquals("1]-", ModList.tokenOf("[UNIMOD:1]-"));
    }

    @Test
    @DisplayName("Only modified, prefixable tokens are offered, once each")
    void listsModifiedTokensOnce() {
        String residues = "\"G\": 57.021464\n"
                + "\"M[Oxidation]\": 147.035400\n"
                + "\"M[Oxidation]\": 147.035400\n"   // a copy-pasted duplicate line
                + "\"[Acetyl]-\": 42.010565\n"
                + "\"[Oxidation]\": 15.994915\n";    // no flanking dash: no derivable prefix
        assertEquals(List.of("M[Oxidation]", "[Acetyl]-"), ModList.modTokens(residues));
    }

    @Test
    @DisplayName("A vocabulary line is read whether or not its token is quoted")
    void readsUnquotedVocabularyLines() {
        // Both forms are valid YAML and Casanovo accepts both; a checklist that saw only the quoted
        // form would tell the user to add a token that is already there.
        assertEquals(List.of("M[Oxidation]", "[Acetyl]-"),
                ModList.modTokens("G: 57.021464\nM[Oxidation]: 147.035400\n[Acetyl]-: 4.2010565e1\n"));
    }

    @Test
    @DisplayName("An entry that isn't an aa:mod_residue pair is unresolvable, token or no token")
    void flagsEntriesThatAreNotPairs() {
        // "M" names a token the vocabulary really does define, so a check that only looked at the
        // token half would pass it — and Casanovo, which splits every entry on ':', would not.
        List<String> tokens = List.of("M", "M[Oxidation]");
        assertEquals(List.of("M"), ModList.unresolvable("M:M[Oxidation],M", tokens));
        assertEquals(List.of("M:", ":M[Oxidation]"),
                ModList.unresolvable("M:,:M[Oxidation]", tokens));
    }

    @Test
    @DisplayName("An entry naming a token outside the vocabulary is unresolvable")
    void flagsEntriesNamingAnUndefinedToken() {
        assertEquals(List.of("N:N[Deamidated]"),
                ModList.unresolvable("M:M[Oxidation],N:N[Deamidated]", List.of("M[Oxidation]")));
        assertEquals(List.of(),
                ModList.unresolvable("M:M[Oxidation]", List.of("M[Oxidation]")));
    }

    @Test
    @DisplayName("With no vocabulary to check against, only the pair shape is judged")
    void checksOnlyTheShapeWhenTheVocabularyIsUnreadable() {
        // Reporting every entry against an empty vocabulary would be noise, not a finding.
        assertEquals(List.of("M"), ModList.unresolvable("M:M[Oxidation],M", List.of()));
    }

    @Test
    @DisplayName("A modified token no mod list references is reported, across both lists")
    void findsTokensNoModListUses() {
        String residues = "\"G\": 57.021464\n"           // unmodified: never reported
                + "\"C[Carbamidomethyl]\": 160.030649\n" // referenced by the fixed list
                + "\"M[Oxidation]\": 147.035400\n"       // referenced by the variable list
                + "\"S[Phospho]\": 166.998359\n"         // referenced by neither
                + "\"[Acetyl]-\": 42.010565\n";          // referenced by neither
        assertEquals(List.of("S[Phospho]", "[Acetyl]-"), ModList.unreferencedTokens(
                residues, "C:C[Carbamidomethyl]", "M:M[Oxidation]"));
    }

    @Test
    @DisplayName("A token no mod list could name is still reported: it can never be searched")
    void reportsTokensWithNoDerivablePrefix() {
        // Casanovo warns on any token whose name holds a bracket, and it is right to: a bare
        // "[Oxidation]" names no amino acid, so no entry can ever bring it into a search.
        assertEquals(List.of("[Oxidation]"),
                ModList.unreferencedTokens("\"[Oxidation]\": 15.994915\n\"G\": 57.021464\n", ""));
    }

    @Test
    @DisplayName("Casanovo's default vocabulary and mod lists leave nothing unreferenced")
    void defaultsReferenceEveryModifiedToken() {
        // The dialog must stay quiet on an untouched config; this is the assertion that keeps it so.
        CasanovoConfig config = new CasanovoConfig();
        assertEquals(List.of(), ModList.unreferencedTokens(
                config.get("residues").getValue(),
                config.get("allowed_fixed_mods").getValue(),
                config.get("allowed_var_mods").getValue()));
    }
}
