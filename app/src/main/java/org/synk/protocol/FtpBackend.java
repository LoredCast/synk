package org.synk.protocol;

import org.synk.core.IgnoreMatcher;
import org.synk.core.SyncModels.AccountConfig;
import org.synk.core.SyncModels.FileEntry;
import org.synk.core.SyncModels.Protocol;
import org.synk.core.SyncProgress;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java RFC 959 + RFC 4217 FTP / FTPS (Explicit TLS) client with MLSD & Unix LIST support.
 */
public final class FtpBackend implements RemoteBackend {

    private static final Pattern PASV_RE = Pattern.compile("\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)");
    private static final Pattern UNIX_LIST_RE = Pattern.compile(
            "^([dl-])\\S+\\s+\\d+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s+(\\w+\\s+\\d+\\s+[\\d:]+)\\s+(.+)$");

    private final String host;
    private final int port;
    private final String rootPrefix;
    private final String username;
    private final String password;
    private final boolean ftps;
    private final boolean trustAllCerts;

    private Socket controlSocket;
    private BufferedReader reader;
    private BufferedWriter writer;

    public FtpBackend(AccountConfig cfg) {
        this.ftps = cfg.protocol == Protocol.FTPS || (cfg.url != null && cfg.url.toLowerCase(Locale.ROOT).startsWith("ftps://"));
        this.trustAllCerts = cfg.trustAllCerts;
        this.username = (cfg.username == null || cfg.username.isEmpty()) ? "anonymous" : cfg.username;
        this.password = cfg.password == null ? "" : cfg.password;

        String raw = cfg.url == null ? "" : cfg.url.trim();
        if (!raw.contains("://")) raw = (ftps ? "ftps://" : "ftp://") + raw;
        String h = "localhost";
        int p = 21;
        String rp = "";
        try {
            URI u = new URI(raw);
            if (u.getHost() != null) h = u.getHost();
            if (u.getPort() > 0) p = u.getPort();
            if (u.getPath() != null) rp = trimSlashes(u.getPath());
        } catch (Exception ignored) {}
        this.host = h;
        this.port = p;
        this.rootPrefix = rp;
    }

    private synchronized void ensureConnected() throws IOException {
        if (controlSocket != null && controlSocket.isConnected() && !controlSocket.isClosed()) {
            return;
        }
        controlSocket = HttpUtil.openSocket(host, port, false, trustAllCerts);
        controlSocket.setSoTimeout(30_000);
        bindControlStreams();

        Reply greet = readReply();
        if (greet.code != 220) throw new IOException("FTP bad greeting: " + greet.line);

        if (ftps) {
            Reply auth = sendCmd("AUTH TLS");
            if (auth.code != 234) throw new IOException("FTPS AUTH TLS rejected: " + auth.line);
            controlSocket = HttpUtil.wrapTlsSocket(controlSocket, host, port, trustAllCerts);
            bindControlStreams();
            sendCmd("PBSZ 0");
            Reply prot = sendCmd("PROT P");
            if (prot.code != 200) throw new IOException("FTPS PROT P rejected: " + prot.line);
        }

        Reply u = sendCmd("USER " + username);
        if (u.code == 331) {
            Reply p = sendCmd("PASS " + password);
            if (p.code != 230) throw new IOException("FTP login failed: " + p.line);
        } else if (u.code != 230) {
            throw new IOException("FTP USER failed: " + u.line);
        }

        sendCmd("TYPE I");
        sendCmd("OPTS UTF8 ON");
    }

    private void bindControlStreams() throws IOException {
        reader = new BufferedReader(new InputStreamReader(controlSocket.getInputStream(), StandardCharsets.UTF_8));
        writer = new BufferedWriter(new OutputStreamWriter(controlSocket.getOutputStream(), StandardCharsets.UTF_8));
    }

    private static final class Reply {
        final int code;
        final String line;
        Reply(int code, String line) { this.code = code; this.line = line; }
    }

