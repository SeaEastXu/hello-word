package com.seaeast.e108gnss.core;

import java.io.*;
import java.util.*;

public final class AssistanceCache {
    public static final long AUTO_MAX_AGE_MS = 4L * 60L * 60L * 1000L;
    private final File file;
    private final LinkedHashMap<String, Record> records = new LinkedHashMap<>();

    public static final class Record {
        public final String key;
        public final long timeMs;
        public final byte[] frame;
        Record(String key, long timeMs, byte[] frame) { this.key=key; this.timeMs=timeMs; this.frame=frame; }
    }

    public AssistanceCache(File dir) {
        this.file = new File(dir, "e108_assistance_cache.bin");
        load();
    }

    public synchronized void putRtcm(byte[] frame, long nowMs) {
        int type = Rtcm3.messageType(frame);
        if (!Rtcm3.isEphemerisType(type) || !Rtcm3.valid(frame)) return;
        int svid = Rtcm3.satelliteIdGuess(frame);
        put("R:" + type + ":" + svid, nowMs, frame);
    }

    public synchronized void putCynosure(byte[] frame, long nowMs) {
        if (!CynosurePacket.valid(frame)) return;
        int cls = CynosurePacket.cls(frame), id = CynosurePacket.id(frame), len = CynosurePacket.payloadLength(frame);
        if (cls != CynosurePacket.CLASS_AID) return;
        if ((id == CynosurePacket.ID_PEPH_GPS && len == 65) ||
            (id == CynosurePacket.ID_PEPH_BDS && len > 1)) {
            int svid = CynosurePacket.svidForPeph(frame);
            put("C:" + id + ":" + svid, nowMs, frame);
        }
    }

    private void put(String key, long nowMs, byte[] frame) {
        records.put(key, new Record(key, nowMs, Arrays.copyOf(frame, frame.length)));
        save();
    }

    public synchronized List<Record> fresh(long nowMs, boolean forceAll) {
        List<Record> out = new ArrayList<>();
        for (Record r : records.values()) {
            if (forceAll || nowMs - r.timeMs <= AUTO_MAX_AGE_MS) out.add(r);
        }
        return out;
    }

    public synchronized int countFresh(long nowMs) { return fresh(nowMs, false).size(); }
    public synchronized int countAll() { return records.size(); }

    public synchronized int countRtcmFresh(long nowMs) {
        int n=0; for (Record r : fresh(nowMs,false)) if (r.key.startsWith("R:")) n++; return n;
    }
    public synchronized int countCynFresh(long nowMs) {
        int n=0; for (Record r : fresh(nowMs,false)) if (r.key.startsWith("C:")) n++; return n;
    }

    public synchronized void clear() { records.clear(); save(); }

    private void load() {
        if (!file.exists()) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (!"E108AID3".equals(in.readUTF())) return;
            int n = in.readInt();
            for (int i=0;i<n;i++) {
                String key = in.readUTF();
                long t = in.readLong();
                int len = in.readInt();
                if (len <= 0 || len > 65536) throw new IOException("bad len");
                byte[] f = new byte[len]; in.readFully(f);
                records.put(key, new Record(key,t,f));
            }
        } catch (Exception ignored) { records.clear(); }
    }

    private void save() {
        File tmp = new File(file.getParentFile(), file.getName()+".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            out.writeUTF("E108AID3");
            out.writeInt(records.size());
            for (Record r : records.values()) {
                out.writeUTF(r.key); out.writeLong(r.timeMs); out.writeInt(r.frame.length); out.write(r.frame);
            }
            out.flush();
            if (!tmp.renameTo(file)) {
                try (InputStream in = new FileInputStream(tmp); OutputStream dst = new FileOutputStream(file)) {
                    byte[] b = new byte[8192]; int k; while ((k=in.read(b))>0) dst.write(b,0,k);
                }
                tmp.delete();
            }
        } catch (Exception ignored) {}
    }

    public File getFile() { return file; }
}
