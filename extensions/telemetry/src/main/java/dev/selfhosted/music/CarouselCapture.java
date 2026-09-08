package dev.selfhosted.music;

import android.os.Looper;
import android.view.View;
import android.view.ViewParent;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.WeakHashMap;

/** Captures only mapped song selections with a structurally associated, visible shelf heading. */
final class CarouselCapture {
    private static final Map<Object, WeakReference<View>> ITEMS = new WeakHashMap<>();
    private CarouselCapture() {}

    static void bind(Object callback, View item) {
        if (callback == null || item == null) return;
        synchronized (ITEMS) {
            if (ITEMS.size() >= 2048) ITEMS.clear();
            ITEMS.put(callback, new WeakReference<>(item));
        }
    }

    static void dispatch(Object callback, Object endpoint) throws Exception {
        if (Looper.myLooper() != Looper.getMainLooper() || endpoint == null) return;
        View item;
        synchronized (ITEMS) {
            WeakReference<View> reference = ITEMS.get(callback);
            item = reference == null ? null : reference.get();
        }
        if (item == null) return;
        String heading = heading(item);
        if (heading == null) return;
        // MessageLite is part of the host. No protobuf classes are bundled or reflected into fields.
        byte[] bytes = (byte[]) endpoint.getClass().getMethod("toByteArray").invoke(endpoint);
        String id = videoId(bytes);
        if (id != null) Telemetry.carouselSelected(id, heading);
    }

    static String heading(View item) {
        View current = item;
        for (int depth = 0; depth < 16; depth++) {
            if (current.getId() == 0x7f0b01de || current.getId() == 0x7f0b01df) {
                // Music 8.40.54 res/k21.xml: carousel content and header are siblings.
                ViewParent parent = current.getParent();
                if (!(parent instanceof View)) return null;
                View header = ((View) parent).findViewById(0x7f0b0550);
                if (header == null || header.getParent() != parent) return null;
                View title = header.findViewById(0x7f0b01ea);
                if (!(title instanceof TextView) || !title.isShown()) return null;
                CharSequence text = ((TextView) title).getText();
                if (text == null) return null;
                String value = text.toString().trim();
                return value.isEmpty() || value.length() > 256 ? null : value;
            }
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) return null;
            current = (View) parent;
        }
        return null;
    }

    /** bmme WatchEndpoint extension 48687757; cais field 1 is its video ID in this APK. */
    static String videoId(byte[] bytes) {
        if (bytes == null || bytes.length > 65536) return null;
        try {
            byte[] watch = field(bytes, 48687757);
            if (watch == null) return null;
            byte[] id = field(watch, 1);
            if (id == null || id.length != 11) return null;
            String value = new String(id, StandardCharsets.UTF_8);
            return value.matches("[A-Za-z0-9_-]{11}") ? value : null;
        } catch (IllegalArgumentException ignored) { return null; }
    }

    private static byte[] field(byte[] bytes, int wanted) {
        int[] offset = {0};
        byte[] result = null;
        while (offset[0] < bytes.length) {
            long tag = varint(bytes, offset);
            long number = tag >>> 3;
            if (number == 0 || number > 0x1fffffffL) throw new IllegalArgumentException();
            int wire = (int) (tag & 7);
            if (wire == 0) varint(bytes, offset);
            else if (wire == 1) skip(bytes, offset, 8);
            else if (wire == 5) skip(bytes, offset, 4);
            else if (wire == 2) {
                long length = varint(bytes, offset);
                if (length < 0 || length > bytes.length - offset[0]) throw new IllegalArgumentException();
                if (number == wanted) {
                    if (result != null) throw new IllegalArgumentException();
                    result = java.util.Arrays.copyOfRange(bytes, offset[0], offset[0] + (int) length);
                }
                skip(bytes, offset, (int) length);
            } else throw new IllegalArgumentException();
        }
        return result;
    }

    private static void skip(byte[] bytes, int[] offset, int count) {
        if (count < 0 || count > bytes.length - offset[0]) throw new IllegalArgumentException();
        offset[0] += count;
    }

    private static long varint(byte[] bytes, int[] offset) {
        long value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (offset[0] >= bytes.length) throw new IllegalArgumentException();
            int b = bytes[offset[0]++] & 255;
            if (shift == 63 && b > 1) throw new IllegalArgumentException();
            value |= (long) (b & 127) << shift;
            if ((b & 128) == 0) return value;
        }
        throw new IllegalArgumentException();
    }
}
