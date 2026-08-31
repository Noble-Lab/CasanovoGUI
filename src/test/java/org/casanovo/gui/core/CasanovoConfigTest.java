package org.casanovo.gui.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CasanovoConfigTest {

    @Test
    @DisplayName("A one-run accelerator override changes generated YAML without changing the session")
    void scalarOverrideDoesNotMutateCurrentValues() {
        CasanovoConfig config = new CasanovoConfig();
        config.get("accelerator").setValue("gpu");

        String complete = config.toYaml(Map.of("accelerator", "cpu"));
        String overlaid = config.overlayOnto(
                "accelerator: auto\ndevices: 1\n", Map.of("accelerator", "cpu"));

        assertTrue(complete.contains("accelerator: \"cpu\""), complete);
        assertTrue(overlaid.contains("accelerator: \"cpu\""), overlaid);
        assertEquals("gpu", config.get("accelerator").getValue(),
                "the next run must still use the user's selected accelerator");
    }

    @Test
    @DisplayName("An untouched mod list runs as it reads, cached base config or not")
    void untouchedModListsAgreeAcrossSerializationPaths() {
        // overlayOnto only writes fields the user changed, so a GUI default narrower than Casanovo's
        // own would leave the Parameters dialog showing one modification while the search used seven.
        // These two lines are what `casanovo configure` writes for 5.2.x.
        String base = "allowed_fixed_mods: \"C:C[Carbamidomethyl]\"\n"
                + "allowed_var_mods: \"M:M[Oxidation],N:N[Deamidated],Q:Q[Deamidated],nterm:[Acetyl]-,"
                + "nterm:[Carbamyl]-,nterm:[Ammonia-loss]-,nterm:[+25.980265]-\"\n";
        CasanovoConfig config = new CasanovoConfig();

        assertEquals(base, config.overlayOnto(base), "an untouched field must not be rewritten");
        String complete = config.toYaml();
        for (String line : base.split("\n")) {
            assertTrue(complete.contains(line),
                    "the self-generated config disagrees with the installed default:\n" + line);
        }
    }

    @Test
    @DisplayName("A same-mass alias in the vocabulary still gets its new_token_init bridge")
    void derivesNewTokenInitFromAnAliasPair() {
        // The timsTOF vocabulary shape: a named token sharing a mass with a numeric-delta token, which
        // is what the checkpoint knows. Trailing comments are part of the real file.
        String aliased = "\"M[Oxidation]\": 147.035400              # Met oxidation\n"
                + "\"[+25.980265]-\": 25.980265               # Carbamylation and ammonia loss\n"
                + "\"[Carbamyl][Ammonia-loss]-\": 25.980265  # same mass, named form\n";
        assertEquals("new_token_init:\n  \"[Carbamyl][Ammonia-loss]-\": \"[+25.980265]-\"",
                CasanovoConfig.newTokenInitYaml(aliased));
        assertEquals("new_token_init: {}",
                CasanovoConfig.newTokenInitYaml("\"G\": 57.021464\n\"M[Oxidation]\": 147.035400\n"));
    }

    @Test
    @DisplayName("An unmodified residue is never made a new_token_init target")
    void neverInitialisesABaseResidueFromAModification() {
        // A mass coincidence would otherwise tell Casanovo to initialise glycine from a modification,
        // a token the checkpoint has known all along.
        assertEquals("new_token_init: {}", CasanovoConfig.newTokenInitYaml(
                "\"G\": 57.021464\n\"[+57.021464]-\": 57.021464\n"));
    }

    @Test
    @DisplayName("A vocabulary key is read in any YAML quoting style, and never keeps the quotes")
    void readsEveryKeyQuotingStyle() {
        // Single quotes are valid YAML and Casanovo's loader accepts them. Carrying the quotes into
        // the token name would put "'M:'M[Oxidation]'" into a mod list the moment its box was checked.
        assertEquals(List.of("M[Oxidation]", "[Acetyl]-", "G"), CasanovoConfig.residueTokens(
                "\"M[Oxidation]\": 147.035400\n'[Acetyl]-': 42.010565\nG: 57.021464\n"));
    }

    @Test
    @DisplayName("A token that contains a colon is not cut in half by it")
    void readsAKeyThatContainsAColon() {
        // The mass anchors the end of the line, so a bare key may hold colons of its own.
        assertEquals(List.of("[UNIMOD:1]-"),
                CasanovoConfig.residueTokens("[UNIMOD:1]-: 42.010565\n"));
        assertEquals(List.of("[UNIMOD:1]-"),
                CasanovoConfig.residueTokens("\"[UNIMOD:1]-\": 42.010565  # N-terminal acetyl\n"));
    }

    @Test
    @DisplayName("Masses are read in every numeric form, and nothing but a mass line is read")
    void readsEveryMassFormAndNothingElse() {
        assertEquals(List.of("A", "B", "C", "D", "E"), CasanovoConfig.residueTokens(
                "\"A\": .5\n\"B\": -17.026549\n\"C\": 4.2010565e1\n\"D\": 57\n\"E\": 57.\n"));
        assertEquals(List.of(), CasanovoConfig.residueTokens("# \"G\": 57.021464\nresidues:\n"));
    }

    @Test
    @DisplayName("A quote or a backslash in a value cannot break out of its YAML scalar")
    void escapesTheDoubleQuotedScalar() {
        // YAML reads a backslash in a double-quoted scalar as an escape, so an unescaped Windows path
        // is not merely ugly - "\\U" is not a valid escape and no parser will load the config.
        CasanovoConfig config = new CasanovoConfig();
        config.get("lance_dir").setValue("C:\\Users\\me\\lance");
        config.get("allowed_var_mods").setValue("M:M[Ox\"]");
        String yaml = config.toYaml();
        assertTrue(yaml.contains("lance_dir: \"C:\\\\Users\\\\me\\\\lance\"\n"), yaml);
        assertTrue(yaml.contains("allowed_var_mods: \"M:M[Ox\\\"]\"\n"), yaml);
        // The overlay path splices the same snippet in through a regex replacement, where a backslash
        // means something again.
        String overlaid = config.overlayOnto("lance_dir: \"\"\n");
        assertTrue(overlaid.contains("lance_dir: \"C:\\\\Users\\\\me\\\\lance\"\n"), overlaid);
    }

    @Test
    @DisplayName("Every default mod-list entry resolves against the default vocabulary")
    void modListDefaultsResolveAgainstTheDefaultVocabulary() {
        CasanovoConfig config = new CasanovoConfig();
        List<String> tokens = CasanovoConfig.residueTokens(config.get("residues").getValue());
        for (String key : List.of("allowed_fixed_mods", "allowed_var_mods")) {
            assertEquals(List.of(), ModList.unresolvable(config.get(key).getValue(), tokens), key);
        }
    }
}
