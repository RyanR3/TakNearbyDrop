package com.taknearbydrop.plugin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.widget.Toast;

import com.atakmap.android.data.URIHelper;
import com.atakmap.android.data.URIScheme;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.FileMapDataRef;
import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.maps.Shape;
import com.atakmap.android.menu.MapMenuButtonWidget;
import com.atakmap.android.menu.MapMenuHandler;
import com.atakmap.android.menu.MapMenuReceiver;
import com.atakmap.android.menu.MapMenuWidget;
import com.atakmap.android.missionpackage.lasso.LassoSelectionReceiver;
import com.atakmap.android.widgets.AbstractButtonWidget;
import com.atakmap.android.widgets.MapWidget;
import com.atakmap.android.widgets.WidgetIcon;
import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import gov.tak.api.widgets.IMapMenuButtonWidget;

/**
 * Map items → Nearby Drop. Four ways in, one way out (a data package built from the items):
 * <ul>
 * <li>a "Nearby Drop" button on every marker / shape radial menu;</li>
 * <li>a "Nearby Drop" action in ATAK's own lasso tool (next to Export / Delete / Send);</li>
 * <li>the pane's "Tap items on the map" mode;</li>
 * <li>the pane's "Lasso an area", which runs ATAK's lasso and sends the result directly.</li>
 * </ul>
 */
final class MapShare implements MapMenuHandler, LassoSelectionReceiver.ExternalLassoCapability {

    interface Listener {
        void onPickChanged();
    }

    private static final String TAG = "NearbyDrop.Map";
    private static final String LASSO_SELECT = "com.atakmap.android.missionpackage.lasso.SELECT";
    private static final String LASSO_DONE = "com.taknearbydrop.plugin.LASSO_DONE";

    private final MapView mapView;
    private final Context pluginContext;
    private final NearbySender sender;
    private final Listener listener;
    private final WidgetIcon radialIcon;
    /** uid -> title of the items picked so far, in tap order. */
    private final Map<String, String> picked = new LinkedHashMap<>();
    private boolean picking;

