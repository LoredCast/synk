package org.synk.pim;

import org.synk.core.NativeBridge;
import org.synk.core.SyncModels.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Stateful 3-way bidirectional CalDAV (Calendar) & CardDAV (Contacts) sync engine.
 */
public final class PimSyncEngine {

    public static final class PimBaseline {
        public final String uid;
        public final String href;
        public final String localHash;
        public final String remoteEtag;

        public PimBaseline(String uid, String href, String localHash, String remoteEtag) {
            this.uid = uid;
            this.href = href == null ? "" : href;
            this.localHash = localHash == null ? "" : localHash;
            this.remoteEtag = remoteEtag == null ? "" : remoteEtag;
        }
    }

    public static final class LocalPimRecord {
        public final String uid;
        public final String serializedPayload; // .ics or .vcf
        public final String canonicalHash;

        public LocalPimRecord(String uid, String serializedPayload, String canonicalFingerprint) {
            this.uid = uid;
            this.serializedPayload = serializedPayload;
            this.canonicalHash = NativeBridge.sha256Bytes(canonicalFingerprint.getBytes(StandardCharsets.UTF_8));
        }
    }

    public interface PimBaselineStore {
        List<PimBaseline> loadPimBaseline(long profileId);
        void upsertPimBaseline(long profileId, PimBaseline item);
        void removePimBaseline(long profileId, String uid);
        void updatePimStatus(long profileId, long syncedAt, String status);
        void appendLog(String source, String level, String message);
    }

    public interface LocalPimAdapter {
        Map<String, LocalPimRecord> readAll() throws IOException;
        void upsert(String uid, String payload) throws IOException;
        void delete(String uid) throws IOException;
    }

    private final PimBaselineStore store;

    public PimSyncEngine(PimBaselineStore store) {
        this.store = store;
    }

    public SyncResult sync(PimProfile profile, LocalPimAdapter localAdapter) {
        long start = System.currentTimeMillis();
        SyncResult res = new SyncResult();
        DavPimClient client = new DavPimClient(profile);

        try {
            /* 1. Load baseline */
            List<PimBaseline> baseList = store.loadPimBaseline(profile.id);
            Map<String, PimBaseline> baseByUid = new HashMap<>();
            Map<String, PimBaseline> baseByHref = new HashMap<>();
            for (PimBaseline b : baseList) {
                baseByUid.put(b.uid, b);
                if (!b.href.isEmpty()) baseByHref.put(b.href, b);
            }

            /* 2. Read local items (CalendarContract or ContactsContract) */
            Map<String, LocalPimRecord> localByUid = localAdapter.readAll();

            /* 3. List remote DAV items (href + etag) */
            List<DavPimClient.RemotePimRef> remoteRefs = client.listItems();
            Map<String, DavPimClient.RemotePimRef> remoteByUid = new HashMap<>();
            Map<String, DavPimClient.RemotePimItem> fetchedCache = new HashMap<>();

            for (DavPimClient.RemotePimRef ref : remoteRefs) {
                PimBaseline known = baseByHref.get(ref.href);
                if (known != null && !known.remoteEtag.isEmpty() && known.remoteEtag.equals(ref.etag)) {
                    /* Remote unchanged since last sync — avoid downloading body! */
                    remoteByUid.put(known.uid, ref);
                } else {
                    /* New or modified remote item: fetch to get authoritative UID + payload */
                    DavPimClient.RemotePimItem item = client.fetchItem(ref.href);
                    String uid = extractUid(profile.kind, item.body);
                    if (uid == null || uid.isEmpty()) {
                        uid = known != null ? known.uid : ref.filename;
                    }
                    DavPimClient.RemotePimRef resolvedRef = new DavPimClient.RemotePimRef(
                            ref.href, ref.filename, !item.etag.isEmpty() ? item.etag : ref.etag);
                    remoteByUid.put(uid, resolvedRef);
                    fetchedCache.put(uid, item);
                }
            }

            /* 4. Union of all UIDs */
            Set<String> allUids = new HashSet<>();
            allUids.addAll(baseByUid.keySet());
            allUids.addAll(localByUid.keySet());
            allUids.addAll(remoteByUid.keySet());

            for (String uid : allUids) {
                try {
                    reconcileItem(profile, client, localAdapter, uid,
                            baseByUid.get(uid), localByUid.get(uid), remoteByUid.get(uid),
                            fetchedCache.get(uid), res);
                } catch (Exception itemErr) {
                    res.errors++;
                    store.appendLog(profile.name, "ERROR", "PIM item " + uid + ": " + itemErr.getMessage());
                }
            }

            res.durationMs = System.currentTimeMillis() - start;
            String summary = res.summary() + " (" + res.durationMs + "ms)";
            store.updatePimStatus(profile.id, System.currentTimeMillis(), summary);
            store.appendLog(profile.name, res.errors > 0 ? "WARN" : "INFO",
                    profile.kind.label + " sync: " + summary);
        } catch (Exception fatal) {
            res.errors++;
            res.durationMs = System.currentTimeMillis() - start;
            String msg = profile.kind.label + " failed: " + fatal.getMessage();
            res.messages.add(msg);
            store.updatePimStatus(profile.id, System.currentTimeMillis(), msg);
            store.appendLog(profile.name, "ERROR", msg);
        }
        return res;
    }

