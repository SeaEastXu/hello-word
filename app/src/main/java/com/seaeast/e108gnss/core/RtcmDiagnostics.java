package com.seaeast.e108gnss.core;

import android.os.SystemClock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lightweight RTCM3 / MSM diagnostics for field testing.
 *
 * This class deliberately does not pretend to be an Android Raw GNSS provider. It only
 * validates that the E108 stream contains the observation fields needed by a future
 * Native GNSS HAL (MSM4/5/7, especially MSM7) and reports message rates/field presence.
 */
public final class RtcmDiagnostics {
    private static final long RATE_WINDOW_MS = 5000;
    private static final int MAX_TS_PER_TYPE = 256;

    private static final class TypeStat {
        long count;
        long lastElapsed;
        int lastBytes;
        final ArrayDeque<Long> timestamps = new ArrayDeque<>();
        MsmSummary lastMsm;
    }

    public static final class MsmSummary {
        public int type;
        public String constellation = "?";
        public int subtype;
        public int stationId;
        public long epochRaw;
        public boolean multipleMessage;
        public int satellites;
        public int signals;
        public int cells;
        public int roughRangeValid;
        public int pseudorangeValid;
        public int phaseValid;
        public int rateValid;
        public int cnrPositive;
        public int halfCycleSet;
        public boolean parsed;
        public String error = "";

        public String compact() {
            if (!parsed) return "parse=" + (error.isEmpty() ? "FAIL" : error);
            return String.format(Locale.US,
                    "%s MSM%d sat=%d sig=%d cell=%d PR=%d PH=%d RR=%d CNR=%d HC=%d",
                    constellation, subtype, satellites, signals, cells,
                    pseudorangeValid, phaseValid, rateValid, cnrPositive, halfCycleSet);
        }
    }

    private final Map<Integer, TypeStat> stats = new HashMap<>();
    private long frames;
    private long validFrames;
    private long invalidFrames;
    private long lastFrameElapsed;

    public synchronized void reset() {
        stats.clear();
        frames = validFrames = invalidFrames = 0;
        lastFrameElapsed = 0;
    }

    public synchronized void onFrame(byte[] frame) {
        frames++;
        final long now = SystemClock.elapsedRealtime();
        lastFrameElapsed = now;
        if (!Rtcm3.valid(frame)) {
            invalidFrames++;
            return;
        }
        validFrames++;
        int type = Rtcm3.messageType(frame);
        TypeStat s = stats.get(type);
        if (s == null) {
            s = new TypeStat();
            stats.put(type, s);
        }
        s.count++;
        s.lastElapsed = now;
        s.lastBytes = frame == null ? 0 : frame.length;
        s.timestamps.addLast(now);
        while (s.timestamps.size() > MAX_TS_PER_TYPE) s.timestamps.removeFirst();
        trim(s.timestamps, now);

        if (isMsm(type)) s.lastMsm = parseMsm(frame);
    }

    public synchronized String brief() {
        long now = SystemClock.elapsedRealtime();
        long age = lastFrameElapsed == 0 ? -1 : now - lastFrameElapsed;
        return String.format(Locale.US, "RTCM=%d valid=%d bad=%d age=%dms",
                frames, validFrames, invalidFrames, age);
    }

