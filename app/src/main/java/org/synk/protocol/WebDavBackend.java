package org.synk.protocol;

import org.synk.core.IgnoreMatcher;
import org.synk.core.SyncModels.AccountConfig;
import org.synk.core.SyncModels.FileEntry;
import org.synk.core.SyncProgress;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure RFC 4918 WebDAV backend supporting Nextcloud, OwnCloud, Seafile (/seafdav),
 * SabreDAV, Apache mod_dav, and generic WebDAV servers.
 */
public final class WebDavBackend implements RemoteBackend {

    private static final byte[] PROPFIND_BODY = (
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<d:propfind xmlns:d=\"DAV:\">" +
            "<d:prop>" +
            "<d:resourcetype/>" +
            "<d:getcontentlength/>" +
            "<d:getlastmodified/>" +
            "<d:getetag/>" +
            "</d:prop>" +
            "</d:propfind>"
    ).getBytes(StandardCharsets.UTF_8);

    private final String baseUrl;
    private final String authHeader;
    private final boolean trustAllCerts;

    public WebDavBackend(AccountConfig cfg) {
        String u = cfg.url == null ? "" : cfg.url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        this.baseUrl = u;
        this.authHeader = (cfg.username != null && !cfg.username.isEmpty())
                || (cfg.password != null && !cfg.password.isEmpty())
                ? HttpUtil.basicAuth(cfg.username, cfg.password) : null;
        this.trustAllCerts = cfg.trustAllCerts;
    }

    private Map<String, String> baseHeaders() {
        Map<String, String> h = new HashMap<>();
        if (authHeader != null) h.put("Authorization", authHeader);
        return h;
    }

