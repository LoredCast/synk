package org.synk.protocol;

import org.synk.core.SyncModels.FileEntry;
import org.synk.core.SyncProgress;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Uniform remote storage abstraction implemented by WebDAV/Nextcloud/Seafile,
 * FTP/FTPS, S3-compatible cloud providers, and Local/Mounted folders.
 */
public interface RemoteBackend extends Closeable {

    /** Verifies connectivity and credentials. Returns a short human-readable server info string. */
    String testConnection() throws IOException;

    /** Lists direct children (directories & files) of remoteDirPath for interactive remote browsing. */
    List<FileEntry> listChildren(String remoteDirPath) throws IOException;

    /** Recursively lists all non-ignored files and directories under remoteBasePath. */
    List<FileEntry> listRecursive(String remoteBasePath, String[] ignorePatterns) throws IOException;

    /** Downloads a single file at remoteBasePath/relPath to targetFile with live progress observer. */
    FileEntry download(String remoteBasePath, String relPath, File targetFile,
                       SyncProgress.TransferObserver observer) throws IOException;

    /** Uploads sourceFile to remoteBasePath/relPath with live progress observer. */
    FileEntry upload(String remoteBasePath, String relPath, File sourceFile,
                     SyncProgress.TransferObserver observer) throws IOException;

    default FileEntry download(String remoteBasePath, String relPath, File targetFile) throws IOException {
        return download(remoteBasePath, relPath, targetFile, SyncProgress.NOOP_OBSERVER);
    }

    default FileEntry upload(String remoteBasePath, String relPath, File sourceFile) throws IOException {
        return upload(remoteBasePath, relPath, sourceFile, SyncProgress.NOOP_OBSERVER);
    }

    /** Creates directory remoteBasePath/relPath (idempotent if it already exists). */
    void mkdir(String remoteBasePath, String relPath) throws IOException;

    /** Deletes file or directory remoteBasePath/relPath (idempotent if already absent). */
    void delete(String remoteBasePath, String relPath, boolean isDir) throws IOException;

    @Override
    default void close() throws IOException {}
}
