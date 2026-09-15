package com.seaeast.e108gnss.core;

import android.os.SystemClock;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RTCM3 / MSM field diagnostics for E108 field testing.
 *
 * v0.5.1 improvements:
 *  - distinguish RTCM frame rate from observation epoch rate;
 *  - count NavIC in constellation/MSM7 totals;
 *  - report PR/PH/RR/CNR completeness ratios;
 *  - avoid calling a stream "fully good" just because some carrier phase exists.
 *
 * This is still a diagnostic layer, not an Android GnssMeasurement provider.
 */
public final class RtcmDiagnostics {
    private static final long RATE_WINDOW_MS = 5000;
    private static final int MAX_TS_PER_TYPE = 512;

    private static final class TypeStat {
        long count;
        long lastElapsed;
        int lastBytes;
        final ArrayDeque<Long> frameTimestamps = new ArrayDeque<>();
        final ArrayDeque<Long> epochTimestamps = new ArrayDeque<>();
        long lastEpochRaw = Long.MIN_VALUE;
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
                    "%s MSM%d sat=%d sig=%d cell=%d PR=%d PH=%d RR=%d CNR=%d HC=%d epoch=%d%s",
                    constellation, subtype, satellites, signals, cells,
                    pseudorangeValid, phaseValid, rateValid, cnrPositive, halfCycleSet,
                    epochRaw, multipleMessage ? " M" : "");
        }
    }

    private static final Object RAW_LOCK=new Object();
    private static BufferedOutputStream rawOut;
    private static File rawFile;
    private static long rawBytes=0,rawFrames=0;

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
        recordRaw(frame);
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
        s.frameTimestamps.addLast(now);
        capAndTrim(s.frameTimestamps, now);

        if (isMsm(type)) {
            MsmSummary m = parseMsm(frame);
            s.lastMsm = m;
            if (m.parsed && m.epochRaw != s.lastEpochRaw) {
                s.lastEpochRaw = m.epochRaw;
                s.epochTimestamps.addLast(now);
                capAndTrim(s.epochTimestamps, now);
            }
        }
    }

    public static String startRawRecording(File externalFilesDir){
        synchronized(RAW_LOCK){
            try{
                if(rawOut!=null)return rawRecordingStatus();
                File base=externalFilesDir==null?null:new File(externalFilesDir,"raw");
                if(base==null)throw new IllegalStateException("externalFilesDir=null");
                if(!base.exists()&&!base.mkdirs())throw new IllegalStateException("mkdir failed");
                rawFile=new File(base,"rtcm_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date())+".rtcm3");
                rawOut=new BufferedOutputStream(new FileOutputStream(rawFile),65536);
                rawBytes=0;rawFrames=0;
                return rawRecordingStatus();
            }catch(Exception e){rawOut=null;return "录制失败: "+e.getClass().getSimpleName()+":"+e.getMessage();}
        }
    }

    public static String stopRawRecording(){
        synchronized(RAW_LOCK){
            if(rawOut!=null){try{rawOut.flush();rawOut.close();}catch(Exception ignored){}rawOut=null;}
            return rawRecordingStatus();
        }
    }

    public static String rawRecordingStatus(){
        synchronized(RAW_LOCK){
            return (rawOut!=null?"录制中":"已停止")+" frames="+rawFrames+" bytes="+rawBytes+"\n文件："+(rawFile==null?"--":rawFile.getAbsolutePath());
        }
    }

    private static void recordRaw(byte[] frame){
        synchronized(RAW_LOCK){
            if(rawOut==null||frame==null)return;
            try{rawOut.write(frame);rawFrames++;rawBytes+=frame.length;}
            catch(Exception e){try{rawOut.close();}catch(Exception ignored){}rawOut=null;}
        }
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
        double badPct = frames <= 0 ? 0.0 : (100.0 * invalidFrames / frames);
        sb.append(String.format(Locale.US,
                "帧 total=%d valid=%d bad=%d (%.3f%%) lastAge=%dms\n",
                frames, validFrames, invalidFrames, badPct, age));

        if (stats.isEmpty()) {
            sb.append("尚未收到有效RTCM3。\n");
            sb.append("观测状态：WAITING");
            return sb.toString();
        }

        List<Integer> types = new ArrayList<>(stats.keySet());
        Collections.sort(types, new Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) {
                return Integer.compare(sortKey(a), sortKey(b));
            }
        });

        boolean gps7=false,glo7=false,gal7=false,bds7=false,qzss7=false,navic7=false,sbas7=false;
        boolean gpsAny=false,gloAny=false,galAny=false,bdsAny=false,qzssAny=false,navicAny=false,sbasAny=false;
        int msmCells=0, pr=0, ph=0, rr=0, cnr=0;

        for (int type : types) {
            TypeStat s = stats.get(type);
            trim(s.frameTimestamps, now);
            trim(s.epochTimestamps, now);
            double frameHz = rateHz(s.frameTimestamps);
            double epochHz = isMsm(type) ? rateHz(s.epochTimestamps) : 0.0;
            if (isMsm(type)) {
                sb.append(String.format(Locale.US, "%d %-12s frame=%5.1fHz epoch=%5.1fHz n=%d %dB",
                        type, name(type), frameHz, epochHz, s.count, s.lastBytes));
            } else {
                sb.append(String.format(Locale.US, "%d %-12s %5.1fHz n=%d %dB",
                        type, name(type), frameHz, s.count, s.lastBytes));
            }
            if (s.lastMsm != null) {
                MsmSummary m=s.lastMsm;
                sb.append("  ").append(m.compact());
                if (m.parsed) {
                    msmCells += m.cells; pr += m.pseudorangeValid; ph += m.phaseValid;
                    rr += m.rateValid; cnr += m.cnrPositive;
                    String c=m.constellation;
                    if ("GPS".equals(c)) gpsAny=true;
                    else if ("GLO".equals(c)) gloAny=true;
                    else if ("GAL".equals(c)) galAny=true;
                    else if ("BDS".equals(c)) bdsAny=true;
                    else if ("QZSS".equals(c)) qzssAny=true;
                    else if ("NAVIC".equals(c)) navicAny=true;
                    else if ("SBAS".equals(c)) sbasAny=true;
                    if (m.subtype == 7) {
                        if ("GPS".equals(c)) gps7=true;
                        else if ("GLO".equals(c)) glo7=true;
                        else if ("GAL".equals(c)) gal7=true;
                        else if ("BDS".equals(c)) bds7=true;
                        else if ("QZSS".equals(c)) qzss7=true;
                        else if ("NAVIC".equals(c)) navic7=true;
                        else if ("SBAS".equals(c)) sbas7=true;
                    }
                }
            }
            sb.append('\n');
        }

        int msmAnyConstellations=countTrue(gpsAny,gloAny,galAny,bdsAny,qzssAny,navicAny,sbasAny);
        int msm7Constellations=countTrue(gps7,glo7,gal7,bds7,qzss7,navic7,sbas7);
        double prPct = pct(pr,msmCells), phPct=pct(ph,msmCells), rrPct=pct(rr,msmCells), cnrPct=pct(cnr,msmCells);

        sb.append(String.format(Locale.US,
                "MSM汇总：星座=%d MSM7星座=%d cell=%d | PR=%d(%.0f%%) PH=%d(%.0f%%) RR=%d(%.0f%%) CNR=%d(%.0f%%)\n",
                msmAnyConstellations, msm7Constellations, msmCells,
                pr,prPct,ph,phPct,rr,rrPct,cnr,cnrPct));

        boolean obsReady = msm7Constellations >= 2 && msmCells > 0 && prPct >= 80.0 && rrPct >= 80.0 && cnrPct >= 80.0;
        String obs = obsReady
                ? "OBS_READY：多星座MSM7 + 伪距/距离率/CNR已具备GnssMeasurement转换基础"
                : (msmAnyConstellations>0 ? "OBS_PARTIAL：已有MSM，但关键观测完整度或星座数不足" : "NO_OBS：未确认MSM观测");
        String carrier;
        if (msmCells <= 0) carrier="WAITING";
        else if (phPct >= 80.0) carrier="FULL-ish（>=80%）";
        else if (phPct >= 20.0) carrier="PARTIAL（20-79%）";
        else carrier="VERY_PARTIAL（<20%）";

        sb.append("观测状态：").append(obs).append('\n');
        sb.append(String.format(Locale.US,"载波相位：%s，当前PH完整度 %.1f%%\n",carrier,phPct));
        sb.append("HAL建议：");
        if(obsReady) sb.append("可以开始GnssMeasurement/GnssClock转换；载波相位单独按完整度处理，不能把少量PH误判成全量可用。");
        else sb.append("先检查E108 RTCM MSM配置和实际观测字段，再进入HAL。");
        return sb.toString();
    }

    private static int countTrue(boolean... v){int n=0;for(boolean b:v)if(b)n++;return n;}
    private static double pct(int n,int d){return d<=0?0.0:100.0*n/d;}

    private static void capAndTrim(ArrayDeque<Long> q,long now){
        while(q.size()>MAX_TS_PER_TYPE)q.removeFirst();
        trim(q,now);
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
