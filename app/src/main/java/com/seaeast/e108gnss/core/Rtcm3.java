package com.seaeast.e108gnss.core;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class Rtcm3 {
    private Rtcm3() {}

    private static final Set<Integer> EPH = new HashSet<>(Arrays.asList(
            1019, 1020, 1041, 1042, 1044, 1045, 1046
    ));

    public static int payloadLength(byte[] frame) {
        if (frame == null || frame.length < 6 || (frame[0] & 0xFF) != 0xD3) return -1;
        return ((frame[1] & 0x03) << 8) | (frame[2] & 0xFF);
    }

    public static int messageType(byte[] frame) {
        if (frame == null || frame.length < 6) return -1;
        return ((frame[3] & 0xFF) << 4) | ((frame[4] & 0xF0) >>> 4);
    }

    public static int satelliteIdGuess(byte[] frame) {
        if (frame == null || frame.length < 6) return -1;
        return ((frame[4] & 0x0F) << 2) | ((frame[5] & 0xC0) >>> 6);
    }

    public static boolean isEphemerisType(int type) { return EPH.contains(type); }

    public static boolean valid(byte[] frame) {
        if (frame == null || frame.length < 6 || (frame[0] & 0xFF) != 0xD3) return false;
        int len = payloadLength(frame);
        if (len < 0 || frame.length != len + 6) return false;
        int crc = crc24q(frame, 0, frame.length - 3);
        int got = ((frame[frame.length-3]&0xFF)<<16) | ((frame[frame.length-2]&0xFF)<<8) | (frame[frame.length-1]&0xFF);
        return crc == got;
    }

    public static int crc24q(byte[] data, int off, int len) {
        int crc = 0;
        final int poly = 0x1864CFB;
        for (int i = off; i < off + len; i++) {
            crc ^= (data[i] & 0xFF) << 16;
            for (int b = 0; b < 8; b++) {
                crc <<= 1;
                if ((crc & 0x1000000) != 0) crc ^= poly;
            }
        }
        return crc & 0xFFFFFF;
    }
}
