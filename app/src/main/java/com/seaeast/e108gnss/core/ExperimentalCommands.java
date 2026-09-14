package com.seaeast.e108gnss.core;

/**
 * Experimental command bytes recovered by binary comparison of the user's
 * GPSAndroid13 v8.0.4 and v8.0.5 reference packages.
 *
 * IMPORTANT: The semantic meaning of these frames is NOT known from the source
 * material. They are therefore disabled from all automatic paths and may only
 * be sent by an explicit user action in the debug UI.
 */
public final class ExperimentalCommands {
    private ExperimentalCommands() {}

    public static final byte[] V805_INIT_1 = hex("BA CE 04 00 06 01 14 01 0A 00 18 01 10 01");
    public static final byte[] V805_INIT_2 = hex("BA CE 04 00 06 01 14 00 01 00 18 00 07 01");
    public static final byte[] V805_INIT_3 = hex("BA CE 08 00 06 12 02 05 02 01 03 00 77 9A 0D 05 7F AD");

    public static byte[] allV805Init() {
        byte[] out = new byte[V805_INIT_1.length + V805_INIT_2.length + V805_INIT_3.length];
        int p = 0;
        System.arraycopy(V805_INIT_1, 0, out, p, V805_INIT_1.length); p += V805_INIT_1.length;
        System.arraycopy(V805_INIT_2, 0, out, p, V805_INIT_2.length); p += V805_INIT_2.length;
        System.arraycopy(V805_INIT_3, 0, out, p, V805_INIT_3.length);
        return out;
    }

    private static byte[] hex(String s) {
        String[] a = s.trim().split("\\s+");
        byte[] out = new byte[a.length];
        for (int i = 0; i < a.length; i++) out[i] = (byte) Integer.parseInt(a[i], 16);
        return out;
    }
}
