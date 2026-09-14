package com.seaeast.e108gnss.core;

public final class GnssSnapshot {
    public boolean connected;
    public boolean fix;
    public int fixQuality;
    public String fixText = "NO FIX";
    public double latitude = Double.NaN;
    public double longitude = Double.NaN;
    public double altitudeM = Double.NaN;
    public float speedMps = Float.NaN;
    public float bearingDeg = Float.NaN;
    public int satellitesUsed = 0;
    public int satellitesVisible = 0;
    public float hdop = Float.NaN;
    public float pdop = Float.NaN;
    public float vdop = Float.NaN;
    public String antennaRaw = "";
    public String clockRaw = "";
    public String insRaw = "";
    public long lastUpdateElapsedMs = 0L;
    public long lastFixWallTimeMs = 0L;
    public String lastNmea = "";

    public GnssSnapshot copy() {
        GnssSnapshot s = new GnssSnapshot();
        s.connected = connected;
        s.fix = fix;
        s.fixQuality = fixQuality;
        s.fixText = fixText;
        s.latitude = latitude;
        s.longitude = longitude;
        s.altitudeM = altitudeM;
        s.speedMps = speedMps;
        s.bearingDeg = bearingDeg;
        s.satellitesUsed = satellitesUsed;
        s.satellitesVisible = satellitesVisible;
        s.hdop = hdop;
        s.pdop = pdop;
        s.vdop = vdop;
        s.antennaRaw = antennaRaw;
        s.clockRaw = clockRaw;
        s.insRaw = insRaw;
        s.lastUpdateElapsedMs = lastUpdateElapsedMs;
        s.lastFixWallTimeMs = lastFixWallTimeMs;
        s.lastNmea = lastNmea;
        return s;
    }
}
