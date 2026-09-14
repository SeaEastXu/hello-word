package com.seaeast.e108gnss.usb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;

import java.util.Locale;

public final class UsbDeviceFingerprint {
    public final int vid;
    public final int pid;
    public final String version;
    public final String product;
    public final String manufacturer;
    public final String interfaceSignature;

    public UsbDeviceFingerprint(int vid, int pid, String version, String product,
                                String manufacturer, String interfaceSignature) {
        this.vid = vid;
        this.pid = pid;
        this.version = clean(version);
        this.product = clean(product);
        this.manufacturer = clean(manufacturer);
        this.interfaceSignature = clean(interfaceSignature);
    }

    public static UsbDeviceFingerprint from(UsbDevice d) {
        if (d == null) return new UsbDeviceFingerprint(0,0,"","","","");
        String ver="";
        try { ver=d.getVersion(); } catch(Throwable ignored) {}
        String prod="";
        try { prod=d.getProductName(); } catch(Throwable ignored) {}
        String man="";
        try { man=d.getManufacturerName(); } catch(Throwable ignored) {}
        return new UsbDeviceFingerprint(d.getVendorId(), d.getProductId(), ver, prod, man, interfaceSig(d));
    }

    public String stableKey() {
        return String.format(Locale.US, "%04X:%04X|v=%s|if=%s",
                vid, pid, esc(version), esc(interfaceSignature));
    }

    public String shortText() {
        return String.format(Locale.US, "%04X:%04X ver=%s product=%s if=%s",
                vid,pid, emptyAsDash(version), emptyAsDash(product), emptyAsDash(interfaceSignature));
    }

    public boolean sameStableIdentity(UsbDeviceFingerprint other) {
        return other != null && stableKey().equals(other.stableKey());
    }

    public boolean looksLikeObservedE108Bridge() {
        return vid == 0x1A86 && pid == 0x7523 && "82.33".equals(version)
                && interfaceSignature.contains("255/1/2")
                && interfaceSignature.contains("B82")
                && interfaceSignature.contains("B02");
    }

    public boolean looksLikeObservedTpmsBridge() {
        return vid == 0x1A86 && pid == 0x7523 && "2.64".equals(version)
                && interfaceSignature.contains("255/1/2");
    }

    private static String interfaceSig(UsbDevice d) {
        StringBuilder sb=new StringBuilder();
        for(int i=0;i<d.getInterfaceCount();i++) {
            if(i>0) sb.append(';');
            UsbInterface inf=d.getInterface(i);
            sb.append("I").append(inf.getId()).append(':')
                    .append(inf.getInterfaceClass()).append('/')
                    .append(inf.getInterfaceSubclass()).append('/')
                    .append(inf.getInterfaceProtocol());
            for(int j=0;j<inf.getEndpointCount();j++) {
                UsbEndpoint ep=inf.getEndpoint(j);
                sb.append(',');
                if(ep.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK) sb.append('B');
                else if(ep.getType()==UsbConstants.USB_ENDPOINT_XFER_INT) sb.append('I');
                else sb.append('T').append(ep.getType());
                sb.append(String.format(Locale.US,"%02X",ep.getAddress()));
                sb.append('/').append(ep.getMaxPacketSize());
            }
        }
        return sb.toString();
    }

    private static String clean(String s) { return s==null?"":s.trim(); }
    private static String esc(String s) { return clean(s).replace("|","_").replace("\n"," ").replace("\r"," "); }
    private static String emptyAsDash(String s) { return clean(s).isEmpty()?"-":clean(s); }
}
