package org.synk.protocol;

import org.synk.core.IgnoreMatcher;
import org.synk.core.NativeBridge;
import org.synk.core.SyncModels.AccountConfig;
import org.synk.core.SyncModels.FileEntry;
import org.synk.core.SyncProgress;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pure-Java AWS Signature Version 4 S3-compatible backend
 * (AWS S3, Backblaze B2, Cloudflare R2, MinIO, Wasabi, Hetzner Object Storage).
 */
public final class S3Backend implements RemoteBackend {

    private static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final String endpoint;
    private final String hostHeader;
    private final String bucket;
    private final String region;
    private final String accessKey;
    private final String secretKey;
    private final boolean trustAllCerts;

    public S3Backend(AccountConfig cfg) {
        String ep = cfg.url == null ? "" : cfg.url.trim();
        while (ep.endsWith("/")) ep = ep.substring(0, ep.length() - 1);
        this.endpoint = ep;
        this.accessKey = cfg.username == null ? "" : cfg.username.trim();
        this.secretKey = cfg.password == null ? "" : cfg.password.trim();
        this.trustAllCerts = cfg.trustAllCerts;

        String b = "";
        String r = "us-east-1";
        if (cfg.extra != null && !cfg.extra.trim().isEmpty()) {
            String[] parts = cfg.extra.trim().split("\\|", 2);
            b = parts[0].trim();
            if (parts.length > 1 && !parts[1].trim().isEmpty()) r = parts[1].trim();
        }
        this.bucket = b;
        this.region = r;

        String hh = "";
        try {
            URI u = new URI(this.endpoint);
            hh = u.getHost();
            int port = u.getPort();
            boolean https = "https".equalsIgnoreCase(u.getScheme());
            if (port > 0 && ((https && port != 443) || (!https && port != 80))) {
                hh = hh + ":" + port;
            }
        } catch (Exception ignored) {}
        this.hostHeader = hh;
    }

    @Override
    public String testConnection() throws IOException {
        if (bucket.isEmpty()) throw new IOException("S3 bucket name is required (extra: bucket|region)");
        TreeMap<String, String> query = new TreeMap<>();
        query.put("list-type", "2");
        query.put("max-keys", "1");
        String canonicalUri = "/" + HttpUtil.encodeUriSegment(bucket);
        String canonicalQuery = buildCanonicalQuery(query);
        Map<String, String> headers = signRequest("GET", canonicalUri, canonicalQuery, EMPTY_SHA256);
        String url = endpoint + canonicalUri + "?" + canonicalQuery;
        HttpUtil.Response r = HttpUtil.request("GET", url, headers, null, trustAllCerts);
        if (r.status >= 200 && r.status < 300) {
            return "S3 OK (bucket: " + bucket + ", region: " + region + ")";
        }
        throw new IOException("S3 auth/bucket check failed: HTTP " + r.status + " " + r.statusMessage);
    }

