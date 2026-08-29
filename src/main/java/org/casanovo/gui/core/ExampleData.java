package org.casanovo.gui.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The bundled example MS/MS dataset, and the run folder it is unpacked into.
 *
 * <p>Fifty spectra from a HeLa tryptic digest, small enough to sequence on a CPU in well under
 * a minute. It ships inside the application jar (see the {@code examples} resource entry in
 * {@code pom.xml}) so that "Load Example MS/MS Data" works with no network access &mdash;
 * which matters, because the point of the example is to confirm a fresh installation works,
 * exactly when adding another download would be least welcome.</p>
 *
 * <p>Casanovo takes a filesystem path, not a classpath resource, so the file has to be
 * materialised. It is written into the run's own output folder, which leaves the user with one
 * self-contained directory holding both the input and the results.</p>
 */
public final class ExampleData {

    /** Classpath location of the bundled dataset. */
    private static final String RESOURCE = "/org/casanovo/gui/examples/hela_50_spectra.mgf";

    /** File name used on disk; matches the name in the repository's {@code examples/} folder. */
    public static final String FILE_NAME = "hela_50_spectra.mgf";

    /** {@code Casanovo_20260826_100156} &mdash; sortable, and unique per second. */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private ExampleData() {
    }

    /**
     * The run folder name for a given moment, e.g. {@code Casanovo_20260826_100156}. Takes the
     * time as a parameter rather than reading the clock so the format can be tested directly.
     */
    public static String runFolderName(LocalDateTime when) {
        return "Casanovo_" + STAMP.format(when);
    }

    /** A fresh run folder under the user's home directory, named for the current time. */
    public static Path newRunFolder() {
        return Paths.get(System.getProperty("user.home"), runFolderName(LocalDateTime.now()));
    }

    /**
     * Copy the bundled dataset into {@code dir}, creating the directory if needed.
     *
     * <p>Overwrites an existing copy: the resource is fixed, so re-extracting simply restores a
     * pristine file rather than accumulating variants.</p>
     *
     * @return the extracted file
     * @throws IOException if the resource is missing from the jar, or the copy fails
     */
    public static File extractTo(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path target = dir.resolve(FILE_NAME);
        try (InputStream in = ExampleData.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("The example dataset is missing from this build ("
                        + RESOURCE + "). Please report this as a packaging bug.");
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target.toFile();
    }
}
