package org.synk.protocol;

import org.synk.core.SyncProgress;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Zero-dependency streaming HTTP/1.1 client supporting all WebDAV/CalDAV/CardDAV/S3 methods
 * (PROPFIND, MKCOL, REPORT, MOVE, GET, PUT, DELETE, HEAD, OPTIONS) on both Android and standard JVM.
 */
public final class HttpUtil {
    private static volatile int connectTimeoutMs = 15_000;
    private static volatile int readTimeoutMs = 60_000;
    private static final int MAX_REDIRECTS = 5;

    private HttpUtil() {}

    public static void setTimeouts(int connectMs, int readMs) {
        if (connectMs >= 1000) connectTimeoutMs = connectMs;
        if (readMs >= 1000) readTimeoutMs = readMs;
    }

    public static int getConnectTimeoutMs() { return connectTimeoutMs; }
    public static int getReadTimeoutMs() { return readTimeoutMs; }

    public static final class Response {
        public final int status;
        public final String statusMessage;
        public final Map<String, String> headers;
        public final byte[] body;

        public Response(int status, String statusMessage, Map<String, String> headers, byte[] body) {
            this.status = status;
            this.statusMessage = statusMessage;
            this.headers = headers;
            this.body = body;
        }

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        public String bodyString() {
            return body == null ? "" : new String(body, StandardCharsets.UTF_8);
        }
    }

