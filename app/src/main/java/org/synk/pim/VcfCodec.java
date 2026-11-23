package org.synk.pim;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Zero-dependency RFC 6350 / 2426 vCard (3.0 & 4.0) parser and serializer for CardDAV sync.
 */
public final class VcfCodec {

    public static final class TypedValue {
        public final String type;
        public final String value;

        public TypedValue(String type, String value) {
            this.type = type == null ? "" : type;
            this.value = value == null ? "" : value;
        }
    }

    public static final class ContactCard {
        public String uid;
        public String fullName = "";
        public String familyName = "";
        public String givenName = "";
        public String org = "";
        public String title = "";
        public String note = "";
        public String birthday = "";
        public final List<TypedValue> phones = new ArrayList<>();
        public final List<TypedValue> emails = new ArrayList<>();
        public final List<String> addresses = new ArrayList<>();

        public String canonicalFingerprint() {
            StringBuilder sb = new StringBuilder();
            sb.append(uid == null ? "" : uid).append('|')
              .append(fullName).append('|').append(familyName).append('|').append(givenName)
              .append('|').append(org).append('|').append(title).append('|').append(note)
              .append('|').append(birthday);
            for (TypedValue p : phones) sb.append("|P:").append(p.type).append('=').append(p.value);
            for (TypedValue e : emails) sb.append("|E:").append(e.type).append('=').append(e.value);
            for (String a : addresses) sb.append("|A:").append(a);
            return sb.toString();
        }
    }

    private VcfCodec() {}

    public static ContactCard parse(String vcf) {
        if (vcf == null || vcf.isEmpty()) return null;
        List<String> lines = IcsCodec.unfoldLines(vcf);
        ContactCard c = new ContactCard();
        boolean inCard = false;
        boolean found = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if ("BEGIN:VCARD".equalsIgnoreCase(trimmed)) {
                inCard = true;
                found = true;
                continue;
            }
            if ("END:VCARD".equalsIgnoreCase(trimmed)) {
                break;
            }
            if (!inCard) continue;

            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String lhs = line.substring(0, colon);
            String val = line.substring(colon + 1);

            /* Strip group prefix like "item1.EMAIL" */
            int dot = lhs.indexOf('.');
            int semi = lhs.indexOf(';');
            String propPart = semi >= 0 ? lhs.substring(0, semi) : lhs;
            if (dot >= 0 && (semi < 0 || dot < semi)) {
                propPart = propPart.substring(dot + 1);
            }
            String propName = propPart.toUpperCase(Locale.ROOT);
            String params = semi >= 0 ? lhs.substring(semi + 1).toUpperCase(Locale.ROOT) : "";

            switch (propName) {
                case "UID":
                    c.uid = val.trim();
                    break;
                case "FN":
                    c.fullName = unescape(val);
                    break;
                case "N": {
                    String[] parts = val.split(";", -1);
                    if (parts.length > 0) c.familyName = unescape(parts[0]);
                    if (parts.length > 1) c.givenName = unescape(parts[1]);
                    break;
                }
                case "ORG":
                    c.org = unescape(val.split(";", 2)[0]);
                    break;
                case "TITLE":
                    c.title = unescape(val);
                    break;
                case "NOTE":
                    c.note = unescape(val);
                    break;
                case "BDAY":
                    c.birthday = val.trim();
                    break;
                case "TEL":
                    if (!val.trim().isEmpty()) {
                        c.phones.add(new TypedValue(extractType(params, "CELL"), val.trim()));
                    }
                    break;
                case "EMAIL":
                    if (!val.trim().isEmpty()) {
                        c.emails.add(new TypedValue(extractType(params, "HOME"), val.trim()));
                    }
                    break;
                case "ADR": {
                    String addr = unescape(val).replace(';', ' ').trim();
                    if (!addr.isEmpty()) c.addresses.add(addr);
                    break;
                }
                default:
                    break;
            }
        }
        if (!found) return null;
        if (c.uid == null || c.uid.isEmpty()) c.uid = UUID.randomUUID().toString();
        if (c.fullName.isEmpty()) {
            c.fullName = (c.givenName + " " + c.familyName).trim();
        }
        return c;
    }

    public static String serialize(ContactCard c) {
        String uid = (c.uid == null || c.uid.isEmpty()) ? UUID.randomUUID().toString() : c.uid;
        StringBuilder sb = new StringBuilder(384);
        sb.append("BEGIN:VCARD\r\n");
        sb.append("VERSION:3.0\r\n");
        sb.append("PRODID:-//Synk//Android CardDAV//EN\r\n");
        sb.append("UID:").append(uid).append("\r\n");
        sb.append("FN:").append(escape(c.fullName.isEmpty() ? (c.givenName + " " + c.familyName).trim() : c.fullName)).append("\r\n");
        sb.append("N:").append(escape(c.familyName)).append(';').append(escape(c.givenName)).append(";;;\r\n");
        if (!c.org.isEmpty()) sb.append("ORG:").append(escape(c.org)).append("\r\n");
        if (!c.title.isEmpty()) sb.append("TITLE:").append(escape(c.title)).append("\r\n");
        for (TypedValue p : c.phones) {
            sb.append("TEL;TYPE=").append(p.type.isEmpty() ? "CELL" : p.type).append(':').append(p.value).append("\r\n");
        }
        for (TypedValue e : c.emails) {
            sb.append("EMAIL;TYPE=").append(e.type.isEmpty() ? "HOME" : e.type).append(':').append(e.value).append("\r\n");
        }
        for (String a : c.addresses) {
            sb.append("ADR:;;").append(escape(a)).append(";;;;\r\n");
        }
        if (!c.birthday.isEmpty()) sb.append("BDAY:").append(c.birthday).append("\r\n");
        if (!c.note.isEmpty()) sb.append("NOTE:").append(escape(c.note)).append("\r\n");
        sb.append("END:VCARD\r\n");
        return sb.toString();
    }

    private static String extractType(String params, String fallback) {
        if (params == null || params.isEmpty()) return fallback;
        for (String p : params.split(";")) {
            if (p.startsWith("TYPE=")) {
                String v = p.substring(5).replace("\"", "");
                int comma = v.indexOf(',');
                return comma > 0 ? v.substring(0, comma) : v;
            }
            if ("WORK".equals(p) || "HOME".equals(p) || "CELL".equals(p) || "MOBILE".equals(p)) {
                return p;
            }
        }
        return fallback;
    }

    private static String escape(String s) {
        if (s == null) return "";
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
