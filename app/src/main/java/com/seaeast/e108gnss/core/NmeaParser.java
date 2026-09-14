package com.seaeast.e108gnss.core;

import java.util.Locale;

public final class NmeaParser {
    private final GnssSnapshot state = new GnssSnapshot();

    public synchronized GnssSnapshot parse(String sentence, long elapsedMs, long wallMs) {
        if (sentence == null) return state.copy();
        sentence = sentence.trim();
        if (!sentence.startsWith("$") || !validChecksum(sentence)) return state.copy();

        state.lastNmea = sentence;
        state.lastUpdateElapsedMs = elapsedMs;

        int star = sentence.indexOf('*');
        String body = star > 0 ? sentence.substring(1, star) : sentence.substring(1);
        String[] f = body.split(",", -1);
        if (f.length == 0) return state.copy();
        String head = f[0].toUpperCase(Locale.ROOT);
        String type = head.length() >= 3 ? head.substring(head.length() - 3) : head;

        try {
            switch (type) {
                case "GGA": parseGga(f, wallMs); break;
                case "RMC": parseRmc(f, wallMs); break;
                case "VTG": parseVtg(f); break;
                case "GSA": parseGsa(f); break;
                case "GSV": parseGsv(f); break;
                case "ANT": state.antennaRaw = sentence; break;
                case "CLK": state.clockRaw = sentence; break;
                case "INS": state.insRaw = sentence; break;
                default: break;
            }
        } catch (RuntimeException ignored) {}
        return state.copy();
    }

    public synchronized void setConnected(boolean connected) {
        state.connected = connected;
        if (!connected) {
            state.fix = false;
            state.fixQuality = 0;
            state.fixText = "NO FIX";
        }
    }

    public synchronized GnssSnapshot snapshot() { return state.copy(); }

    private void parseGga(String[] f, long wallMs) {
        if (f.length < 10) return;
        double lat = parseCoord(f[2], f[3], true);
        double lon = parseCoord(f[4], f[5], false);
        int q = intOr(f[6], 0);
        int sats = intOr(f[7], state.satellitesUsed);
        float hdop = floatOr(f[8], state.hdop);
        double alt = doubleOr(f[9], state.altitudeM);

        if (!Double.isNaN(lat)) state.latitude = lat;
        if (!Double.isNaN(lon)) state.longitude = lon;
        state.fixQuality = q;
        state.fix = q > 0;
        state.fixText = qualityText(q);
        state.satellitesUsed = sats;
        state.hdop = hdop;
        state.altitudeM = alt;
        if (state.fix) state.lastFixWallTimeMs = wallMs;
    }

    private void parseRmc(String[] f, long wallMs) {
        if (f.length < 9) return;
        boolean valid = "A".equalsIgnoreCase(f[2]);
        double lat = parseCoord(f[3], f[4], true);
        double lon = parseCoord(f[5], f[6], false);
        if (!Double.isNaN(lat)) state.latitude = lat;
        if (!Double.isNaN(lon)) state.longitude = lon;
        float knots = floatOr(f[7], Float.NaN);
        if (!Float.isNaN(knots)) state.speedMps = knots * 0.514444f;
        float course = floatOr(f[8], Float.NaN);
        if (!Float.isNaN(course)) state.bearingDeg = normalizeBearing(course);
        if (valid && state.fixQuality == 0) {
            state.fix = true;
            state.fixText = "RMC VALID";
            state.lastFixWallTimeMs = wallMs;
        }
    }

    private void parseVtg(String[] f) {
        if (f.length > 1 && !f[1].isEmpty()) state.bearingDeg = normalizeBearing(floatOr(f[1], state.bearingDeg));
        if (f.length > 7 && !f[7].isEmpty()) {
            float kmh = floatOr(f[7], Float.NaN);
            if (!Float.isNaN(kmh)) state.speedMps = kmh / 3.6f;
        } else if (f.length > 5 && !f[5].isEmpty()) {
            float knots = floatOr(f[5], Float.NaN);
            if (!Float.isNaN(knots)) state.speedMps = knots * 0.514444f;
        }
    }

    private void parseGsa(String[] f) {
        if (f.length < 6) return;
        int n = f.length;
        int pdopIdx = n - 3;
        int hdopIdx = n - 2;
        int vdopIdx = n - 1;
        if (n >= 19 && isSmallInteger(f[n - 1])) {
            pdopIdx = n - 4; hdopIdx = n - 3; vdopIdx = n - 2;
        }
        if (pdopIdx >= 0) state.pdop = floatOr(f[pdopIdx], state.pdop);
        if (hdopIdx >= 0) state.hdop = floatOr(f[hdopIdx], state.hdop);
        if (vdopIdx >= 0) state.vdop = floatOr(f[vdopIdx], state.vdop);

        int used = 0;
        int satStart = 3;
        int satEnd = Math.max(satStart, pdopIdx);
        for (int i = satStart; i < satEnd; i++) if (!f[i].isEmpty()) used++;
        if (used > 0) state.satellitesUsed = used;
    }

    private void parseGsv(String[] f) {
        if (f.length > 3) {
            int visible = intOr(f[3], 0);
            if (visible > state.satellitesVisible) state.satellitesVisible = visible;
        }
    }

    public static boolean validChecksum(String sentence) {
        int star = sentence.indexOf('*');
        if (star < 0 || star + 2 >= sentence.length()) return true;
        int cs = 0;
        for (int i = 1; i < star; i++) cs ^= sentence.charAt(i) & 0xFF;
        try {
            int expected = Integer.parseInt(sentence.substring(star + 1, star + 3), 16);
            return cs == expected;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static double parseCoord(String raw, String hemi, boolean lat) {
        if (raw == null || raw.isEmpty()) return Double.NaN;
        double v = Double.parseDouble(raw);
        int deg = (int) (v / 100.0);
        double min = v - deg * 100.0;
        double out = deg + min / 60.0;
        if ("S".equalsIgnoreCase(hemi) || "W".equalsIgnoreCase(hemi)) out = -out;
        if (lat && Math.abs(out) > 90) return Double.NaN;
        if (!lat && Math.abs(out) > 180) return Double.NaN;
        return out;
    }

    private static String qualityText(int q) {
        switch (q) {
            case 0: return "NO FIX";
            case 1: return "SPS";
            case 2: return "DGPS/SBAS";
            case 3: return "PPS";
            case 4: return "RTK FIX";
            case 5: return "RTK FLOAT";
            case 6: return "DR";
            case 7: return "MANUAL";
            case 8: return "SIM";
            default: return "FIX(" + q + ")";
        }
    }

    private static boolean isSmallInteger(String s) {
        try { int v = Integer.parseInt(s); return v >= 0 && v <= 10; } catch (Exception e) { return false; }
    }
    private static int intOr(String s, int d) { try { return Integer.parseInt(s); } catch (Exception e) { return d; } }
    private static float floatOr(String s, float d) { try { return Float.parseFloat(s); } catch (Exception e) { return d; } }
    private static double doubleOr(String s, double d) { try { return Double.parseDouble(s); } catch (Exception e) { return d; } }
    private static float normalizeBearing(float b) { b %= 360f; if (b < 0) b += 360f; return b; }
}
