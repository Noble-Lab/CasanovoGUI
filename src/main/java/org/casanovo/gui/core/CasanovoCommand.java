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

    public CasanovoCommand(String subcommand, List<String> arguments) {
        this.subcommand = subcommand;
        this.arguments = new ArrayList<>(arguments);
    }

    public String getSubcommand() {
        return subcommand;
    }

    public List<String> getArguments() {
        return new ArrayList<>(arguments);
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