    private void reconcileItem(PimProfile profile, DavPimClient client, LocalPimAdapter localAdapter,
                               String uid, PimBaseline base, LocalPimRecord local,
                               DavPimClient.RemotePimRef remote, DavPimClient.RemotePimItem fetched,
                               SyncResult res) throws IOException {
        boolean localChanged;
        boolean remoteChanged;

        if (base == null) {
            localChanged = (local != null);
            remoteChanged = (remote != null);
        } else {
            localChanged = (local == null) || !base.localHash.equals(local.canonicalHash);
            remoteChanged = (remote == null) || !base.remoteEtag.equals(remote.etag);
        }

        if (!localChanged && !remoteChanged) return;

        /* Both deleted */
        if (local == null && remote == null) {
            store.removePimBaseline(profile.id, uid);
            return;
        }

        /* Only local changed */
        if (localChanged && !remoteChanged) {
            if (local == null) {
                if (remote != null) {
                    client.deleteItem(remote.href);
                    res.deletedRemote++;
                }
                store.removePimBaseline(profile.id, uid);
            } else {
                String targetUrl = (base != null && !base.href.isEmpty()) ? base.href : client.buildItemUrl(uid);
                String newEtag = client.putItem(targetUrl, local.serializedPayload, null);
                store.upsertPimBaseline(profile.id, new PimBaseline(uid, targetUrl, local.canonicalHash, newEtag));
                res.uploaded++;
            }
            return;
        }

        /* Only remote changed */
        if (!localChanged) {
            if (remote == null) {
                localAdapter.delete(uid);
                store.removePimBaseline(profile.id, uid);
                res.deletedLocal++;
            } else {
                DavPimClient.RemotePimItem item = fetched != null ? fetched : client.fetchItem(remote.href);
                localAdapter.upsert(uid, item.body);
                String hash = computePayloadHash(profile.kind, item.body);
                String etag = !item.etag.isEmpty() ? item.etag : remote.etag;
                store.upsertPimBaseline(profile.id, new PimBaseline(uid, remote.href, hash, etag));
                res.downloaded++;
            }
            return;
        }

        /* Both sides changed concurrently (conflict!) */
        if (local == null) {
            /* Local deleted, remote modified */
            if (profile.conflictPolicy == ConflictPolicy.LOCAL_WINS) {
                client.deleteItem(remote.href);
                store.removePimBaseline(profile.id, uid);
                res.deletedRemote++;
            } else {
                DavPimClient.RemotePimItem item = fetched != null ? fetched : client.fetchItem(remote.href);
                localAdapter.upsert(uid, item.body);
                store.upsertPimBaseline(profile.id, new PimBaseline(uid, remote.href,
                        computePayloadHash(profile.kind, item.body), remote.etag));
                res.downloaded++;
            }
            return;
        }

        if (remote == null) {
            /* Local modified, remote deleted */
            if (profile.conflictPolicy == ConflictPolicy.REMOTE_WINS) {
                localAdapter.delete(uid);
                store.removePimBaseline(profile.id, uid);
                res.deletedLocal++;
            } else {
                String targetUrl = client.buildItemUrl(uid);
                String newEtag = client.putItem(targetUrl, local.serializedPayload, null);
                store.upsertPimBaseline(profile.id, new PimBaseline(uid, targetUrl, local.canonicalHash, newEtag));
                res.uploaded++;
            }
            return;
        }

        /* Both modified */
        DavPimClient.RemotePimItem item = fetched != null ? fetched : client.fetchItem(remote.href);
        String remoteHash = computePayloadHash(profile.kind, item.body);
        if (remoteHash.equals(local.canonicalHash)) {
            store.upsertPimBaseline(profile.id, new PimBaseline(uid, remote.href, local.canonicalHash, remote.etag));
            return;
        }

        if (profile.conflictPolicy == ConflictPolicy.LOCAL_WINS) {
            String newEtag = client.putItem(remote.href, local.serializedPayload, null);
            store.upsertPimBaseline(profile.id, new PimBaseline(uid, remote.href, local.canonicalHash, newEtag));
            res.uploaded++;
        } else if (profile.conflictPolicy == ConflictPolicy.REMOTE_WINS
                || profile.conflictPolicy == ConflictPolicy.NEWEST_WINS) {
            localAdapter.upsert(uid, item.body);
            store.upsertPimBaseline(profile.id, new PimBaseline(uid, remote.href, remoteHash, remote.etag));
            res.downloaded++;
        } else {
            /* KEEP_BOTH / ASK / AUTO_MERGE: preserve local as forked UID + pull remote to original UID */
            String copyUid = "conflict-" + UUID.randomUUID();
            String copyPayload = rewriteUid(profile.kind, local.serializedPayload, copyUid);
            String copyUrl = client.buildItemUrl(copyUid);
            String copyEtag = client.putItem(copyUrl, copyPayload, null);
            localAdapter.upsert(copyUid, copyPayload);
            store.upsertPimBaseline(profile.id, new PimBaseline(copyUid, copyUrl,
                    computePayloadHash(profile.kind, copyPayload), copyEtag));

            localAdapter.upsert(uid, item.body);
            store.upsertPimBaseline(profile.id, new PimBaseline(uid, remote.href, remoteHash, remote.etag));
            res.uploaded++;
            res.downloaded++;
            res.conflicts++;
            store.appendLog(profile.name, "WARN", "PIM conflict kept both copies for UID " + uid);
        }
    }

