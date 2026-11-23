package org.synk.pim;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Zero-dependency RFC 5545 iCalendar (VEVENT) parser and serializer for CalDAV sync.
 */
public final class IcsCodec {

    public static final class CalEvent {
        public String uid;
        public String summary = "";
        public String description = "";
        public String location = "";
        public long dtStartMs;
        public long dtEndMs;
        public boolean allDay;
        public String rrule = "";
        public long lastModifiedMs;

        public String canonicalFingerprint() {
            return (uid == null ? "" : uid) + "|" + summary + "|" + description + "|"
                    + location + "|" + dtStartMs + "|" + dtEndMs + "|" + allDay + "|" + rrule;
        }
    }

    private IcsCodec() {}

    public static CalEvent parse(String ics) {
        if (ics == null || ics.isEmpty()) return null;
        List<String> lines = unfoldLines(ics);
        CalEvent ev = new CalEvent();
        boolean inEvent = false;
        boolean foundEvent = false;

        for (String line : lines) {
            if ("BEGIN:VEVENT".equalsIgnoreCase(line.trim())) {
                inEvent = true;
                foundEvent = true;
                continue;
            }
            if ("END:VEVENT".equalsIgnoreCase(line.trim())) {
                break;
            }
            if (!inEvent) continue;

            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String lhs = line.substring(0, colon);
            String val = line.substring(colon + 1);

            int semi = lhs.indexOf(';');
            String propName = (semi >= 0 ? lhs.substring(0, semi) : lhs).toUpperCase(Locale.ROOT);
            String params = semi >= 0 ? lhs.substring(semi + 1).toUpperCase(Locale.ROOT) : "";

            switch (propName) {
                case "UID":
                    ev.uid = val.trim();
                    break;
                case "SUMMARY":
                    ev.summary = unescape(val);
                    break;
                case "DESCRIPTION":
                    ev.description = unescape(val);
                    break;
                case "LOCATION":
                    ev.location = unescape(val);
                    break;
                case "RRULE":
                    ev.rrule = val.trim();
                    break;
                case "DTSTART":
                    if (params.contains("VALUE=DATE") || val.trim().length() == 8) {
                        ev.allDay = true;
                    }
                    ev.dtStartMs = parseIcsDate(val.trim(), params);
                    break;
                case "DTEND":
                    ev.dtEndMs = parseIcsDate(val.trim(), params);
                    break;
                case "LAST-MODIFIED":
                case "DTSTAMP":
                    long ts = parseIcsDate(val.trim(), params);
                    if (ts > ev.lastModifiedMs) ev.lastModifiedMs = ts;
                    break;
                default:
                    break;
            }
        }
        if (!foundEvent) return null;
        if (ev.uid == null || ev.uid.isEmpty()) ev.uid = UUID.randomUUID().toString();
        if (ev.dtEndMs <= 0 && ev.dtStartMs > 0) {
            ev.dtEndMs = ev.dtStartMs + (ev.allDay ? 86_400_000L : 3_600_000L);
        }
        return ev;
    }

    public static String serialize(CalEvent ev) {
        String uid = (ev.uid == null || ev.uid.isEmpty()) ? UUID.randomUUID().toString() : ev.uid;
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder(512);
        sb.append("BEGIN:VCALENDAR\r\n");
        sb.append("VERSION:2.0\r\n");
        sb.append("PRODID:-//Synk//Android CalDAV//EN\r\n");
        sb.append("BEGIN:VEVENT\r\n");
        sb.append("UID:").append(uid).append("\r\n");
        sb.append("DTSTAMP:").append(formatUtcDateTime(now)).append("\r\n");
        if (ev.lastModifiedMs > 0) {
            sb.append("LAST-MODIFIED:").append(formatUtcDateTime(ev.lastModifiedMs)).append("\r\n");
        }
        if (ev.allDay) {
            sb.append("DTSTART;VALUE=DATE:").append(formatDateOnly(ev.dtStartMs)).append("\r\n");
            long end = ev.dtEndMs > ev.dtStartMs ? ev.dtEndMs : ev.dtStartMs + 86_400_000L;
            sb.append("DTEND;VALUE=DATE:").append(formatDateOnly(end)).append("\r\n");
        } else {
            sb.append("DTSTART:").append(formatUtcDateTime(ev.dtStartMs)).append("\r\n");
            long end = ev.dtEndMs > ev.dtStartMs ? ev.dtEndMs : ev.dtStartMs + 3_600_000L;
            sb.append("DTEND:").append(formatUtcDateTime(end)).append("\r\n");
        }
        if (ev.summary != null && !ev.summary.isEmpty()) {
            sb.append("SUMMARY:").append(escape(ev.summary)).append("\r\n");
        }
        if (ev.description != null && !ev.description.isEmpty()) {
            sb.append("DESCRIPTION:").append(escape(ev.description)).append("\r\n");
        }
        if (ev.location != null && !ev.location.isEmpty()) {
            sb.append("LOCATION:").append(escape(ev.location)).append("\r\n");
        }
        if (ev.rrule != null && !ev.rrule.isEmpty()) {
            sb.append("RRULE:").append(ev.rrule).append("\r\n");
        }
        sb.append("END:VEVENT\r\n");
        sb.append("END:VCALENDAR\r\n");
        return sb.toString();
    }

    static List<String> unfoldLines(String text) {
        String norm = text.replace("\r\n", "\n").replace("\r", "\n");
        String[] raw = norm.split("\n");
        List<String> out = new ArrayList<>(raw.length);
        for (String line : raw) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && !out.isEmpty()) {
                int last = out.size() - 1;
                out.set(last, out.get(last) + line.substring(1));
            } else {
                out.add(line);
            }
        }
        return out;
    }

    private static long parseIcsDate(String val, String params) {
        try {
            if (val.length() == 8) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd", Locale.US);
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                return sdf.parse(val).getTime();
            }
            if (val.endsWith("Z")) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                return sdf.parse(val).getTime();
            }
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US);
            TimeZone tz = TimeZone.getDefault();
            int idx = params.indexOf("TZID=");
            if (idx >= 0) {
                String tail = params.substring(idx + 5);
                int semi = tail.indexOf(';');
                String tzId = semi >= 0 ? tail.substring(0, semi) : tail;
                tz = TimeZone.getTimeZone(tzId);
            }
            sdf.setTimeZone(tz);
            return sdf.parse(val).getTime();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String formatUtcDateTime(long ms) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(ms));
    }

    private static String formatDateOnly(long ms) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(ms));
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace(",", "\\,")
                .replace(";", "\\;");
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == 'n' || n == 'N') sb.append('\n');
                else sb.append(n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
