package dev.selfhosted.music;

import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Music 8.40.54 playlist detail headers and their complete currently loaded adapter. */
public final class OpenedPlaylistCapture {
    private static final Map<Object, Page> PAGES = new WeakHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private OpenedPlaylistCapture() {}

    private static final class Page {
        final String id;
        final String title;
        final String metadata;
        Object adapter;
        boolean pending;
        Page(String id, String title, String metadata) { this.id = id; this.title = title; this.metadata = metadata; }
    }

    /** Called before the host overwrites its header argument. Unknown headers are ignored. */
    public static void opened(Object fragment, Object header) {
        try {
            if (Looper.myLooper() != Looper.getMainLooper() || !named(fragment, "ogb")) return;
            String id = null;
            JSONObject metadata = new JSONObject();
            if (named(header, "btjp")) {
                id = string(read(header, "f"));
                Object displayed = extension(read(header, "c"), "btip", "b");
                if (displayed == null) displayed = extension(read(header, "c"), "btjx", "a");
                Object editable = extension(read(header, "d"), "btjs", "a");
                metadata = headerMetadata(displayed, editable);
            } else if (named(header, "btjw") && (((Number) read(header, "b")).intValue() & 16) != 0) {
                id = string(read(read(header, "f"), "b"));
                metadata = headerMetadata(header, null);
            }
            if (id == null) { PAGES.remove(fragment); return; }
            String title = string(metadata.remove("title"));
            Page page = new Page(id, title, metadata.toString());
            PAGES.put(fragment, page);
            // This explicitly partial snapshot also records an empty/unloaded playlist being opened.
            Telemetry.onPlaylistOpened();
            Telemetry.onOpenedPlaylistMetadata(id, title, "[]", page.metadata);
        } catch (Throwable failure) { Telemetry.captureFailure("Playlist header capture failed", failure); }
    }

    /** Coalesce row binds, then read all loaded rows, including those outside the viewport. */
    public static void capture(Object callback, Object adapter) {
        try {
            if (Looper.myLooper() != Looper.getMainLooper() || !named(callback, "ofh")) return;
            Object fragment = read(callback, "a");
            Page page = PAGES.get(fragment);
            if (page == null || !page.id.equals(read(fragment, "aI"))) return;
            page.adapter = adapter;
            if (page.pending) return;
            page.pending = true;
            MAIN.post(() -> {
                try {
                    page.pending = false;
                    if (PAGES.get(fragment) != page || !page.id.equals(read(fragment, "aI"))) return;
                    String tracks = tracks(page.adapter).toString();
                    page.adapter = null;
                    // Persisted deduplication belongs to Telemetry so disabled capture,
                    // failed writes and destination changes can retry the same loaded rows.
                    Telemetry.onOpenedPlaylistMetadata(page.id, page.title, tracks, page.metadata);
                } catch (Throwable failure) {
                    page.adapter = null;
                    Telemetry.captureFailure("Playlist rows capture failed", failure);
                }
            });
        } catch (Throwable failure) { Telemetry.captureFailure("Playlist binding capture failed", failure); }
    }