    public static Response request(String method, String url, Map<String, String> headers,
                                   byte[] body, boolean trustAllCerts) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        String currentUrl = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            sink.reset();
            Response r = executeOnce(method, currentUrl, headers,
                    body != null ? new java.io.ByteArrayInputStream(body) : null,
                    body != null ? body.length : 0L,
                    sink, trustAllCerts, true, null);
            if (isRedirectStatus(r.status)) {
                String loc = r.header("location");
                if (loc != null && !loc.isEmpty()) {
                    currentUrl = resolveRedirect(currentUrl, loc);
                    continue;
                }
            }
            return new Response(r.status, r.statusMessage, r.headers, sink.toByteArray());
        }
        throw new IOException("Too many redirects for " + url);
    }

    public static Response requestStreaming(String method, String url, Map<String, String> headers,
                                            InputStream uploadStream, long contentLength,
                                            OutputStream downloadSink, boolean trustAllCerts) throws IOException {
        return requestStreaming(method, url, headers, uploadStream, contentLength, downloadSink, trustAllCerts, null);
    }

    public static Response requestStreaming(String method, String url, Map<String, String> headers,
                                            InputStream uploadStream, long contentLength,
                                            OutputStream downloadSink, boolean trustAllCerts,
                                            SyncProgress.TransferObserver observer) throws IOException {
        String currentUrl = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            Response r = executeOnce(method, currentUrl, headers, uploadStream, contentLength,
                    downloadSink, trustAllCerts, true, observer);
            if (isRedirectStatus(r.status) && uploadStream == null) {
                String loc = r.header("location");
                if (loc != null && !loc.isEmpty()) {
                    currentUrl = resolveRedirect(currentUrl, loc);
                    if (downloadSink instanceof ByteArrayOutputStream) {
                        ((ByteArrayOutputStream) downloadSink).reset();
                    }
                    continue;
                }
            }
            return r;
        }
        throw new IOException("Too many redirects for " + url);
    }

    private static boolean isRedirectStatus(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static Response executeOnce(String method, String urlStr, Map<String, String> headers,
                                        InputStream uploadStream, long contentLength,
                                        OutputStream downloadSink, boolean trustAllCerts,
                                        boolean allowSinkWrite,
                                        SyncProgress.TransferObserver observer) throws IOException {
        URI uri;
        try {
            uri = new URI(urlStr);
        } catch (URISyntaxException e) {
            throw new IOException("Invalid URL: " + urlStr, e);
        }

        String scheme = uri.getScheme() == null ? "http" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean https = "https".equals(scheme);
        String host = uri.getHost();
        if (host == null || host.isEmpty()) throw new IOException("Missing host in URL: " + urlStr);
        int port = uri.getPort();
        if (port < 0) port = https ? 443 : 80;

        String rawPath = uri.getRawPath();
        if (rawPath == null || rawPath.isEmpty()) rawPath = "/";
        if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
            rawPath = rawPath + "?" + uri.getRawQuery();
        }

        int bufSize = (observer != null && observer.bufferSize() >= 4096) ? observer.bufferSize() : 32768;

        try (Socket sock = openSocket(host, port, https, trustAllCerts)) {
            sock.setSoTimeout(readTimeoutMs);
            BufferedOutputStream out = new BufferedOutputStream(sock.getOutputStream(), Math.min(bufSize, 65536));
            BufferedInputStream in = new BufferedInputStream(sock.getInputStream(), Math.min(bufSize, 65536));

            StringBuilder req = new StringBuilder(512);
            req.append(method).append(' ').append(rawPath).append(" HTTP/1.1\r\n");
            boolean hasHost = false;
            boolean hasLen = false;
            if (headers != null) {
                for (String k : headers.keySet()) {
                    if ("host".equalsIgnoreCase(k)) hasHost = true;
                    if ("content-length".equalsIgnoreCase(k)) hasLen = true;
                }
            }
            if (!hasHost) {
                req.append("Host: ").append(host);
                if ((!https && port != 80) || (https && port != 443)) {
                    req.append(':').append(port);
                }
                req.append("\r\n");
            }
            req.append("User-Agent: Synk/1.0\r\n");
            req.append("Accept-Encoding: identity\r\n");
            req.append("Connection: close\r\n");

            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        req.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
                    }
                }
            }
            if (!hasLen && (uploadStream != null || "PUT".equals(method) || "POST".equals(method)
                    || "PROPFIND".equals(method) || "REPORT".equals(method))) {
                req.append("Content-Length: ").append(Math.max(0L, contentLength)).append("\r\n");
            }
            req.append("\r\n");
            out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));

            if (uploadStream != null && contentLength > 0) {
                byte[] buf = new byte[bufSize];
                long remaining = contentLength;
                long[] throttleWin = new long[2];
                while (remaining > 0) {
                    if (observer != null && observer.isCancelled()) {
                        throw new IOException("Sync cancelled");
                    }
                    int n = uploadStream.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (n < 0) break;
                    out.write(buf, 0, n);
                    remaining -= n;
                    if (observer != null) {
                        observer.onBytes(n);
                        SyncProgress.throttleIfNeeded(observer, n, throttleWin);
                    }
                }
            }
            out.flush();

            /* Read status line (skipping 100 Continue if sent) */
            int status;
            String statusMsg;
            Map<String, String> respHeaders;
            while (true) {
                String statusLine = readLine(in);
                if (statusLine == null) throw new EOFException("Server closed connection before HTTP status");
                int sp1 = statusLine.indexOf(' ');
                if (sp1 < 0) throw new IOException("Malformed HTTP status line: " + statusLine);
                int sp2 = statusLine.indexOf(' ', sp1 + 1);
                String codeStr = sp2 > 0 ? statusLine.substring(sp1 + 1, sp2) : statusLine.substring(sp1 + 1);
                status = Integer.parseInt(codeStr.trim());
                statusMsg = sp2 > 0 ? statusLine.substring(sp2 + 1).trim() : "";

                respHeaders = new HashMap<>();
                String line;
                while ((line = readLine(in)) != null && !line.isEmpty()) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        String k = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                        String v = line.substring(colon + 1).trim();
                        respHeaders.put(k, v);
                    }
                }
                if (status != 100) break;
            }

            boolean hasBody = !"HEAD".equalsIgnoreCase(method)
                    && status != 204 && status != 304 && !(status >= 100 && status < 200);
            boolean isRedirect = isRedirectStatus(status);
            OutputStream sink = (allowSinkWrite && !isRedirect) ? downloadSink : null;
            SyncProgress.TransferObserver dlObs = (sink != null && uploadStream == null) ? observer : null;

            if (hasBody) {
                String te = respHeaders.get("transfer-encoding");
                String cl = respHeaders.get("content-length");
                if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
                    readChunked(in, sink, bufSize, dlObs);
                } else if (cl != null) {
                    long len = Long.parseLong(cl.trim());
                    copyExact(in, sink, len, bufSize, dlObs);
                } else {
                    copyToEof(in, sink, bufSize, dlObs);
                }
            }

            return new Response(status, statusMsg, respHeaders, null);
        }
    }

    public static Socket openSocket(String host, int port, boolean tls, boolean trustAllCerts) throws IOException {
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        if (!tls) return raw;
        return wrapTlsSocket(raw, host, port, trustAllCerts);
    }

    public static SSLSocket wrapTlsSocket(Socket raw, String host, int port, boolean trustAllCerts) throws IOException {
        SSLSocketFactory factory = createSslSocketFactory(trustAllCerts);
        SSLSocket ssl = (SSLSocket) factory.createSocket(raw, host, port, true);
        try {
            SSLParameters params = ssl.getSSLParameters();
            if (!isIpAddress(host)) {
                params.setServerNames(Collections.singletonList(new SNIHostName(host)));
            }
            if (!trustAllCerts) {
                params.setEndpointIdentificationAlgorithm("HTTPS");
            }
            ssl.setSSLParameters(params);
        } catch (Throwable ignored) {}
        ssl.startHandshake();
        return ssl;
    }

    public static SSLSocketFactory createSslSocketFactory(boolean trustAllCerts) throws IOException {
        if (!trustAllCerts) {
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
                @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new SecureRandom());
            return ctx.getSocketFactory();
        } catch (Exception e) {
            throw new IOException("TLS init failed", e);
        }
    }

    private static boolean isIpAddress(String host) {
        if (host == null) return false;
        if (host.indexOf(':') >= 0) return true; // IPv6
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if ((c < '0' || c > '9') && c != '.') return false;
        }
        return true;
    }

    private static String resolveRedirect(String baseUrl, String location) throws IOException {
        try {
            return new URI(baseUrl).resolve(location).toString();
        } catch (URISyntaxException e) {
            throw new IOException("Bad redirect URL: " + location, e);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int b;
        boolean sawAny = false;
        while ((b = in.read()) != -1) {
            sawAny = true;
            if (b == '\n') break;
            if (b != '\r') buf.write(b);
        }
        if (!sawAny) return null;
        return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static void readChunked(InputStream in, OutputStream sink, int bufSize,
                                    SyncProgress.TransferObserver observer) throws IOException {
        byte[] buf = new byte[bufSize];
        long[] throttleWin = new long[2];
        while (true) {
            String line = readLine(in);
            if (line == null) break;
            int semi = line.indexOf(';');
            String hex = (semi >= 0 ? line.substring(0, semi) : line).trim();
            if (hex.isEmpty()) continue;
            long chunkSize = Long.parseLong(hex, 16);
            if (chunkSize == 0) {
                while (true) {
                    String trailer = readLine(in);
                    if (trailer == null || trailer.isEmpty()) break;
                }
                break;
            }
            long rem = chunkSize;
            while (rem > 0) {
                if (observer != null && observer.isCancelled()) throw new IOException("Sync cancelled");
                int n = in.read(buf, 0, (int) Math.min(buf.length, rem));
                if (n < 0) throw new EOFException("Unexpected EOF inside HTTP chunk");
                if (sink != null) sink.write(buf, 0, n);
                rem -= n;
                if (observer != null) {
                    observer.onBytes(n);
                    SyncProgress.throttleIfNeeded(observer, n, throttleWin);
                }
            }
            readLine(in); // trailing CRLF after chunk
        }
    }

    private static void copyExact(InputStream in, OutputStream sink, long len, int bufSize,
                                  SyncProgress.TransferObserver observer) throws IOException {
        byte[] buf = new byte[bufSize];
        long[] throttleWin = new long[2];
        long rem = len;
        while (rem > 0) {
            if (observer != null && observer.isCancelled()) throw new IOException("Sync cancelled");
            int n = in.read(buf, 0, (int) Math.min(buf.length, rem));
            if (n < 0) break;
            if (sink != null) sink.write(buf, 0, n);
            rem -= n;
            if (observer != null) {
                observer.onBytes(n);
                SyncProgress.throttleIfNeeded(observer, n, throttleWin);
            }
        }
    }

    private static void copyToEof(InputStream in, OutputStream sink, int bufSize,
                                  SyncProgress.TransferObserver observer) throws IOException {
        byte[] buf = new byte[bufSize];
        long[] throttleWin = new long[2];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (observer != null && observer.isCancelled()) throw new IOException("Sync cancelled");
            if (sink != null) sink.write(buf, 0, n);
            if (observer != null) {
                observer.onBytes(n);
                SyncProgress.throttleIfNeeded(observer, n, throttleWin);
            }
        }
    }

    public static String basicAuth(String username, String password) {
        if ((username == null || username.isEmpty()) && password != null && password.startsWith("Bearer ")) {
            return password;
        }
        String u = username == null ? "" : username;
        String p = password == null ? "" : password;
        return "Basic " + base64Encode((u + ":" + p).getBytes(StandardCharsets.UTF_8));
    }

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    public static String base64Encode(byte[] data) {
        StringBuilder sb = new StringBuilder(((data.length + 2) / 3) * 4);
        int i = 0;
        while (i < data.length) {
            int b0 = data[i++] & 0xFF;
            int b1 = i < data.length ? (data[i++] & 0xFF) : -1;
            int b2 = i < data.length ? (data[i++] & 0xFF) : -1;
            sb.append(B64[b0 >>> 2]);
            sb.append(B64[((b0 & 0x03) << 4) | (b1 >= 0 ? (b1 >>> 4) : 0)]);
            sb.append(b1 >= 0 ? B64[((b1 & 0x0F) << 2) | (b2 >= 0 ? (b2 >>> 6) : 0)] : '=');
            sb.append(b2 >= 0 ? B64[b2 & 0x3F] : '=');
        }
        return sb.toString();
    }

    public static String encodePathSegments(String path) {
        if (path == null || path.isEmpty()) return "";
        String[] segs = path.split("/", -1);
        StringBuilder sb = new StringBuilder(path.length() + 16);
        for (int i = 0; i < segs.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(encodeUriSegment(segs[i]));
        }
        return sb.toString();
    }

    public static String encodeUriSegment(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit((c >>> 4) & 0xF, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return sb.toString();
    }

    public static String decodeUriPath(String s) {
        if (s == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            byte[] utf = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
            out.write(utf, 0, utf.length);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    public static long parseHttpDate(String s) {
        if (s == null || s.trim().isEmpty()) return 0L;
        String trimmed = s.trim();
        String[] formats = {
                "EEE, dd MMM yyyy HH:mm:ss zzz",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "EEE, dd-MMM-yy HH:mm:ss zzz",
                "yyyyMMddHHmmss"
        };
        for (String fmt : formats) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(fmt, Locale.US);
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                return sdf.parse(trimmed).getTime();
            } catch (ParseException ignored) {}
        }
        return 0L;
    }

    public static String joinUrl(String base, String rel) {
        String b = base == null ? "" : base;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (rel == null || rel.isEmpty() || "/".equals(rel)) return b + "/";
        String r = rel;
        while (r.startsWith("/")) r = r.substring(1);
        return b + "/" + encodePathSegments(r);
    }

    public static String stripQuotes(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("W/")) t = t.substring(2).trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }
}