    @Override
    public List<FileEntry> listChildren(String remoteDirPath) throws IOException {
        String prefix = trimSlashes(remoteDirPath);
        if (!prefix.isEmpty()) prefix += "/";

        List<FileEntry> out = new ArrayList<>();
        Set<String> seenDirs = new HashSet<>();
        TreeMap<String, String> query = new TreeMap<>();
        query.put("delimiter", "/");
        query.put("list-type", "2");
        if (!prefix.isEmpty()) query.put("prefix", prefix);

        String canonicalUri = "/" + HttpUtil.encodeUriSegment(bucket);
        String canonicalQuery = buildCanonicalQuery(query);
        Map<String, String> headers = signRequest("GET", canonicalUri, canonicalQuery, EMPTY_SHA256);
        String url = endpoint + canonicalUri + "?" + canonicalQuery;

        HttpUtil.Response resp = HttpUtil.request("GET", url, headers, null, trustAllCerts);
        if (resp.status < 200 || resp.status >= 300) {
            throw new IOException("S3 ListObjectsV2 failed: HTTP " + resp.status);
        }
        MiniXml.Node root = MiniXml.parse(resp.bodyString());
        for (MiniXml.Node cp : root.childrenNamed("commonprefixes")) {
            String p = cp.childText("prefix");
            if (p != null && p.startsWith(prefix)) {
                String name = trimSlashes(p.substring(prefix.length()));
                if (!name.isEmpty() && seenDirs.add(name)) {
                    out.add(new FileEntry(name, true, 0L, 0L, "", ""));
                }
            }
        }
        for (MiniXml.Node c : root.childrenNamed("contents")) {
            String key = c.childText("key");
            if (key == null || !key.startsWith(prefix)) continue;
            String tail = key.substring(prefix.length());
            if (tail.isEmpty()) continue;
            int slash = tail.indexOf('/');
            if (slash >= 0) {
                String dirName = tail.substring(0, slash);
                if (!dirName.isEmpty() && seenDirs.add(dirName)) {
                    out.add(new FileEntry(dirName, true, 0L, 0L, "", ""));
                }
            } else {
                long size = 0L;
                try { size = Long.parseLong(c.childText("size")); } catch (Exception ignored) {}
                long mtime = HttpUtil.parseHttpDate(c.childText("lastmodified"));
                String etag = HttpUtil.stripQuotes(c.childText("etag"));
                out.add(new FileEntry(tail, false, size, mtime, "", etag));
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
        String prefix = trimSlashes(remoteBasePath);
        if (!prefix.isEmpty()) prefix += "/";

        List<FileEntry> out = new ArrayList<>();
        Set<String> seenDirs = new HashSet<>();
        String continuation = null;

        while (true) {
            TreeMap<String, String> query = new TreeMap<>();
            query.put("list-type", "2");
            if (!prefix.isEmpty()) query.put("prefix", prefix);
            if (continuation != null && !continuation.isEmpty()) {
                query.put("continuation-token", continuation);
            }
            String canonicalUri = "/" + HttpUtil.encodeUriSegment(bucket);
            String canonicalQuery = buildCanonicalQuery(query);
            Map<String, String> headers = signRequest("GET", canonicalUri, canonicalQuery, EMPTY_SHA256);
            String url = endpoint + canonicalUri + "?" + canonicalQuery;

            HttpUtil.Response resp = HttpUtil.request("GET", url, headers, null, trustAllCerts);
            if (resp.status < 200 || resp.status >= 300) {
                throw new IOException("S3 ListObjectsV2 failed: HTTP " + resp.status);
            }
            MiniXml.Node root = MiniXml.parse(resp.bodyString());
            for (MiniXml.Node c : root.childrenNamed("contents")) {
                String key = c.childText("key");
                if (key == null || !key.startsWith(prefix)) continue;
                String rel = key.substring(prefix.length());
                if (rel.isEmpty() || rel.endsWith("/")) continue;
                if (IgnoreMatcher.isIgnored(rel, false, ignorePatterns)) continue;

                /* Ensure virtual parent directories are represented */
                addVirtualParents(rel, ignorePatterns, seenDirs, out);

                long size = 0L;
                try { size = Long.parseLong(c.childText("size")); } catch (Exception ignored) {}
                long mtime = HttpUtil.parseHttpDate(c.childText("lastmodified"));
                String etag = HttpUtil.stripQuotes(c.childText("etag"));
                out.add(new FileEntry(rel, false, size, mtime, "", etag));
            }

            String trunc = root.childText("istruncated");
            if ("true".equalsIgnoreCase(trunc)) {
                continuation = root.childText("nextcontinuationtoken");
                if (continuation == null || continuation.isEmpty()) break;
            } else {
                break;
            }
        }

        Collections.sort(out, new Comparator<FileEntry>() {
            @Override
            public int compare(FileEntry a, FileEntry b) {
                return a.relPath.compareTo(b.relPath);
            }
        });
        return out;
    }

    private static void addVirtualParents(String rel, String[] ignores, Set<String> seen, List<FileEntry> out) {
        int idx = rel.indexOf('/');
        while (idx > 0) {
            String dir = rel.substring(0, idx);
            if (!seen.contains(dir) && !IgnoreMatcher.isIgnored(dir, true, ignores)) {
                seen.add(dir);
                out.add(new FileEntry(dir, true, 0L, 0L, "", ""));
            }
            idx = rel.indexOf('/', idx + 1);
        }
    }

    @Override
    public FileEntry download(String remoteBasePath, String relPath, File targetFile,
                              SyncProgress.TransferObserver observer) throws IOException {
        String key = buildKey(remoteBasePath, relPath);
        String canonicalUri = "/" + HttpUtil.encodeUriSegment(bucket) + "/" + HttpUtil.encodePathSegments(key);
        Map<String, String> headers = signRequest("GET", canonicalUri, "", EMPTY_SHA256);

        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        HttpUtil.Response resp;
        try (FileOutputStream fos = new FileOutputStream(targetFile)) {
            resp = HttpUtil.requestStreaming("GET", endpoint + canonicalUri, headers, null, 0L, fos, trustAllCerts, observer);
        }
        if (resp.status < 200 || resp.status >= 300) {
            targetFile.delete();
            throw new IOException("S3 GET failed (" + resp.status + ") for " + relPath);
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
        String key = buildKey(remoteBasePath, relPath);
        String payloadSha = NativeBridge.sha256File(sourceFile.getAbsolutePath());
        String canonicalUri = "/" + HttpUtil.encodeUriSegment(bucket) + "/" + HttpUtil.encodePathSegments(key);
        Map<String, String> headers = signRequest("PUT", canonicalUri, "", payloadSha);
        headers.put("Content-Type", "application/octet-stream");

        HttpUtil.Response resp;
        try (FileInputStream fis = new FileInputStream(sourceFile)) {
            resp = HttpUtil.requestStreaming("PUT", endpoint + canonicalUri, headers,
                    fis, sourceFile.length(), null, trustAllCerts, observer);
        }
        if (resp.status < 200 || resp.status >= 300) {
            throw new IOException("S3 PUT failed (" + resp.status + ") for " + relPath);
        }
        String etag = HttpUtil.stripQuotes(resp.header("etag"));
        return new FileEntry(relPath, false, sourceFile.length(), sourceFile.lastModified(), payloadSha, etag);
    }

    @Override
    public void mkdir(String remoteBasePath, String relPath) {
        /* S3 uses flat key prefixes; directories are implicit when objects are uploaded. */
    }

    @Override
    public void delete(String remoteBasePath, String relPath, boolean isDir) throws IOException {
        if (isDir) return; // Virtual prefixes disappear once children are deleted
        String key = buildKey(remoteBasePath, relPath);
        String canonicalUri = "/" + HttpUtil.encodeUriSegment(bucket) + "/" + HttpUtil.encodePathSegments(key);
        Map<String, String> headers = signRequest("DELETE", canonicalUri, "", EMPTY_SHA256);
        HttpUtil.Response resp = HttpUtil.request("DELETE", endpoint + canonicalUri, headers, null, trustAllCerts);
        if (resp.status != 204 && resp.status != 200 && resp.status != 404) {
            throw new IOException("S3 DELETE failed (" + resp.status + ") for " + relPath);
        }
    }

    private static String buildKey(String base, String rel) {
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

    /* ---- AWS Signature Version 4 ---- */

    public Map<String, String> signRequest(String method, String canonicalUri,
                                           String canonicalQuery, String payloadSha256) throws IOException {
        return signRequestAt(method, canonicalUri, canonicalQuery, payloadSha256, new Date());
    }

    public Map<String, String> signRequestAt(String method, String canonicalUri,
                                             String canonicalQuery, String payloadSha256,
                                             Date now) throws IOException {
        SimpleDateFormat amzFmt = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        amzFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        String amzDate = amzFmt.format(now);
        String dateStamp = amzDate.substring(0, 8);

        TreeMap<String, String> signed = new TreeMap<>();
        signed.put("host", hostHeader);
        signed.put("x-amz-content-sha256", payloadSha256);
        signed.put("x-amz-date", amzDate);

        StringBuilder canonicalHeaders = new StringBuilder();
        StringBuilder signedHeaderNames = new StringBuilder();
        for (Map.Entry<String, String> e : signed.entrySet()) {
            canonicalHeaders.append(e.getKey()).append(':').append(e.getValue().trim()).append('\n');
            if (signedHeaderNames.length() > 0) signedHeaderNames.append(';');
            signedHeaderNames.append(e.getKey());
        }

        String canonicalRequest = method + "\n"
                + canonicalUri + "\n"
                + canonicalQuery + "\n"
                + canonicalHeaders + "\n"
                + signedHeaderNames + "\n"
                + payloadSha256;

        String scope = dateStamp + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n"
                + amzDate + "\n"
                + scope + "\n"
                + NativeBridge.sha256Bytes(canonicalRequest.getBytes(StandardCharsets.UTF_8));

        try {
            byte[] kDate = hmacSha256(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), dateStamp);
            byte[] kRegion = hmacSha256(kDate, region);
            byte[] kService = hmacSha256(kRegion, "s3");
            byte[] kSigning = hmacSha256(kService, "aws4_request");
            String signature = toHex(hmacSha256(kSigning, stringToSign));

            Map<String, String> headers = new TreeMap<>();
            headers.put("Host", hostHeader);
            headers.put("x-amz-date", amzDate);
            headers.put("x-amz-content-sha256", payloadSha256);
            headers.put("Authorization", "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope
                    + ", SignedHeaders=" + signedHeaderNames + ", Signature=" + signature);
            return headers;
        } catch (Exception e) {
            throw new IOException("AWS SigV4 signing failed", e);
        }
    }

    private static String buildCanonicalQuery(TreeMap<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(HttpUtil.encodeUriSegment(e.getKey()))
              .append('=')
              .append(HttpUtil.encodeUriSegment(e.getValue()));
        }
        return sb.toString();
    }

    private static byte[] hmacSha256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String toHex(byte[] bytes) {
        char[] hex = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            out[i * 2] = hex[b >>> 4];
            out[i * 2 + 1] = hex[b & 0x0F];
        }
        return new String(out);
    }
}
