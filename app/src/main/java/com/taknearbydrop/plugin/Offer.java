package com.taknearbydrop.plugin;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * What a sender is offering. It rides in the Nearby advertisement's endpoint info, so a
 * receiver sees who is sending what (and how big) BEFORE it connects — and can skip an offer
 * it already has without ever opening a link.
 *
 * Wire: {@code u8 version | utf8 "callsign\x1Fid\x1Fsize\x1Fname"}, at most {@link #MAX_BYTES}.
 */
final class Offer {

    private static final byte VERSION = 1;
    private static final char SEP = '\u001F';
    /** Endpoint info must fit a BLE advertisement; Nearby's documented limit is 131 bytes. */
    private static final int MAX_BYTES = 120;

    final String from;
    final String id;
    final long size;
    final String name;

    Offer(String from, String id, long size, String name) {
        this.from = from;
        this.id = id;
        this.size = size;
        this.name = name;
    }

    static Offer create(String from, String name, long size) {
        return new Offer(from, UUID.randomUUID().toString().substring(0, 8), size, name);
    }

    byte[] encode() {
        String head = from + SEP + id + SEP + size + SEP;
        String n = name;
        byte[] body;
        // Long file names are trimmed so the advertisement still fits; the real name
        // comes with the file payload anyway.
        while (true) {
            body = (head + n).getBytes(StandardCharsets.UTF_8);
            if (body.length + 1 <= MAX_BYTES || n.length() <= 4)
                break;
            n = n.substring(0, n.length() - 2) + "…";
        }
        byte[] out = new byte[body.length + 1];
        out[0] = VERSION;
        System.arraycopy(body, 0, out, 1, body.length);
        return out;
    }

    /** Null when the bytes are not one of our offers. */
    static Offer decode(byte[] b) {
        if (b == null || b.length < 2 || b[0] != VERSION)
            return null;
        String[] f = new String(b, 1, b.length - 1, StandardCharsets.UTF_8)
                .split(String.valueOf(SEP), 4);
        if (f.length != 4)
            return null;
        try {
            return new Offer(f[0], f[1], Long.parseLong(f[2]), f[3]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String human(long bytes) {
        if (bytes < 1024)
            return bytes + " B";
        if (bytes < 1024 * 1024)
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
    }
}
