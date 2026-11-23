package org.synk.protocol;

import org.synk.core.NativeBridge;
import org.synk.core.SyncModels.AccountConfig;
import org.synk.core.SyncModels.FileEntry;
import org.synk.core.SyncProgress;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Local / mounted directory backend (SD card, USB OTG, SMB/FUSE mount, or offline test target).
 */
public final class LocalBackend implements RemoteBackend {

    private final File rootDir;

    public LocalBackend(AccountConfig cfg) {
        this.rootDir = new File(cfg.url == null ? "/" : cfg.url.trim());
    }

    private File resolve(String remoteBasePath, String relPath) {
        File base = rootDir;
        if (remoteBasePath != null && !remoteBasePath.trim().isEmpty() && !"/".equals(remoteBasePath.trim())) {
            String p = remoteBasePath.trim();
            while (p.startsWith("/")) p = p.substring(1);
            base = new File(base, p);
        }
        if (relPath == null || relPath.isEmpty()) return base;
        return new File(base, relPath);
    }

    @Override
    public String testConnection() throws IOException {
        if (!rootDir.exists() && !rootDir.mkdirs()) {
            throw new IOException("Cannot access local target: " + rootDir.getAbsolutePath());
        }
        if (!rootDir.isDirectory()) {
            throw new IOException("Target is not a directory: " + rootDir.getAbsolutePath());
        }
        return "Local Folder OK (" + rootDir.getAbsolutePath() + ")";
    }

    @Override
    public List<FileEntry> listChildren(String remoteDirPath) throws IOException {
        File dir = resolve(remoteDirPath, "");
        List<FileEntry> out = new ArrayList<>();
        if (!dir.isDirectory()) return out;
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File f : kids) {
                String n = f.getName();
                if (".".equals(n) || "..".equals(n) || n.startsWith(".synk.tmp.")) continue;
                boolean isDir = f.isDirectory();
                out.add(new FileEntry(n, isDir, isDir ? 0L : f.length(), f.lastModified(), "", ""));
            }
        }
        Collections.sort(out, new Comparator<FileEntry>() {
            @Override
            public int compare(FileEntry a, FileEntry b) {
                if (a.isDir != b.isDir) return a.isDir ? -1 : 1;
                return a.relPath.compareToIgnoreCase(b.relPath);
            }
        });
        return out;
    }

    @Override
    public List<FileEntry> listRecursive(String remoteBasePath, String[] ignorePatterns) throws IOException {
        File dir = resolve(remoteBasePath, "");
        if (!dir.exists()) return Collections.emptyList();
        return NativeBridge.scanDir(dir.getAbsolutePath(), ignorePatterns, true);
    }

    @Override
    public FileEntry download(String remoteBasePath, String relPath, File targetFile,
                              SyncProgress.TransferObserver observer) throws IOException {
        File src = resolve(remoteBasePath, relPath);
        if (!src.isFile()) throw new IOException("Remote local file missing: " + src.getAbsolutePath());
        copyFile(src, targetFile, observer);
        targetFile.setLastModified(src.lastModified());
        String hash = NativeBridge.sha256File(targetFile.getAbsolutePath());
        return new FileEntry(relPath, false, targetFile.length(), targetFile.lastModified(), hash, hash);
    }

    @Override
    public FileEntry upload(String remoteBasePath, String relPath, File sourceFile,
                            SyncProgress.TransferObserver observer) throws IOException {
        File dst = resolve(remoteBasePath, relPath);
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(parent, ".synk.tmp." + System.nanoTime());
        copyFile(sourceFile, tmp, observer);
        tmp.setLastModified(sourceFile.lastModified());
        if (!tmp.renameTo(dst)) {
            copyFile(sourceFile, dst, SyncProgress.NOOP_OBSERVER);
            tmp.delete();
        }
        String hash = NativeBridge.sha256File(dst.getAbsolutePath());
        return new FileEntry(relPath, false, dst.length(), dst.lastModified(), hash, hash);
    }

    @Override
    public void mkdir(String remoteBasePath, String relPath) throws IOException {
        File dir = resolve(remoteBasePath, relPath);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Failed to create directory: " + dir.getAbsolutePath());
        }
    }

    @Override
    public void delete(String remoteBasePath, String relPath, boolean isDir) throws IOException {
        File f = resolve(remoteBasePath, relPath);
        if (!f.exists()) return;
        if (isDir) {
            File[] children = f.listFiles();
            if (children != null && children.length > 0) return;
        }
        if (!f.delete()) {
            throw new IOException("Failed to delete: " + f.getAbsolutePath());
        }
    }

    private static void copyFile(File src, File dst, SyncProgress.TransferObserver observer) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        int bufSize = (observer != null && observer.bufferSize() >= 4096) ? observer.bufferSize() : 32768;
        long[] throttleWin = new long[2];
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[bufSize];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (observer != null && observer.isCancelled()) throw new IOException("Sync cancelled");
                out.write(buf, 0, n);
                if (observer != null) {
                    observer.onBytes(n);
                    SyncProgress.throttleIfNeeded(observer, n, throttleWin);
                }
            }
        }
    }
}
