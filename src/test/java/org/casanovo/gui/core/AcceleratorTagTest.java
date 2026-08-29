package org.casanovo.gui.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The accelerator a run will use is not a command-line argument: it is a tag carried alongside
 * the arguments, read from the GUI's parameters or out of an external config file, and used by
 * the launcher to hide every GPU from a CPU run. These are the points where that tag is derived,
 * and the rewrite that could silently drop it.
 */
class AcceleratorTagTest {

    private static CasanovoCommand withConfig(Path config) {
        return new CasanovoCommand("sequence", List.of("--config", config.toString(), "in.mgf"));
    }

    private static Path config(Path dir, String yaml) throws Exception {
        return Files.writeString(dir.resolve("casanovo.yaml"), yaml, StandardCharsets.UTF_8);
    }

    /** What the run's accelerator resolves to from a config file alone. */
    private static String accelerator(CasanovoCommand command) {
        return ConfigFile.forRun(command, false, "ignored", "1024").accelerator();
    }

    private static DeviceProbe.Report cpuOnlyReport() {
        return new DeviceProbe.Report("2.13.0+cpu", null, false, null, null,
                List.of(), false, false, null);
    }

    // ---------------------------------------------------------------- the tag survives a rewrite

    @Test
    @DisplayName("Rewriting a .raw path to its .mzML keeps the accelerator the run was checked for")
    void substitutionKeepsTheAcceleratorTag(@TempDir Path dir) throws Exception {
        File raw = Files.createFile(dir.resolve("run.raw")).toFile();
        File unconverted = Files.createFile(dir.resolve("second.raw")).toFile();
        File mzml = new File(dir.toFile(), "run.mzML");

        CasanovoCommand command = new CasanovoCommand("sequence",
                List.of("--model", "timstof", raw.getAbsolutePath(), "spectra.mgf",
                        unconverted.getAbsolutePath()))
                .withAccelerator("cpu");
        CasanovoCommand rewritten = RawFiles.substitutePaths(command,
                Map.of(raw.getAbsolutePath(), mzml));

        assertEquals("sequence", rewritten.getSubcommand(), "the subcommand survives the rewrite");
        assertEquals(List.of("--model", "timstof", mzml.getAbsolutePath(), "spectra.mgf",
                        unconverted.getAbsolutePath()),
                rewritten.getArguments(),
                "only a .raw with a converted target is substituted; flags, other inputs and a "
                        + ".raw with no target pass through untouched");
        assertEquals("cpu", rewritten.getAccelerator(),
                "a .raw input must not silently turn a CPU run into an untagged one");

        // What the tag is for: an untagged command leaves the GPUs visible to the subprocess.
        ProcessBuilder pb = new ProcessBuilder("casanovo");
        Os.applyNativeEnv(pb, rewritten.getAccelerator());
        assertEquals(Os.NO_CUDA_DEVICES, pb.environment().get("CUDA_VISIBLE_DEVICES"),
                "a CPU run must still hide every GPU after the raw-path rewrite");
    }

    @Test
    @DisplayName("The run's accelerator comes from the GUI, or from the config file it defers to")
    void acceleratorComesFromWhicheverOwnsTheConfiguration(@TempDir Path dir) throws Exception {
        CasanovoCommand gui = new CasanovoCommand("sequence", List.of("in.mgf"));
        CasanovoCommand external = withConfig(config(dir, "accelerator: gpu\n"));

        ConfigFile.RunValues fromGui = ConfigFile.forRun(gui, true, "cpu", "512");
        assertEquals("cpu", fromGui.accelerator(),
                "when the GUI owns the configuration, its own value is the run's");
        assertEquals(512, fromGui.predictBatchSize(),
                "the generated config's batch size is already available in memory");

        assertEquals("gpu", ConfigFile.forRun(external, false, "cpu", "512").accelerator(),
                "an external config decides for itself; the GUI's value does not apply");

        ConfigFile.RunValues guiFallback = ConfigFile.fallback(true, "cpu", "256");
        assertEquals("cpu", guiFallback.accelerator());
        assertEquals(256, guiFallback.predictBatchSize());
        ConfigFile.RunValues externalFallback = ConfigFile.fallback(false, "cpu", "256");
        assertEquals(DeviceProbe.UNKNOWN, externalFallback.accelerator());
        assertEquals(1024, externalFallback.predictBatchSize());
    }

    // ---------------------------------------------------------------- the tag from an external config

    @Test
    @DisplayName("The accelerator is read from a top-level key, past quotes and trailing comments")
    void configValueIsParsed(@TempDir Path dir) throws Exception {
        Path file = config(dir, """
                precursor_mass_tol: 50
                accelerator: "cpu"   # what this run uses
                devices: 1
                """);
        assertEquals("cpu", accelerator(withConfig(file)));
    }

    @Test
    @DisplayName("A repeated accelerator resolves to the last one, the way PyYAML reads it")
    void lastKeyWins(@TempDir Path dir) throws Exception {
        Path file = config(dir, """
                accelerator: cpu
                devices: 1
                accelerator: gpu
                """);
        assertEquals("gpu", accelerator(withConfig(file)));
    }

    @Test
    @DisplayName("A UTF-8 BOM does not hide an accelerator declared on the first line")
    void byteOrderMarkIsIgnored(@TempDir Path dir) throws Exception {
        Path file = config(dir, "﻿accelerator: cpu\ndevices: 1\n");
        assertEquals("cpu", accelerator(withConfig(file)));
    }