    private static String extractUid(PimKind kind, String body) {
        if (kind == PimKind.CALDAV) {
            IcsCodec.CalEvent ev = IcsCodec.parse(body);
            return ev != null ? ev.uid : null;
        } else {
            VcfCodec.ContactCard c = VcfCodec.parse(body);
            return c != null ? c.uid : null;
        }
    }

    private static String computePayloadHash(PimKind kind, String body) {
        String fp;
        if (kind == PimKind.CALDAV) {
            IcsCodec.CalEvent ev = IcsCodec.parse(body);
            fp = ev != null ? ev.canonicalFingerprint() : body;
        } else {
            VcfCodec.ContactCard c = VcfCodec.parse(body);
            fp = c != null ? c.canonicalFingerprint() : body;
        }
        return NativeBridge.sha256Bytes(fp.getBytes(StandardCharsets.UTF_8));
    }

    private static String rewriteUid(PimKind kind, String payload, String newUid) {
        if (kind == PimKind.CALDAV) {
            IcsCodec.CalEvent ev = IcsCodec.parse(payload);
            if (ev != null) {
                ev.uid = newUid;
                ev.summary = ev.summary + " (Conflict Copy)";
                return IcsCodec.serialize(ev);
            }
        } else {
            VcfCodec.ContactCard c = VcfCodec.parse(payload);
            if (c != null) {
                c.uid = newUid;
                c.fullName = c.fullName + " (Conflict)";
                return VcfCodec.serialize(c);
            }
        }
        return payload;
    }
}
