package org.casanovo.gui.core;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Thermo {@code .raw} inputs, which Casanovo cannot read: the GUI converts them to {@code .mzML}
 * first and then substitutes the converted paths back into the run.
 */
public final class RawFiles {

    private RawFiles() {
    }

    /** Whether {@code arg} names an existing Thermo {@code .raw} file. */
    public static boolean isRawFile(String arg) {
        return arg != null && arg.toLowerCase(Locale.ROOT).endsWith(".raw") && new File(arg).isFile();
    }

    /**
     * Replace each {@code .raw} argument in {@code command} with its converted {@code .mzML} path,
     * leaving every other argument, and any {@code .raw} the conversion did not produce a target
     * for, exactly as it was.
     */
    public static CasanovoCommand substitutePaths(CasanovoCommand command,
                                                  Map<String, File> targets) {
        List<String> args = new ArrayList<>();
        for (String a : command.getArguments()) {
            File target = isRawFile(a) ? targets.get(new File(a).getAbsolutePath()) : null;
            args.add(target != null ? target.getAbsolutePath() : a);
        }
        // Carry the accelerator over: it is not an argument but a tag the launcher reads to hide
        // every GPU from a CPU run, so dropping it here would silently disarm that guard for
        // every run with .raw input (see Os.applyNativeEnv).
        return new CasanovoCommand(command.getSubcommand(), args)
                .withAccelerator(command.getAccelerator());
    }

    /** Replace each {@code .raw} file with its converted {@code .mzML} file, for "Open in PDV". */
    public static List<File> substituteFiles(List<File> files, Map<String, File> targets) {
        List<File> out = new ArrayList<>();
        for (File f : files) {
            File target = targets.get(f.getAbsolutePath());
            out.add(target != null ? target : f);
        }
        return out;
    }
}
