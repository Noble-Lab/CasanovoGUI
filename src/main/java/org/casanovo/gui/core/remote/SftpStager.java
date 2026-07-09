package org.casanovo.gui.core.remote;

import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.xfer.FileSystemFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.Consumer;

/**
 * Moves a run's files between the local machine and the remote workspace over SFTP: uploads the inputs
 * (recursing into a directory input, i.e. a Bruker {@code .d}) and downloads the results. Every upload is
 * recorded in the returned {@link PathMap} so the command arguments can be rewritten local&rarr;remote and,
 * later, the downloaded mzTab rewritten remote&rarr;local.
 */
public final class SftpStager {

    private final SFTPClient sftp;

    public SftpStager(SFTPClient sftp) {
        this.sftp = sftp;
    }

    /**
     * Upload each input to {@code <remoteInDir>/<index>/<basename>}, recording the mapping. The per-input
     * numbered subdir keeps two inputs that share a basename from overwriting each other. A directory input
     * is uploaded recursively; a regular file with {@link SFTPClient#put(String, String)}.
     *
     * @return a {@link PathMap} of every local input to its (unique) remote location
     */
    public PathMap stageIn(List<File> inputs, String remoteInDir, Consumer<String> onStage)
            throws IOException {
        PathMap map = new PathMap();
        sftp.mkdirs(remoteInDir);
        int total = inputs.size();
        int i = 0;
        for (File input : inputs) {
            i++;
            String name = input.getName();
            // A numbered subdir per input disambiguates same-basename inputs (e.g. two spectra named run.mgf).
            String remoteDir = remoteInDir + "/" + i;
            String remote = remoteDir + "/" + name;
            sftp.mkdirs(remoteDir);
            if (onStage != null) {
                onStage.accept("Uploading " + i + "/" + total + ": " + name);
            }
            if (input.isDirectory()) {
                // Recursive upload of a directory (a Bruker .d); sshj creates <remote> and fills it.
                sftp.getFileTransfer().upload(new FileSystemFile(input), remote);
            } else {
                sftp.put(input.getAbsolutePath(), remote);
            }
            map.put(input, remote);
        }
        return map;
    }

    /**
     * Download every regular file in {@code remoteOutDir} into {@code localOutDir} (the mzTab plus the run
     * logs). Each file lands via a {@code .part} temp that is renamed on completion, so a reader never sees
     * a half-written result.
     */
    public void download(String remoteOutDir, File localOutDir, Consumer<String> onStage)
            throws IOException {
        if (localOutDir != null) {
            localOutDir.mkdirs();
        }
        for (RemoteResourceInfo item : sftp.ls(remoteOutDir)) {
            if (!item.isRegularFile()) {
                continue;
            }
            String name = item.getName();
            if (onStage != null) {
                onStage.accept("Downloading " + name);
            }
            File dest = new File(localOutDir, name);
            File part = new File(localOutDir, name + ".part");
            sftp.get(item.getPath(), part.getAbsolutePath());
            try {
                Files.move(part.toPath(), dest.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException | UnsupportedOperationException e) {
                // ATOMIC_MOVE is not supported on every filesystem; a plain replace still publishes it.
                Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
