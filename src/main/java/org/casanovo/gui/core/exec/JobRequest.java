package org.casanovo.gui.core.exec;

import org.casanovo.gui.core.CasanovoCommand;
import org.casanovo.gui.core.Settings;

import java.io.File;
import java.util.List;

/**
 * A backend-agnostic Casanovo job: the command (built with LOCAL paths, as the panes produce it)
 * plus what a non-local backend must stage. {@link LocalBackend} uses only {@code command},
 * {@code settings} and {@code workingDir}; a remote backend additionally uploads {@code inputs} and
 * downloads results into {@code outputDir}.
 *
 * @param command    the subcommand + args, with local paths
 * @param settings   execution settings (local executable path / conda env)
 * @param workingDir process working directory, or {@code null}
 * @param inputs     local inputs a remote backend must upload (spectra files/.d dirs, FASTA, the
 *                   generated config, a local model {@code .ckpt}); empty for local runs
 * @param outputDir  local directory that should receive results (equals {@code workingDir} today)
 */
public record JobRequest(CasanovoCommand command,
                         Settings settings,
                         File workingDir,
                         List<File> inputs,
                         File outputDir) {
}
