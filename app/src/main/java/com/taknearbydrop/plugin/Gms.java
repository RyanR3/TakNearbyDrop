package com.taknearbydrop.plugin;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailabilityLight;

import java.util.ArrayList;
import java.util.List;

/**
 * Makes Google Play services usable from inside ATAK's process.
 *
 * ATAK ships no Play services code, so its manifest has no
 * {@code com.google.android.gms.version} meta-data. Play services reads that tag from the
 * CALLING package's manifest (context.getPackageName()) the first time any API connects, and
 * with ATAK's context it reads 0 and throws GooglePlayServicesMissingManifestValueException on
 * its handler thread — which would take ATAK down. The reader caches its result once per
 * classloader, so we prime it with a context that reports the plugin's package: the plugin's
 * merged manifest carries the tag. Every later call (and the binder calls to Play services)
 * then use ATAK's real context, whose UID owns the permissions.
 */
final class Gms {

    private static final String TAG = "NearbyDrop.Gms";
    private static boolean primed;

    private Gms() {
    }

    /** Prime Play services' manifest-version cache. Returns the version it read (0 = failed). */
    static synchronized int prime(Context atakApp, final String pluginPackage) {
        if (primed)
            return com.google.android.gms.common.internal.zzah.zza(atakApp);
        Context asPlugin = new ContextWrapper(atakApp) {
            @Override
            public String getPackageName() {
                return pluginPackage;
            }
        };
        int v = 0;
        try {
            // play-services-basement's MetadataValueReader (obfuscated name, pinned by our
            // dependency version). Reads metaData of getPackageName() once and caches it.
            v = com.google.android.gms.common.internal.zzah.zza(asPlugin);
        } catch (Throwable t) {
            Log.e(TAG, "prime failed", t);
        }
        primed = v != 0;
        Log.i(TAG, "primed gms.version=" + v);
        return v;
    }

    /** Human-readable availability of Play services, checked with ATAK's context. */
    static String availability(Context atakApp) {
        try {
            int r = GoogleApiAvailabilityLight.getInstance()
                    .isGooglePlayServicesAvailable(atakApp);
            return r == ConnectionResult.SUCCESS ? "OK" : "code " + r;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** Runtime permissions Nearby needs that ATAK's UID does NOT currently hold. */
    static List<String> missingPermissions(Context atakApp) {
        List<String> need = new ArrayList<>();
        need.add("android.permission.ACCESS_FINE_LOCATION");
        if (Build.VERSION.SDK_INT >= 31) {
            need.add("android.permission.BLUETOOTH_SCAN");
            need.add("android.permission.BLUETOOTH_ADVERTISE");
            need.add("android.permission.BLUETOOTH_CONNECT");
        }
        if (Build.VERSION.SDK_INT >= 33)
            need.add("android.permission.NEARBY_WIFI_DEVICES");
        List<String> missing = new ArrayList<>();
        for (String p : need) {
            if (atakApp.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED)
                missing.add(p.substring(p.lastIndexOf('.') + 1));
        }
        return missing;
    }
}