    static JSONArray tracks(Object adapter) throws Exception {
        Method count = adapter.getClass().getMethod("a");
        Method item = adapter.getClass().getMethod("d", int.class);
        int size = ((Number) count.invoke(adapter)).intValue();
        if (size < 0 || size > PlaylistSnapshot.MAX_TRACKS) throw new IllegalArgumentException("Playlist exceeds limit");
        JSONArray result = new JSONArray();
        for (int i = 0; i < size; i++) {
            Object row = item.invoke(adapter, i);
            if (named(row, "oij")) row = read(row, "a");
            if (!named(row, "buew")) continue; // Header/continuation/decoration, not a song.
            JSONObject track = new JSONObject().put("position", result.length()).put("videoId", JSONObject.NULL);
            String video = videoId(read(row, "i"));
            if (video == null) video = videoId(read(row, "k"));
            if (video != null) track.put("videoId", video);
            put(track, "title", text(read(row, "e")));
            put(track, "subtitle", text(read(row, "g")));
            put(track, "secondaryText", text(read(row, "h")));
            JSONArray runs = new JSONArray();
            for (String field : new String[]{"e", "g", "h"}) metadataRuns(read(row, field), runs);
            JSONArray labels = new JSONArray();
            JSONArray images = new JSONArray();
            Object artwork = read(row, "c");
            rendererMetadata(extension(artwork, "btgu", "a"), labels, runs, images);
            rendererMetadata(extension(artwork, "budb", "a"), labels, runs, images);
            Object badges = read(row, "t");
            if (badges instanceof Iterable<?>) {
                for (Object badge : (Iterable<?>) badges) {
                    rendererMetadata(extension(badge, "blol", "b"), labels, runs, images);
                    rendererMetadata(extension(badge, "bthx", "a"), labels, runs, images);
                }
            }
            if (runs.length() > 0) track.put("metadataRuns", runs);
            if (labels.length() > 0) track.put("displayColumns", labels);
            if (images.length() > 0) track.put("thumbnails", images);
            Object occurrence = read(row, "q");
            if (occurrence != null && (((Number) read(occurrence, "b")).intValue() & 1) != 0) {
                put(track, "setVideoId", string(read(occurrence, "c")));
            }
            result.put(track);
        }
        return result;
    }

    /** Preserve proven header content, without interpreting opaque Elements payloads or editor labels. */
    static JSONObject headerMetadata(Object displayed, Object editable) throws Exception {
        JSONObject metadata = new JSONObject();
        JSONArray runs = new JSONArray();
        JSONArray images = new JSONArray();
        if (named(displayed, "btio")) {
            put(metadata, "title", text(read(displayed, "c")));
            put(metadata, "subtitle", text(read(displayed, "d")));
            put(metadata, "secondaryText", text(read(displayed, "e")));
            for (String field : new String[]{"c", "d", "e"}) metadataRuns(read(displayed, field), runs);
        } else if (named(displayed, "btjw")) {
            put(metadata, "title", text(read(displayed, "e")));
            metadataRuns(read(displayed, "e"), runs);
        }
        if (named(editable, "btjr")) {
            if (!metadata.has("title")) put(metadata, "title", text(read(editable, "c")));
            put(metadata, "description", text(read(editable, "e")));
            metadataRuns(read(editable, "c"), runs);
            metadataRuns(read(editable, "e"), runs);
            rendererMetadata(extension(read(editable, "o"), "budb", "a"), new JSONArray(), runs, images);
        }
        if (runs.length() > 0) metadata.put("metadataRuns", runs);
        if (images.length() > 0) metadata.put("thumbnails", images);
        return metadata;
    }

    private static Object extension(Object wrapper, String type, String field) throws Exception {
        if (wrapper == null) return null;
        ClassLoader loader = wrapper.getClass().getClassLoader();
        Class<?> extensionType = Class.forName("bjno", false, loader);
        Object extension = Class.forName(type, false, loader).getField(field).get(null);
        Object optional = Class.forName("qqk", false, loader)
                .getMethod("a", Class.forName("bwtm", false, loader), extensionType).invoke(null, wrapper, extension);
        return (Boolean) optional.getClass().getMethod("isPresent").invoke(optional)
                ? optional.getClass().getMethod("get").invoke(optional) : null;
    }

    /** Known display renderers only; UI control state and opaque extensions are excluded. */
    static void rendererMetadata(Object renderer, JSONArray labels, JSONArray runs, JSONArray images) throws Exception {
        if (named(renderer, "blne") || named(renderer, "bthw")) {
            Object richText = read(renderer, named(renderer, "blne") ? "c" : "b");
            String label = text(richText);
            if (label != null) labels.put(label);
            metadataRuns(richText, runs);
        } else if (named(renderer, "btgt")) {
            Object groups = read(renderer, "b");
            if (groups instanceof Iterable<?>) for (Object group : (Iterable<?>) groups) thumbnails(group, images);
        } else if (named(renderer, "bucw")) thumbnails(read(renderer, "c"), images);
    }

