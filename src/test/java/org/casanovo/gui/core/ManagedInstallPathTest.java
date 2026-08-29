package org.casanovo.gui.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether the configured Casanovo is the one this application installed decides two things the
 * user sees: whether a blocked run offers "Reinstall Casanovo", and whether Update is available
 * at all. The Settings dialog stores the text the user typed or browsed to, so the comparison has
 * to be about where a path leads, not how it is spelled.
 */
class ManagedInstallPathTest {

    private static boolean samePath(Path a, Path b) {
        return Os.samePath(a, b);
    }

    @Test
    @DisplayName("Two spellings of the same existing file are the same install")
    void redundantSegmentsStillMatch(@TempDir Path dir) throws Exception {
        Path exe = Files.createFile(dir.resolve("casanovo"));
        Path viaDot = dir.resolve(".").resolve("casanovo");
        Path viaParent = Files.createDirectory(dir.resolve("bin")).resolve("..").resolve("casanovo");

        assertTrue(samePath(exe, viaDot), "a '.' segment is the same file");
        assertTrue(samePath(exe, viaParent), "so is a detour through a real directory");
    }

    @Test
    @DisplayName("Different files are not the same install, however similar the names")
    void differentFilesDoNotMatch(@TempDir Path dir) throws Exception {
        Path managed = Files.createFile(dir.resolve("casanovo"));
        Path other = Files.createFile(dir.resolve("casanovo.old"));
        Path elsewhere = Files.createFile(
                Files.createDirectory(dir.resolve("other")).resolve("casanovo"));

        assertFalse(samePath(managed, other));
        assertFalse(samePath(managed, elsewhere));
    }

    @Test
    @DisplayName("A path that does not exist yet is still compared, not treated as a mismatch")
    void missingFilesFallBackToNormalisation() throws Exception {
        // The managed executable is absent before the first install, and Settings can point at a
        // venv that has not been created yet — neither should be reported as a different install.
        Path base = Path.of(System.getProperty("java.io.tmpdir"), "casanovo-gui-absent");
        assertTrue(samePath(base.resolve("casanovo"), base.resolve(".").resolve("casanovo")));
        assertFalse(samePath(base.resolve("casanovo"), base.resolve("casanovo2")));
    }

    @Test
    @DisplayName("An alias of the managed launcher resolves to the known managed venv root")
    void executableAliasCannotRedirectTheManagedRoot(@TempDir Path dir) throws Exception {
        Path venv = dir.resolve(".venv");
        Path bin = Files.createDirectories(venv.resolve(Os.isWindows() ? "Scripts" : "bin"));
        Path expected = Files.createFile(bin.resolve(Os.isWindows() ? "casanovo.exe" : "casanovo"));
        Path alias = Files.createLink(dir.resolve("casanovo-alias"), expected);

        assertEquals(venv.toAbsolutePath().normalize(),
                CasanovoInstaller.managedVenvRoot(alias.toString(), false, dir).orElseThrow());
    }

    @Test
    @DisplayName("A fallback launcher anywhere inside the managed venv remains managed")
    void discoveredLauncherInsideManagedVenvIsAccepted(@TempDir Path dir) throws Exception {
        Path venv = dir.resolve(".venv");
        Path fallback = Files.createFile(Files.createDirectories(venv.resolve("alternate"))
                .resolve(Os.isWindows() ? "casanovo.exe" : "casanovo"));

        assertEquals(venv.toAbsolutePath().normalize(),
                CasanovoInstaller.managedVenvRoot(fallback.toString(), false, dir).orElseThrow());
        assertTrue(CasanovoInstaller.managedVenvRoot(fallback.toString(), true, dir).isEmpty(),
                "Conda mode is never the GUI-managed venv");
    }
}
