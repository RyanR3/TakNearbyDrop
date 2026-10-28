package com.taknearbydrop.plugin;

import android.content.Context;
import android.graphics.drawable.Drawable;

import com.atakmap.android.data.URIHelper;
import com.atakmap.android.data.URIScheme;
import com.atakmap.android.importexport.send.MissionPackageSender;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.missionpackage.api.MissionPackageApi;
import com.atakmap.android.missionpackage.file.MissionPackageBuilder;
import com.atakmap.android.missionpackage.file.MissionPackageManifest;
import com.atakmap.android.missionpackage.file.task.MissionPackageBaseTask;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;

import java.io.File;
import java.util.Collection;

/**
 * "Nearby Drop" in ATAK's send dialog. Covers everything that dialog can send:
 * <ul>
 * <li>files and saved data packages ({@code file://}, saved {@code mpm://}) go as they are;</li>
 * <li>map items arrive as an unsaved data package (extending {@link MissionPackageSender} is
 * what makes the dialog hand it to us). We build the zip with ATAK's own builder into a
 * scratch folder ATAK does not watch, so — like a send to a contact — the sender's Data
 * Packages list gets no copy; the receiver imports it like any server-delivered package.</li>
 * </ul>
 */
final class NearbySender extends MissionPackageSender {

    private static final String TAG = "NearbyDrop.Sender";

    interface Target {
        void sendNearby(File f);
    }

    private final Context pluginContext;
    private final Target target;
    /** Packages built from map items live here only while they are being offered. */
    private final File outgoing = FileSystemUtils.getItem("tools/nearbydrop/outgoing");

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
        Log.i(TAG, "building " + manifest.getName() + " (" + manifest.getMapItemCount()
                + " map items, " + manifest.getFileCount() + " files)");
        if (!outgoing.isDirectory() && !outgoing.mkdirs())
            Log.w(TAG, "cannot create " + outgoing);
        String file = manifest.getName().replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        manifest.setPath(new File(outgoing, file.isEmpty() ? "package.zip" : file + ".zip")
                .getAbsolutePath());
        new Thread(() -> {
            String built = null;
            try {
                built = new MissionPackageBuilder(NO_PROGRESS, manifest, _mapView.getRootGroup())
                        .build();
            } catch (Exception e) {
                Log.e(TAG, "build failed", e);
            }
            File f = built != null ? new File(built) : null;
            boolean ok = f != null && f.isFile();
            Log.i(TAG, "built " + (ok ? f + " (" + f.length() + " B)" : "FAILED"));
            _mapView.post(() -> {
                if (ok)
                    target.sendNearby(f);
                if (mpCallback != null)
                    mpCallback.onMissionPackageTaskComplete(null, ok);
                done(uri, ok, callback);
            });
        }, "NearbyDrop-build").start();
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

    /** Deletes packages built for earlier sends; nothing else is ever kept there. */
    void clearOutgoing() {
        File[] old = outgoing.listFiles();
        if (old != null)
            for (File f : old)
                FileSystemUtils.deleteFile(f);
    }

    private static final MissionPackageBuilder.Progress NO_PROGRESS =
            new MissionPackageBuilder.Progress() {
                @Override
                public void publish(int progress) {
                }

                @Override
                public void cancel(String reason) {
                    Log.w(TAG, "build cancelled: " + reason);
                }

                @Override
                public boolean isCancelled() {
                    return false;
                }
            };

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
