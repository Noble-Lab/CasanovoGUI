package org.casanovo.gui.core;

import java.util.ArrayList;
import java.util.List;

/**
 * A value object describing a single Casanovo invocation: the sub-command
 * (e.g. {@code sequence}, {@code db-search}) and its ordered argument list
 * (everything that comes after {@code casanovo <subcommand>}).
 *
 * <p>Given a {@link Settings} instance it can produce the concrete OS-level
 * command list to hand to a {@link ProcessBuilder}, optionally wrapping the
 * call in {@code conda run} so it executes inside a named Conda environment.</p>
 */
public class CasanovoCommand {

    private final String subcommand;
    private final List<String> arguments;
    private final String accelerator;

    public CasanovoCommand(String subcommand, List<String> arguments) {
        this(subcommand, arguments, null);
    }

    private CasanovoCommand(String subcommand, List<String> arguments, String accelerator) {
        this.subcommand = subcommand;
        this.arguments = new ArrayList<>(arguments);
        this.accelerator = accelerator;
    }

    public String getSubcommand() {
        return subcommand;
    }

    public List<String> getArguments() {
        return new ArrayList<>(arguments);
    }

    /**
     * Whether this command is a de novo sequencing run, as opposed to Evaluate or a database
     * search. Both De novo and Evaluate emit the {@code sequence} subcommand, so Evaluate is told
     * apart by its {@code --evaluate} argument. One copy, because more than one feature keys off
     * it and a rename upstream must not fix only some of them.
     */
    public boolean isDenovoSequencing() {
        return "sequence".equals(subcommand) && !arguments.contains("--evaluate");
    }

    /**
     * The Casanovo {@code accelerator} this run will use, or {@code null} when the run sets none
     * and Casanovo's own default ({@code auto}) therefore applies. An external config file that
     * the GUI could not read yields {@link DeviceProbe#UNKNOWN} instead: "nobody knows" is a
     * different answer from "not set", and only the first has to suppress the device check.
     *
     * <p>It is not part of the command line — it lives in the YAML config — but the launcher
     * needs it to shape the subprocess environment, notably to hide every GPU from a run the
     * user asked to perform on the CPU (see {@code Os.applyNativeEnv}).</p>
     */
    public String getAccelerator() {
        return accelerator;
    }

    /** A copy of this command tagged with the accelerator its config selects. */
    public CasanovoCommand withAccelerator(String accelerator) {
        return new CasanovoCommand(subcommand, arguments, accelerator);
    }

    /**
     * Build the full process command (program + args) according to the supplied
     * settings. When Conda execution is enabled the result is
     * {@code conda run --no-capture-output -n <env> <casanovo> <subcommand> <args...>};
     * otherwise it is simply {@code <casanovo> <subcommand> <args...>}.
     *
     * <p>{@code --no-capture-output} is important: it lets stdout/stderr stream
     * back live so the Console panel updates in real time.</p>
     */
    public List<String> toProcessCommand(Settings settings) {
        List<String> cmd = new ArrayList<>();
        if (settings.isUseConda() && !settings.getCondaEnv().isEmpty()) {
            cmd.add(settings.getCondaExecutable());
            cmd.add("run");
            cmd.add("--no-capture-output");
            cmd.add("-n");
            cmd.add(settings.getCondaEnv());
            cmd.add("casanovo"); // resolve casanovo inside the env; the configured executable path is bypassed
        } else {
            cmd.add(settings.getCasanovoExecutable());
        }
        if (subcommand != null && !subcommand.isEmpty()) {
            cmd.add(subcommand);
        }
        cmd.addAll(arguments);
        return cmd;
    }

    /**
     * Render the command as a single human-readable shell-style string for the
     * "command preview" field. Arguments containing whitespace are quoted.
     */
    public String toDisplayString(Settings settings) {
        StringBuilder sb = new StringBuilder();
        for (String part : toProcessCommand(settings)) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(quoteIfNeeded(part));
        }
        return sb.toString();
    }

    private static String quoteIfNeeded(String part) {
        if (part == null || part.isEmpty()) {
            return "\"\"";
        }
        if (part.chars().anyMatch(Character::isWhitespace)) {
            return '"' + part + '"';
        }
        return part;
    }
}