    private static void thumbnails(Object group, JSONArray images) throws Exception {
        if (!named(group, "bytq")) return;
        Object variants = read(group, "c");
        if (!(variants instanceof Iterable<?>)) return;
        for (Object variant : (Iterable<?>) variants) {
            if (!named(variant, "bytp")) continue;
            String url = string(read(variant, "c"));
            if (url == null) continue;
            JSONObject image = new JSONObject().put("url", url);
            int presence = ((Number) read(variant, "b")).intValue();
            if ((presence & 2) != 0) image.put("width", read(variant, "d"));
            if ((presence & 4) != 0) image.put("height", read(variant, "e"));
            images.put(image);
        }
    }

    private static void metadataRuns(Object text, JSONArray output) throws Exception {
        Object runs = read(text, "c");
        if (!(runs instanceof Iterable<?>)) return;
        for (Object run : (Iterable<?>) runs) {
            JSONObject metadata = new JSONObject();
            put(metadata, "text", string(read(run, "c")));
            Object endpoint = read(run, "l");
            if (endpoint != null) {
                byte[] bytes = (byte[]) endpoint.getClass().getMethod("toByteArray").invoke(endpoint);
                put(metadata, "videoId", CarouselCapture.videoId(bytes));
                byte[] browse = field(bytes, 48687626);
                byte[] id = browse == null ? null : field(browse, 2);
                if (id != null) put(metadata, "browseId", new String(id, java.nio.charset.StandardCharsets.UTF_8));
            }
            if (metadata.length() > 0) output.put(metadata);
        }
    }

    /** Only decode the known browse ID, never tracking parameters or opaque endpoint payloads. */
    private static byte[] field(byte[] bytes, int wanted) {
        if (bytes.length > 65536) return null;
        int[] offset = {0};
        byte[] result = null;
        while (offset[0] < bytes.length) {
            long tag = varint(bytes, offset);
            long number = tag >>> 3;
            if (number == 0 || number > 0x1fffffffL) throw new IllegalArgumentException();
            int wire = (int) (tag & 7);
            if (wire == 0) { varint(bytes, offset); continue; }
            long length = wire == 1 ? 8 : wire == 5 ? 4 : wire == 2 ? varint(bytes, offset) : -1;
            if (length < 0 || length > bytes.length - offset[0]) throw new IllegalArgumentException();
            if (wire == 2 && number == wanted) {
                if (result != null) throw new IllegalArgumentException();
                result = java.util.Arrays.copyOfRange(bytes, offset[0], offset[0] + (int) length);
            }
            offset[0] += (int) length;
        }
        return result;
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

    private static String videoId(Object endpoint) throws Exception {
        return endpoint == null ? null : CarouselCapture.videoId((byte[]) endpoint.getClass().getMethod("toByteArray").invoke(endpoint));
    }

    static String text(Object value) throws Exception {
        if (value == null) return null;
        String plain = string(read(value, "d"));
        if (plain != null) return plain;
        Object runs = read(value, "c");
        if (!(runs instanceof Iterable<?>)) return null;
        StringBuilder output = new StringBuilder();
        for (Object run : (Iterable<?>) runs) {
            String part = string(read(run, "c"));
            if (part != null) output.append(part);
        }
        return string(output.toString());
    }

    private static boolean named(Object value, String name) { return value != null && value.getClass().getSimpleName().equals(name); }
    private static Object read(Object value, String field) throws Exception { return value == null ? null : value.getClass().getField(field).get(value); }
    private static String string(Object value) { return value instanceof String && !((String) value).isEmpty() ? (String) value : null; }
    private static void put(JSONObject target, String key, String value) throws Exception { if (value != null) target.put(key, value); }
}
