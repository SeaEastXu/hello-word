package com.seaeast.e108gnss.service;

import android.app.*;
import android.content.*;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.*;
import android.util.Log;

import com.seaeast.e108gnss.MainActivity;
import com.seaeast.e108gnss.core.*;
import com.seaeast.e108gnss.location.MockLocationBridge;
import com.seaeast.e108gnss.usb.UsbCh340Transport;
import com.seaeast.e108gnss.usb.UsbDeviceFingerprint;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class GnssForegroundService extends Service {
    public interface UiListener {
        void onSnapshot(GnssSnapshot s);
        void onStatus(String s);
        void onLog(String s);
        void onTtff(String label,double seconds);
        void onCacheChanged(int fresh,int all,int rtcmFresh,int cynFresh);
    }
    public final class LocalBinder extends Binder { public GnssForegroundService getService(){return GnssForegroundService.this;} }

    private static final String CHANNEL="e108_gnss";
    private static final String USB_PERMISSION="com.seaeast.e108gnss.USB_PERMISSION";
    private static final int NOTIF=5500, BAUD=460800;
    private static final String K_FP="e108_fp",K_FP_TEXT="e108_fp_text";

    private final IBinder binder=new LocalBinder();
    private final CopyOnWriteArrayList<UiListener> listeners=new CopyOnWriteArrayList<>();
    private final NmeaParser nmea=new NmeaParser();
    private UsbManager usb;
    private UsbCh340Transport serial;
    private StreamDemux demux;
    private AssistanceCache cache;
    private SharedPreferences prefs;
    private MockLocationBridge mock;
    private HandlerThread frameworkThread;
    private Handler framework;
    private PendingIntent permissionPi;
    private File logFile;
    private BufferedWriter logWriter;
    private volatile UsbDeviceFingerprint activeFp;
    private volatile String learnedFp="",learnedFpText="";
    private volatile boolean confirmed=false;
    private volatile long nmeaCount,ggaCount,rmcCount,mockCount,lastNmeaElapsed;
    private volatile GnssSnapshot latest=new GnssSnapshot();
    private volatile boolean mockPending=false;
    private long ttffStart=-1;
    private String ttffLabel="";
    private boolean lastFix=false;
    private final Set<Integer> tried=new HashSet<>();

    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            String a=i.getAction();
            UsbDevice d=i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if(USB_PERMISSION.equals(a)){
                if(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false)&&d!=null) openCandidate(d);
                else status("USB权限被拒绝");
            }else if(UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)){
                if(UsbCh340Transport.looksLikeCh34x(d)) scanAndConnect();
            }else if(UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)){
                if(serial!=null&&serial.getDevice()!=null&&d!=null&&serial.getDevice().getDeviceId()==d.getDeviceId()){
                    disconnect(); new Handler(Looper.getMainLooper()).postDelayed(()->scanAndConnect(),500);
                }
            }
        }
    };

    @Override public void onCreate(){
        super.onCreate();
        usb=(UsbManager)getSystemService(USB_SERVICE);
        prefs=getSharedPreferences("e108",MODE_PRIVATE);
        learnedFp=prefs.getString(K_FP,""); learnedFpText=prefs.getString(K_FP_TEXT,"");
        cache=new AssistanceCache(getFilesDir()); mock=new MockLocationBridge(this);
        frameworkThread=new HandlerThread("e108-framework"); frameworkThread.start(); framework=new Handler(frameworkThread.getLooper());
        demux=new StreamDemux(new StreamDemux.Listener(){
            @Override public void onNmea(String s){handleNmea(s);}
            @Override public void onRtcm(byte[] f){handleRtcm(f);}
            @Override public void onCynosure(byte[] f){handleCyn(f);}
            @Override public void onGarbage(int n){if(n>64)log("discarded bytes="+n);}
        });
        createChannel(); startForeground(NOTIF,notification("等待E108")); registerUsbReceiver(); openLog(); notifyCache();
    }
    @Override public int onStartCommand(Intent i,int f,int id){scanAndConnect();return START_STICKY;}
    @Override public IBinder onBind(Intent i){return binder;}
    @Override public void onDestroy(){try{unregisterReceiver(receiver);}catch(Exception ignored){} disconnect();try{mock.setEnabled(false);}catch(Exception ignored){} if(frameworkThread!=null)frameworkThread.quitSafely();try{if(logWriter!=null)logWriter.close();}catch(Exception ignored){}super.onDestroy();}

    public void addUiListener(UiListener l){if(l!=null){listeners.addIfAbsent(l);l.onSnapshot(nmea.snapshot());notifyCache();}}
    public void removeUiListener(UiListener l){listeners.remove(l);}

    private List<UsbDevice> candidates(){
        ArrayList<UsbDevice> list=new ArrayList<>();
        for(UsbDevice d:usb.getDeviceList().values()) if(UsbCh340Transport.looksLikeCh34x(d)) list.add(d);
        list.sort((a,b)->Integer.compare(score(b),score(a)));
        return list;
    }
    private int score(UsbDevice d){
        UsbDeviceFingerprint fp=UsbDeviceFingerprint.from(d); int s=0;
        if(!learnedFp.isEmpty()&&learnedFp.equals(fp.stableKey()))s+=1000;
        if(fp.looksLikeObservedE108Bridge())s+=500;
        if(fp.looksLikeObservedTpmsBridge())s-=500;
        return s;
    }
    private String label(UsbDeviceFingerprint fp){
        if(fp==null)return "NONE";
        if(!learnedFp.isEmpty()&&learnedFp.equals(fp.stableKey()))return "LEARNED-E108";
        if(fp.looksLikeObservedE108Bridge())return "E108-HW-HINT";
        if(fp.looksLikeObservedTpmsBridge())return "TPMS-HW-HINT";
        return "UNKNOWN";
    }

    public synchronized void scanAndConnect(){
        if(serial!=null&&serial.isOpen())return;
        tried.clear(); List<UsbDevice> list=candidates();
        if(list.isEmpty()){status("未发现CH340");return;}
        connectFirstUntried(list);
    }
    private void connectFirstUntried(List<UsbDevice> list){
        for(UsbDevice d:list){
            if(tried.contains(d.getDeviceId()))continue;
            UsbDeviceFingerprint fp=UsbDeviceFingerprint.from(d);
            if(fp.looksLikeObservedTpmsBridge()&&!(!learnedFp.isEmpty()&&learnedFp.equals(fp.stableKey())))continue;
            tried.add(d.getDeviceId()); requestOrOpen(d); return;
        }
        status("未找到可用E108候选设备");
    }
    public synchronized void connectNextCh340(){disconnect(); tried.clear(); List<UsbDevice> list=candidates(); if(list.isEmpty()){status("未发现CH340");return;} requestOrOpen(list.get(list.size()>1?1:0));}
    private void requestOrOpen(UsbDevice d){
        if(usb.hasPermission(d)) openCandidate(d);
        else{
            Intent pi=new Intent(USB_PERMISSION).setPackage(getPackageName());
            permissionPi=PendingIntent.getBroadcast(this,d.getDeviceId(),pi,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            usb.requestPermission(d,permissionPi); status("请求USB权限 "+deviceText(d));
        }
    }
    private synchronized void openCandidate(UsbDevice d){
        disconnect(); confirmed=false; activeFp=UsbDeviceFingerprint.from(d);
        boolean learned=!learnedFp.isEmpty()&&learnedFp.equals(activeFp.stableKey());
        boolean force=learned||activeFp.looksLikeObservedE108Bridge();
        serial=new UsbCh340Transport(usb,new UsbCh340Transport.Listener(){
            @Override public void onBytes(byte[] data,int len){demux.feed(data,len);}
            @Override public void onStatus(String s){status(s);}
            @Override public void onError(String where,Throwable t){log(where+" "+t);framework.post(()->{disconnect();new Handler(Looper.getMainLooper()).postDelayed(()->scanAndConnect(),500);});}
        });
        try{
            serial.open(d,BAUD,force);
            status("候选已连接 "+deviceText(d)+" ["+label(activeFp)+"]，等待GNSS协议确认");
            new Handler(Looper.getMainLooper()).postDelayed(()->{
                if(serial!=null&&serial.isOpen()&&!confirmed){
                    log("候选未确认E108，切换下一只CH340"); disconnect(); connectFirstUntried(candidates());
                }
            },3000);
        }catch(Exception e){log("open failed "+e);disconnect();connectFirstUntried(candidates());}
    }
    public synchronized void disconnect(){if(serial!=null)serial.close();serial=null;confirmed=false;nmea.setConnected(false);}

    private void confirmE108(String why){
        if(!confirmed){confirmed=true;nmea.setConnected(true);log("E108 confirmed: "+why);}
        UsbDeviceFingerprint fp=activeFp;
        if(fp!=null&&!fp.stableKey().equals(learnedFp)){
            learnedFp=fp.stableKey(); learnedFpText=fp.shortText(); prefs.edit().putString(K_FP,learnedFp).putString(K_FP_TEXT,learnedFpText).apply();
            log("learned E108 fingerprint "+learnedFpText);
        }
    }
    public synchronized void forgetE108DeviceBinding(){learnedFp="";learnedFpText="";prefs.edit().remove(K_FP).remove(K_FP_TEXT).apply();status("已忘记E108绑定");}
    public synchronized void relearnE108Device(){forgetE108DeviceBinding();disconnect();new Handler(Looper.getMainLooper()).postDelayed(()->scanAndConnect(),300);}
    public String deviceRegistrySummary(){StringBuilder b=new StringBuilder("已学习E108：").append(learnedFpText.isEmpty()?"无":learnedFpText).append("\n当前CH340：");List<UsbDevice> l=candidates();if(l.isEmpty())return b.append("无").toString();for(UsbDevice d:l){UsbDeviceFingerprint fp=UsbDeviceFingerprint.from(d);b.append("\n• ").append(deviceText(d)).append(" ").append(fp.shortText()).append(" [").append(label(fp)).append("]");}return b.toString();}

    private void handleNmea(String line){
        nmeaCount++;lastNmeaElapsed=SystemClock.elapsedRealtime();String type=nmeaType(line);
        if("GGA".equals(type))ggaCount++;if("RMC".equals(type))rmcCount++;
        if(Arrays.asList("GGA","RMC","GSA","GSV","VTG","INS","ANT","CLK").contains(type))confirmE108("NMEA "+type);
        GnssSnapshot s=nmea.parse(line,lastNmeaElapsed,System.currentTimeMillis());latest=s;
        if(s.fix&&("GGA".equals(type)||"RMC".equals(type))){
            final GnssSnapshot copy=s.copy(); if(!mockPending&&mock.isEnabled()){mockPending=true;framework.postDelayed(()->{try{mock.push(latest);mockCount++;}catch(Exception e){log("mock "+e);}finally{mockPending=false;}},200);}
            if(!Double.isNaN(copy.latitude)&&!Double.isNaN(copy.longitude)) prefs.edit().putLong("last_lat",Double.doubleToRawLongBits(copy.latitude)).putLong("last_lon",Double.doubleToRawLongBits(copy.longitude)).putLong("last_alt",Double.doubleToRawLongBits(Double.isNaN(copy.altitudeM)?0:copy.altitudeM)).putLong("last_time",System.currentTimeMillis()).apply();
        }
        if(ttffStart>0&&s.fix&&!lastFix){double sec=(SystemClock.elapsedRealtime()-ttffStart)/1000.0;for(UiListener l:listeners)l.onTtff(ttffLabel,sec);log("TTFF "+ttffLabel+"="+sec+"s");ttffStart=-1;}lastFix=s.fix;
        for(UiListener l:listeners)try{l.onSnapshot(s);}catch(Exception ignored){}
    }
    private void handleRtcm(byte[] f){if(Rtcm3.valid(f)){confirmE108("RTCM "+Rtcm3.messageType(f));if(Rtcm3.isEphemerisType(Rtcm3.messageType(f))){cache.putRtcm(f,System.currentTimeMillis());notifyCache();}}}
    private void handleCyn(byte[] f){if(CynosurePacket.valid(f)){confirmE108("F1D9 "+CynosurePacket.id(f));cache.putCynosure(f,System.currentTimeMillis());notifyCache();}}
    private static String nmeaType(String s){if(s==null||!s.startsWith("$"))return "";int e=s.indexOf(',');if(e<0)e=s.indexOf('*');if(e<0)e=s.length();String h=s.substring(1,e).toUpperCase(Locale.ROOT);return h.length()>=3?h.substring(h.length()-3):h;}

    public void startBaselineTtff(){ttffLabel="BASELINE";ttffStart=SystemClock.elapsedRealtime();lastFix=false;log("TTFF baseline start");}
    public void startAssistedTtff(){ttffLabel="ASSISTED";ttffStart=SystemClock.elapsedRealtime();lastFix=false;new Thread(()->{injectCurrentTime();injectLastPosition();injectCachedAssistance(false);},"assist").start();}
    public void injectCurrentTime(){try{write(CynosurePacket.aidTimeUtc(System.currentTimeMillis()));log("AID-TIME sent");}catch(Exception e){log("AID-TIME "+e);}}
    public void injectLastPosition(){try{if(!prefs.contains("last_lat")){log("no last position");return;}double lat=Double.longBitsToDouble(prefs.getLong("last_lat",0)),lon=Double.longBitsToDouble(prefs.getLong("last_lon",0)),alt=Double.longBitsToDouble(prefs.getLong("last_alt",0));write(CynosurePacket.aidPositionLla(lat,lon,alt,100f));log("AID-POS sent");}catch(Exception e){log("AID-POS "+e);}}
    public void pollGpsBdsProprietaryEphemeris(){try{write(CynosurePacket.pollGpsEphemerisAll());SystemClock.sleep(100);write(CynosurePacket.pollBdsEphemerisAll());log("PEPH poll sent");}catch(Exception e){log("PEPH poll "+e);}}
    public int injectCachedAssistance(boolean force){int n=0;for(AssistanceCache.Record r:cache.fresh(System.currentTimeMillis(),force)){try{write(r.frame);n++;SystemClock.sleep(20);}catch(Exception e){log("cache inject "+e);break;}}log("cache injected records="+n+" force="+force);return n;}
    public int injectRaw(byte[] b)throws IOException{return write(b);}
    public void sendAscii(String s){try{if(s==null||s.isEmpty())return;write((s+"\r\n").getBytes(StandardCharsets.US_ASCII));log("TX ASCII "+s);}catch(Exception e){log("ASCII "+e);}}
    public void sendV805InitExperiment(){try{write(ExperimentalCommands.V805_INIT_1);SystemClock.sleep(20);write(ExperimentalCommands.V805_INIT_2);SystemClock.sleep(20);write(ExperimentalCommands.V805_INIT_3);log("sent v8.0.5 experimental BA CE frames");}catch(Exception e){log("BA CE "+e);}}
    private synchronized int write(byte[] b)throws IOException{if(serial==null||!serial.isOpen())throw new IOException("E108 not connected");return serial.write(b,3000);}

    public void setMockEnabled(boolean on){framework.post(()->{try{mock.setEnabled(on);status("Mock GPS "+(on?"已启用":"已关闭"));}catch(Exception e){status("Mock GPS失败，请在开发者选项选择本App: "+e.getMessage());}});}
    public boolean isMockEnabled(){return mock!=null&&mock.isEnabled();}
    public void clearCache(){cache.clear();notifyCache();}
    public File getLogFile(){return logFile;}
    public File getCacheFile(){return cache.getFile();}
    public String diagnosticStats(){long age=lastNmeaElapsed==0?-1:SystemClock.elapsedRealtime()-lastNmeaElapsed;long rx=serial==null?0:serial.getRxBytes();return String.format(Locale.US,"RX=%dB NMEA=%d GGA=%d RMC=%d mock=%d age=%dms E108=%s",rx,nmeaCount,ggaCount,rmcCount,mockCount,age,confirmed?"YES":"NO");}

    private void notifyCache(){if(cache==null)return;long now=System.currentTimeMillis();int f=cache.countFresh(now),a=cache.countAll(),r=cache.countRtcmFresh(now),c=cache.countCynFresh(now);for(UiListener l:listeners)try{l.onCacheChanged(f,a,r,c);}catch(Exception ignored){}}
    private void status(String s){log("STATUS "+s);for(UiListener l:listeners)try{l.onStatus(s);}catch(Exception ignored){}}
    private void log(String s){Log.i("E108GNSS",s);String line=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.US).format(new Date())+" "+s;try{if(logWriter!=null){logWriter.write(line);logWriter.newLine();logWriter.flush();}}catch(Exception ignored){}for(UiListener l:listeners)try{l.onLog(line);}catch(Exception ignored){}}
    private void openLog(){try{File dir=new File(getExternalFilesDir(null),"logs");dir.mkdirs();logFile=new File(dir,"e108_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date())+".log");logWriter=new BufferedWriter(new FileWriter(logFile,true));}catch(Exception ignored){}}
    private void registerUsbReceiver(){IntentFilter f=new IntentFilter();f.addAction(USB_PERMISSION);f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(receiver,f);}
    private void createChannel(){if(Build.VERSION.SDK_INT>=26)((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel(CHANNEL,"E108 GNSS",NotificationManager.IMPORTANCE_LOW));}
    private Notification notification(String text){PendingIntent pi=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("E108 GNSS 0x55002613").setContentText(text).setContentIntent(pi).setOngoing(true).build();}
    private static String deviceText(UsbDevice d){return d==null?"null":String.format(Locale.US,"%04X:%04X id=%d path=%s",d.getVendorId(),d.getProductId(),d.getDeviceId(),d.getDeviceName());}
}
