package org.synk.pim.android;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;

import org.synk.core.SyncModels.PimProfile;
import org.synk.pim.PimSyncEngine;
import org.synk.pim.VcfCodec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Android ContactsContract adapter for bidirectional CardDAV synchronization.
 */
public final class AndroidContactsAdapter implements PimSyncEngine.LocalPimAdapter {

    private static final String ACCOUNT_TYPE = "org.synk.carddav";

    private final ContentResolver resolver;
    private final String accountName;

    public AndroidContactsAdapter(Context ctx, PimProfile profile) {
        this.resolver = ctx.getContentResolver();
        this.accountName = "synk_ab_" + profile.id;
    }

    private Uri asSyncAdapter(Uri uri) {
        return uri.buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                .build();
    }

    @Override
    public Map<String, PimSyncEngine.LocalPimRecord> readAll() throws IOException {
        Map<String, PimSyncEngine.LocalPimRecord> out = new HashMap<>();
        String[] rawProj = { RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.DELETED };
        String rawSel = RawContacts.ACCOUNT_NAME + "=? AND " + RawContacts.ACCOUNT_TYPE + "=?";
        String[] rawArgs = { accountName, ACCOUNT_TYPE };

        try (Cursor rc = resolver.query(RawContacts.CONTENT_URI, rawProj, rawSel, rawArgs, null)) {
            if (rc == null) return out;
            while (rc.moveToNext()) {
                if (rc.getInt(2) != 0) continue;
                long rawId = rc.getLong(0);
                String uid = rc.getString(1);
                if (uid == null || uid.isEmpty()) uid = "synk-ct-" + rawId;

                VcfCodec.ContactCard card = readCardData(rawId, uid);
                out.put(uid, new PimSyncEngine.LocalPimRecord(
                        uid, VcfCodec.serialize(card), card.canonicalFingerprint()));
            }
        } catch (SecurityException e) {
            throw new IOException("Contacts permission not granted", e);
        }
        return out;
    }

    private VcfCodec.ContactCard readCardData(long rawContactId, String uid) {
        VcfCodec.ContactCard card = new VcfCodec.ContactCard();
        card.uid = uid;

        String[] proj = { Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4 };
        try (Cursor c = resolver.query(Data.CONTENT_URI, proj,
                Data.RAW_CONTACT_ID + "=?", new String[]{ String.valueOf(rawContactId) }, null)) {
            if (c == null) return card;
            while (c.moveToNext()) {
                String mime = c.getString(0);
                String d1 = safe(c.getString(1));
                String d2 = safe(c.getString(2));
                String d3 = safe(c.getString(3));
                String d4 = safe(c.getString(4));

                if (CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE.equals(mime)) {
                    card.fullName = d1;
                    card.givenName = d2;
                    card.familyName = d3;
                } else if (CommonDataKinds.Phone.CONTENT_ITEM_TYPE.equals(mime)) {
                    if (!d1.isEmpty()) card.phones.add(new VcfCodec.TypedValue(phoneTypeLabel(d2), d1));
                } else if (CommonDataKinds.Email.CONTENT_ITEM_TYPE.equals(mime)) {
                    if (!d1.isEmpty()) card.emails.add(new VcfCodec.TypedValue(emailTypeLabel(d2), d1));
                } else if (CommonDataKinds.Organization.CONTENT_ITEM_TYPE.equals(mime)) {
                    card.org = d1;
                    card.title = d4;
                } else if (CommonDataKinds.Note.CONTENT_ITEM_TYPE.equals(mime)) {
                    card.note = d1;
                } else if (CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE.equals(mime)) {
                    if (!d1.isEmpty()) card.addresses.add(d1);
                }
            }
        }
        return card;
    }

