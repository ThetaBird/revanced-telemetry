package dev.selfhosted.music;

import java.lang.reflect.Method;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** APK 8.40.54 native queue adapter. Host references never cross the callback boundary. */
public final class NativeQueueCapture {
    private NativeQueueCapture() {}

    public static void capture(Object owner) {
        try {
            Object provider = owner.getClass().getField("b").get(owner);
            Object manager = call(provider, "gg");
            context(manager);
            String snapshot = snapshot(manager);
            if (snapshot != null) Telemetry.onPlaybackQueueMetadata(snapshot);
        } catch (Throwable failure) {
            Telemetry.captureFailure("Native queue capture failed", failure);
        }
    }

    static String snapshot(Object manager) throws Exception {
        Object value = call(manager, "o");
        if (!(value instanceof List<?>)) return null;
        List<?> queue = (List<?>) value;
        if (queue.size() > 50000) return null;
        JSONArray tracks = new JSONArray();
        for (Object item : queue) {
            JSONObject track = new JSONObject();
            track.put("videoId", JSONObject.NULL);
            if (item != null) {
                putString(track, "videoId", call(item, "t"));
                Object queueId = optional(item, "p");
                if (queueId instanceof Long) track.put("queueId", queueId.toString());
                putString(track, "title", optional(item, "h"));
                putString(track, "subtitle", optional(item, "g"));
                thumbnails(track, optional(item, "e"));
                Object descriptor = optional(item, "m");
                if (descriptor != null) {
                    putString(track, "playlistId", optional(descriptor, "t"));
                    Object index = optional(descriptor, "a");
                    if (index instanceof Integer && ((Integer) index) >= 0) track.put("playlistIndex", index);
                }
            }
            tracks.put(track);
        }
        return tracks.toString();
    }

    private static void context(Object manager) {
        try {
            Object item = call(manager, "j");
            Object descriptor = item == null ? null : call(item, "m");
            if (item != null && descriptor == null) return;
            Object videoId = descriptor == null ? null : call(descriptor, "u");
            Object playlistId = descriptor == null ? null : call(descriptor, "t");
            Object index = descriptor == null ? null : call(descriptor, "a");
            Telemetry.onPlaylistContext(videoId instanceof String ? (String) videoId : null,
                    playlistId instanceof String ? (String) playlistId : null,
                    index instanceof Integer ? (Integer) index : -1);
        } catch (Throwable failure) { Telemetry.captureFailure("Native queue metadata failed", failure); }
    }

    private static void thumbnails(JSONObject track, Object thumbnail) {
        if (thumbnail == null) return;
        try {
            Object entries = thumbnail.getClass().getField("c").get(thumbnail);
            if (!(entries instanceof List<?>)) return;
            JSONArray images = new JSONArray();
            for (Object entry : (List<?>) entries) {
                if (entry == null) continue;
                JSONObject image = new JSONObject();
                putString(image, "url", entry.getClass().getField("c").get(entry));
                Object width = entry.getClass().getField("d").get(entry);
                Object height = entry.getClass().getField("e").get(entry);
                if (width instanceof Integer) image.put("width", width);
                if (height instanceof Integer) image.put("height", height);
                if (image.has("url")) images.put(image);
            }
            if (images.length() > 0) track.put("thumbnails", images);
        } catch (Throwable failure) { Telemetry.captureFailure("Native queue metadata failed", failure); }
    }

    private static void putString(JSONObject target, String key, Object value) throws Exception {
        if (value instanceof String && !((String) value).isEmpty()) target.put(key, value);
    }

    private static Object optional(Object target, String name) {
        try { return call(target, name); }
        catch (NoSuchMethodException absent) { return null; }
        catch (Throwable failure) {
            Telemetry.captureFailure("Native queue optional metadata failed", failure);
            return null;
        }
    }

    private static Object call(Object target, String name) throws Exception {
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }
}
