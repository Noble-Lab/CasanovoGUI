package org.casanovo.gui.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the bundled example dataset. The packaging assertion matters most: the feature's
 * likeliest failure is the resource silently not making it into the artifact, which no amount
 * of source-level correctness would catch.
 */
class ExampleDataTest {

    @Test
    @DisplayName("Run folders are named Casanovo_<date>_<time>")
    void runFolderNaming() {
        assertEquals("Casanovo_20260826_100156",
                ExampleData.runFolderName(LocalDateTime.of(2026, 8, 26, 10, 1, 56)));
        // Zero-padded throughout, so names sort chronologically as plain text.
        assertEquals("Casanovo_20260101_000000",
                ExampleData.runFolderName(LocalDateTime.of(2026, 1, 1, 0, 0, 0)));
        assertEquals("Casanovo_20261231_235959",
                ExampleData.runFolderName(LocalDateTime.of(2026, 12, 31, 23, 59, 59)));
    }

    @Test
    @DisplayName("A run folder is placed in the user's home directory")
    void runFolderLivesInHome() {
        Path folder = ExampleData.newRunFolder();
        assertEquals(Path.of(System.getProperty("user.home")), folder.getParent());
        assertTrue(folder.getFileName().toString().startsWith("Casanovo_"));
    }

    @Test
    @DisplayName("The dataset is on the classpath and extracts as a valid MGF")
    void extractsTheDataset(@TempDir Path dir) throws IOException {
        // Fails loudly if the pom's `examples` resource entry is ever dropped: the file would
        // not be in the jar, and the menu item would break only at run time for the user.
        File mgf = ExampleData.extractTo(dir.resolve("run"));

        assertTrue(mgf.isFile(), "the example should have been written to disk");
        assertEquals(ExampleData.FILE_NAME, mgf.getName());

        List<String> lines = Files.readAllLines(mgf.toPath(), StandardCharsets.UTF_8);
        assertEquals("BEGIN IONS", lines.get(0), "should be an MGF");
        assertEquals(50, lines.stream().filter("BEGIN IONS"::equals).count(),
                "the example is a 50-spectrum dataset");
        assertEquals(50, lines.stream().filter("END IONS"::equals).count(),
                "every spectrum should be closed");
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("PEPMASS=")),
                "Casanovo needs a precursor m/z");
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("CHARGE=")),
                "Casanovo needs a precursor charge");
    }

    @Test
    @DisplayName("Extracting twice restores a pristine copy rather than failing")
    void extractIsRepeatable(@TempDir Path dir) throws IOException {
        File first = ExampleData.extractTo(dir);
        long size = first.length();

        Files.writeString(first.toPath(), "corrupted");
        File second = ExampleData.extractTo(dir);

        assertEquals(first.getAbsolutePath(), second.getAbsolutePath());
        assertEquals(size, second.length(), "a damaged copy should be replaced, not kept");
    }

    @Test
    @DisplayName("The target directory is created when it does not exist")
    void createsTheTargetDirectory(@TempDir Path dir) throws IOException {
        Path nested = dir.resolve("Casanovo_20260826_100156");
        assertTrue(ExampleData.extractTo(nested).isFile());
        assertTrue(Files.isDirectory(nested));
    }
}
