package org.casanovo.gui.core;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Locates a real {@code java} launcher to spawn external tools (PDV, the Limelight
 * jars), robust across launch modes. The native jpackage launcher boots the JVM
 * directly via libjli, so a bundled {@code java} may not be on the PATH — this walks
 * the per-OS app-image layouts to find one.
 *
 * <p>Search order:</p>
 * <ol>
 *   <li>{@code ProcessHandle} command if it is already a java launcher;</li>
 *   <li>if this app was started from a jpackage native launcher
 *       (e.g. {@code CasanovoGUI.exe}), the bundled {@code runtime/bin/java};</li>
 *   <li>{@code java.home/bin/java};</li>
 *   <li>{@code JAVA_HOME/bin/java};</li>
 *   <li>bare {@code java} on PATH.</li>
 * </ol>
 */
public final class JavaLauncher {

    private JavaLauncher() {
    }

    /** Find a usable {@code java} launcher, logging the candidates considered. */
    public static String find(Consumer<String> log) {
        String javaName = Os.isWindows() ? "java.exe" : "java";

        String home = System.getProperty("java.home");
        log.accept("[java] java.home = " + home);

        String procCmd = null;
        try {
            Optional<String> cmd = ProcessHandle.current().info().command();
            if (cmd.isPresent()) {
                procCmd = cmd.get();
            }
        } catch (Throwable ignored) {
        }
        log.accept("[java] launcher command = " + procCmd);

        List<File> candidates = new ArrayList<>();
        // 1. The launcher itself, if it is already a java binary.
        if (procCmd != null) {
            String lc = procCmd.toLowerCase();
            if (lc.endsWith("java.exe") || lc.endsWith(File.separator + "java") || lc.equals("java")) {
                candidates.add(new File(procCmd));
            }
            // 2. jpackage app-image layouts relative to the native launcher, which differ per OS:
            //      Windows : <app>\CasanovoGUI.exe       -> <app>\runtime\bin\java.exe
            //      Linux   : <app>/bin/CasanovoGUI       -> <app>/lib/runtime/bin/java
            //      macOS   : <app>.app/Contents/MacOS/.. -> <app>.app/Contents/runtime/Contents/Home/bin/java
            File dir = new File(procCmd).getParentFile();
            if (dir != null) {
                candidates.add(new File(dir, "runtime/bin/" + javaName));   // Windows: beside the exe
                candidates.add(new File(dir, javaName));
                File up = dir.getParentFile();
                if (up != null) {
                    candidates.add(new File(up, "runtime/bin/" + javaName));
                    candidates.add(new File(up, "lib/runtime/bin/" + javaName));            // Linux app-image
                    candidates.add(new File(up, "runtime/Contents/Home/bin/" + javaName));  // macOS .app
                }
            }
        }
        // 3. The current runtime (java.home) -- the bundled jpackage runtime on every OS, once
        //    the build copies the stripped launcher back into its bin. Works for Win/Linux/macOS.
        if (home != null && !home.isEmpty()) {
            candidates.add(new File(home, "bin/" + javaName));
            File hp = new File(home).getParentFile();
            if (hp != null) {
                candidates.add(new File(hp, "bin/" + javaName));
                candidates.add(new File(hp, "runtime/bin/" + javaName));
            }
        }
        // 4. JAVA_HOME environment variable.
        String envHome = System.getenv("JAVA_HOME");
        if (envHome != null && !envHome.isEmpty()) {
            candidates.add(new File(envHome, "bin/" + javaName));
        }

        for (File c : candidates) {
            if (c.isFile()) {
                log.accept("[java] using java: " + c.getAbsolutePath());
                return c.getAbsolutePath();
            }
        }
        log.accept("[java] no bundled java found; falling back to '" + javaName + "' on PATH.");
        return javaName;
    }
}
