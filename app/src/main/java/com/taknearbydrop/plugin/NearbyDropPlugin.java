package com.taknearbydrop.plugin;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.data.URIContentManager;
import com.atakmap.android.importexport.ImportExportMapComponent;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.filesystem.FileSystemUtils;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

/**
 * Nearby Drop — "AirDrop for data packages". One phone offers map items, a data package or
 * any file and every nearby phone on the same team code can pull it straight over Bluetooth /
 * Wi-Fi via Google Nearby Connections, no server, no mesh.
 *
 * The pane also keeps an activity log with connect time, link type and throughput per peer.
 */
public class NearbyDropPlugin implements IPlugin, DropLink.Listener, NearbySender.Target,
        MapShare.Listener {

    private static final String TAG = "NearbyDrop";
    private static final String PREF_TEAM = "nearbydrop.team";
    private static final String PREF_WIFI = "nearbydrop.wifi_upgrade";
    private static final String PREF_AUTO = "nearbydrop.auto_accept";
    private static final int MAX_LOG_LINES = 200;

    private final IServiceController serviceController;
    private Context pluginContext;
    private IHostUIService uiService;
    private ToolbarItem toolbarItem;
    private Pane pane;

    private MapView mapView;
    private SharedPreferences prefs;   // ATAK's host prefs: survive plugin reinstalls
    private DropLink link;
    private NearbySender sender;
    private MapShare mapShare;
    private String diagnostics = "";
    private boolean healthy = true;

    private EditText teamEdit;
    private CheckBox wifiBox, autoBox;
    private View idleActions, sessionCard, pickCard, settingsBox, logBox;
    private TextView teamLabel, sessionTitle, sessionSub, peersTitle, peersEmpty, logText;
    private TextView pickTitle, pickSub;
    private TextView settingsToggle, logToggle;
    private LinearLayout peersContainer;
    /** endpointId -> its row, updated in place so progress doesn't rebuild views. */
    private final Map<String, View> peerRows = new HashMap<>();
    private final List<String> logLines = new ArrayList<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);

    public NearbyDropPlugin(IServiceController serviceController) {
        this.serviceController = serviceController;
        PluginContextProvider ctxProvider =
                serviceController.getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }
        uiService = serviceController.getService(IHostUIService.class);

        Drawable icon = pluginContext.getResources().getDrawable(R.drawable.ic_launcher);
        toolbarItem = new ToolbarItem.Builder(pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(icon, Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
                .setListener(new ToolbarItemAdapter() {
                    @Override
                    public void onClick(ToolbarItem item) {
                        showPane();
                    }
                })
                .setIdentifier(pluginContext.getPackageName())
                .build();
    }

    // ---- IPlugin ------------------------------------------------------------

    @Override
    public void onStart() {
        if (uiService == null)
            return;
        uiService.addToolbarItem(toolbarItem);
        mapView = MapView.getMapView();
        if (mapView == null)
            return;
        Context atakApp = mapView.getContext().getApplicationContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(atakApp);

        // Must run before the first Nearby call — see Gms.
        int gmsVersion = Gms.prime(atakApp, pluginContext.getPackageName());
        List<String> missing = Gms.missingPermissions(atakApp);
        String gms = Gms.availability(atakApp);
        healthy = "OK".equals(gms) && gmsVersion != 0 && missing.isEmpty();
        diagnostics = "Play services: " + gms + " (manifest " + gmsVersion + ")\nATAK permissions: "
                + (missing.isEmpty() ? "all granted" : "MISSING " + missing);
        Log.i(TAG, diagnostics.replace('\n', ' '));

        File incoming = FileSystemUtils.getItem("tools/nearbydrop");
        if (!incoming.isDirectory() && !incoming.mkdirs())
            Log.w(TAG, "cannot create " + incoming);
        link = new DropLink(atakApp, incoming, this);

        sender = new NearbySender(mapView, pluginContext, this);
        URIContentManager.getInstance().registerSender(sender);
        mapShare = new MapShare(mapView, pluginContext, sender, this);
        mapShare.start();
    }

    @Override
    public void onStop() {
        if (uiService != null)
            uiService.removeToolbarItem(toolbarItem);
        if (sender != null) {
            URIContentManager.getInstance().unregisterSender(sender);
            sender.clearOutgoing();
        }
        if (mapShare != null)
            mapShare.stop();
        if (link != null)
            link.stop();
    }

    // ---- Pane -------------------------------------------------------------

    private void showPane() {
        if (pane == null) {
            View v = PluginLayoutInflater.inflate(pluginContext, R.layout.main_layout, null);
            teamLabel = v.findViewById(R.id.team_label);
            idleActions = v.findViewById(R.id.idle_actions);
            sessionCard = v.findViewById(R.id.session_card);
            pickCard = v.findViewById(R.id.pick_card);
            pickTitle = v.findViewById(R.id.pick_title);
            pickSub = v.findViewById(R.id.pick_sub);
            sessionTitle = v.findViewById(R.id.session_title);
            sessionSub = v.findViewById(R.id.session_sub);
            peersTitle = v.findViewById(R.id.peers_title);
            peersEmpty = v.findViewById(R.id.peers_empty);
            peersContainer = v.findViewById(R.id.peers);
            settingsBox = v.findViewById(R.id.settings);
            settingsToggle = v.findViewById(R.id.settings_toggle);
            logBox = v.findViewById(R.id.log_box);
            logToggle = v.findViewById(R.id.log_toggle);
            logText = v.findViewById(R.id.log);
            teamEdit = v.findViewById(R.id.team_code);
            wifiBox = v.findViewById(R.id.wifi_upgrade);
            autoBox = v.findViewById(R.id.auto_accept);
            ((TextView) v.findViewById(R.id.diagnostics)).setText(diagnostics);

            TextView warning = v.findViewById(R.id.warning);
            if (!healthy) {
                warning.setText("Nearby is not available on this device.\n" + diagnostics);
                warning.setVisibility(View.VISIBLE);
            }

            teamEdit.setText(prefs.getString(PREF_TEAM, ""));
            wifiBox.setChecked(prefs.getBoolean(PREF_WIFI, true));
            autoBox.setChecked(prefs.getBoolean(PREF_AUTO, false));
            teamEdit.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence c, int a, int b, int d) {
                }

                @Override
                public void onTextChanged(CharSequence c, int a, int b, int d) {
                }

                @Override
                public void afterTextChanged(android.text.Editable e) {
                    saveSettings();
                    refresh();
                }
            });
            wifiBox.setOnCheckedChangeListener((b, on) -> saveSettings());
            autoBox.setOnCheckedChangeListener((b, on) -> saveSettings());

            v.findViewById(R.id.tile_send).setOnClickListener(x -> chooseWhatToSend());
            v.findViewById(R.id.btn_pick_cancel).setOnClickListener(x -> mapShare.stopPicking(false));
            v.findViewById(R.id.btn_pick_send).setOnClickListener(x -> mapShare.stopPicking(true));
            v.findViewById(R.id.tile_receive).setOnClickListener(x ->
                    link.startReceiving(callsign(), team(), wifiBox.isChecked(),
                            autoBox.isChecked()));
            v.findViewById(R.id.btn_stop).setOnClickListener(x -> link.stop());
            settingsToggle.setOnClickListener(x -> toggle(settingsBox, settingsToggle,
                    R.string.settings_open, R.string.settings_closed));
            logToggle.setOnClickListener(x -> toggle(logBox, logToggle,
                    R.string.log_open, R.string.log_closed));
            v.findViewById(R.id.btn_clear_log).setOnClickListener(x -> {
                logLines.clear();
                logText.setText("");
            });

            pane = new PaneBuilder(v)
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();
        }
        if (!uiService.isPaneVisible(pane))
            uiService.showPane(pane, null);
        refresh();
    }

    private void toggle(View box, TextView header, int openText, int closedText) {
        boolean open = box.getVisibility() != View.VISIBLE;
        box.setVisibility(open ? View.VISIBLE : View.GONE);
        header.setText(pluginContext.getString(open ? openText : closedText));
    }

    private void saveSettings() {
        if (teamEdit == null)
            return;
        prefs.edit().putString(PREF_TEAM, team())
                .putBoolean(PREF_WIFI, wifiBox.isChecked())
                .putBoolean(PREF_AUTO, autoBox.isChecked())
                .apply();
    }

    private String team() {
        return teamEdit != null ? teamEdit.getText().toString().trim()
                : prefs.getString(PREF_TEAM, "");
    }

    private String callsign() {
        String c = mapView.getDeviceCallsign();
        return c == null || c.isEmpty() ? "ATAK" : c;
    }

    private void chooseWhatToSend() {
        String[] choices = {"Tap items on the map", "Lasso an area", "A data package…"};
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Send nearby")
                .setItems(choices, (d, which) -> {
                    if (which == 0)
                        mapShare.startPicking();
                    else if (which == 1)
                        mapShare.startLasso();
                    else
                        pickPackage();
                })
                .show();
    }

    @Override
    public void onPickChanged() {
        mapView.post(this::refresh);
    }

    /** Pick one of this device's data packages (or any file in the data package folder). */
    private void pickPackage() {
        File dir = FileSystemUtils.getItem("tools/datapackage");
        File[] files = dir.listFiles(File::isFile);
        if (files == null || files.length == 0) {
            toast("No data packages in " + dir.getPath()
                    + " — or use Send → " + pluginContext.getString(R.string.send_nearby)
                    + " from Data Packages");
            return;
        }
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++)
            names[i] = files[i].getName() + "  (" + Offer.human(files[i].length()) + ")";
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Send nearby")
                .setItems(names, (d, which) -> sendNearby(files[which]))
                .show();
    }

    @Override
    public void sendNearby(File f) {
        mapView.post(() -> {
            showPane();
            saveSettings();
            link.startSending(f, callsign(), team(), wifiBox == null || wifiBox.isChecked());
        });
    }

    // ---- DropLink.Listener --------------------------------------------------

    @Override
    public void onLog(String line) {
        mapView.post(() -> {
            logLines.add(clock.format(new Date()) + "  " + line);
            while (logLines.size() > MAX_LOG_LINES)
                logLines.remove(0);
            if (logText != null)
                logText.setText(String.join("\n", logLines));
        });
    }

    @Override
    public void onStateChanged() {
        mapView.post(() -> {
            // A send session ended: drop any package we built from map items for it.
            if (link.mode() == DropLink.Mode.IDLE && sender != null)
                sender.clearOutgoing();
            refresh();
        });
    }

    @Override
    public void onOfferFound(String endpointId, Offer o) {
        mapView.post(() -> {
            if (pane != null && uiService.isPaneVisible(pane))
                return;     // the pane shows the offer with its own Accept button
            new AlertDialog.Builder(mapView.getContext())
                .setTitle("Nearby data package")
                .setMessage(o.from + " is sharing\n\n" + shown(o.name) + "\n" + Offer.human(o.size))
                .setPositiveButton("Accept", (d, w) -> link.accept(endpointId))
                .setNegativeButton("Ignore", (d, w) -> link.ignore(endpointId))
                .show();
        });
    }

    @Override
    public void onReceived(Offer o, File file) {
        mapView.post(() -> {
            if (file.getName().endsWith(".cot") && dispatchCot(file)) {
                toast("Received " + shown(o.name) + " from " + o.from);
                return;
            }
            toast("Received " + o.name + " from " + o.from + " — importing");
            // Same path a user-opened file takes: data packages go to the Data Package
            // importer, KML/images/etc. to theirs.
            Intent i = new Intent(ImportExportMapComponent.USER_HANDLE_IMPORT_FILE_ACTION);
            i.putExtra("filepath", file.getAbsolutePath());
            i.putExtra("importInPlace", false);
            i.putExtra("promptOnMultipleMatch", true);
            i.putExtra("zoomToFile", false);
            AtakBroadcast.getInstance().sendBroadcast(i);
        });
    }

    /**
     * A single marker arrives as a bare CoT event. Hand it to ATAK's internal dispatcher — the
     * same path a marker sent to this user through a server takes — so it lands on the map
     * without a Data Packages entry.
     */
    private boolean dispatchCot(File file) {
        try {
            String xml = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            CotEvent event = CotEvent.parse(xml);
            if (event == null || !event.isValid()) {
                Log.w(TAG, "not a valid CoT event: " + file);
                return false;
            }
            CotMapComponent.getInternalDispatcher().dispatch(event);
            Log.i(TAG, "dispatched CoT " + event.getUID() + " (" + event.getType() + ")");
            FileSystemUtils.deleteFile(file);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "CoT dispatch failed", e);
            return false;
        }
    }

    private void refresh() {
        if (sessionCard == null || link == null)
            return;
        DropLink.Mode m = link.mode();
        String t = team();
        teamLabel.setText(callsign() + "  ·  " + (t.isEmpty() ? "Open to anyone nearby"
                : "Team \u201c" + t + "\u201d"));
        boolean idle = m == DropLink.Mode.IDLE;
        boolean picking = idle && mapShare != null && mapShare.isPicking();
        idleActions.setVisibility(idle && !picking ? View.VISIBLE : View.GONE);
        pickCard.setVisibility(picking ? View.VISIBLE : View.GONE);
        if (picking) {
            List<String> titles = mapShare.pickedTitles();
            pickTitle.setText(titles.isEmpty() ? "Tap items on the map"
                    : titles.size() + (titles.size() == 1 ? " item selected" : " items selected"));
            pickSub.setText(titles.isEmpty() ? "Markers, routes and shapes. Tap again to remove."
                    : String.join(", ", titles));
        }
        sessionCard.setVisibility(idle ? View.GONE : View.VISIBLE);
        teamEdit.setEnabled(idle);

        List<DropLink.Link> links = link.links();
        if (m == DropLink.Mode.SENDING) {
            Offer o = link.currentOffer();
            int done = link.doneCount();
            sessionTitle.setText(shown(o.name));
            sessionSub.setText(Offer.human(o.size) + "  ·  "
                    + (done == 0 ? "Waiting for nearby phones…"
                            : done + (done == 1 ? " phone has it" : " phones have it")));
            peersTitle.setText("Receivers");
            peersEmpty.setText("Phones that tap Receive will show up here.");
        } else if (m == DropLink.Mode.RECEIVING) {
            sessionTitle.setText("Looking for packages…");
            sessionSub.setText(autoBox.isChecked() ? "Packages from your team download automatically"
                    : "Keep this open — offers appear below");
            peersTitle.setText("Nearby packages");
            peersEmpty.setText("Nothing shared nearby yet.");
        }
        peersTitle.setVisibility(idle ? View.GONE : View.VISIBLE);
        peersEmpty.setVisibility(!idle && links.isEmpty() ? View.VISIBLE : View.GONE);

        // Update rows in place; drop rows whose link went away.
        Map<String, DropLink.Link> byId = new HashMap<>();
        for (DropLink.Link l : links)
            byId.put(l.endpointId, l);
        for (Iterator<Map.Entry<String, View>> it = peerRows.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, View> e = it.next();
            if (!byId.containsKey(e.getKey())) {
                peersContainer.removeView(e.getValue());
                it.remove();
            }
        }
        for (DropLink.Link l : links) {
            View row = peerRows.get(l.endpointId);
            if (row == null) {
                row = PluginLayoutInflater.inflate(pluginContext, R.layout.peer_row, null);
                final String ep = l.endpointId;
                row.findViewById(R.id.peer_accept).setOnClickListener(x -> link.accept(ep));
                row.findViewById(R.id.peer_ignore).setOnClickListener(x -> link.ignore(ep));
                peersContainer.addView(row);
                peerRows.put(l.endpointId, row);
            }
            bindRow(row, l, m);
        }
    }

    private void bindRow(View row, DropLink.Link l, DropLink.Mode m) {
        TextView name = row.findViewById(R.id.peer_name);
        TextView state = row.findViewById(R.id.peer_state);
        TextView detail = row.findViewById(R.id.peer_detail);
        ProgressBar bar = row.findViewById(R.id.peer_progress);
        View actions = row.findViewById(R.id.peer_actions);

        boolean receiving = m == DropLink.Mode.RECEIVING && l.offer != null;
        name.setText(receiving ? shown(l.offer.name) : l.name);

        String s = l.status;
        int pct = l.percent();
        String label;
        int color = pluginContext.getResources().getColor(R.color.subtle_gray);
        if (s.startsWith("done")) {
            label = receiving ? "Imported ✓" : "Delivered ✓";
            color = pluginContext.getResources().getColor(R.color.ok_green);
        } else if (s.contains("fail") || s.startsWith("rejected") || "disconnected".equals(s)) {
            label = "Failed";
            color = pluginContext.getResources().getColor(R.color.err_red);
        } else if ("sending".equals(s) || "receiving".equals(s)) {
            label = Math.max(0, pct) + "%";
            color = pluginContext.getResources().getColor(R.color.accent_blue);
        } else if ("sent".equals(s)) {
            label = "Sent";
        } else if ("offered".equals(s)) {
            label = Offer.human(l.offer != null ? l.offer.size : 0);
        } else {
            label = "Connecting…";
        }
        state.setText(label);
        state.setTextColor(color);

        // Second line: who/what, then the link once we know it.
        StringBuilder d = new StringBuilder(receiving ? "From " + l.name : "");
        if (l.tFileEnd > 0)
            d.append(d.length() > 0 ? "  ·  " : "").append(DropLink.rate(l));
        else if ("sending".equals(s) || "receiving".equals(s))
            d.append(d.length() > 0 ? "  ·  " : "").append(Offer.human(l.bytes))
                    .append(" of ").append(Offer.human(l.total));
        if (l.quality != com.google.android.gms.nearby.connection.BandwidthInfo.Quality.UNKNOWN)
            d.append(d.length() > 0 ? "  ·  " : "").append(l.qualityName());
        detail.setText(d);
        detail.setVisibility(d.length() > 0 ? View.VISIBLE : View.GONE);

        boolean showBar = pct >= 0;
        bar.setVisibility(showBar ? View.VISIBLE : View.GONE);
        if (showBar) {
            bar.setProgressDrawable(pluginContext.getDrawable(s.startsWith("done")
                    ? R.drawable.progress_bar_done : R.drawable.progress_bar));
            bar.setProgress(pct);
        }
        actions.setVisibility(receiving && "offered".equals(s) ? View.VISIBLE : View.GONE);
    }

    /** A single marker travels as "Name.cot"; people just see the marker's name. */
    private static String shown(String name) {
        return name.endsWith(".cot") ? name.substring(0, name.length() - 4) : name;
    }

    private void toast(String s) {
        Toast.makeText(mapView.getContext(), s, Toast.LENGTH_LONG).show();
    }
}
