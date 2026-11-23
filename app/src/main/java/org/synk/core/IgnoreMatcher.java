package org.synk.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java glob/ignore matcher mirroring native C scanner.c rules.
 */
public final class IgnoreMatcher {
    private IgnoreMatcher() {}

    public static String[] parsePatterns(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new String[0];
        String[] lines = raw.split("\\r?\\n");
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) {
                out.add(t);
            }
        }
        return out.toArray(new String[0]);
    }

    public static boolean isIgnored(String relPath, boolean isDir, String[] patterns) {
        if (relPath == null || relPath.isEmpty()) return true;
        int slash = relPath.lastIndexOf('/');
        String base = slash >= 0 ? relPath.substring(slash + 1) : relPath;
        if (base.startsWith(".synk.tmp.") || base.equals(".synk_base_cache")) {
            return true;
        }
        if (patterns == null) return false;
        for (String p : patterns) {
            if (p != null && globMatch(p, relPath, isDir)) return true;
        }
        return false;
    }

    public static boolean globMatch(String pattern, String relPath, boolean isDir) {
        if (pattern == null || relPath == null) return false;
        String pat = pattern.trim();
        if (pat.isEmpty() || pat.startsWith("#")) return false;

        boolean dirOnly = false;
        if (pat.endsWith("/")) {
            dirOnly = true;
            pat = pat.substring(0, pat.length() - 1);
        }
        if (pat.isEmpty()) return false;

        boolean anchored = false;
        if (pat.startsWith("/")) {
            anchored = true;
            pat = pat.substring(1);
        }
        if (pat.isEmpty()) return false;

        if (dirOnly && !isDir) {
            int idx = relPath.indexOf('/');
            while (idx > 0) {
                String ancestor = relPath.substring(0, idx);
                if (globMatch(pattern, ancestor, true)) return true;
                idx = relPath.indexOf('/', idx + 1);
            }
            return false;
        }

        if (anchored || pat.indexOf('/') >= 0) {
            return matchWild(pat, 0, relPath, 0);
        }

        int slash = relPath.lastIndexOf('/');
        String base = slash >= 0 ? relPath.substring(slash + 1) : relPath;
        if (matchWild(pat, 0, base, 0)) return true;

        String[] segs = relPath.split("/");
        for (int i = 0; i < segs.length - 1; i++) {
            if (matchWild(pat, 0, segs[i], 0)) return true;
        }
        return false;
    }

    private static boolean matchWild(String p, int pi, String s, int si) {
        int pn = p.length();
        int sn = s.length();
        while (pi < pn) {
            if (pi + 1 < pn && p.charAt(pi) == '*' && p.charAt(pi + 1) == '*') {
                while (pi < pn && p.charAt(pi) == '*') pi++;
                if (pi < pn && p.charAt(pi) == '/') pi++;
                if (pi == pn) return true;
                for (int t = si; t <= sn; t++) {
                    if (matchWild(p, pi, s, t)) return true;
                }
                return false;
            } else if (p.charAt(pi) == '*') {
                pi++;
                for (int t = si; t <= sn; t++) {
                    if (matchWild(p, pi, s, t)) return true;
                    if (t < sn && s.charAt(t) == '/') break;
                }
                return false;
            } else if (p.charAt(pi) == '?') {
                if (si >= sn || s.charAt(si) == '/') return false;
                pi++;
                si++;
            } else {
                if (si >= sn || p.charAt(pi) != s.charAt(si)) return false;
                pi++;
                si++;
            }
        }
        return si == sn;
    }
}