    private String resolveUrl(String remoteBasePath, String relPath, boolean trailingSlash) {
        String combined = combinePath(remoteBasePath, relPath);
        String url = combined.isEmpty() ? baseUrl : HttpUtil.joinUrl(baseUrl, combined);
        if (trailingSlash && !url.endsWith("/")) url += "/";
        if (!trailingSlash && url.endsWith("/") && url.length() > baseUrl.length()) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static String combinePath(String base, String rel) {
        String b = trimSlashes(base);
        String r = trimSlashes(rel);
        if (b.isEmpty()) return r;
        if (r.isEmpty()) return b;
        return b + "/" + r;
    }

    private static String trimSlashes(String s) {
        if (s == null) return "";
        String t = s.trim();
        while (t.startsWith("/")) t = t.substring(1);
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    @Override
    public String testConnection() throws IOException {
        Map<String, String> h = baseHeaders();
        h.put("Depth", "0");
        h.put("Content-Type", "application/xml; charset=utf-8");
        HttpUtil.Response r = HttpUtil.request("PROPFIND", baseUrl + "/", h, PROPFIND_BODY, trustAllCerts);
        if (r.status == 207 || r.status == 200) {
            String srv = r.header("server");
            return "WebDAV OK (" + r.status + (srv != null ? " " + srv : "") + ")";
        }
        if (r.status == 401 || r.status == 403) {
            throw new IOException("WebDAV auth failed: HTTP " + r.status);
        }
        throw new IOException("WebDAV connection failed: HTTP " + r.status + " " + r.statusMessage);
    }

    @Override
    public List<FileEntry> listChildren(String remoteDirPath) throws IOException {
        List<FileEntry> out = listSingleDir(remoteDirPath, "");
        Collections.sort(out, new Comparator<FileEntry>() {
            @Override
            public int compare(FileEntry a, FileEntry b) {
                if (a.isDir != b.isDir) return a.isDir ? -1 : 1;
                return a.relPath.compareToIgnoreCase(b.relPath);
            }
        });
        return out;
    }

    private List<FileEntry> listSingleDir(String remoteBasePath, String dirRel) throws IOException {
        List<FileEntry> out = new ArrayList<>();
        String dirUrl = resolveUrl(remoteBasePath, dirRel, true);
        String dirPathNorm = normalizeUriPath(dirUrl);

        Map<String, String> h = baseHeaders();
        h.put("Depth", "1");
        h.put("Content-Type", "application/xml; charset=utf-8");
        HttpUtil.Response resp = HttpUtil.request("PROPFIND", dirUrl, h, PROPFIND_BODY, trustAllCerts);
        if (resp.status == 404) return out;
        if (resp.status != 207 && resp.status != 200) {
            throw new IOException("PROPFIND failed (" + resp.status + ") at " + dirUrl);
        }

        MiniXml.Node root = MiniXml.parse(resp.bodyString());
        List<MiniXml.Node> responses = root.findAllDescendants("response");
        for (MiniXml.Node rn : responses) {
            String href = rn.childText("href");
            if (href == null || href.trim().isEmpty()) continue;
            String itemPathNorm = normalizeUriPath(href.trim());
            if (itemPathNorm.equals(dirPathNorm)) continue;

            String childName = extractChildName(dirPathNorm, itemPathNorm);
            if (childName == null || childName.isEmpty() || childName.indexOf('/') >= 0) continue;

            MiniXml.Node prop = findOkProp(rn);
            if (prop == null) continue;

            MiniXml.Node resType = prop.firstChild("resourcetype");
            boolean isDir = (resType != null && resType.firstChild("collection") != null)
                    || href.trim().endsWith("/");

            String childRel = dirRel.isEmpty() ? childName : (dirRel + "/" + childName);
            long size = 0L;
            String lenStr = prop.childText("getcontentlength");
            if (!isDir && lenStr != null && !lenStr.trim().isEmpty()) {
                try { size = Long.parseLong(lenStr.trim()); } catch (NumberFormatException ignored) {}
            }
            long mtime = HttpUtil.parseHttpDate(prop.childText("getlastmodified"));
            String etag = HttpUtil.stripQuotes(prop.childText("getetag"));

            out.add(new FileEntry(childRel, isDir, size, mtime, "", etag));
        }
        return out;
    }

    @Override
    public List<FileEntry> listRecursive(String remoteBasePath, String[] ignorePatterns) throws IOException {
        List<FileEntry> result = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add("");

        while (!queue.isEmpty()) {
            String dirRel = queue.poll();
            List<FileEntry> children = listSingleDir(remoteBasePath, dirRel);
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

    private static MiniXml.Node findOkProp(MiniXml.Node responseNode) {
        List<MiniXml.Node> stats = responseNode.childrenNamed("propstat");
        for (MiniXml.Node ps : stats) {
            String st = ps.childText("status");
            if (st == null || st.contains(" 200 ")) {
                MiniXml.Node p = ps.firstChild("prop");
                if (p != null) return p;
            }
        }
        return responseNode.findFirstDescendant("prop");
    }

    private static String normalizeUriPath(String hrefOrUrl) {
        String p = hrefOrUrl;
        if (p.startsWith("http://") || p.startsWith("https://")) {
            try {
                p = new URI(p).getRawPath();
            } catch (Exception ignored) {}
        }
        if (p == null) p = "";
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        p = HttpUtil.decodeUriPath(p);
        while (p.endsWith("/") && p.length() > 1) p = p.substring(0, p.length() - 1);
        if (!p.startsWith("/")) p = "/" + p;
        return p;
    }

    private static String extractChildName(String parentNorm, String itemNorm) {
        String prefix = parentNorm.equals("/") ? "/" : (parentNorm + "/");
        if (!itemNorm.startsWith(prefix)) return null;
        String tail = itemNorm.substring(prefix.length());
        while (tail.endsWith("/")) tail = tail.substring(0, tail.length() - 1);
        return tail;
    }

    @Override
    public FileEntry download(String remoteBasePath, String relPath, File targetFile,
                              SyncProgress.TransferObserver observer) throws IOException {
        String url = resolveUrl(remoteBasePath, relPath, false);
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        HttpUtil.Response resp;
        try (FileOutputStream fos = new FileOutputStream(targetFile)) {
            resp = HttpUtil.requestStreaming("GET", url, baseHeaders(), null, 0L, fos, trustAllCerts, observer);
        }
        if (resp.status < 200 || resp.status >= 300) {
            targetFile.delete();
            throw new IOException("WebDAV GET failed (" + resp.status + ") for " + relPath);
        }
        String etag = HttpUtil.stripQuotes(resp.header("etag"));
        long mtime = HttpUtil.parseHttpDate(resp.header("last-modified"));
        if (mtime > 0) targetFile.setLastModified(mtime);
        return new FileEntry(relPath, false, targetFile.length(),
                mtime > 0 ? mtime : targetFile.lastModified(), "", etag);
    }

    @Override
    public FileEntry upload(String remoteBasePath, String relPath, File sourceFile,
                            SyncProgress.TransferObserver observer) throws IOException {
        String url = resolveUrl(remoteBasePath, relPath, false);
        HttpUtil.Response resp = putFile(url, sourceFile, observer);
        if (resp.status == 409 || resp.status == 404) {
            ensureParentDirs(remoteBasePath, relPath);
            resp = putFile(url, sourceFile, observer);
        }
        if (resp.status < 200 || resp.status >= 300) {
            throw new IOException("WebDAV PUT failed (" + resp.status + ") for " + relPath);
        }
        String etag = HttpUtil.stripQuotes(resp.header("etag"));
        long mtime = HttpUtil.parseHttpDate(resp.header("last-modified"));
        if (etag.isEmpty()) {
            HttpUtil.Response head = HttpUtil.request("HEAD", url, baseHeaders(), null, trustAllCerts);
            if (head.status >= 200 && head.status < 300) {
                etag = HttpUtil.stripQuotes(head.header("etag"));
                long hm = HttpUtil.parseHttpDate(head.header("last-modified"));
                if (hm > 0) mtime = hm;
            }
        }
        return new FileEntry(relPath, false, sourceFile.length(),
                mtime > 0 ? mtime : sourceFile.lastModified(), "", etag);
    }

    private HttpUtil.Response putFile(String url, File sourceFile,
                                      SyncProgress.TransferObserver observer) throws IOException {
        Map<String, String> h = baseHeaders();
        h.put("Content-Type", "application/octet-stream");
        try (FileInputStream fis = new FileInputStream(sourceFile)) {
            return HttpUtil.requestStreaming("PUT", url, h, fis, sourceFile.length(), null, trustAllCerts, observer);
        }
    }

    private void ensureParentDirs(String remoteBasePath, String relPath) throws IOException {
        String full = combinePath(remoteBasePath, relPath);
        int lastSlash = full.lastIndexOf('/');
        if (lastSlash <= 0) return;
        String[] segs = full.substring(0, lastSlash).split("/");
        StringBuilder acc = new StringBuilder();
        for (String seg : segs) {
            if (seg.isEmpty()) continue;
            if (acc.length() > 0) acc.append('/');
            acc.append(seg);
            String url = HttpUtil.joinUrl(baseUrl, acc.toString());
            HttpUtil.Response r = HttpUtil.request("MKCOL", url, baseHeaders(), null, trustAllCerts);
            if (r.status != 201 && r.status != 405 && r.status != 301 && r.status != 200 && r.status != 409) {
                throw new IOException("MKCOL failed (" + r.status + ") for " + acc);
            }
        }
    }

    @Override
    public void mkdir(String remoteBasePath, String relPath) throws IOException {
        ensureParentDirs(remoteBasePath, relPath + "/_");
    }

    @Override
    public void delete(String remoteBasePath, String relPath, boolean isDir) throws IOException {
        String url = resolveUrl(remoteBasePath, relPath, isDir);
        HttpUtil.Response resp = HttpUtil.request("DELETE", url, baseHeaders(), null, trustAllCerts);
        if (resp.status == 404) return;
        if (resp.status < 200 || resp.status >= 300) {
            throw new IOException("WebDAV DELETE failed (" + resp.status + ") for " + relPath);
        }
    }
}
