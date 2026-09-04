package org.casanovo.gui.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Most of these cover {@link GlissadeInstaller#patchRequiresPython}, the one load-bearing edit in
 * the whole feature: glissade's {@code requires-python = ">=3.13"} is the only reason it will not
 * install into Casanovo's 3.11 environment, and rewriting a line of somebody else's packaging must
 * fail loudly rather than quietly do nothing.
 */
class GlissadeInstallerTest {

    /** glissade's real pyproject.toml at the pinned commit, trimmed to the parts that matter. */
    private static final String REAL_PYPROJECT = """
            [project]
            name = "glissade"
            version = "0.0.1"
            requires-python = ">=3.13"
            dependencies = [
                "numpy<2.0",
                "pandas>2.3.0",
            ]
            """;

    @Test
    @DisplayName("The real pyproject is relaxed to 3.11, leaving everything else alone")
    void patchesRealPyproject() {
        String patched = GlissadeInstaller.patchRequiresPython(REAL_PYPROJECT);
        assertTrue(patched.contains("requires-python = \">=3.11\""));
        assertTrue(!patched.contains("3.13"));
        assertTrue(patched.contains("\"numpy<2.0\""), "dependencies must be untouched");
        assertTrue(patched.contains("name = \"glissade\""));
    }

    @Test
    @DisplayName("Spacing and quote style do not matter")
    void patchesReformatted() {
        String odd = "[project]\n  requires-python='>=3.13'\nname = \"glissade\"\n";
        assertTrue(GlissadeInstaller.patchRequiresPython(odd)
                .contains("requires-python = \">=3.11\""));
    }

    @Test
    @DisplayName("No line, or more than one, fails the install instead of guessing")
    void refusesAmbiguousPyproject() {
        IllegalStateException none = assertThrows(IllegalStateException.class,
                () -> GlissadeInstaller.patchRequiresPython("[project]\nname = \"glissade\"\n"));
        assertTrue(none.getMessage().contains("found 0"));

        IllegalStateException two = assertThrows(IllegalStateException.class,
                () -> GlissadeInstaller.patchRequiresPython(
                        "requires-python = \">=3.13\"\nrequires-python = \">=3.12\"\n"));
        assertTrue(two.getMessage().contains("found 2"));
    }

    @Test
    @DisplayName("Bookkeeping lives beside the other helpers, never inside the venv")
    void bookkeepingLocation(@TempDir Path root) {
        Path dir = GlissadeInstaller.glissadeDir(root);
        assertEquals(root.resolve("glissade"), dir);
        assertTrue(!dir.startsWith(root.resolve(".venv")),
                "a Casanovo reinstall clears .venv; the marker must survive it to be re-checked");
    }

    @Test
    @DisplayName("Installed means the launcher AND the distribution — a cleared venv is not installed")
    void installedNeedsBothHalves(@TempDir Path root) throws IOException {
        Path venv = root.resolve(".venv");
        Path exe = GlissadeInstaller.exeIn(venv);
        Files.createDirectories(exe.getParent());
        Files.writeString(exe, "launcher", StandardCharsets.UTF_8);
        // A launcher with no dist-info is exactly what a half-removed package looks like.
        assertTrue(GlissadeInstaller.installedExeIn(venv).isEmpty());

        Path distInfo = venv.resolve("Lib").resolve("site-packages").resolve("glissade-0.0.1.dist-info");
        Files.createDirectories(distInfo);
        Files.writeString(distInfo.resolve("METADATA"), "Name: glissade\nVersion: 0.0.1\n",
                StandardCharsets.UTF_8);
        assertEquals(Optional.of(exe), GlissadeInstaller.installedExeIn(venv));
    }

    @Test
    @DisplayName("The version marker is read when present and shrugged off when not")
    void versionMarker(@TempDir Path root) throws IOException {
        assertTrue(GlissadeInstaller.installedRef(root).isEmpty());
        Path dir = GlissadeInstaller.glissadeDir(root);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("VERSION.txt"), GlissadeInstaller.GLISSADE_REF + "\n",
                StandardCharsets.UTF_8);
        assertEquals(Optional.of(GlissadeInstaller.GLISSADE_REF),
                GlissadeInstaller.installedRef(root));
        assertEquals("3b70cf9", GlissadeInstaller.shortRef(GlissadeInstaller.GLISSADE_REF));
    }

    @Test
    @DisplayName("Conda and PATH environments are never install targets")
    void neverInstallsIntoForeignEnvironments() {
        assertTrue(GlissadeInstaller.targetVenv("casanovo", false).isEmpty(), "a bare PATH name");
        assertTrue(GlissadeInstaller.targetVenv("C:\\conda\\envs\\x\\Scripts\\casanovo.exe", true)
                .isEmpty(), "a Conda environment");
        assertTrue(GlissadeInstaller.findInstalledExe("anything", true).isEmpty(),
                "Conda: the configured path is conda's, so nothing can be inferred from it");
    }

    @Test
    @DisplayName("A GitHub source archive has exactly one top-level folder")
    void singleChildOfArchive(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("glissade-main"));
        assertEquals(dir.resolve("glissade-main"), GlissadeInstaller.singleChild(dir));

        Files.createDirectories(dir.resolve("unexpected"));
        assertThrows(IOException.class, () -> GlissadeInstaller.singleChild(dir));
    }
}
