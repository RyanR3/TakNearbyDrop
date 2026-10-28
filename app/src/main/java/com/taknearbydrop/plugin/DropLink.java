package com.taknearbydrop.plugin;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.BandwidthInfo;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionOptions;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One sender → many receivers over Google Nearby Connections, P2P_STAR.
 *
 * The SENDER is the hub: it advertises its {@link Offer} in the endpoint info and pushes the
 * file to every receiver that connects. A RECEIVER discovers, sees the offer without
 * connecting, and connecting IS accepting — the sender starts the file as soon as the link is
 * up. Radios run only while a send or receive session is open (no always-on mesh), which keeps
 * idle battery at zero and keeps clear of MeshRelay's own Nearby mesh.
 *
 * Lessons carried over from MeshRelay's NearbyMesh: peers are tracked by endpoint id only for
 * the life of one link (ids churn on reconnect); STATUS_ALREADY_ADVERTISING / _DISCOVERING
 * (8001 / 8002) are the only failures that mean "already up"; Bluetooth going off silently ends
 * advertise/discovery, so a watchdog restarts them.
 */
final class DropLink {

    interface Listener {
        void onLog(String line);

        /** Sender: receivers changed. Receiver: offers in range changed. */
        void onStateChanged();

        /** Receiver: a file arrived complete and was saved here. */
        void onReceived(Offer offer, File file);

        /** Receiver: a new offer is in range and auto-accept is off. */
        void onOfferFound(String endpointId, Offer offer);
    }

    enum Mode { IDLE, SENDING, RECEIVING }

    private static final String TAG = "NearbyDrop";
    private static final Strategy STRATEGY = Strategy.P2P_STAR;
    private static final String SERVICE_PREFIX = "com.taknearbydrop.";
    /** A send session ends itself after this long so a forgotten send can't drain the battery. */
    private static final long SEND_TIMEOUT_MS = 15 * 60_000;
    private static final byte MSG_DONE = 'D';

    /** Per-link state + timing shown in the pane and the activity log. */
    static final class Link {
        final String endpointId;
        final String name;
        final long tFound;
        long tAccepted, tConnected, tFileStart, tFileEnd;
        long bytes, total;
        int quality = BandwidthInfo.Quality.UNKNOWN;
        String status = "found";
        Offer offer;          // receiver side: what this sender is offering
        long payloadId = -1;  // receiver side: the incoming file payload

        /** From accept (or discovery when auto-accepting) to link up; excludes dialog time. */
        long connectMs() {
            return tConnected - (tAccepted > 0 ? tAccepted : tFound);
        }

        /** 0-100, or -1 when no file transfer has started. */
        int percent() {
            if (status.startsWith("done") || "sent".equals(status))
                return 100;
            if (total <= 0 || tFileStart == 0)
                return -1;
            return (int) (bytes * 100 / total);
        }

        String detail() {
            StringBuilder sb = new StringBuilder(status);
            if (total > 0 && bytes > 0 && bytes < total)
                sb.append(' ').append(bytes * 100 / total).append('%');
            if (tFileEnd > 0)
                sb.append(" · ").append(rate(this));
            if (quality != BandwidthInfo.Quality.UNKNOWN)
                sb.append(" · ").append(qualityName());
            return sb.toString();
        }

        Link(String endpointId, String name) {
            this.endpointId = endpointId;
            this.name = name;
            this.tFound = SystemClock.elapsedRealtime();
        }

        String qualityName() {
            switch (quality) {
                case BandwidthInfo.Quality.HIGH:
                    return "Wi-Fi";
                case BandwidthInfo.Quality.MEDIUM:
                    return "Bluetooth";
                case BandwidthInfo.Quality.LOW:
                    return "Bluetooth LE";
                default:
                    return "?";
            }
        }
    }

    private final Context context;      // ATAK's application context (owns the permissions)
    private final File incomingDir;
    private final Listener listener;
    private final ConnectionsClient client;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile Mode mode = Mode.IDLE;
    private volatile boolean radioUp;
    private String serviceId;
    private String localName;
    private boolean allowWifiUpgrade;
    private boolean autoAccept;

    // Sender
    private Offer offer;
    private File file;
    private long sessionStart;

    // Both: endpointId -> Link for the current session
    private final Map<String, Link> links = new ConcurrentHashMap<>();
    // Receiver: offers already received this ATAK session (never re-download one)
    private final Set<String> receivedOfferIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
    // Receiver: incoming FILE payloads by payload id until they complete
    private final Map<Long, Payload> incoming = new ConcurrentHashMap<>();

    DropLink(Context atakApp, File incomingDir, Listener listener) {
        this.context = atakApp;
        this.incomingDir = incomingDir;
        this.listener = listener;
        this.client = Nearby.getConnectionsClient(atakApp);
    }

