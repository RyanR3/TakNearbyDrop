package com.taknearbydrop.plugin;

import android.content.Context;
import android.graphics.drawable.Drawable;

import com.atakmap.android.data.URIHelper;
import com.atakmap.android.data.URIScheme;
import com.atakmap.android.importexport.send.MissionPackageSender;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.missionpackage.api.MissionPackageApi;
import com.atakmap.android.missionpackage.MissionPackageMapComponent;
import com.atakmap.android.missionpackage.file.MissionPackageManifest;
import com.atakmap.android.missionpackage.file.task.MissionPackageBaseTask;
import com.atakmap.coremap.log.Log;

import java.io.File;
import java.util.Collection;

/**
 * "Nearby Drop" in ATAK's send dialog. Covers everything that dialog can send:
 * <ul>
 * <li>files and saved data packages ({@code file://}, saved {@code mpm://}) go as they are;</li>
 * <li>map items — a single marker's Send, or a lasso / multi-select Send — arrive as an
 * unsaved data package. Extending {@link MissionPackageSender} is what makes the dialog hand
 * us that package; we let ATAK save it to a zip (exactly as a contact send does) and drop
 * the zip, so the receiver imports the markers like any server-delivered package.</li>
 * </ul>
 */
final class NearbySender extends MissionPackageSender {

    private static final String TAG = "NearbyDrop.Sender";

    interface Target {
        void sendNearby(File f);
    }

    private final Context pluginContext;
    private final Target target;

    NearbySender(MapView mapView, Context pluginContext, Target target) {
        super(mapView);
        this.pluginContext = pluginContext;
        this.target = target;
    }

    @Override
    public String getName() {
        return pluginContext.getString(R.string.send_nearby);
    }

    @Override
    public Drawable getIcon() {
        return pluginContext.getDrawable(R.drawable.ic_launcher);
    }

    @Override
    public boolean isSupported(String uri) {
        return uri != null && (uri.startsWith(URIScheme.FILE) || uri.startsWith(URIScheme.MPM));
    }

    @Override
    public boolean sendContent(String uri, Callback callback) {
        if (uri != null && uri.startsWith(URIScheme.MPM)) {
            MissionPackageManifest m = URIHelper.getManifest(uri);
            if (m == null)
                return done(uri, false, callback);
            if (!m.pathExists())
                return sendMissionPackage(m, null, callback);
        }
        File f = resolve(uri);
        boolean ok = f != null && f.isFile();
        if (ok)
            target.sendNearby(f);
        return done(uri, ok, callback);
    }

    @Override
    public boolean sendMissionPackage(MissionPackageManifest manifest,
            MissionPackageBaseTask.Callback mpCallback, Callback callback) {
        String uri = URIHelper.getURI(manifest);
        if (manifest == null || manifest.isEmpty())
            return done(uri, false, callback);
        Log.i(TAG, "saving " + manifest.getName() + " (" + manifest.getMapItemCount()
                + " map items, " + manifest.getFileCount() + " files)");
        MissionPackageMapComponent.getInstance().getFileIO().save(manifest,
                (task, success) -> {
                    MissionPackageManifest saved = task != null ? task.getManifest() : manifest;
                    File f = saved != null ? new File(saved.getPath()) : null;
                    boolean ok = success && f != null && f.isFile();
                    Log.i(TAG, "saved " + (ok ? f : "FAILED"));
                    if (ok)
                        target.sendNearby(f);
                    if (mpCallback != null)
                        mpCallback.onMissionPackageTaskComplete(task, ok);
                    done(uri, ok, callback);
                });
        return true;
    }

    /** Packages map items (by uid) and files into one data package and drops it. */
    boolean sendItems(String name, Collection<String> uids, Collection<File> files) {
        MissionPackageManifest m = MissionPackageApi.CreateTempManifest(name);
        for (String uid : uids)
            m.addMapItem(uid);
        for (File f : files)
            m.addFile(f, null);
        return sendMissionPackage(m, null, null);
    }

    private boolean done(String uri, boolean ok, Callback callback) {
        if (callback != null)
            callback.onSentContent(this, uri, ok);
        return ok;
    }

    /** file:// → the file; mpm:// → the data package's zip (only once it has been saved). */
    static File resolve(String uri) {
        if (uri == null)
            return null;
        if (uri.startsWith(URIScheme.MPM)) {
            MissionPackageManifest m = URIHelper.getManifest(uri);
            return m != null && m.pathExists() ? new File(m.getPath()) : null;
        }
        return URIHelper.getFile(uri);
    }
}
