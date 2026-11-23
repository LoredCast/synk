package org.synk.test;

import org.synk.core.NativeBridge;
import org.synk.core.SyncEngine;
import org.synk.core.SyncModels.*;
import org.synk.core.SyncOptions;
import org.synk.core.SyncProgress;
import org.synk.pim.IcsCodec;
import org.synk.pim.PimSyncEngine;
import org.synk.pim.VcfCodec;
import org.synk.protocol.BackendFactory;
import org.synk.protocol.RemoteBackend;
import org.synk.protocol.S3Backend;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Zero-dependency end-to-end integration test suite for Synk (C11 JNI + Java engine +
 * WebDAV + FTP + S3 + CalDAV + CardDAV + diff3 merge + conflict resolution + inotify).
 */
public final class SynkTestSuite {

    private static int passed = 0;

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("Assertion failed: " + msg);
    }

    private static void checkEq(Object expected, Object actual, String msg) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(msg + " | expected=<" + expected + "> actual=<" + actual + ">");
        }
    }

    private static void run(String name, TestCase tc) throws Exception {
        System.out.print("  [TEST] " + name + " ... ");
        tc.run();
        passed++;
        System.out.println("OK");
    }

    interface TestCase { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        System.out.println("Running Synk End-to-End Test Suite (Engine: " + NativeBridge.engineName() + ")");
        check(NativeBridge.isNativeLoaded(), "libsynk_native.so must be loaded via -Djava.library.path");

        run("JNI vs Pure-Java Fallback Parity (SHA-256, Scan, Diff3, Planner)", SynkTestSuite::testNativeVsJavaParity);
        run("JNI Native Linux inotify Recursive Watcher", SynkTestSuite::testJniWatcher);
        run("WebDAV Two-Way Sync + 3-Way Diff3 Auto-Merge + Conflicts", SynkTestSuite::testWebDavTwoWayAndConflicts);
        run("WebDAV Spaces/UTF-8 Paths + KEEP_BOTH & NEWEST_WINS Policies", SynkTestSuite::testUnicodeAndConflictPolicies);
        run("Sync Modes (Backup Append, Upload Mirror, Download Mirror)", SynkTestSuite::testSyncModes);
        run("FTP End-to-End Bidirectional Sync", SynkTestSuite::testFtpSync);
        run("S3 SigV4 Signing & End-to-End Object Sync", SynkTestSuite::testS3Sync);
        run("CalDAV & CardDAV Codecs + Bidirectional Sync + Offline Conflicts", SynkTestSuite::testCalDavAndCardDav);
        run("Remote File Explorer (listChildren on WebDAV, FTP, S3, Local)", SynkTestSuite::testRemoteFileExplorer);
        run("Live Sync Progress Telemetry, SyncOptions & Cooperative Cancel", SynkTestSuite::testLiveProgressOptionsAndCancel);

        System.out.println("All " + passed + " integration tests passed!");
    }

    private static void testNativeVsJavaParity() throws Exception {
        File tmp = Files.createTempDirectory("synk_parity_").toFile();
        try {
            writeText(new File(tmp, "a.txt"), "hello world\n");
            writeText(new File(tmp, "sub/b.txt"), "nested file\n");
            writeText(new File(tmp, "skip.tmp"), "temp");

            String[] ignores = { "*.tmp" };
            byte[] base   = "one\ntwo\nthree\nfour\n".getBytes(StandardCharsets.UTF_8);
            byte[] local  = "ONE\ntwo\nthree\nfour\n".getBytes(StandardCharsets.UTF_8);
            byte[] remote = "one\ntwo\nthree\nFOUR\n".getBytes(StandardCharsets.UTF_8);

            /* Run on C11 JNI */
            NativeBridge.setForceJavaFallbackForTest(false);
            String hC = NativeBridge.sha256Bytes(base);
            List<FileEntry> scanC = NativeBridge.scanDir(tmp.getAbsolutePath(), ignores, true);
            MergeResult mergeC = NativeBridge.diff3Merge(base, local, remote, "L", "R");

            /* Run on Pure Java Fallback */
            NativeBridge.setForceJavaFallbackForTest(true);
            String hJ = NativeBridge.sha256Bytes(base);
            List<FileEntry> scanJ = NativeBridge.scanDir(tmp.getAbsolutePath(), ignores, true);
            MergeResult mergeJ = NativeBridge.diff3Merge(base, local, remote, "L", "R");
            NativeBridge.setForceJavaFallbackForTest(false);

            checkEq(hC, hJ, "SHA-256 parity");
            checkEq(scanC.size(), scanJ.size(), "scan count parity");
            for (int i = 0; i < scanC.size(); i++) {
                checkEq(scanC.get(i).relPath, scanJ.get(i).relPath, "scan path parity");
                checkEq(scanC.get(i).hash, scanJ.get(i).hash, "scan hash parity");
            }
            checkEq(mergeC.conflictCount, mergeJ.conflictCount, "diff3 conflict count parity");
            checkEq(new String(mergeC.mergedBytes, StandardCharsets.UTF_8),
                    new String(mergeJ.mergedBytes, StandardCharsets.UTF_8), "diff3 output parity");
            checkEq("ONE\ntwo\nthree\nFOUR\n", new String(mergeC.mergedBytes, StandardCharsets.UTF_8), "diff3 clean merge");

            /* Verify planner parity across all modes & policies */
            List<FileEntry> bList = new ArrayList<>();
            List<FileEntry> lList = new ArrayList<>();
            List<FileEntry> rList = new ArrayList<>();
            bList.add(new FileEntry("both.txt", false, 10, 1000, "h0", "e0"));
            lList.add(new FileEntry("both.txt", false, 12, 5000, "hL", ""));
            rList.add(new FileEntry("both.txt", false, 14, 9000, "", "eR"));
            lList.add(new FileEntry("local_only.txt", false, 5, 2000, "hl", ""));
            rList.add(new FileEntry("remote_only.txt", false, 7, 3000, "", "er"));

            for (SyncMode mode : SyncMode.values()) {
                for (ConflictPolicy pol : ConflictPolicy.values()) {
                    NativeBridge.setForceJavaFallbackForTest(false);
                    List<PlanAction> pC = NativeBridge.planSync(bList, lList, rList, mode, pol);
                    NativeBridge.setForceJavaFallbackForTest(true);
                    List<PlanAction> pJ = NativeBridge.planSync(bList, lList, rList, mode, pol);
                    NativeBridge.setForceJavaFallbackForTest(false);
                    checkEq(pC.size(), pJ.size(), "plan size parity " + mode + "/" + pol);
                    for (int i = 0; i < pC.size(); i++) {
                        checkEq(pC.get(i).relPath, pJ.get(i).relPath, "plan path parity");
                        checkEq(pC.get(i).action, pJ.get(i).action, "plan action parity " + mode + "/" + pol);
                        checkEq(pC.get(i).conflictKind, pJ.get(i).conflictKind, "plan conflict kind parity");
                    }
                }
            }
        } finally {
            deleteRec(tmp);
        }
    }

    private static void testJniWatcher() throws Exception {
        File tmp = Files.createTempDirectory("synk_jni_watch_").toFile();
        try {
            CountDownLatch latch = new CountDownLatch(1);
            String[] observed = { "" };
            long handle = NativeBridge.startWatcher(tmp.getAbsolutePath(), new String[]{ "*.tmp" }, 100,
                    (lastRelPath, count) -> {
                        observed[0] = lastRelPath;
                        if (lastRelPath.contains("hello.md")) latch.countDown();
                    });
            check(handle > 0, "Native inotify handle should be positive");
            Thread.sleep(80);
            File sub = new File(tmp, "notes");
            sub.mkdirs();
            Thread.sleep(80);
            writeText(new File(sub, "hello.md"), "watched!");
            boolean fired = latch.await(3, TimeUnit.SECONDS);
            NativeBridge.stopWatcher(handle);
            check(fired, "JNI inotify watcher should fire on nested file write, got: " + observed[0]);
            checkEq("notes/hello.md", observed[0], "JNI watcher relative path");
        } finally {
            deleteRec(tmp);
        }
    }

    private static void testWebDavTwoWayAndConflicts() throws Exception {
        File localDir = Files.createTempDirectory("synk_dav_local_").toFile();
        try (MockWebDavServer srv = new MockWebDavServer("alice", "s3cr3t")) {
            srv.putDir("/dav/sync");
            srv.putFile("/dav/sync/remote_note.md", "line1\nline2\nline3\nline4\n".getBytes(StandardCharsets.UTF_8), "text/markdown");
            srv.putFile("/dav/sync/conflict.txt", "header\nbody\nfooter\n".getBytes(StandardCharsets.UTF_8), "text/plain");

            writeText(new File(localDir, "local_only.txt"), "from phone\n");
            writeText(new File(localDir, "ignored.tmp"), "should not upload");

            AccountConfig acc = new AccountConfig(1, "TestDAV", Protocol.WEBDAV,
                    srv.baseUrl("/dav"), "alice", "s3cr3t", "", false);
            SyncPair pair = new SyncPair();
            pair.id = 1;
            pair.name = "Notes";
            pair.accountId = 1;
            pair.localPath = localDir.getAbsolutePath();
            pair.remotePath = "/sync";
            pair.mode = SyncMode.TWO_WAY;
            pair.conflictPolicy = ConflictPolicy.AUTO_MERGE;
            pair.ignorePatterns = "*.tmp";

            InMemoryStateStore store = new InMemoryStateStore();
            SyncEngine engine = new SyncEngine(store);

            try (RemoteBackend backend = BackendFactory.create(acc)) {
                check(backend.testConnection().contains("WebDAV OK"), "testConnection");

                /* Sync #1: Initial bidirectional sync */
                SyncResult r1 = engine.syncPair(pair, backend);
                checkEq(0, r1.errors, "Sync 1 errors");
                checkEq(1, r1.uploaded, "Sync 1 uploads");
                checkEq(2, r1.downloaded, "Sync 1 downloads");
                check(srv.getFile("/dav/sync/local_only.txt") != null, "local_only uploaded");
                check(srv.getFile("/dav/sync/ignored.tmp") == null, "ignored.tmp skipped");
                check(new File(localDir, "remote_note.md").isFile(), "remote_note downloaded");

                /* Simulate offline period: BOTH sides edit remote_note.md on non-overlapping lines! */
                Thread.sleep(20);
                writeText(new File(localDir, "remote_note.md"), "LINE1_PHONE\nline2\nline3\nline4\n");
                srv.putFile("/dav/sync/remote_note.md",
                        "line1\nline2\nline3\nLINE4_SERVER\n".getBytes(StandardCharsets.UTF_8), "text/markdown");

                /* BOTH sides edit conflict.txt on the SAME line -> overlapping conflict! */
                writeText(new File(localDir, "conflict.txt"), "header\nPHONE_BODY\nfooter\n");
                srv.putFile("/dav/sync/conflict.txt",
                        "header\nSERVER_BODY\nfooter\n".getBytes(StandardCharsets.UTF_8), "text/plain");

                /* Local edits local_only.txt while server deletes it -> LOCAL_MOD_REMOTE_DEL conflict! */
                writeText(new File(localDir, "local_only.txt"), "phone edited while offline\n");
                srv.removeItem("/dav/sync/local_only.txt");

                /* Sync #2: Offline reconciliation! */
                SyncResult r2 = engine.syncPair(pair, backend);
                checkEq(0, r2.errors, "Sync 2 errors");
                checkEq(1, r2.merged, "remote_note.md should auto-merge cleanly via C diff3!");
                checkEq(2, r2.conflicts, "conflict.txt & local_only.txt queued as conflicts");

                String expectedMerged = "LINE1_PHONE\nline2\nline3\nLINE4_SERVER\n";
                checkEq(expectedMerged, readText(new File(localDir, "remote_note.md")), "Local merged content");
                checkEq(expectedMerged, new String(srv.getFile("/dav/sync/remote_note.md"), StandardCharsets.UTF_8), "Remote merged content");

                /* Inspect queued conflicts & resolve interactively */
                List<ConflictRecord> conflicts = store.listConflicts();
                checkEq(2, conflicts.size(), "2 conflicts in Conflict Manager");

                ConflictRecord overlap = null;
                ConflictRecord modDel = null;
                for (ConflictRecord c : conflicts) {
                    if ("conflict.txt".equals(c.relPath)) overlap = c;
                    if ("local_only.txt".equals(c.relPath)) modDel = c;
                }
                check(overlap != null && overlap.kind == ConflictKind.MERGE_OVERLAP, "Merge overlap conflict kind");
                check(overlap.mergedPreview != null && overlap.mergedPreview.contains("<<<<<<< LOCAL"), "Diff3 preview present");
                check(modDel != null && modDel.kind == ConflictKind.LOCAL_MOD_REMOTE_DEL, "Modify-vs-delete conflict kind");

                /* Resolve overlap via USE_MERGED with custom edited resolution */
                String customResolved = "header\nPHONE_AND_SERVER_COMBINED\nfooter\n";
                engine.resolveConflict(pair, backend, overlap, ConflictResolution.USE_MERGED,
                        customResolved.getBytes(StandardCharsets.UTF_8));
                checkEq(customResolved, readText(new File(localDir, "conflict.txt")), "Resolved merged local");
                checkEq(customResolved, new String(srv.getFile("/dav/sync/conflict.txt"), StandardCharsets.UTF_8), "Resolved merged remote");

                /* Resolve mod-vs-del via KEEP_LOCAL (restores edited file on server!) */
                engine.resolveConflict(pair, backend, modDel, ConflictResolution.KEEP_LOCAL, null);
                checkEq("phone edited while offline\n",
                        new String(srv.getFile("/dav/sync/local_only.txt"), StandardCharsets.UTF_8),
                        "Keep local restored deleted remote file");
                checkEq(0, store.listConflicts().size(), "All conflicts cleared");

                /* Sync #3: Steady state -> zero transfers! */
                SyncResult r3 = engine.syncPair(pair, backend);
                checkEq(0, r3.uploaded + r3.downloaded + r3.merged + r3.conflicts + r3.errors, "Steady state no-op");
            }
        } finally {
            deleteRec(localDir);
        }
    }

    private static void testUnicodeAndConflictPolicies() throws Exception {
        File localDir = Files.createTempDirectory("synk_utf8_").toFile();
        try (MockWebDavServer srv = new MockWebDavServer("u", "p")) {
            srv.putDir("/dav/box");
            writeText(new File(localDir, "My Notes/Café 2026.txt"), "initial");

            AccountConfig acc = new AccountConfig(9, "DavUTF8", Protocol.WEBDAV,
                    srv.baseUrl("/dav"), "u", "p", "", false);
            SyncPair pair = new SyncPair();
            pair.id = 9;
            pair.name = "UTF8";
            pair.accountId = 9;
            pair.localPath = localDir.getAbsolutePath();
            pair.remotePath = "/box";
            pair.mode = SyncMode.TWO_WAY;
            pair.conflictPolicy = ConflictPolicy.KEEP_BOTH;

            InMemoryStateStore store = new InMemoryStateStore();
            SyncEngine engine = new SyncEngine(store);

            try (RemoteBackend backend = BackendFactory.create(acc)) {
                SyncResult r1 = engine.syncPair(pair, backend);
                checkEq(0, r1.errors, "UTF8 sync 1 errors");
                checkEq("initial",
                        new String(srv.getFile("/dav/box/My Notes/Café 2026.txt"), StandardCharsets.UTF_8),
                        "UTF8 + spaces uploaded cleanly");

                /* Diverge on both sides -> KEEP_BOTH creates conflict copy & syncs both versions */
                Thread.sleep(20);
                writeText(new File(localDir, "My Notes/Café 2026.txt"), "phone copy");
                srv.putFile("/dav/box/My Notes/Café 2026.txt", "server copy".getBytes(StandardCharsets.UTF_8), "text/plain");

                SyncResult r2 = engine.syncPair(pair, backend);
                checkEq(0, r2.errors, "KEEP_BOTH errors");
                checkEq("server copy", readText(new File(localDir, "My Notes/Café 2026.txt")), "Original receives server copy");

                File notesDir = new File(localDir, "My Notes");
                File[] files = notesDir.listFiles();
                check(files != null && files.length == 2, "KEEP_BOTH produced 2 files in My Notes");
                boolean foundConflictCopy = false;
                for (File f : files) {
                    if (f.getName().contains(".conflict-")) {
                        foundConflictCopy = true;
                        checkEq("phone copy", readText(f), "Conflict copy holds local edit");
                        check(srv.getFile("/dav/box/My Notes/" + f.getName()) != null, "Conflict copy uploaded to server");
                    }
                }
                check(foundConflictCopy, "Conflict copy filename created");

                /* Steady state after KEEP_BOTH */
                SyncResult r3 = engine.syncPair(pair, backend);
                checkEq(0, r3.uploaded + r3.downloaded + r3.errors + r3.conflicts, "Steady state after KEEP_BOTH");
            }
        } finally {
            deleteRec(localDir);
        }
    }

    private static void testSyncModes() throws Exception {
        File localDir = Files.createTempDirectory("synk_mode_local_").toFile();
        File remoteDir = Files.createTempDirectory("synk_mode_remote_").toFile();
        try {
            AccountConfig acc = new AccountConfig(2, "LocalTarget", Protocol.LOCAL,
                    remoteDir.getAbsolutePath(), "", "", "", false);
            SyncPair pair = new SyncPair();
            pair.id = 2;
            pair.name = "Photos";
            pair.accountId = 2;
            pair.localPath = localDir.getAbsolutePath();
            pair.remotePath = "/";

            InMemoryStateStore store = new InMemoryStateStore();
            SyncEngine engine = new SyncEngine(store);

            try (RemoteBackend backend = BackendFactory.create(acc)) {
                /* BACKUP_APPEND: uploads local, never deletes remote extras even when deleted locally */
                pair.mode = SyncMode.BACKUP_APPEND;
                writeText(new File(localDir, "p1.jpg"), "photo1");
                writeText(new File(remoteDir, "old_archive.jpg"), "keep me");

                SyncResult r1 = engine.syncPair(pair, backend);
                checkEq(1, r1.uploaded, "Backup uploaded p1");
                check(new File(remoteDir, "p1.jpg").isFile(), "p1 on remote");
                check(new File(remoteDir, "old_archive.jpg").isFile(), "archive untouched");
                check(!new File(localDir, "old_archive.jpg").exists(), "backup didn't download");

                /* Delete p1 locally -> BACKUP_APPEND must NOT delete remote p1 */
                new File(localDir, "p1.jpg").delete();
                SyncResult r2 = engine.syncPair(pair, backend);
                checkEq(0, r2.deletedRemote, "Backup never deletes remote");
                check(new File(remoteDir, "p1.jpg").isFile(), "remote p1 preserved");

                /* UPLOAD_MIRROR: remote becomes exact mirror of local */
                pair.mode = SyncMode.UPLOAD_MIRROR;
                writeText(new File(localDir, "fresh.txt"), "only this");
                SyncResult r3 = engine.syncPair(pair, backend);
                checkEq(1, r3.uploaded, "Mirror uploaded fresh.txt");
                checkEq(2, r3.deletedRemote, "Mirror deleted remote extras");
                check(!new File(remoteDir, "p1.jpg").exists(), "p1 pruned by upload mirror");
                check(!new File(remoteDir, "old_archive.jpg").exists(), "old_archive pruned by upload mirror");

                /* DOWNLOAD_MIRROR: local becomes exact mirror of remote */
                pair.mode = SyncMode.DOWNLOAD_MIRROR;
                writeText(new File(localDir, "stray_local.txt"), "delete me");
                writeText(new File(remoteDir, "server_master.txt"), "pull me");
                SyncResult r4 = engine.syncPair(pair, backend);
                checkEq(1, r4.downloaded, "Download mirror pulled server_master.txt");
                checkEq(1, r4.deletedLocal, "Download mirror pruned stray_local.txt");
                check(!new File(localDir, "stray_local.txt").exists(), "stray deleted");
                checkEq("pull me", readText(new File(localDir, "server_master.txt")), "master pulled");
            }
        } finally {
            deleteRec(localDir);
            deleteRec(remoteDir);
        }
    }

    private static void testFtpSync() throws Exception {
        File localDir = Files.createTempDirectory("synk_ftp_local_").toFile();
        File ftpRoot = Files.createTempDirectory("synk_ftp_root_").toFile();
        try (MockFtpServer ftp = new MockFtpServer(ftpRoot, "ftpuser", "ftppass")) {
            writeText(new File(ftpRoot, "remote_doc.txt"), "from ftp server");
            writeText(new File(localDir, "sub/local_doc.txt"), "from android client");

            AccountConfig acc = new AccountConfig(3, "HomeFTP", Protocol.FTP,
                    "ftp://127.0.0.1:" + ftp.getPort(), "ftpuser", "ftppass", "", false);
            SyncPair pair = new SyncPair();
            pair.id = 3;
            pair.name = "FtpPair";
            pair.accountId = 3;
            pair.localPath = localDir.getAbsolutePath();
            pair.remotePath = "/";
            pair.mode = SyncMode.TWO_WAY;
            pair.conflictPolicy = ConflictPolicy.ASK;

            InMemoryStateStore store = new InMemoryStateStore();
            SyncEngine engine = new SyncEngine(store);

            try (RemoteBackend backend = BackendFactory.create(acc)) {
                check(backend.testConnection().startsWith("FTP OK"), "FTP testConnection");
                SyncResult r1 = engine.syncPair(pair, backend);
                checkEq(0, r1.errors, "FTP sync 1 errors");
                checkEq(1, r1.uploaded, "FTP uploaded local_doc");
                checkEq(1, r1.downloaded, "FTP downloaded remote_doc");
                checkEq("from android client", readText(new File(ftpRoot, "sub/local_doc.txt")), "FTP stored file");
                checkEq("from ftp server", readText(new File(localDir, "remote_doc.txt")), "FTP retrieved file");

                /* Local delete propagates to FTP server */
                new File(localDir, "remote_doc.txt").delete();
                SyncResult r2 = engine.syncPair(pair, backend);
                checkEq(0, r2.errors, "FTP sync 2 errors");
                checkEq(1, r2.deletedRemote, "FTP propagated delete");
                check(!new File(ftpRoot, "remote_doc.txt").exists(), "FTP file deleted");
            }
        } finally {
            deleteRec(localDir);
            deleteRec(ftpRoot);
        }
    }

    private static void testS3Sync() throws Exception {
        File localDir = Files.createTempDirectory("synk_s3_local_").toFile();
        try (MockWebDavServer srv = new MockWebDavServer("u", "p")) {
            srv.putFile("/s3/mybucket/cloud/hello.txt", "hello from s3".getBytes(StandardCharsets.UTF_8), "text/plain");
            writeText(new File(localDir, "upload.txt"), "uploaded to s3");

            AccountConfig acc = new AccountConfig(4, "MinIO", Protocol.S3,
                    srv.baseUrl("/s3"), "AKIAEXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                    "mybucket|us-east-1", false);
            S3Backend s3 = new S3Backend(acc);

            /* Verify SigV4 format */
            Map<String, String> signed = s3.signRequestAt("GET", "/mybucket", "list-type=2",
                    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", new Date(1700000000000L));
            check(signed.get("Authorization").startsWith("AWS4-HMAC-SHA256 Credential=AKIAEXAMPLE/20231114/us-east-1/s3/aws4_request"),
                    "SigV4 credential scope format");

            check(s3.testConnection().contains("S3 OK"), "S3 testConnection");

            SyncPair pair = new SyncPair();
            pair.id = 4;
            pair.name = "S3Docs";
            pair.accountId = 4;
            pair.localPath = localDir.getAbsolutePath();
            pair.remotePath = "cloud";
            pair.mode = SyncMode.TWO_WAY;
            pair.conflictPolicy = ConflictPolicy.ASK;

            SyncEngine engine = new SyncEngine(new InMemoryStateStore());
            SyncResult r = engine.syncPair(pair, s3);
            checkEq(0, r.errors, "S3 sync errors");
            checkEq(1, r.uploaded, "S3 upload count");
            checkEq(1, r.downloaded, "S3 download count");
            checkEq("hello from s3", readText(new File(localDir, "hello.txt")), "S3 downloaded object");
            checkEq("uploaded to s3", new String(srv.getFile("/s3/mybucket/cloud/upload.txt"), StandardCharsets.UTF_8), "S3 uploaded object");
        } finally {
            deleteRec(localDir);
        }
    }

    private static void testCalDavAndCardDav() throws Exception {
        /* 1. Codec round-trips */
        IcsCodec.CalEvent ev = new IcsCodec.CalEvent();
        ev.uid = "ev-100";
        ev.summary = "Dentist, checkup; urgent";
        ev.description = "Line 1\nLine 2";
        ev.location = "Main St";
        ev.dtStartMs = 1763900000000L;
        ev.dtEndMs = 1763903600000L;
        IcsCodec.CalEvent parsedEv = IcsCodec.parse(IcsCodec.serialize(ev));
        checkEq(ev.uid, parsedEv.uid, "ICS UID round-trip");
        checkEq(ev.summary, parsedEv.summary, "ICS summary escaping round-trip");
        checkEq(ev.description, parsedEv.description, "ICS multiline description round-trip");

        VcfCodec.ContactCard ct = new VcfCodec.ContactCard();
        ct.uid = "ct-200";
        ct.fullName = "Ada Lovelace";
        ct.familyName = "Lovelace";
        ct.givenName = "Ada";
        ct.org = "Analytical Engine Ltd";
        ct.phones.add(new VcfCodec.TypedValue("CELL", "+44-1234-5678"));
        ct.emails.add(new VcfCodec.TypedValue("WORK", "ada@example.org"));
        VcfCodec.ContactCard parsedCt = VcfCodec.parse(VcfCodec.serialize(ct));
        checkEq(ct.uid, parsedCt.uid, "VCF UID round-trip");
        checkEq(ct.fullName, parsedCt.fullName, "VCF FN round-trip");
        checkEq(1, parsedCt.phones.size(), "VCF phone count");
        checkEq("+44-1234-5678", parsedCt.phones.get(0).value, "VCF phone value");
        checkEq("ada@example.org", parsedCt.emails.get(0).value, "VCF email value");

        /* 2. Live loopback CalDAV & CardDAV bidirectional sync + conflict test */
        try (MockWebDavServer srv = new MockWebDavServer("bob", "pw")) {
            srv.putFile("/cal/ev-100.ics", IcsCodec.serialize(ev).getBytes(StandardCharsets.UTF_8), "text/calendar");
            srv.putFile("/ab/ct-200.vcf", VcfCodec.serialize(ct).getBytes(StandardCharsets.UTF_8), "text/vcard");

            InMemoryStateStore store = new InMemoryStateStore();
            PimSyncEngine pimEngine = new PimSyncEngine(store);

            /* CalDAV test */
            PimProfile calProfile = new PimProfile();
            calProfile.id = 10;
            calProfile.name = "Personal Cal";
            calProfile.kind = PimKind.CALDAV;
            calProfile.url = srv.baseUrl("/cal/");
            calProfile.username = "bob";
            calProfile.password = "pw";
            calProfile.conflictPolicy = ConflictPolicy.KEEP_BOTH;

            Map<String, String> localEvents = new HashMap<>();
            IcsCodec.CalEvent phoneEv = new IcsCodec.CalEvent();
            phoneEv.uid = "ev-local-1";
            phoneEv.summary = "Created on Phone";
            phoneEv.dtStartMs = 1763910000000L;
            phoneEv.dtEndMs = 1763913600000L;
            localEvents.put(phoneEv.uid, IcsCodec.serialize(phoneEv));

            PimSyncEngine.LocalPimAdapter calAdapter = inMemoryPimAdapter(localEvents, PimKind.CALDAV);
            SyncResult cr1 = pimEngine.sync(calProfile, calAdapter);
            checkEq(0, cr1.errors, "CalDAV sync 1 errors");
            checkEq(1, cr1.downloaded, "CalDAV pulled ev-100");
            checkEq(1, cr1.uploaded, "CalDAV pushed ev-local-1");
            check(localEvents.containsKey("ev-100"), "Local calendar has ev-100");
            check(srv.getFile("/cal/ev-local-1.ics") != null, "Server has ev-local-1.ics");

            /* Offline conflict on ev-100: both sides edit -> KEEP_BOTH forks local & pulls server */
            IcsCodec.CalEvent localEdit = IcsCodec.parse(localEvents.get("ev-100"));
            localEdit.summary = "Dentist (Phone Edit)";
            localEvents.put("ev-100", IcsCodec.serialize(localEdit));

            IcsCodec.CalEvent serverEdit = IcsCodec.parse(IcsCodec.serialize(ev));
            serverEdit.summary = "Dentist (Server Edit)";
            Thread.sleep(20);
            srv.putFile("/cal/ev-100.ics", IcsCodec.serialize(serverEdit).getBytes(StandardCharsets.UTF_8), "text/calendar");

            SyncResult cr2 = pimEngine.sync(calProfile, calAdapter);
            checkEq(0, cr2.errors, "CalDAV conflict sync errors");
            checkEq(1, cr2.conflicts, "CalDAV conflict detected & preserved both");
            checkEq(3, localEvents.size(), "Local calendar now has original + forked copy + phoneEv");
            checkEq("Dentist (Server Edit)", IcsCodec.parse(localEvents.get("ev-100")).summary, "Original updated to server");

            /* CardDAV test */
            PimProfile abProfile = new PimProfile();
            abProfile.id = 11;
            abProfile.name = "Contacts";
            abProfile.kind = PimKind.CARDDAV;
            abProfile.url = srv.baseUrl("/ab/");
            abProfile.username = "bob";
            abProfile.password = "pw";

            Map<String, String> localContacts = new HashMap<>();
            PimSyncEngine.LocalPimAdapter abAdapter = inMemoryPimAdapter(localContacts, PimKind.CARDDAV);
            SyncResult ar1 = pimEngine.sync(abProfile, abAdapter);
            checkEq(0, ar1.errors, "CardDAV sync 1 errors");
            checkEq(1, ar1.downloaded, "CardDAV pulled Ada");
            check(localContacts.containsKey("ct-200"), "Local contacts has Ada");
        }
    }

    private static void testRemoteFileExplorer() throws Exception {
        File localRoot = Files.createTempDirectory("synk_exp_local_").toFile();
        File ftpRoot = Files.createTempDirectory("synk_exp_ftp_").toFile();
        try (MockWebDavServer srv = new MockWebDavServer("u", "p");
             MockFtpServer ftp = new MockFtpServer(ftpRoot, "u", "p")) {
            /* WebDAV hierarchy */
            srv.putFile("/dav/Docs/readme.md", "hi".getBytes(StandardCharsets.UTF_8), "text/markdown");
            srv.putFile("/dav/Docs/Sub/deep.txt", "deep".getBytes(StandardCharsets.UTF_8), "text/plain");
            srv.putFile("/dav/top.txt", "top".getBytes(StandardCharsets.UTF_8), "text/plain");

            AccountConfig davAcc = new AccountConfig(20, "DAV", Protocol.WEBDAV,
                    srv.baseUrl("/dav"), "u", "p", "", false);
            try (RemoteBackend dav = BackendFactory.create(davAcc)) {
                List<FileEntry> rootKids = dav.listChildren("/");
                checkEq(2, rootKids.size(), "WebDAV root children count");
                check(rootKids.get(0).isDir && "Docs".equals(rootKids.get(0).relPath), "WebDAV dirs sorted first");
                check(!rootKids.get(1).isDir && "top.txt".equals(rootKids.get(1).relPath), "WebDAV file second");

                List<FileEntry> docsKids = dav.listChildren("/Docs");
                checkEq(2, docsKids.size(), "WebDAV /Docs children count");
                check(docsKids.get(0).isDir && "Sub".equals(docsKids.get(0).relPath), "WebDAV nested dir");
            }

            /* S3 hierarchy */
            srv.putFile("/s3/bkt/Photos/2026/a.jpg", "jpg".getBytes(StandardCharsets.UTF_8), "image/jpeg");
            srv.putFile("/s3/bkt/root.txt", "root".getBytes(StandardCharsets.UTF_8), "text/plain");
            AccountConfig s3Acc = new AccountConfig(21, "S3", Protocol.S3,
                    srv.baseUrl("/s3"), "AK", "SK", "bkt|us-east-1", false);
            try (RemoteBackend s3 = BackendFactory.create(s3Acc)) {
                List<FileEntry> s3Root = s3.listChildren("/");
                checkEq(2, s3Root.size(), "S3 root children count");
                check(s3Root.get(0).isDir && "Photos".equals(s3Root.get(0).relPath), "S3 virtual dir");
                check(!s3Root.get(1).isDir && "root.txt".equals(s3Root.get(1).relPath), "S3 root file");
            }

            /* FTP hierarchy */
            writeText(new File(ftpRoot, "Archive/nested.txt"), "nested");
            writeText(new File(ftpRoot, "index.txt"), "idx");
            AccountConfig ftpAcc = new AccountConfig(22, "FTP", Protocol.FTP,
                    "ftp://127.0.0.1:" + ftp.getPort(), "u", "p", "", false);
            try (RemoteBackend fb = BackendFactory.create(ftpAcc)) {
                List<FileEntry> ftpKids = fb.listChildren("/");
                checkEq(2, ftpKids.size(), "FTP root children count");
                check(ftpKids.get(0).isDir && "Archive".equals(ftpKids.get(0).relPath), "FTP dir first");
            }

            /* Local hierarchy */
            writeText(new File(localRoot, "Media/clip.mp4"), "mp4");
            writeText(new File(localRoot, "notes.txt"), "n");
            AccountConfig locAcc = new AccountConfig(23, "LOC", Protocol.LOCAL,
                    localRoot.getAbsolutePath(), "", "", "", false);
            try (RemoteBackend lb = BackendFactory.create(locAcc)) {
                List<FileEntry> locKids = lb.listChildren("/");
                checkEq(2, locKids.size(), "Local root children count");
                check(locKids.get(0).isDir && "Media".equals(locKids.get(0).relPath), "Local dir first");
            }
        } finally {
            deleteRec(localRoot);
            deleteRec(ftpRoot);
        }
    }

    private static void testLiveProgressOptionsAndCancel() throws Exception {
        File localDir = Files.createTempDirectory("synk_prog_local_").toFile();
        try (MockWebDavServer srv = new MockWebDavServer("u", "p")) {
            srv.putDir("/dav/perf");
            /* Write ~200KB payload + ignored file */
            byte[] payload = new byte[128 * 1024];
            for (int i = 0; i < payload.length; i++) payload[i] = (byte) ('A' + (i % 26));
            try (FileOutputStream fos = new FileOutputStream(new File(localDir, "big1.bin"))) { fos.write(payload); }
            try (FileOutputStream fos = new FileOutputStream(new File(localDir, "big2.bin"))) { fos.write(payload); }
            writeText(new File(localDir, "skip.glob"), "filtered by global ignore");

            AccountConfig acc = new AccountConfig(30, "PerfDAV", Protocol.WEBDAV,
                    srv.baseUrl("/dav"), "u", "p", "", false);
            SyncPair pair = new SyncPair();
            pair.id = 30;
            pair.name = "PerfPair";
            pair.accountId = 30;
            pair.localPath = localDir.getAbsolutePath();
            pair.remotePath = "/perf";
            pair.mode = SyncMode.UPLOAD_ONLY;
            pair.conflictPolicy = ConflictPolicy.ASK;
            pair.ignorePatterns = "";

            SyncOptions opts = new SyncOptions();
            opts.bufferSizeBytes = 16384;
            opts.globalIgnorePatterns = "*.glob";
            opts.deviceTag = "pixel9";

            List<SyncProgress.Snapshot> snapshots = new ArrayList<>();
            java.util.concurrent.atomic.AtomicBoolean cancelFlag = new java.util.concurrent.atomic.AtomicBoolean(false);
            SyncEngine engine = new SyncEngine(new InMemoryStateStore());

            try (RemoteBackend backend = BackendFactory.create(acc)) {
                SyncResult r = engine.syncPair(pair, backend, opts, cancelFlag, snapshots::add);
                checkEq(0, r.errors, "Progress sync errors");
                checkEq(2, r.uploaded, "Uploaded 2 bin files (global ignore filtered skip.glob)");
                check(srv.getFile("/dav/perf/skip.glob") == null, "Global ignore respected");
                check(snapshots.size() >= 4, "Emitted multiple live progress snapshots");

                boolean sawFile = false, sawBytes = false, sawIdleEnd = false;
                for (SyncProgress.Snapshot s : snapshots) {
                    if ("big1.bin".equals(s.currentFile) || "big2.bin".equals(s.currentFile)) sawFile = true;
                    if (s.totalBytesDone > 0) sawBytes = true;
                    if (!s.active && s.phase == SyncProgress.Phase.IDLE) sawIdleEnd = true;
                }
                check(sawFile, "Snapshot reported active filename");
                check(sawBytes, "Snapshot reported transferred byte progress");
                check(sawIdleEnd, "Snapshot ended cleanly with IDLE");

                /* Verify custom deviceTag conflict copy naming */
                String tagged = SyncEngine.makeConflictCopyName("notes/todo.md", opts.deviceTag);
                check(tagged.startsWith("notes/todo.conflict-pixel9-") && tagged.endsWith(".md"),
                        "Conflict copy includes custom deviceTag: " + tagged);

                /* Verify cooperative cancellation stops sync immediately */
                writeText(new File(localDir, "after_cancel.txt"), "should not upload");
                java.util.concurrent.atomic.AtomicBoolean preCancelled = new java.util.concurrent.atomic.AtomicBoolean(true);
                SyncResult rc = engine.syncPair(pair, backend, opts, preCancelled, null);
                checkEq(0, rc.uploaded, "Pre-cancelled sync uploaded nothing");
                check(srv.getFile("/dav/perf/after_cancel.txt") == null, "Cancelled file not uploaded");
            }
        } finally {
            deleteRec(localDir);
        }
    }

    private static PimSyncEngine.LocalPimAdapter inMemoryPimAdapter(final Map<String, String> map, final PimKind kind) {
        return new PimSyncEngine.LocalPimAdapter() {
            @Override
            public Map<String, PimSyncEngine.LocalPimRecord> readAll() {
                Map<String, PimSyncEngine.LocalPimRecord> out = new HashMap<>();
                for (Map.Entry<String, String> e : map.entrySet()) {
                    String fp = kind == PimKind.CALDAV
                            ? IcsCodec.parse(e.getValue()).canonicalFingerprint()
                            : VcfCodec.parse(e.getValue()).canonicalFingerprint();
                    out.put(e.getKey(), new PimSyncEngine.LocalPimRecord(e.getKey(), e.getValue(), fp));
                }
                return out;
            }
            @Override public void upsert(String uid, String payload) { map.put(uid, payload); }
            @Override public void delete(String uid) { map.remove(uid); }
        };
    }

    private static void writeText(File f, String content) throws IOException {
        File p = f.getParentFile();
        if (p != null && !p.exists()) p.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String readText(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteRec(k);
        }
        f.delete();
    }
}