    @Override
    public void upsert(String uid, String payload) throws IOException {
        VcfCodec.ContactCard card = VcfCodec.parse(payload);
        if (card == null) return;
        card.uid = uid;

        long existingRawId = findRawContactId(uid);
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        int rawRefIndex = -1;

        if (existingRawId > 0) {
            ops.add(ContentProviderOperation.newDelete(asSyncAdapter(Data.CONTENT_URI))
                    .withSelection(Data.RAW_CONTACT_ID + "=?", new String[]{ String.valueOf(existingRawId) })
                    .build());
        } else {
            rawRefIndex = 0;
            ops.add(ContentProviderOperation.newInsert(asSyncAdapter(RawContacts.CONTENT_URI))
                    .withValue(RawContacts.ACCOUNT_NAME, accountName)
                    .withValue(RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
                    .withValue(RawContacts.SOURCE_ID, uid)
                    .build());
        }

        ops.add(dataInsert(existingRawId, rawRefIndex)
                .withValue(Data.MIMETYPE, CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(CommonDataKinds.StructuredName.DISPLAY_NAME, card.fullName)
                .withValue(CommonDataKinds.StructuredName.GIVEN_NAME, card.givenName)
                .withValue(CommonDataKinds.StructuredName.FAMILY_NAME, card.familyName)
                .build());

        for (VcfCodec.TypedValue p : card.phones) {
            ops.add(dataInsert(existingRawId, rawRefIndex)
                    .withValue(Data.MIMETYPE, CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(CommonDataKinds.Phone.NUMBER, p.value)
                    .withValue(CommonDataKinds.Phone.TYPE, phoneTypeCode(p.type))
                    .build());
        }
        for (VcfCodec.TypedValue e : card.emails) {
            ops.add(dataInsert(existingRawId, rawRefIndex)
                    .withValue(Data.MIMETYPE, CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    .withValue(CommonDataKinds.Email.ADDRESS, e.value)
                    .withValue(CommonDataKinds.Email.TYPE, emailTypeCode(e.type))
                    .build());
        }
        if (!card.org.isEmpty() || !card.title.isEmpty()) {
            ops.add(dataInsert(existingRawId, rawRefIndex)
                    .withValue(Data.MIMETYPE, CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                    .withValue(CommonDataKinds.Organization.COMPANY, card.org)
                    .withValue(CommonDataKinds.Organization.TITLE, card.title)
                    .build());
        }
        if (!card.note.isEmpty()) {
            ops.add(dataInsert(existingRawId, rawRefIndex)
                    .withValue(Data.MIMETYPE, CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                    .withValue(CommonDataKinds.Note.NOTE, card.note)
                    .build());
        }
        for (String addr : card.addresses) {
            ops.add(dataInsert(existingRawId, rawRefIndex)
                    .withValue(Data.MIMETYPE, CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE)
                    .withValue(CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS, addr)
                    .build());
        }

        try {
            resolver.applyBatch(ContactsContract.AUTHORITY, ops);
        } catch (Exception e) {
            throw new IOException("ContactsContract upsert failed for " + uid, e);
        }
    }

    private ContentProviderOperation.Builder dataInsert(long existingRawId, int rawRefIndex) {
        ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(asSyncAdapter(Data.CONTENT_URI));
        if (existingRawId > 0) {
            b.withValue(Data.RAW_CONTACT_ID, existingRawId);
        } else {
            b.withValueBackReference(Data.RAW_CONTACT_ID, rawRefIndex);
        }
        return b;
    }

    @Override
    public void delete(String uid) throws IOException {
        long rawId = findRawContactId(uid);
        if (rawId > 0) {
            resolver.delete(asSyncAdapter(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId)), null, null);
        }
    }

    private long findRawContactId(String uid) {
        String[] proj = { RawContacts._ID };
        String sel = RawContacts.ACCOUNT_NAME + "=? AND " + RawContacts.ACCOUNT_TYPE + "=? AND "
                + RawContacts.SOURCE_ID + "=?";
        String[] args = { accountName, ACCOUNT_TYPE, uid };
        try (Cursor c = resolver.query(RawContacts.CONTENT_URI, proj, sel, args, null)) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        }
        return -1L;
    }

    private static int phoneTypeCode(String t) {
        if ("WORK".equalsIgnoreCase(t)) return CommonDataKinds.Phone.TYPE_WORK;
        if ("HOME".equalsIgnoreCase(t)) return CommonDataKinds.Phone.TYPE_HOME;
        return CommonDataKinds.Phone.TYPE_MOBILE;
    }

    private static String phoneTypeLabel(String codeStr) {
        if (String.valueOf(CommonDataKinds.Phone.TYPE_WORK).equals(codeStr)) return "WORK";
        if (String.valueOf(CommonDataKinds.Phone.TYPE_HOME).equals(codeStr)) return "HOME";
        return "CELL";
    }

    private static int emailTypeCode(String t) {
        if ("WORK".equalsIgnoreCase(t)) return CommonDataKinds.Email.TYPE_WORK;
        return CommonDataKinds.Email.TYPE_HOME;
    }

    private static String emailTypeLabel(String codeStr) {
        if (String.valueOf(CommonDataKinds.Email.TYPE_WORK).equals(codeStr)) return "WORK";
        return "HOME";
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
