package com.seaeast.e108gnss.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Locale;

public final class CynosurePacket {
    private CynosurePacket() {}

    public static final int CLASS_AID = 0x0B;
    public static final int ID_AID_POS = 0x10;
    public static final int ID_AID_TIME = 0x11;
    public static final int ID_PEPH_GPS = 0x32;
    public static final int ID_PEPH_BDS = 0x33;

    public static byte[] build(int cls, int id, byte[] payload) {
        if (payload == null) payload = new byte[0];
        ByteBuffer b = ByteBuffer.allocate(2 + 2 + 2 + payload.length + 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte)0xF1).put((byte)0xD9);
        b.put((byte)cls).put((byte)id);
        b.putShort((short)payload.length);
        b.put(payload);
        byte[] tmp = b.array();
        int[] ck = fletcher(tmp, 2, 4 + payload.length);
        b.put((byte)ck[0]).put((byte)ck[1]);
        return b.array();
    }

    public static byte[] aidTimeUtc(long wallTimeMs) {
        Instant instant = Instant.ofEpochMilli(wallTimeMs);
        ZonedDateTime z = instant.atZone(ZoneOffset.UTC);
        ByteBuffer p = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        p.put((byte)0);
        p.put((byte)0);
        p.put((byte)0xFF);
        p.putShort((short)z.getYear());
        p.put((byte)z.getMonthValue());
        p.put((byte)z.getDayOfMonth());
        p.put((byte)z.getHour());
        p.put((byte)z.getMinute());
        p.put((byte)z.getSecond());
        p.putInt(z.getNano());
        p.putShort((short)0);
        p.putInt(100_000_000);
        return build(CLASS_AID, ID_AID_TIME, p.array());
    }

    public static byte[] aidPositionLla(double latDeg, double lonDeg, double altM, float accuracyM) {
        if (latDeg < -90 || latDeg > 90 || lonDeg < -180 || lonDeg > 180) {
            throw new IllegalArgumentException("Invalid LLA");
        }
        ByteBuffer p = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN);
        p.put((byte)1);
        p.putInt((int)Math.round(latDeg * 1e7));
        p.putInt((int)Math.round(lonDeg * 1e7));
        p.putInt((int)Math.round(altM * 100.0));
        long cm = Math.round(Math.max(accuracyM, 50f) * 100.0);
        if (cm > 0xFFFFFFFFL) cm = 0xFFFFFFFFL;
        p.putInt((int)cm);
        return build(CLASS_AID, ID_AID_POS, p.array());
    }

    public static byte[] pollGpsEphemerisAll() { return build(CLASS_AID, ID_PEPH_GPS, new byte[]{0}); }
    public static byte[] pollBdsEphemerisAll() { return build(CLASS_AID, ID_PEPH_BDS, new byte[]{0}); }

    public static boolean valid(byte[] frame) {
        if (frame == null || frame.length < 8) return false;
        if ((frame[0]&0xFF)!=0xF1 || (frame[1]&0xFF)!=0xD9) return false;
        int len = (frame[4]&0xFF) | ((frame[5]&0xFF)<<8);
        if (frame.length != 8 + len) return false;
        int[] ck = fletcher(frame, 2, 4 + len);
        return (frame[frame.length-2]&0xFF)==ck[0] && (frame[frame.length-1]&0xFF)==ck[1];
    }

    public static int cls(byte[] frame) { return valid(frame) ? (frame[2]&0xFF) : -1; }
    public static int id(byte[] frame) { return valid(frame) ? (frame[3]&0xFF) : -1; }
    public static int payloadLength(byte[] frame) {
        if (frame == null || frame.length < 6) return -1;
        return (frame[4]&0xFF) | ((frame[5]&0xFF)<<8);
    }
    public static int svidForPeph(byte[] frame) {
        if (!valid(frame)) return -1;
        int id = id(frame), len = payloadLength(frame);
        if (id == ID_PEPH_GPS && len == 65) return frame[7]&0xFF;
        if (id == ID_PEPH_BDS && len >= 2) return frame[7]&0xFF;
        return -1;
    }

    public static String summary(byte[] frame) {
        if (!valid(frame)) return "CYN invalid";
        return String.format(Locale.US, "CYN %02X/%02X len=%d svid=%d", cls(frame), id(frame), payloadLength(frame), svidForPeph(frame));
    }

    private static int[] fletcher(byte[] data, int off, int len) {
        int a = 0, b = 0;
        for (int i = off; i < off + len; i++) {
            a = (a + (data[i]&0xFF)) & 0xFF;
            b = (b + a) & 0xFF;
        }
        return new int[]{a,b};
    }

    public static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte v : data) sb.append(String.format(Locale.US, "%02X ", v & 0xFF));
        return sb.toString().trim();
    }
}
