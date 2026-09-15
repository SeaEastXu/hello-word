package com.seaeast.e108gnss.location;

import android.content.Context;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.SystemClock;

import com.seaeast.e108gnss.core.GnssSnapshot;

public final class MockLocationBridge {
    private final LocationManager lm;
    private volatile boolean enabled;

    public MockLocationBridge(Context context) {
        lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    @SuppressWarnings("deprecation")
    public void setEnabled(boolean on) {
        if (on == enabled) return;
        if (on) {
            try { lm.removeTestProvider(LocationManager.GPS_PROVIDER); } catch (Exception ignored) {}
            lm.addTestProvider(LocationManager.GPS_PROVIDER,
                    false, true, false, false,
                    true, true, true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
            lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
            enabled = true;
        } else {
            try { lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false); } catch (Exception ignored) {}
            try { lm.removeTestProvider(LocationManager.GPS_PROVIDER); } catch (Exception ignored) {}
            enabled = false;
        }
    }

    @SuppressWarnings("deprecation")
    public void push(GnssSnapshot s) {
        if (!enabled || s == null || !s.fix || Double.isNaN(s.latitude) || Double.isNaN(s.longitude)) return;
        Location l = new Location(LocationManager.GPS_PROVIDER);
        l.setLatitude(s.latitude);
        l.setLongitude(s.longitude);
        if (!Double.isNaN(s.altitudeM)) l.setAltitude(s.altitudeM);
        if (!Float.isNaN(s.speedMps)) l.setSpeed(s.speedMps);
        if (!Float.isNaN(s.bearingDeg)) l.setBearing(s.bearingDeg);
        float acc = Float.isNaN(s.hdop) ? 5f : Math.max(1f, s.hdop * 3f);
        l.setAccuracy(acc);
        l.setTime(System.currentTimeMillis());
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, l);
    }

    public boolean isEnabled() { return enabled; }
}