    @Test
    @DisplayName("A space before the colon is the same mapping to the parser Casanovo uses")
    void spaceBeforeTheColonStillCounts(@TempDir Path dir) throws Exception {
        Path file = config(dir, "accelerator : cpu\npredict_batch_size\t: 256\n");
        assertEquals("cpu", accelerator(withConfig(file)));
        assertEquals(256, ConfigFile.predictBatchSize(withConfig(file)));
    }

    @Test
    @DisplayName("A uniformly indented file is still a top-level mapping, and still configures the run")
    void uniformIndentationIsStillTopLevel(@TempDir Path dir) throws Exception {
        // yaml.safe_load("  accelerator: cpu\n") is {'accelerator': 'cpu'} — so anchoring at
        // column 0 would report this run as unconfigured and drop its CPU guard.
        Path file = config(dir, """
                  accelerator: cpu
                  predict_batch_size: 64
                """);
        assertEquals("cpu", accelerator(withConfig(file)));
        assertEquals(64, ConfigFile.predictBatchSize(withConfig(file)));
    }

    @Test
    @DisplayName("A YAML document marker does not establish the root mapping's indentation")
    void documentMarkerBeforeIndentedRootIsIgnored(@TempDir Path dir) throws Exception {
        Path file = config(dir, "--- # explicit YAML document\n"
                + "  accelerator: cpu\n"
                + "  predict_batch_size: 64\n"
                + "...\n");

        assertEquals("cpu", accelerator(withConfig(file)));
        assertEquals(64, ConfigFile.predictBatchSize(withConfig(file)));
    }

    @Test
    @DisplayName("An accelerator nested under another block is not the run's own")
    void nestedKeysAreNotTheRunSetting(@TempDir Path dir) throws Exception {
        Path file = config(dir, """
                devices: 1
                trainer:
                  accelerator: gpu
                  predict_batch_size: 64
                """);
        assertNull(accelerator(withConfig(file)));
        assertEquals(1024, ConfigFile.predictBatchSize(withConfig(file)), "Casanovo's own default");
    }

    @Test
    @DisplayName("No config, a missing file, or no accelerator key all mean Casanovo's own default")
    void unsetMeansAuto(@TempDir Path dir) throws Exception {
        assertNull(accelerator(new CasanovoCommand("sequence", List.of("in.mgf"))),
                "no --config at all");
        assertNull(accelerator(withConfig(dir.resolve("absent.yaml"))),
                "a --config path that does not exist");
        assertNull(accelerator(withConfig(config(dir, "devices: 1\n"))),
                "a config without the key");

        // "unset" is knowable — Casanovo applies auto — so the device check may pass it cleanly.
        assertEquals(DeviceProbe.Status.OK,
                DeviceProbe.validate(null, cpuOnlyReport()).status());
    }

    @Test
    @DisplayName("Bytes that are not valid UTF-8 do not cost the run its accelerator")
    void undecodableBytesDoNotHideTheAccelerator(@TempDir Path dir) throws Exception {
        // A stray Latin-1 byte in a comment used to make the whole file unreadable, reported as
        // "unknown" — and an unknown accelerator is not "cpu", so the run silently lost the
        // CUDA_VISIBLE_DEVICES guard the user asked for.
        byte[] ascii = "accelerator: cpu  # caf".getBytes(StandardCharsets.US_ASCII);
        byte[] bytes = new byte[ascii.length + 2];
        System.arraycopy(ascii, 0, bytes, 0, ascii.length);
        bytes[ascii.length] = (byte) 0xE9;      // 'e-acute' in Latin-1; not valid UTF-8 alone
        bytes[ascii.length + 1] = (byte) '\n';
        Path file = Files.write(dir.resolve("casanovo.yaml"), bytes);

        assertEquals("cpu", accelerator(withConfig(file)));

        // UNKNOWN is reserved for a file that genuinely could not be read; it warns rather than
        // passing as auto, which is the distinction the sentinel exists for.
        assertEquals(DeviceProbe.Status.WARN,
                DeviceProbe.validate(DeviceProbe.UNKNOWN, cpuOnlyReport()).status());
    }

    @Test
    @DisplayName("Both values a run needs come from a single pass over the file")
    void runValuesAreReadTogether(@TempDir Path dir) throws Exception {
        Path file = config(dir, """
                accelerator: cpu
                predict_batch_size: 256
                """);
        ConfigFile.RunValues values = ConfigFile.forRun(withConfig(file), false, null, null);
        assertEquals("cpu", values.accelerator());
        assertEquals(256, values.predictBatchSize());

        // Defaults when the file says nothing, and the unknown sentinel when it cannot be read.
        ConfigFile.RunValues silent = ConfigFile.forRun(
                withConfig(config(dir, "devices: 1\n")), false, null, null);
        assertNull(silent.accelerator());
        assertEquals(1024, silent.predictBatchSize(), "Casanovo's own default");
    }

    @Test
    @DisplayName("One read serves both keys the run needs from an external config")
    void oneReadServesBothKeys(@TempDir Path dir) throws Exception {
        Path file = config(dir, """
                predict_batch_size: 512   # per device
                accelerator: cpu
                """);
        ConfigFile.RunValues values = ConfigFile.forRun(withConfig(file), false, null, null);
        assertEquals(512, values.predictBatchSize());
        assertEquals("cpu", values.accelerator());
    }
}
