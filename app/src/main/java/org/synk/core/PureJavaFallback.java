package org.synk.core;

import org.synk.core.SyncModels.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Pure-Java mirror of the C native core (sha256, scanner, diff3, planner).
 * Used automatically as a transparent fallback when libsynk_native.so is not bundled.
 */
final class PureJavaFallback {
    private static final long MTIME_TOLERANCE_MS = 2000L;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private PureJavaFallback() {}

    static String sha256Bytes(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            if (data != null && data.length > 0) md.update(data);
            return toHex(md.digest());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static String sha256File(String absPath) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            try (FileInputStream in = new FileInputStream(absPath)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            return toHex(md.digest());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private static String toHex(byte[] digest) {
        char[] out = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            int b = digest[i] & 0xFF;
            out[i * 2] = HEX[b >>> 4];
            out[i * 2 + 1] = HEX[b & 0x0F];
        }
        return new String(out);
    }

    static List<FileEntry> scanDir(String rootDir, String[] ignores, boolean computeHashes) {
        List<FileEntry> out = new ArrayList<>();
        File root = new File(rootDir);
        if (!root.isDirectory()) return out;
        scanRec(root, "", ignores, computeHashes, out, 0);
        Collections.sort(out, new Comparator<FileEntry>() {
            @Override
            public int compare(FileEntry a, FileEntry b) {
                return a.relPath.compareTo(b.relPath);
            }
        });
        return out;
    }

    private static void scanRec(File dir, String relPrefix, String[] ignores,
                                boolean computeHashes, List<FileEntry> out, int depth) {
        if (depth > 64) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            String name = f.getName();
            if (".".equals(name) || "..".equals(name)) continue;
            try {
                if (Files.isSymbolicLink(f.toPath())) continue;
            } catch (Exception ignored) {}
            String rel = relPrefix.isEmpty() ? name : (relPrefix + "/" + name);
            boolean isDir = f.isDirectory();
            if (!isDir && !f.isFile()) continue;
            if (IgnoreMatcher.isIgnored(rel, isDir, ignores)) continue;

            String hash = "";
            if (!isDir && computeHashes) {
                try {
                    hash = sha256File(f.getAbsolutePath());
                } catch (IOException ignored) {}
            }
            out.add(new FileEntry(rel, isDir, isDir ? 0L : f.length(), f.lastModified(), hash, ""));
            if (isDir) {
                scanRec(f, rel, ignores, computeHashes, out, depth + 1);
            }
        }
    }

