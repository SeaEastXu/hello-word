package com.seaeast.e108gnss.usb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Android USB-host CH340/CH341 transport for E108-GN07IS.
 *
 * v0.3.3 UIS7870/Android 13 fix:
 *  - claim interface 0 BEFORE any CH34x vendor/control transfer;
 *  - keep the same claim for the whole connection lifetime;
 *  - do not issue SET_INTERFACE for alt-setting 0 (vendor kernels may disturb usbfs claim state);
 *  - repeated bulk I/O failures abort the connection so the service can fully reopen it.
 *
 * Target line coding: 460800 8N1.
 */
public final class UsbCh340Transport {
    public interface Listener {
        void onBytes(byte[] data, int len);
        void onStatus(String s);
        void onError(String where, Throwable t);
    }

    public static final int WCH_VID = 0x1A86;
    private static final int TIMEOUT = 3000;
    private static final int LCR_ENABLE_RX = 0x80;
    private static final int LCR_ENABLE_TX = 0x40;
    private static final int LCR_CS8 = 0x03;
    private static final int SCL_DTR = 0x20;
    private static final int SCL_RTS = 0x40;

    private final UsbManager manager;
    private final Listener listener;
    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbInterface dataInterface;
    private UsbEndpoint epIn, epOut;
    private Thread readThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong rxBytes = new AtomicLong(0);
    private volatile int baudRate = 460800;
    private volatile boolean dtr = false, rts = false;
    private volatile boolean allowForceClaim = false;

    public UsbCh340Transport(UsbManager manager, Listener listener) {
        this.manager = manager;
        this.listener = listener;
    }

