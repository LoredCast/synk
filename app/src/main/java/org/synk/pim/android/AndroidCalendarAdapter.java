package org.synk.pim.android;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import org.synk.core.SyncModels.PimProfile;
import org.synk.pim.IcsCodec;
import org.synk.pim.PimSyncEngine;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

/**
 * Android CalendarContract adapter for bidirectional CalDAV synchronization.
 * Creates/manages a dedicated local Synk calendar per CalDAV profile so events
 * appear natively in any Android calendar app without extra sync-adapter bloat.
 */
public final class AndroidCalendarAdapter implements PimSyncEngine.LocalPimAdapter {

    private final ContentResolver resolver;
    private final String accountName;
    private final String calendarDisplayName;
    private long calendarId = -1L;

    public AndroidCalendarAdapter(Context ctx, PimProfile profile) {
        this.resolver = ctx.getContentResolver();
        this.accountName = "synk_cal_" + profile.id;
        this.calendarDisplayName = "Synk: " + profile.name;
    }

    private Uri asSyncAdapter(Uri uri) {
        return uri.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                .build();
    }

    private long ensureCalendarId() throws IOException {
        if (calendarId > 0) return calendarId;
        String[] proj = { CalendarContract.Calendars._ID };
        String sel = CalendarContract.Calendars.ACCOUNT_NAME + "=? AND "
                + CalendarContract.Calendars.ACCOUNT_TYPE + "=?";
        String[] args = { accountName, CalendarContract.ACCOUNT_TYPE_LOCAL };
        try (Cursor c = resolver.query(CalendarContract.Calendars.CONTENT_URI, proj, sel, args, null)) {
            if (c != null && c.moveToFirst()) {
                calendarId = c.getLong(0);
                return calendarId;
            }
        } catch (SecurityException e) {
            throw new IOException("Calendar permission not granted", e);
        }

        ContentValues cv = new ContentValues();
        cv.put(CalendarContract.Calendars.ACCOUNT_NAME, accountName);
        cv.put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL);
        cv.put(CalendarContract.Calendars.NAME, accountName);
        cv.put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, calendarDisplayName);
        cv.put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF00E676);
        cv.put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER);
        cv.put(CalendarContract.Calendars.OWNER_ACCOUNT, accountName);
        cv.put(CalendarContract.Calendars.VISIBLE, 1);
        cv.put(CalendarContract.Calendars.SYNC_EVENTS, 1);
        cv.put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().getID());

        Uri inserted = resolver.insert(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI), cv);
        if (inserted == null) throw new IOException("Failed to create local Synk calendar");
        calendarId = ContentUris.parseId(inserted);
        return calendarId;
    }

    @Override
    public Map<String, PimSyncEngine.LocalPimRecord> readAll() throws IOException {
        long calId = ensureCalendarId();
        Map<String, PimSyncEngine.LocalPimRecord> out = new HashMap<>();
        String[] proj = {
                CalendarContract.Events._ID,
                CalendarContract.Events._SYNC_ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.RRULE,
                CalendarContract.Events.DELETED
        };
        String sel = CalendarContract.Events.CALENDAR_ID + "=?";
        String[] args = { String.valueOf(calId) };
        try (Cursor c = resolver.query(CalendarContract.Events.CONTENT_URI, proj, sel, args, null)) {
            if (c == null) return out;
            while (c.moveToNext()) {
                if (c.getInt(9) != 0) continue; // deleted
                long rowId = c.getLong(0);
                String uid = c.getString(1);
                if (uid == null || uid.isEmpty()) {
                    uid = "synk-ev-" + rowId;
                    ContentValues fix = new ContentValues();
                    fix.put(CalendarContract.Events._SYNC_ID, uid);
                    resolver.update(asSyncAdapter(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, rowId)),
                            fix, null, null);
                }
                IcsCodec.CalEvent ev = new IcsCodec.CalEvent();
                ev.uid = uid;
                ev.summary = safe(c.getString(2));
                ev.description = safe(c.getString(3));
                ev.location = safe(c.getString(4));
                ev.dtStartMs = c.getLong(5);
                ev.dtEndMs = c.getLong(6);
                ev.allDay = c.getInt(7) != 0;
                ev.rrule = safe(c.getString(8));

                out.put(uid, new PimSyncEngine.LocalPimRecord(
                        uid, IcsCodec.serialize(ev), ev.canonicalFingerprint()));
            }
        } catch (SecurityException e) {
            throw new IOException("Calendar read permission denied", e);
        }
        return out;
    }

    @Override
    public void upsert(String uid, String payload) throws IOException {
        long calId = ensureCalendarId();
        IcsCodec.CalEvent ev = IcsCodec.parse(payload);
        if (ev == null) return;
        ev.uid = uid;

        ContentValues cv = new ContentValues();
        cv.put(CalendarContract.Events.CALENDAR_ID, calId);
        cv.put(CalendarContract.Events._SYNC_ID, uid);
        cv.put(CalendarContract.Events.TITLE, ev.summary);
        cv.put(CalendarContract.Events.DESCRIPTION, ev.description);
        cv.put(CalendarContract.Events.EVENT_LOCATION, ev.location);
        cv.put(CalendarContract.Events.DTSTART, ev.dtStartMs);
        cv.put(CalendarContract.Events.ALL_DAY, ev.allDay ? 1 : 0);
        cv.put(CalendarContract.Events.EVENT_TIMEZONE, ev.allDay ? "UTC" : TimeZone.getDefault().getID());
        if (ev.rrule != null && !ev.rrule.isEmpty()) {
            cv.put(CalendarContract.Events.RRULE, ev.rrule);
            long durSec = Math.max(60L, (ev.dtEndMs - ev.dtStartMs) / 1000L);
            cv.put(CalendarContract.Events.DURATION, "PT" + durSec + "S");
            cv.putNull(CalendarContract.Events.DTEND);
        } else {
            cv.put(CalendarContract.Events.DTEND, ev.dtEndMs > ev.dtStartMs ? ev.dtEndMs : ev.dtStartMs + 3_600_000L);
            cv.putNull(CalendarContract.Events.DURATION);
        }
        cv.put(CalendarContract.Events.DIRTY, 0);

        long existingId = findEventId(calId, uid);
        if (existingId > 0) {
            resolver.update(asSyncAdapter(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existingId)),
                    cv, null, null);
        } else {
            resolver.insert(asSyncAdapter(CalendarContract.Events.CONTENT_URI), cv);
        }
    }

    @Override
    public void delete(String uid) throws IOException {
        long calId = ensureCalendarId();
        long existingId = findEventId(calId, uid);
        if (existingId > 0) {
            resolver.delete(asSyncAdapter(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existingId)),
                    null, null);
        }
    }

    private long findEventId(long calId, String uid) {
        String[] proj = { CalendarContract.Events._ID };
        String sel = CalendarContract.Events.CALENDAR_ID + "=? AND " + CalendarContract.Events._SYNC_ID + "=?";
        String[] args = { String.valueOf(calId), uid };
        try (Cursor c = resolver.query(CalendarContract.Events.CONTENT_URI, proj, sel, args, null)) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        }
        return -1L;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
