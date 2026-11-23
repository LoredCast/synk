package org.synk.test;

import org.synk.core.NativeBridge;
import org.synk.protocol.HttpUtil;
import org.synk.protocol.MiniXml;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * Embedded loopback HTTP/1.1 server speaking RFC 4918 WebDAV, CalDAV, CardDAV, and S3 ListObjectsV2.
 */
public final class MockWebDavServer implements Closeable {

    public static final class StoredItem {
        public boolean isDir;
        public byte[] data;
        public long mtime;
        public String etag;
        public String contentType;
    }

    private final ServerSocket serverSocket;
    private final Thread acceptThread;
    private final Map<String, StoredItem> items = new TreeMap<>();
    private final String expectedAuth;
    private volatile boolean running = true;

    public MockWebDavServer(String user, String pass) throws IOException {
        this.expectedAuth = HttpUtil.basicAuth(user, pass);
        putDir("/");
        putDir("/dav");
        putDir("/cal");
        putDir("/ab");

        this.serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        final Socket sock = serverSocket.accept();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                handleClient(sock);
                            }
                        }).start();
                    } catch (IOException e) {
                        if (!running) break;
                    }
                }
            }
        }, "mock-webdav");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    public String baseUrl(String prefix) {
        return "http://127.0.0.1:" + getPort() + prefix;
    }

    public synchronized void putDir(String path) {
        String p = norm(path);
        StoredItem it = new StoredItem();
        it.isDir = true;
        it.mtime = System.currentTimeMillis();
        it.etag = "dir-" + it.mtime;
        items.put(p, it);
    }

    public synchronized String putFile(String path, byte[] data, String contentType) {
        String p = norm(path);
        ensureParents(p);
        StoredItem it = new StoredItem();
        it.isDir = false;
        it.data = data.clone();
        it.mtime = System.currentTimeMillis();
        it.etag = NativeBridge.sha256Bytes(data).substring(0, 16) + "-" + it.mtime;
        it.contentType = contentType != null ? contentType : "application/octet-stream";
        items.put(p, it);
        return it.etag;
    }

    public synchronized byte[] getFile(String path) {
        StoredItem it = items.get(norm(path));
        return (it != null && !it.isDir) ? it.data.clone() : null;
    }

    public synchronized void removeItem(String path) {
        String p = norm(path);
        items.remove(p);
        String prefix = p + "/";
        items.keySet().removeIf(k -> k.startsWith(prefix));
    }

    private void ensureParents(String p) {
        int idx = p.indexOf('/', 1);
        while (idx > 0) {
            String anc = p.substring(0, idx);
            if (!items.containsKey(anc)) putDir(anc);
            idx = p.indexOf('/', idx + 1);
        }
    }

    private static String norm(String p) {
        if (p == null || p.isEmpty()) return "/";
        String d = HttpUtil.decodeUriPath(p);
        while (d.endsWith("/") && d.length() > 1) d = d.substring(0, d.length() - 1);
        if (!d.startsWith("/")) d = "/" + d;
        return d;
    }

    private void handleClient(Socket sock) {
        try (Socket s = sock) {
            BufferedInputStream in = new BufferedInputStream(s.getInputStream());
            BufferedOutputStream out = new BufferedOutputStream(s.getOutputStream());

            String reqLine = readLine(in);
            if (reqLine == null || reqLine.isEmpty()) return;
            String[] parts = reqLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String rawTarget = parts[1];

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) headers.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT),
                        line.substring(c + 1).trim());
            }

            byte[] body = new byte[0];
            String cl = headers.get("content-length");
            if (cl != null) {
                int len = Integer.parseInt(cl.trim());
                body = new byte[len];
                int off = 0;
                while (off < len) {
                    int n = in.read(body, off, len - off);
                    if (n < 0) break;
                    off += n;
                }
            }

            String pathPart = rawTarget;
            String queryPart = "";
            int q = rawTarget.indexOf('?');
            if (q >= 0) {
                pathPart = rawTarget.substring(0, q);
                queryPart = rawTarget.substring(q + 1);
            }

            /* S3 route: /s3/<bucket>... verified by AWS4-HMAC-SHA256 header presence */
            if (pathPart.startsWith("/s3/")) {
                handleS3(method, pathPart, queryPart, headers, body, out);
                return;
            }

            String auth = headers.get("authorization");
            if (!expectedAuth.equals(auth)) {
                send(out, 401, "Unauthorized", "text/plain", new byte[0], null);
                return;
            }

            String path = norm(pathPart);
            if ("PROPFIND".equals(method)) {
                handlePropfind(path, headers.get("depth"), out);
            } else if ("GET".equals(method) || "HEAD".equals(method)) {
                handleGet(path, "HEAD".equals(method), out);
            } else if ("PUT".equals(method)) {
                String ctype = headers.get("content-type");
                String etag = putFile(path, body, ctype);
                Map<String, String> rh = new HashMap<>();
                rh.put("ETag", "\"" + etag + "\"");
                rh.put("Last-Modified", httpDate(System.currentTimeMillis()));
                send(out, 201, "Created", "text/plain", new byte[0], rh);
            } else if ("MKCOL".equals(method)) {
                synchronized (this) {
                    if (items.containsKey(path)) {
                        send(out, 405, "Method Not Allowed", "text/plain", new byte[0], null);
                    } else {
                        putDir(path);
                        send(out, 201, "Created", "text/plain", new byte[0], null);
                    }
                }
            } else if ("DELETE".equals(method)) {
                synchronized (this) {
                    if (!items.containsKey(path)) {
                        send(out, 404, "Not Found", "text/plain", new byte[0], null);
                    } else {
                        removeItem(path);
                        send(out, 204, "No Content", "text/plain", new byte[0], null);
                    }
                }
            } else {
                send(out, 405, "Method Not Allowed", "text/plain", new byte[0], null);
            }
        } catch (Exception ignored) {}
    }

    private void handleS3(String method, String pathPart, String queryPart,
                          Map<String, String> headers, byte[] body, BufferedOutputStream out) throws IOException {
        String auth = headers.get("authorization");
        if (auth == null || !auth.startsWith("AWS4-HMAC-SHA256 ")) {
            send(out, 403, "Forbidden", "text/plain", new byte[0], null);
            return;
        }
        String normPath = norm(pathPart); // e.g. /s3/mybucket or /s3/mybucket/key
        if ("GET".equals(method) && queryPart.contains("list-type=2")) {
            String prefix = "";
            for (String kv : queryPart.split("&")) {
                if (kv.startsWith("prefix=")) prefix = HttpUtil.decodeUriPath(kv.substring(7));
            }
            String bucketPrefix = normPath + "/";
            StringBuilder xml = new StringBuilder();
            xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            xml.append("<ListBucketResult><IsTruncated>false</IsTruncated>");
            synchronized (this) {
                for (Map.Entry<String, StoredItem> e : items.entrySet()) {
                    if (e.getValue().isDir) continue;
                    if (!e.getKey().startsWith(bucketPrefix)) continue;
                    String key = e.getKey().substring(bucketPrefix.length());
                    if (!key.startsWith(prefix)) continue;
                    xml.append("<Contents>");
                    xml.append("<Key>").append(MiniXml.escape(key)).append("</Key>");
                    xml.append("<Size>").append(e.getValue().data.length).append("</Size>");
                    xml.append("<ETag>\"").append(MiniXml.escape(e.getValue().etag)).append("\"</ETag>");
                    xml.append("<LastModified>").append(isoDate(e.getValue().mtime)).append("</LastModified>");
                    xml.append("</Contents>");
                }
            }
            xml.append("</ListBucketResult>");
            send(out, 200, "OK", "application/xml", xml.toString().getBytes(StandardCharsets.UTF_8), null);
        } else if ("PUT".equals(method)) {
            String etag = putFile(normPath, body, "application/octet-stream");
            Map<String, String> rh = new HashMap<>();
            rh.put("ETag", "\"" + etag + "\"");
            send(out, 200, "OK", "text/plain", new byte[0], rh);
        } else if ("GET".equals(method)) {
            handleGet(normPath, false, out);
        } else if ("DELETE".equals(method)) {
            removeItem(normPath);
            send(out, 204, "No Content", "text/plain", new byte[0], null);
        } else {
            send(out, 405, "Method Not Allowed", "text/plain", new byte[0], null);
        }
    }

    private synchronized void handlePropfind(String path, String depth, BufferedOutputStream out) throws IOException {
        StoredItem root = items.get(path);
        if (root == null) {
            send(out, 404, "Not Found", "text/plain", new byte[0], null);
            return;
        }
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
        xml.append("<d:multistatus xmlns:d=\"DAV:\">");
        appendPropResponse(xml, path, root);
        if (!"0".equals(depth) && root.isDir) {
            String prefix = "/".equals(path) ? "/" : (path + "/");
            for (Map.Entry<String, StoredItem> e : items.entrySet()) {
                String k = e.getKey();
                if (!k.startsWith(prefix) || k.equals(path)) continue;
                String rem = k.substring(prefix.length());
                if (rem.indexOf('/') >= 0) continue; // Depth: 1 direct children only
                appendPropResponse(xml, k, e.getValue());
            }
        }
        xml.append("</d:multistatus>");
        send(out, 207, "Multi-Status", "application/xml; charset=utf-8",
                xml.toString().getBytes(StandardCharsets.UTF_8), null);
    }

    private static void appendPropResponse(StringBuilder xml, String path, StoredItem it) {
        String href = HttpUtil.encodePathSegments(path.substring(1));
        href = "/" + href + (it.isDir && !path.equals("/") ? "/" : "");
        xml.append("<d:response>");
        xml.append("<d:href>").append(MiniXml.escape(href)).append("</d:href>");
        xml.append("<d:propstat><d:prop>");
        if (it.isDir) {
            xml.append("<d:resourcetype><d:collection/></d:resourcetype>");
        } else {
            xml.append("<d:resourcetype/>");
            xml.append("<d:getcontentlength>").append(it.data.length).append("</d:getcontentlength>");
            xml.append("<d:getcontenttype>").append(MiniXml.escape(it.contentType)).append("</d:getcontenttype>");
        }
        xml.append("<d:getlastmodified>").append(httpDate(it.mtime)).append("</d:getlastmodified>");
        xml.append("<d:getetag>\"").append(MiniXml.escape(it.etag)).append("\"</d:getetag>");
        xml.append("</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>");
        xml.append("</d:response>");
    }

    private synchronized void handleGet(String path, boolean headOnly, BufferedOutputStream out) throws IOException {
        StoredItem it = items.get(path);
        if (it == null || it.isDir) {
            send(out, 404, "Not Found", "text/plain", new byte[0], null);
            return;
        }
        Map<String, String> rh = new HashMap<>();
        rh.put("ETag", "\"" + it.etag + "\"");
        rh.put("Last-Modified", httpDate(it.mtime));
        rh.put("Content-Length", String.valueOf(it.data.length));
        send(out, 200, "OK", it.contentType, headOnly ? new byte[0] : it.data, rh);
    }

    private static void send(BufferedOutputStream out, int code, String reason,
                             String ctype, byte[] body, Map<String, String> extra) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        sb.append("Server: SynkMockDAV/1.0\r\n");
        sb.append("Content-Type: ").append(ctype).append("\r\n");
        boolean hasCl = extra != null && extra.containsKey("Content-Length");
        if (!hasCl) sb.append("Content-Length: ").append(body.length).append("\r\n");
        if (extra != null) {
            for (Map.Entry<String, String> e : extra.entrySet()) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (body.length > 0) out.write(body);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int b;
        boolean any = false;
        while ((b = in.read()) != -1) {
            any = true;
            if (b == '\n') break;
            if (b != '\r') buf.write(b);
        }
        return any ? new String(buf.toByteArray(), StandardCharsets.ISO_8859_1) : null;
    }

    private static String httpDate(long ms) {
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(ms));
    }

    private static String isoDate(long ms) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(ms));
    }

    @Override
    public void close() throws IOException {
        running = false;
        serverSocket.close();
    }
}