    private synchronized Reply sendCmd(String cmd) throws IOException {
        writer.write(cmd);
        writer.write("\r\n");
        writer.flush();
        return readReply();
    }

    private synchronized Reply readReply() throws IOException {
        String first = reader.readLine();
        if (first == null) throw new IOException("FTP connection closed by server");
        if (first.length() < 3) throw new IOException("Malformed FTP reply: " + first);
        int code = Integer.parseInt(first.substring(0, 3));
        String last = first;
        if (first.length() >= 4 && first.charAt(3) == '-') {
            String prefix = first.substring(0, 3) + " ";
            while (true) {
                String line = reader.readLine();
                if (line == null) break;
                last = line;
                if (line.startsWith(prefix)) break;
            }
        }
        return new Reply(code, last);
    }

    private Socket openPassiveDataSocket() throws IOException {
        Reply r = sendCmd("PASV");
        if (r.code != 227) throw new IOException("FTP PASV failed: " + r.line);
        Matcher m = PASV_RE.matcher(r.line);
        if (!m.find()) throw new IOException("Cannot parse PASV response: " + r.line);
        String ip = m.group(1) + "." + m.group(2) + "." + m.group(3) + "." + m.group(4);
        int dataPort = (Integer.parseInt(m.group(5)) << 8) | Integer.parseInt(m.group(6));
        if ("0.0.0.0".equals(ip)) ip = host;
        Socket s = HttpUtil.openSocket(ip, dataPort, ftps, trustAllCerts);
        s.setSoTimeout(60_000);
        return s;
    }

    private String fullRemotePath(String remoteBasePath, String relPath) {
        String p = joinPaths(rootPrefix, joinPaths(remoteBasePath, relPath));
        return p.isEmpty() ? "/" : ("/" + p);
    }

    private static String joinPaths(String a, String b) {
        String ta = trimSlashes(a);
        String tb = trimSlashes(b);
        if (ta.isEmpty()) return tb;
        if (tb.isEmpty()) return ta;
        return ta + "/" + tb;
    }

    private static String trimSlashes(String s) {
        if (s == null) return "";
        String t = s.trim();
        while (t.startsWith("/")) t = t.substring(1);
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    @Override
    public synchronized String testConnection() throws IOException {
        ensureConnected();
        Reply pwd = sendCmd("PWD");
        return (ftps ? "FTPS" : "FTP") + " OK (" + pwd.line + ")";
    }

    @Override
    public synchronized List<FileEntry> listChildren(String remoteDirPath) throws IOException {
        ensureConnected();
        String absDir = fullRemotePath(remoteDirPath, "");
        List<FileEntry> out = listSingleDir(absDir, "");
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
    public synchronized List<FileEntry> listRecursive(String remoteBasePath, String[] ignorePatterns) throws IOException {
        ensureConnected();
        List<FileEntry> result = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add("");

        while (!queue.isEmpty()) {
            String dirRel = queue.poll();
            String absDir = fullRemotePath(remoteBasePath, dirRel);
            List<FileEntry> children = listSingleDir(absDir, dirRel);
            for (FileEntry child : children) {
                if (IgnoreMatcher.isIgnored(child.relPath, child.isDir, ignorePatterns)) continue;
                result.add(child);
                if (child.isDir) queue.add(child.relPath);
            }
        }

        Collections.sort(result, new Comparator<FileEntry>() {
            @Override
            public int compare(FileEntry a, FileEntry b) {
                return a.relPath.compareTo(b.relPath);
            }
        });
        return result;
    }

    private List<FileEntry> listSingleDir(String absDir, String dirRel) throws IOException {
        List<FileEntry> out = new ArrayList<>();
        boolean useMlsd = true;
        List<String> lines = readDirListing("MLSD " + absDir);
        if (lines == null) {
            useMlsd = false;
            lines = readDirListing("LIST " + absDir);
        }
        if (lines == null) return out;

        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) continue;
            if (useMlsd) {
                parseMlsdLine(line, dirRel, out);
            } else {
                parseUnixListLine(line, dirRel, out);
            }
        }
        return out;
    }