    Mode mode() {
        return mode;
    }

    Offer currentOffer() {
        return offer;
    }

    List<Link> links() {
        return new ArrayList<>(links.values());
    }

    /** Team code gates who sees whom: different codes = different Nearby service ids. */
    static String serviceIdFor(String teamCode) {
        String t = teamCode == null ? "" : teamCode.trim();
        if (t.isEmpty())
            return SERVICE_PREFIX + "open";
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(t.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++)
                sb.append(String.format(Locale.US, "%02x", d[i]));
            return SERVICE_PREFIX + sb;
        } catch (Exception e) {
            return SERVICE_PREFIX + Integer.toHexString(t.hashCode());
        }
    }

    // ---- Sender ---------------------------------------------------------

    void startSending(File f, String callsign, String teamCode, boolean wifiUpgrade) {
        stop();
        mode = Mode.SENDING;
        file = f;
        localName = callsign;
        serviceId = serviceIdFor(teamCode);
        allowWifiUpgrade = wifiUpgrade;
        offer = Offer.create(callsign, f.getName(), f.length());
        sessionStart = SystemClock.elapsedRealtime();
        log("SEND " + f.getName() + " (" + Offer.human(f.length()) + ") offer " + offer.id
                + " on " + serviceId + (wifiUpgrade ? "" : " [keep Wi-Fi network]"));
        startRadios();
        main.postDelayed(sendTimeout, SEND_TIMEOUT_MS);
        listener.onStateChanged();
    }

    private final Runnable sendTimeout = () -> {
        if (mode == Mode.SENDING) {
            log("send session timed out after " + SEND_TIMEOUT_MS / 60_000 + " min");
            stop();
        }
    };

    private void pushFile(Link l) {
        try {
            Payload p = Payload.fromFile(file);
            l.payloadId = p.getId();
            l.total = file.length();
            l.tFileStart = SystemClock.elapsedRealtime();
            l.status = "sending";
            client.sendPayload(l.endpointId, p)
                    .addOnFailureListener(e -> {
                        l.status = "send failed";
                        log(l.name + ": send failed: " + e.getMessage());
                        listener.onStateChanged();
                    });
        } catch (Exception e) {
            l.status = "send failed";
            log(l.name + ": cannot open file: " + e.getMessage());
        }
        listener.onStateChanged();
    }

    // ---- Receiver -------------------------------------------------------

    void startReceiving(String callsign, String teamCode, boolean wifiUpgrade, boolean auto) {
        stop();
        mode = Mode.RECEIVING;
        localName = callsign;
        serviceId = serviceIdFor(teamCode);
        allowWifiUpgrade = wifiUpgrade;
        autoAccept = auto;
        sessionStart = SystemClock.elapsedRealtime();
        log("RECEIVE on " + serviceId + (auto ? " [auto-accept]" : ""));
        startRadios();
        listener.onStateChanged();
    }

    /** Receiver: hide an offer for the rest of this session (never connects to it). */
    void ignore(String endpointId) {
        Link l = links.remove(endpointId);
        if (l != null && l.offer != null)
            receivedOfferIds.add(l.offer.id);
        listener.onStateChanged();
    }

    /** Receivers that have confirmed the current offer (sender side). */
    int doneCount() {
        int n = 0;
        for (Link l : links.values())
            if (l.status.startsWith("done"))
                n++;
        return n;
    }

    /** Receiver: accept an offer in range = connect to its sender. */
    void accept(String endpointId) {
        Link l = links.get(endpointId);
        if (l == null || mode != Mode.RECEIVING)
            return;
        l.status = "connecting";
        l.tAccepted = SystemClock.elapsedRealtime();
        ConnectionOptions opts = new ConnectionOptions.Builder()
                .setDisruptiveUpgrade(allowWifiUpgrade)
                .build();
        client.requestConnection(localName, endpointId, lifecycle, opts)
                .addOnFailureListener(e -> {
                    l.status = "connect failed";
                    log(l.name + ": connect failed: " + e.getMessage());
                    listener.onStateChanged();
                });
        listener.onStateChanged();
    }

    // ---- Lifecycle ------------------------------------------------------

    void stop() {
        if (mode == Mode.IDLE)
            return;
        main.removeCallbacks(sendTimeout);
        main.removeCallbacks(watchdog);
        try {
            client.stopAdvertising();
            client.stopDiscovery();
            client.stopAllEndpoints();
        } catch (Exception ignored) {
        }
        for (Payload p : incoming.values())
            closeQuietly(p);
        incoming.clear();
        links.clear();
        radioUp = false;
        log("stopped " + mode.name().toLowerCase(Locale.US));
        mode = Mode.IDLE;
        offer = null;
        file = null;
        listener.onStateChanged();
    }

    private void startRadios() {
        if (mode == Mode.SENDING) {
            AdvertisingOptions opts = new AdvertisingOptions.Builder()
                    .setStrategy(STRATEGY)
                    .setDisruptiveUpgrade(allowWifiUpgrade)
                    .build();
            client.startAdvertising(offer.encode(), serviceId, lifecycle, opts)
                    .addOnSuccessListener(u -> {
                        radioUp = true;
                        log("advertising");
                    })
                    .addOnFailureListener(e -> radioFailed("advertising", e, "8001"));
        } else if (mode == Mode.RECEIVING) {
            DiscoveryOptions opts = new DiscoveryOptions.Builder()
                    .setStrategy(STRATEGY)
                    .build();
            client.startDiscovery(serviceId, discovery, opts)
                    .addOnSuccessListener(u -> {
                        radioUp = true;
                        log("discovering");
                    })
                    .addOnFailureListener(e -> radioFailed("discovery", e, "8002"));
        }
        main.removeCallbacks(watchdog);
        main.postDelayed(watchdog, 3000);
    }

    private void radioFailed(String what, Exception e, String alreadyCode) {
        String msg = String.valueOf(e.getMessage());
        radioUp = msg.contains(alreadyCode);
        if (!radioUp)
            log(what + " FAILED (will retry): " + msg);
    }

    /** Restarts advertise/discovery that failed or were dropped (Bluetooth toggled, etc.). */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (mode == Mode.IDLE)
                return;
            if (!radioUp)
                startRadios();
            else
                main.postDelayed(this, 3000);
        }
    };

    // ---- Nearby callbacks -----------------------------------------------

    private final EndpointDiscoveryCallback discovery = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(@NonNull String endpointId,
                @NonNull DiscoveredEndpointInfo info) {
            Offer o = Offer.decode(info.getEndpointInfo());
            if (o == null)
                return;
            if (receivedOfferIds.contains(o.id))
                return;     // already have it — never even connect
            Link l = new Link(endpointId, o.from);
            l.offer = o;
            l.status = "offered";
            links.put(endpointId, l);
            log("found " + o.from + ": " + o.name + " (" + Offer.human(o.size) + ") +"
                    + (l.tFound - sessionStart) + " ms");
            listener.onStateChanged();
            if (autoAccept)
                accept(endpointId);
            else
                listener.onOfferFound(endpointId, o);
        }

        @Override
        public void onEndpointLost(@NonNull String endpointId) {
            Link l = links.get(endpointId);
            if (l != null && "offered".equals(l.status)) {
                links.remove(endpointId);
                log("lost " + l.name);
                listener.onStateChanged();
            }
        }
    };

    private final ConnectionLifecycleCallback lifecycle = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(@NonNull String endpointId,
                @NonNull ConnectionInfo info) {
            if (mode == Mode.SENDING) {
                // Anyone on our team code who connects wants the file.
                links.put(endpointId, new Link(endpointId, info.getEndpointName()));
            } else if (!links.containsKey(endpointId)) {
                client.rejectConnection(endpointId);
                return;
            }
            Link l = links.get(endpointId);
            l.status = "accepting";
            client.acceptConnection(endpointId, payloads);
            listener.onStateChanged();
        }

        @Override
        public void onConnectionResult(@NonNull String endpointId,
                @NonNull ConnectionResolution result) {
            Link l = links.get(endpointId);
            if (l == null)
                return;
            int code = result.getStatus().getStatusCode();
            if (code != ConnectionsStatusCodes.STATUS_OK) {
                l.status = "rejected (" + code + ")";
                log(l.name + ": connection failed " + code);
                listener.onStateChanged();
                return;
            }
            l.tConnected = SystemClock.elapsedRealtime();
            l.status = "connected";
            log(l.name + ": connected in " + l.connectMs() + " ms");
            if (mode == Mode.SENDING)
                pushFile(l);
            listener.onStateChanged();
        }

        @Override
        public void onBandwidthChanged(@NonNull String endpointId, @NonNull BandwidthInfo info) {
            Link l = links.get(endpointId);
            if (l == null)
                return;
            l.quality = info.getQuality();
            log(l.name + ": link " + l.qualityName());
            listener.onStateChanged();
        }

        @Override
        public void onDisconnected(@NonNull String endpointId) {
            Link l = links.get(endpointId);
            if (l == null)
                return;
            if (!l.status.startsWith("done"))
                l.status = "disconnected";
            log(l.name + ": disconnected");
            listener.onStateChanged();
        }
    };

    private final PayloadCallback payloads = new PayloadCallback() {
        @Override
        public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
            Link l = links.get(endpointId);
            if (l == null)
                return;
            if (payload.getType() == Payload.Type.FILE && mode == Mode.RECEIVING) {
                incoming.put(payload.getId(), payload);
                l.payloadId = payload.getId();
                l.tFileStart = SystemClock.elapsedRealtime();
                l.status = "receiving";
            } else if (payload.getType() == Payload.Type.BYTES && mode == Mode.SENDING) {
                byte[] b = payload.asBytes();
                if (b != null && b.length > 0 && b[0] == MSG_DONE) {
                    l.status = "done";
                    log(l.name + ": confirmed " + new String(b, 1, b.length - 1,
                            StandardCharsets.UTF_8));
                    // Free the hub slot; the receiver also drops the link.
                    client.disconnectFromEndpoint(endpointId);
                }
            }
            listener.onStateChanged();
        }

        @Override
        public void onPayloadTransferUpdate(@NonNull String endpointId,
                @NonNull PayloadTransferUpdate u) {
            Link l = links.get(endpointId);
            if (l == null)
                return;
            // Only the file payload counts; small BYTES messages report here too.
            if (u.getPayloadId() != l.payloadId)
                return;
            l.bytes = u.getBytesTransferred();
            l.total = u.getTotalBytes();
            switch (u.getStatus()) {
                case PayloadTransferUpdate.Status.SUCCESS:
                    l.tFileEnd = SystemClock.elapsedRealtime();
                    if (mode == Mode.SENDING) {
                        if (!l.status.startsWith("done"))
                            l.status = "sent";
                        log(l.name + ": sent " + rate(l));
                    } else {
                        finishReceive(l);
                    }
                    break;
                case PayloadTransferUpdate.Status.FAILURE:
                case PayloadTransferUpdate.Status.CANCELED:
                    l.status = "transfer failed";
                    log(l.name + ": transfer failed at " + Offer.human(l.bytes));
                    if (mode == Mode.RECEIVING)
                        closeQuietly(incoming.remove(l.payloadId));
                    break;
                default:
                    break;
            }
            listener.onStateChanged();
        }
    };

    private void finishReceive(Link l) {
        Payload p = incoming.remove(l.payloadId);
        if (p == null || p.asFile() == null)
            return;
        Offer o = l.offer;
        File out = uniqueFile(incomingDir, safeName(o != null ? o.name : "nearby_drop.zip"));
        try {
            copyOut(p.asFile(), out);
        } catch (Exception e) {
            l.status = "save failed";
            log(l.name + ": save failed: " + e.getMessage());
            closeQuietly(p);
            return;
        }
        closeQuietly(p);
        if (o != null)
            receivedOfferIds.add(o.id);
        l.status = "done";
        String stats = rate(l) + ", connect " + l.connectMs() + " ms, "
                + l.qualityName();
        log("received " + out.getName() + " from " + l.name + ": " + stats);
        client.sendPayload(l.endpointId, Payload.fromBytes(
                concat(MSG_DONE, (localName + " " + stats).getBytes(StandardCharsets.UTF_8))));
        // Give the confirmation a moment to leave before dropping the link.
        String ep = l.endpointId;
        main.postDelayed(() -> client.disconnectFromEndpoint(ep), 1500);
        if (o != null)
            listener.onReceived(o, out);
    }

    /** Android 10+ hands the file back as a content Uri in Nearby's own folder; move it out. */
    private void copyOut(Payload.File pf, File out) throws Exception {
        Uri uri = pf.asUri();
        if (uri != null) {
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                    OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0)
                    os.write(buf, 0, n);
            }
            try {
                context.getContentResolver().delete(uri, null, null);
            } catch (Exception ignored) {
            }
            return;
        }
        @SuppressWarnings("deprecation")
        File src = pf.asJavaFile();
        if (src == null || !src.renameTo(out))
            throw new java.io.IOException("no file in payload");
    }

    // ---- Helpers ----------------------------------------------------------

    static String rate(Link l) {
        long ms = Math.max(1, l.tFileEnd - l.tFileStart);
        double mbps = l.total * 8.0 / 1e6 / (ms / 1000.0);
        return String.format(Locale.US, "%s in %.1f s = %.2f Mbit/s", Offer.human(l.total),
                ms / 1000.0, mbps);
    }

    private static String safeName(String n) {
        String s = n.replace("…", "").replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        return s.isEmpty() ? "nearby_drop" : s;
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; f.exists(); i++)
            f = new File(dir, base + " (" + i + ")" + ext);
        return f;
    }

    private static byte[] concat(byte b, byte[] rest) {
        byte[] out = new byte[rest.length + 1];
        out[0] = b;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }

    private static void closeQuietly(Payload p) {
        if (p == null)
            return;
        try {
            p.close();
        } catch (Exception ignored) {
        }
    }

    private void log(String line) {
        Log.i(TAG, line);
        listener.onLog(line);
    }
}