    public synchronized String summary() {
        long now = SystemClock.elapsedRealtime();
        StringBuilder sb = new StringBuilder();
        long age = lastFrameElapsed == 0 ? -1 : now - lastFrameElapsed;
        sb.append(String.format(Locale.US,
                "帧 total=%d valid=%d bad=%d lastAge=%dms\n", frames, validFrames, invalidFrames, age));

        if (stats.isEmpty()) {
            sb.append("尚未收到有效RTCM3。\n");
            sb.append("Raw状态：WAITING");
            return sb.toString();
        }

        List<Integer> types = new ArrayList<>(stats.keySet());
        Collections.sort(types, new Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) {
                return Integer.compare(sortKey(a), sortKey(b));
            }
        });

        int msm7Constellations = 0;
        int msmAnyConstellations = 0;
        boolean gps7=false,glo7=false,gal7=false,bds7=false,qzss7=false;
        int msmCells=0, pr=0, ph=0, rr=0, cnr=0;

        for (int type : types) {
            TypeStat s = stats.get(type);
            trim(s.timestamps, now);
            double hz = rateHz(s.timestamps);
            sb.append(String.format(Locale.US, "%d %-12s %5.1f Hz  n=%d  %dB",
                    type, name(type), hz, s.count, s.lastBytes));
            if (s.lastMsm != null) {
                MsmSummary m=s.lastMsm;
                sb.append("  ").append(m.compact());
                if (m.parsed) {
                    msmCells += m.cells; pr += m.pseudorangeValid; ph += m.phaseValid;
                    rr += m.rateValid; cnr += m.cnrPositive;
                    if (m.subtype == 7) {
                        if ("GPS".equals(m.constellation)) gps7=true;
                        else if ("GLO".equals(m.constellation)) glo7=true;
                        else if ("GAL".equals(m.constellation)) gal7=true;
                        else if ("BDS".equals(m.constellation)) bds7=true;
                        else if ("QZSS".equals(m.constellation)) qzss7=true;
                    }
                }
            }
            sb.append('\n');
        }

        boolean gpsAny=false,gloAny=false,galAny=false,bdsAny=false,qzssAny=false;
        for (int type:types) {
            if (!isMsm(type)) continue;
            String c=constellation(type);
            if ("GPS".equals(c)) gpsAny=true;
            else if ("GLO".equals(c)) gloAny=true;
            else if ("GAL".equals(c)) galAny=true;
            else if ("BDS".equals(c)) bdsAny=true;
            else if ("QZSS".equals(c)) qzssAny=true;
        }
        if(gpsAny)msmAnyConstellations++; if(gloAny)msmAnyConstellations++; if(galAny)msmAnyConstellations++;
        if(bdsAny)msmAnyConstellations++; if(qzssAny)msmAnyConstellations++;
        if(gps7)msm7Constellations++; if(glo7)msm7Constellations++; if(gal7)msm7Constellations++;
        if(bds7)msm7Constellations++; if(qzss7)msm7Constellations++;

        sb.append(String.format(Locale.US,
                "MSM汇总：星座=%d  MSM7星座=%d  cell=%d  PR=%d  PH=%d  RR=%d  CNR=%d\n",
                msmAnyConstellations, msm7Constellations, msmCells, pr, ph, rr, cnr));

        String raw;
        if (msm7Constellations >= 2 && pr > 0 && ph > 0 && rr > 0 && cnr > 0) {
            raw = "GOOD：已看到多星座MSM7及伪距/载波相位/距离率/CNR字段；可进入Native HAL转换测试";
        } else if (msmAnyConstellations > 0 && (pr > 0 || ph > 0)) {
            raw = "PARTIAL：已有MSM观测，但MSM7/关键字段/星座数量仍不足，先检查E108 RTCM输出配置";
        } else {
            raw = "NO-OBS：目前没有可确认的MSM观测字段";
        }
        sb.append("Raw状态：").append(raw);
        return sb.toString();
    }

    private static void trim(ArrayDeque<Long> q,long now){
        long min=now-RATE_WINDOW_MS;
        while(!q.isEmpty() && q.peekFirst()<min) q.removeFirst();
    }

    private static double rateHz(ArrayDeque<Long> q){
        if(q.isEmpty()) return 0.0;
        if(q.size()==1) return 0.2;
        long first=q.peekFirst(), last=q.peekLast();
        long span=Math.max(250, last-first);
        return (q.size()-1)*1000.0/span;
    }

    private static int sortKey(int t){
        if(isMsm(t)) return t;
        if(Rtcm3.isEphemerisType(t)) return 20000+t;
        return 40000+t;
    }

    public static boolean isMsm(int type){
        int family=type/10;
        int sub=type%10;
        return family>=107 && family<=113 && sub>=1 && sub<=7;
    }

    public static String constellation(int type){
        switch(type/10){
            case 107:return "GPS";
            case 108:return "GLO";
            case 109:return "GAL";
            case 110:return "SBAS";
            case 111:return "QZSS";
            case 112:return "BDS";
            case 113:return "NAVIC";
            default:return "?";
        }
    }

    public static String name(int type){
        if(isMsm(type)) return constellation(type)+"-MSM"+(type%10);
        switch(type){
            case 1005:return "Station-ARP";
            case 1006:return "Station-ARP-H";
            case 1019:return "GPS-EPH";
            case 1020:return "GLO-EPH";
            case 1041:return "NAVIC-EPH";
            case 1042:return "BDS-EPH";
            case 1043:return "SBAS-EPH";
            case 1044:return "QZSS-EPH";
            case 1045:return "GAL-FNAV";
            case 1046:return "GAL-INAV";
            default:return "RTCM";
        }
    }

    public static MsmSummary parseMsm(byte[] frame){
        MsmSummary m=new MsmSummary();
        m.type=Rtcm3.messageType(frame);
        m.constellation=constellation(m.type);
        m.subtype=m.type%10;
        if(frame==null || !Rtcm3.valid(frame) || !isMsm(m.type)){
            m.error="not-valid-msm"; return m;
        }
        try{
            int payloadLen=Rtcm3.payloadLength(frame);
            BitReader b=new BitReader(frame,3,payloadLen);
            int type=(int)b.u(12);
            if(type!=m.type) throw new IllegalStateException("type");
            m.stationId=(int)b.u(12);
            m.epochRaw=b.u(30);
            m.multipleMessage=b.u(1)!=0;
            b.u(3); b.u(7); b.u(2); b.u(2); b.u(1); b.u(3);

            for(int i=0;i<64;i++)if(b.u(1)!=0)m.satellites++;
            for(int i=0;i<32;i++)if(b.u(1)!=0)m.signals++;
            int matrix=m.satellites*m.signals;
            for(int i=0;i<matrix;i++)if(b.u(1)!=0)m.cells++;

            int ns=m.satellites;
            if(m.subtype==4){
                for(int i=0;i<ns;i++){long v=b.u(8);if(v!=255)m.roughRangeValid++;}
                for(int i=0;i<ns;i++)b.u(10);
                for(int i=0;i<m.cells;i++){if(b.s(15)!=-(1L<<14))m.pseudorangeValid++;}
                for(int i=0;i<m.cells;i++){if(b.s(22)!=-(1L<<21))m.phaseValid++;}
                for(int i=0;i<m.cells;i++)b.u(4);
                for(int i=0;i<m.cells;i++){if(b.u(1)!=0)m.halfCycleSet++;}
                for(int i=0;i<m.cells;i++){if(b.u(6)>0)m.cnrPositive++;}
            } else if(m.subtype==5){
                for(int i=0;i<ns;i++){long v=b.u(8);if(v!=255)m.roughRangeValid++;}
                for(int i=0;i<ns;i++)b.u(4);
                for(int i=0;i<ns;i++)b.u(10);
                for(int i=0;i<ns;i++)b.s(14);
                for(int i=0;i<m.cells;i++){if(b.s(15)!=-(1L<<14))m.pseudorangeValid++;}
                for(int i=0;i<m.cells;i++){if(b.s(22)!=-(1L<<21))m.phaseValid++;}
                for(int i=0;i<m.cells;i++)b.u(4);
                for(int i=0;i<m.cells;i++){if(b.u(1)!=0)m.halfCycleSet++;}
                for(int i=0;i<m.cells;i++){if(b.u(6)>0)m.cnrPositive++;}
                for(int i=0;i<m.cells;i++){if(b.s(15)!=-(1L<<14))m.rateValid++;}
            } else if(m.subtype==7){
                for(int i=0;i<ns;i++){long v=b.u(8);if(v!=255)m.roughRangeValid++;}
                for(int i=0;i<ns;i++)b.u(4);
                for(int i=0;i<ns;i++)b.u(10);
                for(int i=0;i<ns;i++)b.s(14);
                for(int i=0;i<m.cells;i++){if(b.s(20)!=-(1L<<19))m.pseudorangeValid++;}
                for(int i=0;i<m.cells;i++){if(b.s(24)!=-(1L<<23))m.phaseValid++;}
                for(int i=0;i<m.cells;i++)b.u(10);
                for(int i=0;i<m.cells;i++){if(b.u(1)!=0)m.halfCycleSet++;}
                for(int i=0;i<m.cells;i++){if(b.u(10)>0)m.cnrPositive++;}
                for(int i=0;i<m.cells;i++){if(b.s(15)!=-(1L<<14))m.rateValid++;}
            }
            m.parsed=true;
        }catch(Throwable t){m.error=t.getClass().getSimpleName()+":"+(t.getMessage()==null?"":t.getMessage());}
        return m;
    }

    private static final class BitReader{
        private final byte[] data;
        private final int endBit;
        private int bit;
        BitReader(byte[] data,int byteOff,int byteLen){this.data=data;bit=byteOff*8;endBit=(byteOff+byteLen)*8;}
        long u(int n){
            if(n<0||n>63||bit+n>endBit)throw new IndexOutOfBoundsException("bits");
            long v=0;for(int i=0;i<n;i++){int p=bit++;v=(v<<1)|((data[p>>>3]>>(7-(p&7)))&1);}return v;
        }
        long s(int n){long v=u(n);long sign=1L<<(n-1);return (v&sign)!=0?v-(1L<<n):v;}
    }
}
