package com.seaeast.e108gnss.core;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class StreamDemux {
    public interface Listener {
        void onNmea(String line);
        void onRtcm(byte[] frame);
        void onCynosure(byte[] frame);
        void onGarbage(int bytes);
    }

    private final Listener listener;
    private byte[] buf = new byte[0];
    private int garbage = 0;

    public StreamDemux(Listener listener) { this.listener = listener; }

    public synchronized void feed(byte[] data, int len) {
        if (data == null || len <= 0) return;
        int old = buf.length;
        buf = Arrays.copyOf(buf, old + len);
        System.arraycopy(data, 0, buf, old, len);
        parseLoop();
        if (buf.length > 128 * 1024) {
            garbage += buf.length;
            buf = new byte[0];
            flushGarbage();
        }
    }

    private void parseLoop() {
        while (buf.length > 0) {
            int b0 = buf[0] & 0xFF;
            if (b0 == '$') {
                int end = findCrlf(buf);
                if (end < 0) return;
                byte[] line = Arrays.copyOfRange(buf, 0, end);
                consume(end + 2);
                flushGarbage();
                listener.onNmea(new String(line, StandardCharsets.US_ASCII));
                continue;
            }
            if (b0 == 0xD3) {
                if (buf.length < 3) return;
                int plen = ((buf[1] & 0x03) << 8) | (buf[2] & 0xFF);
                int total = plen + 6;
                if (plen > 1023) { dropOne(); continue; }
                if (buf.length < total) return;
                byte[] frame = Arrays.copyOfRange(buf, 0, total);
                consume(total);
                flushGarbage();
                listener.onRtcm(frame);
                continue;
            }
            if (b0 == 0xF1) {
                if (buf.length < 2) return;
                if ((buf[1] & 0xFF) != 0xD9) { dropOne(); continue; }
                if (buf.length < 6) return;
                int plen = (buf[4]&0xFF) | ((buf[5]&0xFF)<<8);
                if (plen > 8192) { dropOne(); continue; }
                int total = 8 + plen;
                if (buf.length < total) return;
                byte[] frame = Arrays.copyOfRange(buf, 0, total);
                consume(total);
                flushGarbage();
                listener.onCynosure(frame);
                continue;
            }
            dropOne();
        }
    }

    private int findCrlf(byte[] a) {
        for (int i = 1; i < a.length - 1; i++) {
            if (a[i] == '\r' && a[i+1] == '\n') return i;
        }
        return -1;
    }
    private void dropOne() { garbage++; consume(1); }
    private void consume(int n) { buf = n >= buf.length ? new byte[0] : Arrays.copyOfRange(buf, n, buf.length); }
    private void flushGarbage() { if (garbage > 0) { listener.onGarbage(garbage); garbage = 0; } }
}
