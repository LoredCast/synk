package org.synk.protocol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tiny, fast, zero-dependency, XXE-proof namespace-stripping XML parser
 * for WebDAV, CalDAV, CardDAV, and S3 XML responses.
 */
public final class MiniXml {

    public static final class Node {
        public final String tag;      // local name, lowercased (namespace prefix stripped)
        public final String rawTag;
        public final Map<String, String> attrs = new HashMap<>();
        public final List<Node> children = new ArrayList<>();
        public String text = "";

        public Node(String rawTag) {
            this.rawTag = rawTag;
            int colon = rawTag.indexOf(':');
            this.tag = (colon >= 0 ? rawTag.substring(colon + 1) : rawTag).toLowerCase(Locale.ROOT);
        }

        public Node firstChild(String localName) {
            String target = localName.toLowerCase(Locale.ROOT);
            for (Node c : children) {
                if (c.tag.equals(target)) return c;
            }
            return null;
        }

        public List<Node> childrenNamed(String localName) {
            String target = localName.toLowerCase(Locale.ROOT);
            List<Node> out = new ArrayList<>();
            for (Node c : children) {
                if (c.tag.equals(target)) out.add(c);
            }
            return out;
        }

        public Node findFirstDescendant(String localName) {
            String target = localName.toLowerCase(Locale.ROOT);
            for (Node c : children) {
                if (c.tag.equals(target)) return c;
                Node found = c.findFirstDescendant(target);
                if (found != null) return found;
            }
            return null;
        }

        public List<Node> findAllDescendants(String localName) {
            String target = localName.toLowerCase(Locale.ROOT);
            List<Node> out = new ArrayList<>();
            collectDescendants(this, target, out);
            return out;
        }

        private static void collectDescendants(Node n, String target, List<Node> out) {
            for (Node c : n.children) {
                if (c.tag.equals(target)) out.add(c);
                collectDescendants(c, target, out);
            }
        }

        public String childText(String localName) {
            Node c = firstChild(localName);
            return c != null ? c.text : null;
        }
    }

    private final String xml;
    private int pos;
    private final int len;

    private MiniXml(String xml) {
        this.xml = xml == null ? "" : xml;
        this.len = this.xml.length();
    }

    public static Node parse(String xml) {
        MiniXml parser = new MiniXml(xml);
        Node root = new Node("__root__");
        parser.parseChildren(root, null);
        if (root.children.size() == 1) return root.children.get(0);
        return root;
    }

    private void parseChildren(Node parent, String closingRawTag) {
        StringBuilder textBuf = new StringBuilder();
        while (pos < len) {
            int lt = xml.indexOf('<', pos);
            if (lt < 0) {
                textBuf.append(unescape(xml.substring(pos)));
                pos = len;
                break;
            }
            if (lt > pos) {
                textBuf.append(unescape(xml.substring(pos, lt)));
                pos = lt;
            }
            if (startsWith("<!--")) {
                int end = xml.indexOf("-->", pos + 4);
                pos = end < 0 ? len : end + 3;
            } else if (startsWith("<![CDATA[")) {
                int end = xml.indexOf("]]>", pos + 9);
                if (end < 0) {
                    textBuf.append(xml.substring(pos + 9));
                    pos = len;
                } else {
                    textBuf.append(xml.substring(pos + 9, end));
                    pos = end + 3;
                }
            } else if (startsWith("<?") || startsWith("<!")) {
                int end = xml.indexOf('>', pos + 2);
                pos = end < 0 ? len : end + 1;
            } else if (startsWith("</")) {
                pos += 2;
                int end = xml.indexOf('>', pos);
                if (end < 0) {
                    pos = len;
                    break;
                }
                String closeName = xml.substring(pos, end).trim();
                pos = end + 1;
                if (closingRawTag != null && closeName.equalsIgnoreCase(closingRawTag)) {
                    break;
                }
                if (closingRawTag != null && stripNs(closeName).equalsIgnoreCase(stripNs(closingRawTag))) {
                    break;
                }
            } else {
                pos++; // skip '<'
                skipWs();
                int nameStart = pos;
                while (pos < len && !isWs(xml.charAt(pos)) && xml.charAt(pos) != '>' && xml.charAt(pos) != '/') {
                    pos++;
                }
                if (pos == nameStart) break;
                String rawName = xml.substring(nameStart, pos);
                Node child = new Node(rawName);
                boolean selfClosing = false;

                while (pos < len) {
                    skipWs();
                    if (pos >= len) break;
                    char c = xml.charAt(pos);
                    if (c == '>') {
                        pos++;
                        break;
                    }
                    if (c == '/' && pos + 1 < len && xml.charAt(pos + 1) == '>') {
                        selfClosing = true;
                        pos += 2;
                        break;
                    }
                    int aStart = pos;
                    while (pos < len && !isWs(xml.charAt(pos)) && xml.charAt(pos) != '=' && xml.charAt(pos) != '>' && xml.charAt(pos) != '/') {
                        pos++;
                    }
                    String attrName = xml.substring(aStart, pos);
                    skipWs();
                    String attrVal = "";
                    if (pos < len && xml.charAt(pos) == '=') {
                        pos++;
                        skipWs();
                        if (pos < len && (xml.charAt(pos) == '"' || xml.charAt(pos) == '\'')) {
                            char q = xml.charAt(pos++);
                            int vStart = pos;
                            int vEnd = xml.indexOf(q, pos);
                            if (vEnd < 0) {
                                attrVal = unescape(xml.substring(vStart));
                                pos = len;
                            } else {
                                attrVal = unescape(xml.substring(vStart, vEnd));
                                pos = vEnd + 1;
                            }
                        }
                    }
                    if (!attrName.isEmpty()) {
                        child.attrs.put(attrName, attrVal);
                    }
                }

                if (!selfClosing) {
                    parseChildren(child, rawName);
                }
                parent.children.add(child);
            }
        }
        parent.text = textBuf.toString();
    }

    private boolean startsWith(String prefix) {
        return xml.startsWith(prefix, pos);
    }

    private void skipWs() {
        while (pos < len && isWs(xml.charAt(pos))) pos++;
    }

    private static boolean isWs(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }

    private static String stripNs(String s) {
        int i = s.indexOf(':');
        return i >= 0 ? s.substring(i + 1) : s;
    }

    public static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default: sb.append(c); break;
            }
        }
        return sb.toString();
    }

    public static String unescape(String s) {
        if (s == null || s.indexOf('&') < 0) return s == null ? "" : s;
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '&') {
                int semi = s.indexOf(';', i + 1);
                if (semi > i && semi - i <= 12) {
                    String ent = s.substring(i + 1, semi);
                    if ("amp".equals(ent)) { sb.append('&'); i = semi + 1; continue; }
                    if ("lt".equals(ent)) { sb.append('<'); i = semi + 1; continue; }
                    if ("gt".equals(ent)) { sb.append('>'); i = semi + 1; continue; }
                    if ("quot".equals(ent)) { sb.append('"'); i = semi + 1; continue; }
                    if ("apos".equals(ent)) { sb.append('\''); i = semi + 1; continue; }
                    if (ent.startsWith("#x") || ent.startsWith("#X")) {
                        try {
                            int cp = Integer.parseInt(ent.substring(2), 16);
                            sb.appendCodePoint(cp);
                            i = semi + 1;
                            continue;
                        } catch (Exception ignored) {}
                    } else if (ent.startsWith("#")) {
                        try {
                            int cp = Integer.parseInt(ent.substring(1));
                            sb.appendCodePoint(cp);
                            i = semi + 1;
                            continue;
                        } catch (Exception ignored) {}
                    }
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }
}