    public static boolean looksLikeCh34x(UsbDevice d) {
        if (d == null || d.getVendorId() != WCH_VID) return false;
        for (int i=0;i<d.getInterfaceCount();i++) {
            UsbInterface inf = d.getInterface(i);
            boolean in=false,out=false;
            for (int j=0;j<inf.getEndpointCount();j++) {
                UsbEndpoint ep=inf.getEndpoint(j);
                if (ep.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.getDirection()==UsbConstants.USB_DIR_IN) in=true; else out=true;
                }
            }
            if (in && out) return true;
        }
        return false;
    }

    private static UsbInterface findDataInterface(UsbDevice d) {
        for (int i=0;i<d.getInterfaceCount();i++) {
            UsbInterface inf=d.getInterface(i);
            boolean in=false,out=false;
            for (int j=0;j<inf.getEndpointCount();j++) {
                UsbEndpoint ep=inf.getEndpoint(j);
                if(ep.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if(ep.getDirection()==UsbConstants.USB_DIR_IN) in=true; else out=true;
                }
            }
            if(in&&out) return inf;
        }
        return null;
    }

    public synchronized void open(UsbDevice d, int baud) throws IOException {
        open(d, baud, false);
    }

    public synchronized void open(UsbDevice d, int baud, boolean allowForceClaim) throws IOException {
        close();
        this.allowForceClaim = allowForceClaim;
        if (d == null) throw new IOException("USB device is null");
        if (!manager.hasPermission(d)) throw new IOException("No USB permission");

        UsbInterface inf=findDataInterface(d);
        if(inf==null) throw new IOException("No CH34x bulk data interface");
        UsbEndpoint in=null,out=null;
        for(int j=0;j<inf.getEndpointCount();j++) {
            UsbEndpoint ep=inf.getEndpoint(j);
            if(ep.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if(ep.getDirection()==UsbConstants.USB_DIR_IN) in=ep; else out=ep;
            }
        }
        if(in==null||out==null) throw new IOException("No bulk IN/OUT endpoint found");

        UsbDeviceConnection c = manager.openDevice(d);
        if (c == null) throw new IOException("UsbManager.openDevice returned null");
        this.device=d;
        this.connection=c;
        this.dataInterface=inf;
        this.epIn=in;
        this.epOut=out;
        this.baudRate=baud;
        this.rxBytes.set(0);

        try {
            claimDataInterfaceOrThrow();
            initializeRelaxed();
            setParameters(baud);
            setControlLines();

            listener.onStatus(String.format(Locale.US,
                    "CH34x CLAIMED-FIRST %s id=%d if=%d IN=0x%02X OUT=0x%02X @ %d 8N1",
                    d.getDeviceName(),d.getDeviceId(),inf.getId(),in.getAddress(),out.getAddress(),baud));
            startReader();
        } catch (Throwable t) {
            close();
            if(t instanceof IOException) throw (IOException)t;
            throw new IOException("CH34x open failed",t);
        }
    }

    private void claimDataInterfaceOrThrow() throws IOException {
        UsbDeviceConnection c=connection;
        UsbInterface inf=dataInterface;
        if(c==null||inf==null) throw new IOException("USB connection/interface missing");
        boolean ok=false;
        try { ok=c.claimInterface(inf,false); } catch(Throwable ignored) {}
        if(!ok && allowForceClaim) {
            try { ok=c.claimInterface(inf,true); } catch(Throwable ignored) {}
        }
        if(!ok) throw new IOException("Cannot claim USB interface "+inf.getId()+" (busy/owned by another app; forceClaim="+allowForceClaim+")");
    }

    private int controlOut(int request, int value, int index) {
        int type = UsbConstants.USB_TYPE_VENDOR | UsbConstants.USB_DIR_OUT;
        return connection.controlTransfer(type,request,value,index,null,0,TIMEOUT);
    }
    private int controlIn(int request, int value, int index, byte[] dst) {
        int type = UsbConstants.USB_TYPE_VENDOR | UsbConstants.USB_DIR_IN;
        return connection.controlTransfer(type,request,value,index,dst,dst.length,TIMEOUT);
    }

    private void initializeRelaxed() throws IOException {
        byte[] two = new byte[2];
        int r = controlIn(0x5f,0,0,two);
        if (r < 0) throw new IOException("CH34x init#1 failed");
        if (controlOut(0xa1,0,0) < 0) throw new IOException("CH34x init#2 failed");
        setBaudRate(9600);
        controlIn(0x95,0x2518,0,two);
        if (controlOut(0x9a,0x2518,LCR_ENABLE_RX|LCR_ENABLE_TX|LCR_CS8) < 0)
            throw new IOException("CH34x init LCR failed");
        controlIn(0x95,0x0706,0,two);
        if (controlOut(0xa1,0x501f,0xd90a) < 0) throw new IOException("CH34x init#7 failed");
        setBaudRate(9600);
    }

    private void setBaudRate(int baud) throws IOException {
        if (baud <= 0) throw new IllegalArgumentException("baud");
        long factor, divisor;
        if (baud == 921600) {
            divisor = 7; factor = 0xf300;
        } else {
            final long BASE = 1532620800L;
            factor = BASE / baud;
            divisor = 3;
            while (factor > 0xfff0 && divisor > 0) { factor >>= 3; divisor--; }
            if (factor > 0xfff0) throw new IOException("Unsupported CH34x baud " + baud);
            factor = 0x10000 - factor;
        }
        divisor |= 0x0080;
        int val1 = (int)((factor & 0xff00) | divisor);
        int val2 = (int)(factor & 0xff);
        if (controlOut(0x9a,0x1312,val1) < 0) throw new IOException("CH34x baud stage1 failed");
        if (controlOut(0x9a,0x0f2c,val2) < 0) throw new IOException("CH34x baud stage2 failed");
    }

    public synchronized void setParameters(int baud) throws IOException {
        setBaudRate(baud);
        int lcr = LCR_ENABLE_RX|LCR_ENABLE_TX|LCR_CS8;
        if (controlOut(0x9a,0x2518,lcr) < 0) throw new IOException("CH34x 8N1 failed");
        baudRate = baud;
    }

    public synchronized void setDtrRts(boolean dtr, boolean rts) throws IOException {
        this.dtr=dtr; this.rts=rts; setControlLines();
    }
    private void setControlLines() throws IOException {
        int bits=(dtr?SCL_DTR:0)|(rts?SCL_RTS:0);
        if (controlOut(0xa4,(~bits)&0xFFFF,0)<0) throw new IOException("CH34x control lines failed");
    }

    private void startReader() {
        running.set(true);
        readThread = new Thread(() -> {
            byte[] buf = new byte[4096];
            int consecutiveFailures=0;
            while (running.get()) {
                try {
                    UsbDeviceConnection c = connection;
                    UsbEndpoint in = epIn;
                    if (c == null || in == null) break;
                    int n = c.bulkTransfer(in,buf,buf.length,1000);
                    if (n > 0) {
                        consecutiveFailures=0;
                        rxBytes.addAndGet(n);
                        byte[] copy = new byte[n];
                        System.arraycopy(buf,0,copy,0,n);
                        listener.onBytes(copy,n);
                    } else if (running.get()) {
                        consecutiveFailures++;
                        if(consecutiveFailures>=3) {
                            throw new IOException("bulk read failed result="+n+" x"+consecutiveFailures+"; full reopen required");
                        }
                        Thread.sleep(80);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); break;
                } catch (Throwable t) {
                    if (running.get()) listener.onError("USB read",t);
                    break;
                }
            }
        },"e108-ch340-reader");
        readThread.start();
    }

    public synchronized int write(byte[] data, int timeoutMs) throws IOException {
        if (connection == null || epOut == null) throw new IOException("USB not open");
        int off=0;
        while (off < data.length) {
            int chunk=Math.min(4096,data.length-off);
            byte[] b=new byte[chunk]; System.arraycopy(data,off,b,0,chunk);
            int n=connection.bulkTransfer(epOut,b,chunk,Math.max(timeoutMs,100));
            if (n <= 0) {
                throw new IOException("USB write failed at "+off+" result="+n+"; reconnect required");
            }
            off += n;
        }
        return off;
    }

    public synchronized void close() {
        running.set(false);
        Thread t=readThread;
        if (t != null) t.interrupt();
        readThread=null;
        if(t!=null && t!=Thread.currentThread()) {
            try { t.join(250); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (connection != null) {
            if(dataInterface!=null) try { connection.releaseInterface(dataInterface); } catch(Throwable ignored) {}
            try { connection.close(); } catch (Throwable ignored) {}
        }
        connection=null; device=null; dataInterface=null; epIn=null; epOut=null;
    }

    public boolean isOpen() { return connection != null && running.get(); }
    public UsbDevice getDevice() { return device; }
    public int getBaudRate() { return baudRate; }
    public long getRxBytes() { return rxBytes.get(); }
}
