package org.synk.pim;

import org.synk.core.SyncModels.PimKind;
import org.synk.core.SyncModels.PimProfile;
import org.synk.protocol.HttpUtil;
import org.synk.protocol.MiniXml;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lightweight RFC 4791 (CalDAV) & RFC 6352 (CardDAV) client over HttpUtil.
 */
public final class DavPimClient {

    public static final class RemotePimRef {
        public final String href;       // absolute URL
        public final String filename;   // e.g. "abc-123.ics" or "xyz.vcf"
        public final String etag;

        public RemotePimRef(String href, String filename, String etag) {
            this.href = href;
            this.filename = filename;
            this.etag = etag == null ? "" : etag;
        }
    }

    public static final class RemotePimItem {
        public final String href;
        public final String etag;
        public final String body;

        public RemotePimItem(String href, String etag, String body) {
            this.href = href;
            this.etag = etag == null ? "" : etag;
            this.body = body == null ? "" : body;
        }
    }

    private static final byte[] PROPFIND_ETAG = (
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<d:propfind xmlns:d=\"DAV:\">" +
            "<d:prop><d:getetag/><d:getcontenttype/><d:resourcetype/></d:prop>" +
            "</d:propfind>"
    ).getBytes(StandardCharsets.UTF_8);

    private final PimProfile profile;
    private final String collectionUrl;
    private final String authHeader;

    public DavPimClient(PimProfile profile) {
        this.profile = profile;
        String u = profile.url == null ? "" : profile.url.trim();
        if (!u.endsWith("/")) u += "/";
        this.collectionUrl = u;
        this.authHeader = (profile.username != null && !profile.username.isEmpty())
                || (profile.password != null && !profile.password.isEmpty())
                ? HttpUtil.basicAuth(profile.username, profile.password) : null;
    }

    private Map<String, String> baseHeaders() {
        Map<String, String> h = new HashMap<>();
        if (authHeader != null) h.put("Authorization", authHeader);
        return h;
    }

    public String testConnection() throws IOException {
        Map<String, String> h = baseHeaders();
        h.put("Depth", "0");
        h.put("Content-Type", "application/xml; charset=utf-8");
        HttpUtil.Response r = HttpUtil.request("PROPFIND", collectionUrl, h, PROPFIND_ETAG, profile.trustAllCerts);
        if (r.status == 207 || r.status == 200) {
            return profile.kind.label + " OK (" + r.status + ")";
        }
        throw new IOException(profile.kind.label + " check failed: HTTP " + r.status);
    }

    public List<RemotePimRef> listItems() throws IOException {
        Map<String, String> h = baseHeaders();
        h.put("Depth", "1");
        h.put("Content-Type", "application/xml; charset=utf-8");
        HttpUtil.Response resp = HttpUtil.request("PROPFIND", collectionUrl, h, PROPFIND_ETAG, profile.trustAllCerts);
        if (resp.status != 207 && resp.status != 200) {
            throw new IOException("DAV PROPFIND failed: HTTP " + resp.status);
        }

        String ext = profile.kind == PimKind.CALDAV ? ".ics" : ".vcf";
        String expectedMime = profile.kind == PimKind.CALDAV ? "text/calendar" : "text/vcard";
        List<RemotePimRef> out = new ArrayList<>();
        MiniXml.Node root = MiniXml.parse(resp.bodyString());

        URI baseUri;
        try {
            baseUri = new URI(collectionUrl);
        } catch (Exception e) {
            throw new IOException("Invalid collection URL: " + collectionUrl, e);
        }

        for (MiniXml.Node rn : root.findAllDescendants("response")) {
            String href = rn.childText("href");
            if (href == null || href.trim().isEmpty()) continue;
            MiniXml.Node prop = rn.findFirstDescendant("prop");
            if (prop == null) continue;
            MiniXml.Node rt = prop.firstChild("resourcetype");
            if (rt != null && rt.firstChild("collection") != null) continue;

            String resolved = baseUri.resolve(href.trim()).toString();
            String lower = resolved.toLowerCase(Locale.ROOT);
            String ctype = prop.childText("getcontenttype");
            boolean matchMime = ctype != null && (ctype.toLowerCase(Locale.ROOT).contains(expectedMime)
                    || (profile.kind == PimKind.CARDDAV && ctype.toLowerCase(Locale.ROOT).contains("x-vcard")));
            if (!lower.endsWith(ext) && !matchMime) continue;

            int slash = resolved.lastIndexOf('/');
            String filename = slash >= 0 ? resolved.substring(slash + 1) : resolved;
            String etag = HttpUtil.stripQuotes(prop.childText("getetag"));
            out.add(new RemotePimRef(resolved, filename, etag));
        }
        return out;
    }

    public RemotePimItem fetchItem(String itemUrl) throws IOException {
        HttpUtil.Response r = HttpUtil.request("GET", itemUrl, baseHeaders(), null, profile.trustAllCerts);
        if (r.status < 200 || r.status >= 300) {
            throw new IOException("DAV GET failed (" + r.status + ") for " + itemUrl);
        }
        return new RemotePimItem(itemUrl, HttpUtil.stripQuotes(r.header("etag")), r.bodyString());
    }

    public String buildItemUrl(String uid) {
        String safe = uid.replaceAll("[^a-zA-Z0-9._-]", "_");
        String ext = profile.kind == PimKind.CALDAV ? ".ics" : ".vcf";
        return collectionUrl + safe + ext;
    }

    public String putItem(String itemUrl, String payload, String ifMatchEtag) throws IOException {
        Map<String, String> h = baseHeaders();
        h.put("Content-Type", profile.kind == PimKind.CALDAV
                ? "text/calendar; charset=utf-8"
                : "text/vcard; charset=utf-8");
        if (ifMatchEtag != null && !ifMatchEtag.isEmpty()) {
            h.put("If-Match", "\"" + ifMatchEtag + "\"");
        }
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        HttpUtil.Response r = HttpUtil.request("PUT", itemUrl, h, bytes, profile.trustAllCerts);
        if (r.status < 200 || r.status >= 300) {
            throw new IOException("DAV PUT failed (" + r.status + ") for " + itemUrl);
        }
        String etag = HttpUtil.stripQuotes(r.header("etag"));
        if (etag.isEmpty()) {
            HttpUtil.Response head = HttpUtil.request("HEAD", itemUrl, baseHeaders(), null, profile.trustAllCerts);
            if (head.status >= 200 && head.status < 300) {
                etag = HttpUtil.stripQuotes(head.header("etag"));
            }
        }
        return etag;
    }

    public void deleteItem(String itemUrl) throws IOException {
        HttpUtil.Response r = HttpUtil.request("DELETE", itemUrl, baseHeaders(), null, profile.trustAllCerts);
        if (r.status != 200 && r.status != 204 && r.status != 404) {
            throw new IOException("DAV DELETE failed (" + r.status + ") for " + itemUrl);
        }
    }
}