    private final BroadcastReceiver lassoDone = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Serializable uris = i.getSerializableExtra("uris");
            List<Object> items = new ArrayList<>();
            if (uris instanceof Collection)
                items.addAll((Collection<?>) uris);
            else if (uris instanceof Object[])
                Collections.addAll(items, (Object[]) uris);
            process(items);
        }
    };

    MapShare(MapView mapView, Context pluginContext, NearbySender sender, Listener listener) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        this.sender = sender;
        this.listener = listener;
        this.radialIcon = new WidgetIcon.Builder()
                .setImageRef(0, new FileMapDataRef(iconFile(R.drawable.radial_nearby)))
                .setAnchor(20, 20)
                .setSize(40, 40)
                .build();
    }

    void start() {
        MapMenuReceiver menus = MapMenuReceiver.getInstance();
        if (menus != null)
            menus.registerMapMenuHandler(this);
        LassoSelectionReceiver.registerExternalLassoCapability(this);
        AtakBroadcast.getInstance().registerReceiver(lassoDone,
                new AtakBroadcast.DocumentedIntentFilter(LASSO_DONE));
    }

    void stop() {
        stopPicking(false);
        MapMenuReceiver menus = MapMenuReceiver.getInstance();
        if (menus != null)
            menus.unregisterMapMenuHandler(this);
        LassoSelectionReceiver.unregisterExternalLassoCapability(this);
        AtakBroadcast.getInstance().unregisterReceiver(lassoDone);
    }

    // ---- Radial menu --------------------------------------------------------

    @Override
    public void updateMenu(MapItem item, MapMenuWidget menu) {
        if (menu == null || !shareable(item))
            return;
        MapMenuButtonWidget button = new MapMenuButtonWidget(mapView.getContext());
        button.setIcon(radialIcon);
        // Borrow the ring's own background and weight so the button looks like its neighbours;
        // ATAK re-spaces the ring after handlers run.
        MapWidget first = menu.getChildCount() > 0 ? menu.getChildAt(0) : null;
        if (first instanceof AbstractButtonWidget)
            button.setBackground(((AbstractButtonWidget) first).getBackground());
        if (first instanceof MapMenuButtonWidget) {
            // ATAK's layout re-spaces angles but keeps each button's own width and ring
            // radius (set from the menu XML), so a new button must match its neighbours.
            MapMenuButtonWidget n = (MapMenuButtonWidget) first;
            button.setLayoutWeight(n.getLayoutWeight());
            button.setButtonSize(n.getButtonWidth(), n.getButtonWidth());
            button.setOrientation(n.getOrientationAngle(), n.getOrientationRadius());
        }
        final String uid = item.getUID();
        button.setOnButtonClickHandler(new IMapMenuButtonWidget.OnButtonClickHandler() {
            @Override
            public boolean isSupported(Object o) {
                return true;
            }

            @Override
            public void performAction(Object o) {
                MapMenuReceiver.getInstance().hideMenu();
                send(Collections.singletonList(uid), Collections.emptyList());
            }
        });
        menu.addWidget(button);
    }

    // ---- ATAK lasso action --------------------------------------------------

    @Override
    public String getUniqueIdentifier() {
        return pluginContext.getPackageName() + ".lasso";
    }

    @Override
    public Drawable getIcon() {
        return pluginContext.getDrawable(R.drawable.ic_launcher);
    }

    @Override
    public String getTitle() {
        return pluginContext.getString(R.string.send_nearby);
    }

    /** Lasso results: MapItems and Files from ATAK's action, URI strings from our callback. */
    @Override
    public void process(List<Object> items) {
        List<String> uids = new ArrayList<>();
        List<File> files = new ArrayList<>();
        for (Object o : items) {
            if (o instanceof String) {
                String uri = (String) o;
                o = uri.startsWith(URIScheme.FILE) ? URIHelper.getFile(uri)
                        : URIHelper.getMapItem(mapView, uri);
            }
            if (o instanceof MapItem && shareable((MapItem) o))
                uids.add(((MapItem) o).getUID());
            else if (o instanceof File && ((File) o).isFile())
                files.add((File) o);
        }
        if (uids.isEmpty() && files.isEmpty()) {
            toast("Nothing in the lasso can be sent");
            return;
        }
        send(uids, files);
    }

    /** Runs ATAK's lasso tool; the selection comes back to {@link #lassoDone}. */
    void startLasso() {
        Intent i = new Intent(LASSO_SELECT);
        i.putExtra("callback", new Intent(LASSO_DONE));
        AtakBroadcast.getInstance().sendBroadcast(i);
    }

    // ---- Pane tap-to-pick ---------------------------------------------------

    void startPicking() {
        if (picking)
            return;
        picked.clear();
        picking = true;
        // A tool's usual move: take over item taps (which also keeps radial menus closed).
        MapEventDispatcher d = mapView.getMapEventDispatcher();
        d.pushListeners();
        d.clearListeners(MapEvent.ITEM_CLICK);
        d.clearListeners(MapEvent.ITEM_LONG_PRESS);
        d.addMapEventListener(MapEvent.ITEM_CLICK, this::onPickClick);
        listener.onPickChanged();
    }

    private void onPickClick(MapEvent event) {
        MapItem item = event.getItem();
        if (!shareable(item)) {
            toast("That can't be sent");
            return;
        }
        String uid = item.getUID();
        if (picked.remove(uid) == null)
            picked.put(uid, title(item));
        listener.onPickChanged();
    }

    /** Leaves pick mode; with {@code send} the picked items go out as one package. */
    void stopPicking(boolean send) {
        if (!picking)
            return;
        picking = false;
        mapView.getMapEventDispatcher().popListeners();
        if (send && !picked.isEmpty())
            send(new ArrayList<>(picked.keySet()), Collections.emptyList());
        picked.clear();
        listener.onPickChanged();
    }

    boolean isPicking() {
        return picking;
    }

    List<String> pickedTitles() {
        return new ArrayList<>(picked.values());
    }

    // ---- Shared -------------------------------------------------------------

    private void send(List<String> uids, List<File> files) {
        String name;
        if (uids.size() == 1 && files.isEmpty()) {
            MapItem item = mapView.getRootGroup().deepFindUID(uids.get(0));
            name = item != null ? title(item) : "Marker";
        } else {
            int n = uids.size() + files.size();
            name = n + " items " + callsign() + " "
                    + new SimpleDateFormat("HHmmss", Locale.US).format(new Date());
        }
        name = name.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        Log.i(TAG, "send " + uids.size() + " items + " + files.size() + " files as " + name);
        if (!sender.sendItems(name, uids, files))
            toast("Could not package those items");
    }

    private boolean shareable(MapItem item) {
        return item != null && item.getUID() != null
                && item != mapView.getSelfMarker()
                && (item instanceof PointMapItem || item instanceof Shape)
                && !item.getMetaBoolean("nevercot", false);
    }

    private static String title(MapItem item) {
        String t = item.getTitle();
        return t == null || t.isEmpty() ? item.getUID() : t;
    }

    private String callsign() {
        String c = mapView.getDeviceCallsign();
        return c == null || c.isEmpty() ? "ATAK" : c;
    }

    /**
     * Radial icons are drawn by ATAK's GL renderer, which loads them by URI; it ignores
     * base64:// and cannot read the plugin's own files, so copy the PNG into ATAK's cache.
     */
    private String iconFile(int res) {
        File f = new File(mapView.getContext().getCacheDir(), "nearbydrop_radial.png");
        Bitmap b = BitmapFactory.decodeResource(pluginContext.getResources(), res);
        try (FileOutputStream out = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (java.io.IOException e) {
            Log.w(TAG, "radial icon: " + e);
        }
        return f.getAbsolutePath();
    }

    private void toast(String s) {
        mapView.post(() -> Toast.makeText(mapView.getContext(), s, Toast.LENGTH_SHORT).show());
    }
}
