package com.seaeast.e108gnss;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import com.seaeast.e108gnss.core.GnssSnapshot;
import com.seaeast.e108gnss.service.GnssForegroundService;

import java.io.*;
import java.util.Locale;

public class MainActivity extends Activity implements GnssForegroundService.UiListener {
    private static final int REQ_RAW_FILE=1001;
    private GnssForegroundService svc;
    private boolean bound;
    private TextView tvStatus,tvFix,tvPos,tvMotion,tvSat,tvAux,tvDiag,tvUsbRegistry,tvPaths,tvLog;
    private CheckBox cbMock;
    private EditText etCmd;
    private final StringBuilder logBuf=new StringBuilder();
    private final Handler uiHandler=new Handler(Looper.getMainLooper());
    private final Runnable diagTick=new Runnable(){@Override public void run(){if(bound&&svc!=null){if(tvDiag!=null)tvDiag.setText("数据链路："+svc.diagnosticStats());if(tvUsbRegistry!=null)tvUsbRegistry.setText(svc.deviceRegistrySummary());}uiHandler.postDelayed(this,1000);}};

    private final ServiceConnection conn=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName n,android.os.IBinder b){
            svc=((GnssForegroundService.LocalBinder)b).getService(); bound=true; svc.addUiListener(MainActivity.this); cbMock.setChecked(svc.isMockEnabled());
        }
        @Override public void onServiceDisconnected(ComponentName n){bound=false;svc=null;}
    };

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        setContentView(buildUi());
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},900);
        Intent s=new Intent(this,GnssForegroundService.class);
        if(Build.VERSION.SDK_INT>=26) startForegroundService(s); else startService(s);
        bindService(s,conn,BIND_AUTO_CREATE);
        uiHandler.post(diagTick);
        handleAttachIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);handleAttachIntent(i);}
    private void handleAttachIntent(Intent i){ if(i!=null && android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction())) { } }

    @Override protected void onDestroy(){uiHandler.removeCallbacks(diagTick);if(bound&&svc!=null)svc.removeUiListener(this);if(bound)unbindService(conn);super.onDestroy();}

    private View buildUi(){
        ScrollView scroll=new ScrollView(this);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(20,16,20,30);scroll.addView(root);

        TextView title=t("E108-GN07IS 车机GNSS调试驱动 v0.4.2",22,true);root.addView(title);
        root.addView(t("固件专用：0x55002613   CH340 / UART0 / 460800 / 8N1",14,true));
        root.addView(t("目标：UIS7870 Android 13。支持多CH340共存、跨USB口识别E108、NMEA + RTCM3 + Cynosure AGNSS、TTFF与辅助数据缓存/回灌。",13,false));

        tvStatus=t("状态：服务启动中…",16,true);root.addView(tvStatus);
        tvFix=t("定位：--",18,true);root.addView(tvFix);
        tvPos=t("位置：--",15,false);root.addView(tvPos);
        tvMotion=t("速度/航向：--",15,false);root.addView(tvMotion);
        tvSat=t("卫星/DOP：--",15,false);root.addView(tvSat);
        tvAux=t("辅助缓存：--",14,false);root.addView(tvAux);
        tvDiag=t("数据链路：--",13,true);root.addView(tvDiag);

        root.addView(section("USB / CH340 多设备识别"));
        LinearLayout r1=row();
        r1.addView(btn("扫描并连接",v->{if(svc!=null)svc.scanAndConnect();}));
        r1.addView(btn("切换CH340",v->{if(svc!=null)svc.connectNextCh340();}));
        r1.addView(btn("断开",v->{if(svc!=null)svc.disconnect();}));root.addView(r1);
        LinearLayout r1b=row();
        r1b.addView(btn("重新识别E108",v->{if(svc!=null)svc.relearnE108Device();}));
        r1b.addView(btn("忘记E108绑定",v->{if(svc!=null)svc.forgetE108DeviceBinding();}));root.addView(r1b);
        tvUsbRegistry=t("设备识别：--",12,false);tvUsbRegistry.setTextIsSelectable(true);root.addView(tvUsbRegistry);
        root.addView(t("不把 /001/009 等总线地址当身份。优先使用已学习的 VID/PID + USB version + 接口/端点指纹；当前实车 E108 桥版本特征为 82.33，胎压桥为 2.64。最终仍由 GGA/RMC/GSV/RTCM/F1D9 协议帧确认。未知CH340只做非强制 claim，避免抢占胎压。",12,false));

        root.addView(section("冷启动 / AGNSS 测试"));
        LinearLayout r2=row();
        r2.addView(btn("基线 TTFF",v->{if(svc!=null)svc.startBaselineTtff();}));
        r2.addView(btn("一键辅助 TTFF",v->{if(svc!=null)svc.startAssistedTtff();}));root.addView(r2);
        LinearLayout r3=row();
        r3.addView(btn("注入UTC时间",v->{if(svc!=null)svc.injectCurrentTime();}));
        r3.addView(btn("注入上次位置",v->{if(svc!=null)svc.injectLastPosition();}));root.addView(r3);
        LinearLayout r4=row();
        r4.addView(btn("读取GPS+BDS星历",v->{if(svc!=null)new Thread(()->svc.pollGpsBdsProprietaryEphemeris()).start();}));
        r4.addView(btn("注入≤4h缓存",v->{if(svc!=null)new Thread(()->svc.injectCachedAssistance(false)).start();}));root.addView(r4);
        LinearLayout r5=row();
        r5.addView(btn("强制注入全部缓存",v->{if(svc!=null)new Thread(()->svc.injectCachedAssistance(true)).start();}));
        r5.addView(btn("清空辅助缓存",v->{if(svc!=null)svc.clearCache();}));root.addView(r5);
        root.addView(btnWide("导入并注入 AGNSS/RTCM 原始 .bin",v->openRawFile()));

        root.addView(section("地图快速验证（仅测试）"));
        cbMock=new CheckBox(this);cbMock.setText("把E108位置作为 Android Mock GPS 输出");cbMock.setTextSize(14);root.addView(cbMock);
        cbMock.setOnCheckedChangeListener((btt,on)->{if(svc!=null)svc.setMockEnabled(on);});
        Button mockHelp=btnWide("打开开发者选项（选择本App为模拟位置信息应用）",v->{try{startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));}catch(Exception e){Toast.makeText(this,"无法打开开发者选项",Toast.LENGTH_SHORT).show();}});root.addView(mockHelp);

        root.addView(section("E108 ASCII 控制台"));
        etCmd=new EditText(this);etCmd.setSingleLine(true);etCmd.setText("$POLCFGSAVE");etCmd.setTextSize(14);root.addView(etCmd);
        root.addView(btnWide("发送 ASCII + CRLF",v->{if(svc!=null)svc.sendAscii(etCmd.getText().toString().trim());}));

        root.addView(section("参考驱动 v8.0.5 初始化命令实验"));
        root.addView(t("仅用于A/B研究：v8.0.5比v8.0.4新增的3帧 BA CE 二进制命令，共46字节。当前不知道其具体语义，因此不会自动发送。测试前先记录RX/NMEA/RTCM计数。",12,false));
        root.addView(btnWide("实验：发送v8.0.5三帧 BA CE（需确认）",v->new android.app.AlertDialog.Builder(this)
                .setTitle("实验命令确认")
                .setMessage("这3帧命令来自v8.0.4/8.0.5二进制差异，具体含义尚未确认。只建议在可恢复配置、静态测试条件下发送。是否继续？")
                .setNegativeButton("取消",null)
                .setPositiveButton("发送",(d,w)->{if(svc!=null)new Thread(()->svc.sendV805InitExperiment(),"bace-exp").start();})
                .show()));

        root.addView(section("0x55002613 当前/推荐检查点"));
        TextView profile=t(
                "当前你的配置（已按上传JSON核对）：\n"+
                "• Baudrate=460800, Working Rate=5Hz, Measurement Rate=5Hz\n"+
                "• Flash Aiding=ENABLE, Fast Fix=FULL-TIME, Long Ephemeris=ENABLE\n"+
                "• Msg Input Mask=0x3F（UART0_RTCM / UART0_AGNSS 已开）\n"+
                "• NMEA=0x827（GGA/GSA/GSV/RMC/INS），RTCM=0x1，EPH周期300s\n"+
                "后续A/B建议：NMEA增加VTG/CLK/ANT；RTCM增加EPH并设60s；RTK车辆模式测试High Precision。",
                13,false);root.addView(profile);

        root.addView(section("文件 / 日志"));
        tvPaths=t("日志路径：--",12,false);tvPaths.setTextIsSelectable(true);root.addView(tvPaths);
        root.addView(btnWide("刷新日志/缓存路径",v->refreshPaths()));

        root.addView(section("实时日志（最近约150行）"));
        tvLog=t("",11,false);tvLog.setTypeface(Typeface.MONOSPACE);tvLog.setTextIsSelectable(true);root.addView(tvLog,new LinearLayout.LayoutParams(-1,700));
        return scroll;
    }

    private TextView section(String s){TextView v=t("\n"+s,16,true);v.setPadding(0,8,0,4);return v;}
    private TextView t(String s,int sp,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private Button btn(String s,View.OnClickListener c){Button b=new Button(this);b.setText(s);b.setTextSize(12);b.setOnClickListener(c);b.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1f));return b;}
    private Button btnWide(String s,View.OnClickListener c){Button b=new Button(this);b.setText(s);b.setTextSize(13);b.setOnClickListener(c);b.setLayoutParams(new LinearLayout.LayoutParams(-1,-2));return b;}

    private void openRawFile(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");startActivityForResult(i,REQ_RAW_FILE);}
    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(req==REQ_RAW_FILE&&result==RESULT_OK&&data!=null&&data.getData()!=null&&svc!=null){
            Uri u=data.getData();
            new Thread(()->{try(InputStream in=getContentResolver().openInputStream(u);ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] b=new byte[8192];int n;int total=0;while((n=in.read(b))>0){total+=n;if(total>20*1024*1024)throw new IOException("文件>20MB");out.write(b,0,n);}int sent=svc.injectRaw(out.toByteArray());runOnUiThread(()->Toast.makeText(this,"已注入 "+sent+" bytes",Toast.LENGTH_LONG).show());
            }catch(Exception e){runOnUiThread(()->Toast.makeText(this,"注入失败: "+e.getMessage(),Toast.LENGTH_LONG).show());}}).start();
        }
    }

    private void refreshPaths(){if(svc==null)return;File l=svc.getLogFile(),c=svc.getCacheFile();tvPaths.setText("日志："+(l==null?"--":l.getAbsolutePath())+"\n缓存："+(c==null?"--":c.getAbsolutePath()));}

    @Override public void onSnapshot(GnssSnapshot s){runOnUiThread(()->{
        tvFix.setText("定位："+s.fixText+"  quality="+s.fixQuality);
        tvPos.setText(String.format(Locale.US,"位置：%.7f, %.7f   H=%.2fm",s.latitude,s.longitude,s.altitudeM));
        tvMotion.setText(String.format(Locale.US,"速度/航向：%.2f km/h   %.2f°",s.speedMps*3.6f,s.bearingDeg));
        tvSat.setText(String.format(Locale.US,"卫星：used=%d visible~%d   HDOP=%.2f PDOP=%.2f VDOP=%.2f",s.satellitesUsed,s.satellitesVisible,s.hdop,s.pdop,s.vdop));
    });}
    @Override public void onStatus(String s){runOnUiThread(()->tvStatus.setText("状态："+s));}
    @Override public void onLog(String s){runOnUiThread(()->{logBuf.append(s).append('\n');String[] lines=logBuf.toString().split("\n");if(lines.length>160){logBuf.setLength(0);for(int i=lines.length-150;i<lines.length;i++)logBuf.append(lines[i]).append('\n');}tvLog.setText(logBuf.toString());});}
    @Override public void onTtff(String label,double sec){runOnUiThread(()->Toast.makeText(this,String.format(Locale.US,"TTFF %s = %.3f s",label,sec),Toast.LENGTH_LONG).show());}
    @Override public void onCacheChanged(int fresh,int all,int rtcmFresh,int cynFresh){runOnUiThread(()->{tvAux.setText("辅助缓存：fresh="+fresh+" / all="+all+"  RTCM="+rtcmFresh+"  PEPH="+cynFresh);refreshPaths();});}
}