    /* ---- 3-way Diff3 Text Merge ---- */
    static MergeResult diff3Merge(byte[] base, byte[] local, byte[] remote,
                                  String localLabel, String remoteLabel) {
        if (base == null) base = new byte[0];
        if (local == null) local = new byte[0];
        if (remote == null) remote = new byte[0];
        if (localLabel == null || localLabel.isEmpty()) localLabel = "LOCAL";
        if (remoteLabel == null || remoteLabel.isEmpty()) remoteLabel = "REMOTE";

        if (looksBinary(base) || looksBinary(local) || looksBinary(remote)) {
            return new MergeResult(true, 0, null);
        }
        if (Arrays.equals(local, remote)) return new MergeResult(false, 0, local.clone());
        if (Arrays.equals(local, base)) return new MergeResult(false, 0, remote.clone());
        if (Arrays.equals(remote, base)) return new MergeResult(false, 0, local.clone());

        List<String> bl = splitLines(new String(base, StandardCharsets.UTF_8));
        List<String> ll = splitLines(new String(local, StandardCharsets.UTF_8));
        List<String> rl = splitLines(new String(remote, StandardCharsets.UTF_8));

        int[] ml = lcsMatch(bl, ll);
        int[] mr = lcsMatch(bl, rl);

        StringBuilder sb = new StringBuilder();
        int b = 0, lPos = 0, rPos = 0, conflicts = 0;

        while (b <= bl.size()) {
            int bSync = b;
            int lEnd = ll.size();
            int rEnd = rl.size();
            while (bSync < bl.size()) {
                if (ml[bSync] >= lPos && mr[bSync] >= rPos) {
                    lEnd = ml[bSync];
                    rEnd = mr[bSync];
                    break;
                }
                bSync++;
            }

            if (b < bSync || lPos < lEnd || rPos < rEnd) {
                boolean lEqB = slicesEq(ll, lPos, lEnd, bl, b, bSync);
                boolean rEqB = slicesEq(rl, rPos, rEnd, bl, b, bSync);
                boolean lEqR = slicesEq(ll, lPos, lEnd, rl, rPos, rEnd);
                if (lEqB && rEqB) {
                    appendSlice(sb, bl, b, bSync);
                } else if (lEqB) {
                    appendSlice(sb, rl, rPos, rEnd);
                } else if (rEqB || lEqR) {
                    appendSlice(sb, ll, lPos, lEnd);
                } else {
                    conflicts++;
                    sb.append("<<<<<<< ").append(localLabel).append('\n');
                    appendSlice(sb, ll, lPos, lEnd);
                    if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
                    sb.append("=======\n");
                    appendSlice(sb, rl, rPos, rEnd);
                    if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
                    sb.append(">>>>>>> ").append(remoteLabel).append('\n');
                }
            }

            if (bSync == bl.size()) break;
            sb.append(bl.get(bSync));
            b = bSync + 1;
            lPos = lEnd + 1;
            rPos = rEnd + 1;
        }

        return new MergeResult(false, conflicts, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static boolean looksBinary(byte[] b) {
        int n = Math.min(b.length, 8192);
        for (int i = 0; i < n; i++) {
            if (b[i] == 0) return true;
        }
        return false;
    }

    private static List<String> splitLines(String s) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                out.add(s.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < s.length()) out.add(s.substring(start));
        return out;
    }

    private static boolean slicesEq(List<String> a, int a0, int a1, List<String> b, int b0, int b1) {
        if ((a1 - a0) != (b1 - b0)) return false;
        for (int i = 0; i < a1 - a0; i++) {
            if (!a.get(a0 + i).equals(b.get(b0 + i))) return false;
        }
        return true;
    }

    private static void appendSlice(StringBuilder sb, List<String> list, int s, int e) {
        for (int i = s; i < e; i++) sb.append(list.get(i));
    }

    private static int[] lcsMatch(List<String> base, List<String> side) {
        int n = base.size(), m = side.size();
        int[] match = new int[n];
        Arrays.fill(match, -1);

        int prefix = 0;
        while (prefix < n && prefix < m && base.get(prefix).equals(side.get(prefix))) {
            match[prefix] = prefix;
            prefix++;
        }
        int suffix = 0;
        while (suffix < (n - prefix) && suffix < (m - prefix)
                && base.get(n - 1 - suffix).equals(side.get(m - 1 - suffix))) {
            match[n - 1 - suffix] = m - 1 - suffix;
            suffix++;
        }

        int na = n - prefix - suffix;
        int nb = m - prefix - suffix;
        if (na == 0 || nb == 0 || (long) na * nb > 16_000_000L) return match;

        short[][] dp = new short[na + 1][nb + 1];
        for (int i = na - 1; i >= 0; i--) {
            String bs = base.get(prefix + i);
            for (int j = nb - 1; j >= 0; j--) {
                if (bs.equals(side.get(prefix + j))) {
                    dp[i][j] = (short) (dp[i + 1][j + 1] + 1);
                } else {
                    dp[i][j] = dp[i + 1][j] >= dp[i][j + 1] ? dp[i + 1][j] : dp[i][j + 1];
                }
            }
        }
        int i = 0, j = 0;
        while (i < na && j < nb) {
            if (base.get(prefix + i).equals(side.get(prefix + j))) {
                match[prefix + i] = prefix + j;
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        return match;
    }

    /* ---- 3-Way Sync Planner ---- */
    private static final int CH_NONE = 0, CH_CREATED = 1, CH_MODIFIED = 2, CH_DELETED = 3;

    static List<PlanAction> planSync(List<FileEntry> base, List<FileEntry> local,
                                     List<FileEntry> remote, SyncMode mode, ConflictPolicy policy) {
        List<FileEntry> sb = sortedCopy(base);
        List<FileEntry> sl = sortedCopy(local);
        List<FileEntry> sr = sortedCopy(remote);

        List<PlanAction> plan = new ArrayList<>();
        int ib = 0, il = 0, ir = 0;
        while (ib < sb.size() || il < sl.size() || ir < sr.size()) {
            String min = null;
            if (ib < sb.size()) min = sb.get(ib).relPath;
            if (il < sl.size() && (min == null || sl.get(il).relPath.compareTo(min) < 0)) {
                min = sl.get(il).relPath;
            }
            if (ir < sr.size() && (min == null || sr.get(ir).relPath.compareTo(min) < 0)) {
                min = sr.get(ir).relPath;
            }
            FileEntry eb = (ib < sb.size() && sb.get(ib).relPath.equals(min)) ? sb.get(ib++) : null;
            FileEntry el = (il < sl.size() && sl.get(il).relPath.equals(min)) ? sl.get(il++) : null;
            FileEntry er = (ir < sr.size() && sr.get(ir).relPath.equals(min)) ? sr.get(ir++) : null;

            if (mode == SyncMode.TWO_WAY) {
                decideTwoWay(min, eb, el, er, policy, plan);
            } else {
                decideOneWay(min, eb, el, er, mode, plan);
            }
        }

        Collections.sort(plan, new Comparator<PlanAction>() {
            @Override
            public int compare(PlanAction a, PlanAction b) {
                int pa = phase(a.action);
                int pb = phase(b.action);
                if (pa != pb) return Integer.compare(pa, pb);
                int da = depth(a.relPath);
                int db = depth(b.relPath);
                if (pa == 0 && da != db) return Integer.compare(da, db);
                if (pa == 2) {
                    if (da != db) return Integer.compare(db, da);
                    if (a.isDir != b.isDir) return a.isDir ? 1 : -1;
                }
                return a.relPath.compareTo(b.relPath);
            }
        });
        return plan;
    }

    private static List<FileEntry> sortedCopy(List<FileEntry> in) {
        List<FileEntry> c = in == null ? new ArrayList<FileEntry>() : new ArrayList<>(in);
        Collections.sort(c, new Comparator<FileEntry>() {
            @Override
            public int compare(FileEntry a, FileEntry b) {
                return a.relPath.compareTo(b.relPath);
            }
        });
        return c;
    }

    private static int phase(ActionType a) {
        switch (a) {
            case MKDIR_LOCAL:
            case MKDIR_REMOTE:
                return 0;
            case DELETE_LOCAL:
            case DELETE_REMOTE:
            case REMOVE_BASE:
                return 2;
            default:
                return 1;
        }
    }

    private static int depth(String p) {
        int d = 0;
        for (int i = 0; i < p.length(); i++) {
            if (p.charAt(i) == '/') d++;
        }
        return d;
    }

    private static int detectLocal(FileEntry base, FileEntry local) {
        if (base == null && local == null) return CH_NONE;
        if (base == null) return CH_CREATED;
        if (local == null) return CH_DELETED;
        if (base.isDir != local.isDir) return CH_MODIFIED;
        if (local.isDir) return CH_NONE;
        if (!base.hash.isEmpty() && !local.hash.isEmpty()) {
            return base.hash.equals(local.hash) ? CH_NONE : CH_MODIFIED;
        }
        if (base.size != local.size) return CH_MODIFIED;
        if (Math.abs(base.mtimeMs - local.mtimeMs) > MTIME_TOLERANCE_MS) return CH_MODIFIED;
        return CH_NONE;
    }

    private static int detectRemote(FileEntry base, FileEntry remote) {
        if (base == null && remote == null) return CH_NONE;
        if (base == null) return CH_CREATED;
        if (remote == null) return CH_DELETED;
        if (base.isDir != remote.isDir) return CH_MODIFIED;
        if (remote.isDir) return CH_NONE;
        if (!base.etag.isEmpty() && !remote.etag.isEmpty()) {
            return base.etag.equals(remote.etag) ? CH_NONE : CH_MODIFIED;
        }
        if (!base.hash.isEmpty() && !remote.hash.isEmpty()) {
            return base.hash.equals(remote.hash) ? CH_NONE : CH_MODIFIED;
        }
        if (base.size != remote.size) return CH_MODIFIED;
        if (base.mtimeMs > 0 && remote.mtimeMs > 0
                && Math.abs(base.mtimeMs - remote.mtimeMs) > MTIME_TOLERANCE_MS) {
            return CH_MODIFIED;
        }
        return CH_NONE;
    }

    private static boolean knownIdentical(FileEntry l, FileEntry r) {
        return l != null && r != null && !l.isDir && !r.isDir
                && !l.hash.isEmpty() && !r.hash.isEmpty() && l.hash.equals(r.hash);
    }

    private static void push(List<PlanAction> plan, String path, boolean isDir,
                             ActionType act, ConflictKind ck, FileEntry l, FileEntry r) {
        if (act == ActionType.NONE) return;
        plan.add(new PlanAction(path, isDir, act, ck,
                l != null ? l.size : 0L, l != null ? l.mtimeMs : 0L,
                r != null ? r.size : 0L, r != null ? r.mtimeMs : 0L,
                l != null ? l.hash : "", r != null ? r.etag : ""));
    }

    private static void decideTwoWay(String path, FileEntry base, FileEntry local, FileEntry remote,
                                     ConflictPolicy policy, List<PlanAction> plan) {
        int lc = detectLocal(base, local);
        int rc = detectRemote(base, remote);
        boolean isDir = local != null ? local.isDir : (remote != null ? remote.isDir : (base != null && base.isDir));

        if (local != null && remote != null && local.isDir && remote.isDir) {
            if (base == null) push(plan, path, true, ActionType.UPDATE_BASE, ConflictKind.NONE, local, remote);
            return;
        }
        if (local != null && remote != null && local.isDir != remote.isDir) {
            push(plan, path, isDir, ActionType.CONFLICT_ASK, ConflictKind.TYPE_MISMATCH, local, remote);
            return;
        }
        if (lc == CH_NONE && rc == CH_NONE) {
            if (base == null && local != null && remote != null) {
                push(plan, path, isDir, ActionType.UPDATE_BASE, ConflictKind.NONE, local, remote);
            }
            return;
        }
        if (lc != CH_NONE && rc == CH_NONE) {
            if (lc == CH_CREATED || lc == CH_MODIFIED) {
                push(plan, path, isDir, isDir ? ActionType.MKDIR_REMOTE : ActionType.UPLOAD, ConflictKind.NONE, local, remote);
            } else {
                push(plan, path, isDir, remote != null ? ActionType.DELETE_REMOTE : ActionType.REMOVE_BASE, ConflictKind.NONE, local, remote);
            }
            return;
        }
        if (lc == CH_NONE) {
            if (rc == CH_CREATED || rc == CH_MODIFIED) {
                push(plan, path, isDir, isDir ? ActionType.MKDIR_LOCAL : ActionType.DOWNLOAD, ConflictKind.NONE, local, remote);
            } else {
                push(plan, path, isDir, local != null ? ActionType.DELETE_LOCAL : ActionType.REMOVE_BASE, ConflictKind.NONE, local, remote);
            }
            return;
        }
        if (lc == CH_DELETED && rc == CH_DELETED) {
            push(plan, path, isDir, ActionType.REMOVE_BASE, ConflictKind.NONE, local, remote);
            return;
        }
        if ((lc == CH_CREATED || lc == CH_MODIFIED) && (rc == CH_CREATED || rc == CH_MODIFIED)) {
            if (knownIdentical(local, remote)) {
                push(plan, path, false, ActionType.UPDATE_BASE, ConflictKind.NONE, local, remote);
                return;
            }
            ConflictKind ck = base != null ? ConflictKind.BOTH_MODIFIED : ConflictKind.BOTH_CREATED;
            switch (policy) {
                case LOCAL_WINS:
                    push(plan, path, false, ActionType.UPLOAD, ck, local, remote);
                    break;
                case REMOTE_WINS:
                    push(plan, path, false, ActionType.DOWNLOAD, ck, local, remote);
                    break;
                case NEWEST_WINS:
                    push(plan, path, false, local.mtimeMs >= remote.mtimeMs ? ActionType.UPLOAD : ActionType.DOWNLOAD, ck, local, remote);
                    break;
                case KEEP_BOTH:
                    push(plan, path, false, ActionType.CONFLICT_KEEP_BOTH, ck, local, remote);
                    break;
                case AUTO_MERGE:
                    push(plan, path, false, ActionType.TRY_MERGE, ck, local, remote);
                    break;
                case ASK:
                default:
                    push(plan, path, false, ActionType.CONFLICT_ASK, ck, local, remote);
                    break;
            }
            return;
        }
        if (lc == CH_MODIFIED && rc == CH_DELETED) {
            ConflictKind ck = ConflictKind.LOCAL_MOD_REMOTE_DEL;
            if (policy == ConflictPolicy.LOCAL_WINS || policy == ConflictPolicy.KEEP_BOTH || policy == ConflictPolicy.NEWEST_WINS) {
                push(plan, path, isDir, isDir ? ActionType.MKDIR_REMOTE : ActionType.UPLOAD, ck, local, remote);
            } else if (policy == ConflictPolicy.REMOTE_WINS) {
                push(plan, path, isDir, ActionType.DELETE_LOCAL, ck, local, remote);
            } else {
                push(plan, path, isDir, ActionType.CONFLICT_ASK, ck, local, remote);
            }
            return;
        }
        if (lc == CH_DELETED && rc == CH_MODIFIED) {
            ConflictKind ck = ConflictKind.LOCAL_DEL_REMOTE_MOD;
            if (policy == ConflictPolicy.LOCAL_WINS) {
                push(plan, path, isDir, ActionType.DELETE_REMOTE, ck, local, remote);
            } else if (policy == ConflictPolicy.REMOTE_WINS || policy == ConflictPolicy.KEEP_BOTH || policy == ConflictPolicy.NEWEST_WINS) {
                push(plan, path, isDir, isDir ? ActionType.MKDIR_LOCAL : ActionType.DOWNLOAD, ck, local, remote);
            } else {
                push(plan, path, isDir, ActionType.CONFLICT_ASK, ck, local, remote);
            }
        }
    }

    private static void decideOneWay(String path, FileEntry base, FileEntry local, FileEntry remote,
                                     SyncMode mode, List<PlanAction> plan) {
        int lc = detectLocal(base, local);
        int rc = detectRemote(base, remote);
        if (local == null && remote == null && base != null) {
            push(plan, path, base.isDir, ActionType.REMOVE_BASE, ConflictKind.NONE, null, null);
            return;
        }
        if (mode == SyncMode.UPLOAD_ONLY || mode == SyncMode.BACKUP_APPEND) {
            if (local == null) return;
            if (local.isDir) {
                if (remote == null) push(plan, path, true, ActionType.MKDIR_REMOTE, ConflictKind.NONE, local, null);
                else if (base == null) push(plan, path, true, ActionType.UPDATE_BASE, ConflictKind.NONE, local, remote);
                return;
            }
            if (remote == null) {
                push(plan, path, false, ActionType.UPLOAD, ConflictKind.NONE, local, null);
            } else if (lc == CH_MODIFIED || lc == CH_CREATED) {
                push(plan, path, false, knownIdentical(local, remote) ? ActionType.UPDATE_BASE : ActionType.UPLOAD, ConflictKind.NONE, local, remote);
            }
            return;
        }
        if (mode == SyncMode.UPLOAD_MIRROR) {
            if (local != null && remote == null) {
                push(plan, path, local.isDir, local.isDir ? ActionType.MKDIR_REMOTE : ActionType.UPLOAD, ConflictKind.NONE, local, null);
            } else if (local == null && remote != null) {
                push(plan, path, remote.isDir, ActionType.DELETE_REMOTE, ConflictKind.NONE, null, remote);
            } else if (local != null) {
                if (local.isDir && remote.isDir) {
                    if (base == null) push(plan, path, true, ActionType.UPDATE_BASE, ConflictKind.NONE, local, remote);
                } else if (local.isDir != remote.isDir) {
                    push(plan, path, local.isDir, ActionType.CONFLICT_ASK, ConflictKind.TYPE_MISMATCH, local, remote);
                } else if (lc != CH_NONE || rc != CH_NONE) {
                    push(plan, path, false, knownIdentical(local, remote) ? ActionType.UPDATE_BASE : ActionType.UPLOAD, ConflictKind.NONE, local, remote);
                }
            }
            return;
        }
        if (mode == SyncMode.DOWNLOAD_ONLY) {
            if (remote == null) return;
            if (remote.isDir) {
                if (local == null) push(plan, path, true, ActionType.MKDIR_LOCAL, ConflictKind.NONE, null, remote);
                else if (base == null) push(plan, path, true, ActionType.UPDATE_BASE, ConflictKind.NONE, local, remote);
                return;
            }
            if (local == null) {
                push(plan, path, false, ActionType.DOWNLOAD, ConflictKind.NONE, null, remote);
            } else if (rc == CH_MODIFIED || rc == CH_CREATED) {
                push(plan, path, false, knownIdentical(local, remote) ? ActionType.UPDATE_BASE : ActionType.DOWNLOAD, ConflictKind.NONE, local, remote);
            }
            return;
        }
        if (mode == SyncMode.DOWNLOAD_MIRROR) {
            if (remote != null && local == null) {
                push(plan, path, remote.isDir, remote.isDir ? ActionType.MKDIR_LOCAL : ActionType.DOWNLOAD, ConflictKind.NONE, null, remote);
            } else if (remote == null && local != null) {
                push(plan, path, local.isDir, ActionType.DELETE_LOCAL, ConflictKind.NONE, local, null);
            } else if (local != null) {
                if (local.isDir && remote.isDir) {
                    if (base == null) push(plan, path, true, ActionType.UPDATE_BASE, ConflictKind.NONE, local, remote);
                } else if (local.isDir != remote.isDir) {
                    push(plan, path, remote.isDir, ActionType.CONFLICT_ASK, ConflictKind.TYPE_MISMATCH, local, remote);
                } else if (lc != CH_NONE || rc != CH_NONE) {
                    push(plan, path, false, knownIdentical(local, remote) ? ActionType.UPDATE_BASE : ActionType.DOWNLOAD, ConflictKind.NONE, local, remote);
                }
            }
        }
    }
}