    private List<String> readDirListing(String cmd) throws IOException {
        Socket data = openPassiveDataSocket();
        try {
            Reply r = sendCmd(cmd);
            if (r.code == 500 || r.code == 502 || r.code == 550) {
                data.close();
                return null;
            }
            if (r.code != 150 && r.code != 125) {
                data.close();
                throw new IOException("FTP " + cmd + " failed: " + r.line);
            }
            List<String> lines = new ArrayList<>();
            try (BufferedReader dr = new BufferedReader(new InputStreamReader(data.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = dr.readLine()) != null) lines.add(line);
            }
            readReply(); // 226 Transfer complete
            return lines;
        } finally {
            try { data.close(); } catch (IOException ignored) {}
        }
    }

    private static void parseMlsdLine(String line, String dirRel, List<FileEntry> out) {
        int sp = line.indexOf(' ');
        if (sp <= 0) return;
        String factsStr = line.substring(0, sp);
        String name = line.substring(sp + 1).trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) return;

        String type = "";
        long size = 0L;
        long mtime = 0L;
        for (String fact : factsStr.split(";")) {
            int eq = fact.indexOf('=');
            if (eq <= 0) continue;
            String k = fact.substring(0, eq).toLowerCase(Locale.ROOT);
            String v = fact.substring(eq + 1);
            if ("type".equals(k)) type = v.toLowerCase(Locale.ROOT);
            else if ("size".equals(k)) {
                try { size = Long.parseLong(v); } catch (NumberFormatException ignored) {}
            } else if ("modify".equals(k)) {
                mtime = HttpUtil.parseHttpDate(v.length() >= 14 ? v.substring(0, 14) : v);
            }
        }
        if ("cdir".equals(type) || "pdir".equals(type)) return;
        boolean isDir = "dir".equals(type);
        String rel = dirRel.isEmpty() ? name : (dirRel + "/" + name);
        out.add(new FileEntry(rel, isDir, size, mtime, "", ""));
    }

    private static void parseUnixListLine(String line, String dirRel, List<FileEntry> out) {
        Matcher m = UNIX_LIST_RE.matcher(line);
        if (!m.matches()) return;
        String kind = m.group(1);
        if ("l".equals(kind)) return; // skip symlinks
        boolean isDir = "d".equals(kind);
        long size = 0L;
        try { size = Long.parseLong(m.group(2)); } catch (NumberFormatException ignored) {}
        String name = m.group(4).trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) return;
        String rel = dirRel.isEmpty() ? name : (dirRel + "/" + name);
        out.add(new FileEntry(rel, isDir, size, 0L, "", ""));
    }

    @Override
    public synchronized FileEntry download(String remoteBasePath, String relPath, File targetFile,
                                           SyncProgress.TransferObserver observer) throws IOException {
        ensureConnected();
        String abs = fullRemotePath(remoteBasePath, relPath);
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        int bufSize = (observer != null && observer.bufferSize() >= 4096) ? observer.bufferSize() : 32768;
        long mtime = fetchMdtm(abs);
        Socket data = openPassiveDataSocket();
        try {
            Reply r = sendCmd("RETR " + abs);
            if (r.code != 150 && r.code != 125) {
                throw new IOException("FTP RETR failed for " + relPath + ": " + r.line);
            }
            long[] throttleWin = new long[2];
            try (InputStream in = new BufferedInputStream(data.getInputStream());
                 FileOutputStream fos = new FileOutputStream(targetFile)) {
                byte[] buf = new byte[bufSize];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (observer != null && observer.isCancelled()) throw new IOException("Sync cancelled");
                    fos.write(buf, 0, n);
                    if (observer != null) {
                        observer.onBytes(n);
                        SyncProgress.throttleIfNeeded(observer, n, throttleWin);
                    }
                }
            }
        } finally {
            try { data.close(); } catch (IOException ignored) {}
        }
        Reply done = readReply();
        if (done.code != 226 && done.code != 250) {
            throw new IOException("FTP RETR incomplete: " + done.line);
        }
        if (mtime > 0) targetFile.setLastModified(mtime);
        return new FileEntry(relPath, false, targetFile.length(),
                mtime > 0 ? mtime : targetFile.lastModified(), "", "");
    }

    @Override
    public synchronized FileEntry upload(String remoteBasePath, String relPath, File sourceFile,
                                         SyncProgress.TransferObserver observer) throws IOException {
        ensureConnected();
        String abs = fullRemotePath(remoteBasePath, relPath);
        ensureParentDirs(abs);

        int bufSize = (observer != null && observer.bufferSize() >= 4096) ? observer.bufferSize() : 32768;
        Socket data = openPassiveDataSocket();
        try {
            Reply r = sendCmd("STOR " + abs);
            if (r.code != 150 && r.code != 125) {
                throw new IOException("FTP STOR failed for " + relPath + ": " + r.line);
            }
            long[] throttleWin = new long[2];
            try (FileInputStream fis = new FileInputStream(sourceFile);
                 OutputStream out = data.getOutputStream()) {
                byte[] buf = new byte[bufSize];
                int n;
                while ((n = fis.read(buf)) > 0) {
                    if (observer != null && observer.isCancelled()) throw new IOException("Sync cancelled");
                    out.write(buf, 0, n);
                    if (observer != null) {
                        observer.onBytes(n);
                        SyncProgress.throttleIfNeeded(observer, n, throttleWin);
                    }
                }
                out.flush();
            }
        } finally {
            try { data.close(); } catch (IOException ignored) {}
        }
        Reply done = readReply();
        if (done.code != 226 && done.code != 250) {
            throw new IOException("FTP STOR incomplete: " + done.line);
        }
        long mtime = fetchMdtm(abs);
        return new FileEntry(relPath, false, sourceFile.length(),
                mtime > 0 ? mtime : sourceFile.lastModified(), "", "");
    }

    private long fetchMdtm(String absPath) {
        try {
            Reply r = sendCmd("MDTM " + absPath);
            if (r.code == 213 && r.line.length() >= 18) {
                String ts = r.line.substring(4).trim();
                return HttpUtil.parseHttpDate(ts.length() >= 14 ? ts.substring(0, 14) : ts);
            }
        } catch (Exception ignored) {}
        return 0L;
    }

    private void ensureParentDirs(String absPath) throws IOException {
        int slash = absPath.lastIndexOf('/');
        if (slash <= 0) return;
        String[] segs = absPath.substring(1, slash).split("/");
        StringBuilder acc = new StringBuilder();
        for (String seg : segs) {
            if (seg.isEmpty()) continue;
            acc.append('/').append(seg);
            sendCmd("MKD " + acc);
        }
    }

    @Override
    public synchronized void mkdir(String remoteBasePath, String relPath) throws IOException {
        ensureConnected();
        String abs = fullRemotePath(remoteBasePath, relPath);
        ensureParentDirs(abs + "/_");
    }

    @Override
    public synchronized void delete(String remoteBasePath, String relPath, boolean isDir) throws IOException {
        ensureConnected();
        String abs = fullRemotePath(remoteBasePath, relPath);
        Reply r = sendCmd((isDir ? "RMD " : "DELE ") + abs);
        if (r.code != 250 && r.code != 550) {
            throw new IOException("FTP delete failed for " + relPath + ": " + r.line);
        }
    }

    @Override
    public synchronized void close() {
        if (controlSocket != null) {
            try { sendCmd("QUIT"); } catch (Exception ignored) {}
            try { controlSocket.close(); } catch (Exception ignored) {}
            controlSocket = null;
        }
    }
}
