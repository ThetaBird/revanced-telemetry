package dev.selfhosted.music;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class NativeQueueCaptureTests {
    private NativeQueueCaptureTests() {}
    static void run() throws Exception {
        Manager manager = new Manager();
        for (int i = 0; i < 60; i++) manager.items.add(new Item());
        manager.items.add(null);
        manager.items.add(new UnresolvedItem());
        String serialized = NativeQueueCapture.snapshot(manager);
        manager.items.clear();
        JSONArray snapshot = new JSONArray(serialized);
        check(snapshot.length() == 62, "queue truncated or positions removed");
        JSONObject first = snapshot.getJSONObject(0);
        check("abcdefghijk".equals(first.getString("videoId")), "native video ID missing");
        check("9223372036854775807".equals(first.getString("queueId")), "queue ID lost precision");
        check("Song".equals(first.getString("title")), "title lost");
        check("Artist • Album".equals(first.getString("subtitle")), "subtitle lost");
        check("PLfixture".equals(first.getString("playlistId")), "playlist ID lost");
        check(first.getInt("playlistIndex") == 3, "playlist index lost");
        check(first.getJSONArray("thumbnails").getJSONObject(0).getInt("width") == 120, "thumbnail lost");
        check(snapshot.getJSONObject(60).isNull("videoId") && snapshot.getJSONObject(61).isNull("videoId"), "unknown position lost");
        check("[]".equals(NativeQueueCapture.snapshot(manager)), "empty queue lost");
        java.lang.reflect.Field droppedField = Telemetry.class.getDeclaredField("DROPPED_CALLBACKS");
        droppedField.setAccessible(true);
        java.util.concurrent.atomic.AtomicLong dropped = (java.util.concurrent.atomic.AtomicLong) droppedField.get(null);
        long before = dropped.get();
        NativeQueueCapture.capture(null);
        NativeQueueCapture.capture(new Object());
        check(dropped.get() == before + 2, "reflection failures were silently lost");
        manager.items.add(new Object());
        try { NativeQueueCapture.snapshot(manager); throw new AssertionError("broken native contract silently accepted"); }
        catch (NoSuchMethodException expected) { /* Fail closed rather than invent an unresolved song. */ }
    }
    public static final class Manager {
        final List<Object> items = new ArrayList<>();
        public List<Object> o() { return items; }
    }
    public static final class UnresolvedItem { public String t() { return null; } }
    public static final class Item {
        public String t() { return "abcdefghijk"; }
        public Long p() { return Long.MAX_VALUE; }
        public String h() { return "Song"; }
        public String g() { return "Artist • Album"; }
        public Descriptor m() { return new Descriptor(); }
        public Thumbnails e() { return new Thumbnails(); }
    }
    public static final class Descriptor {
        public String t() { return "PLfixture"; }
        public int a() { return 3; }
    }
    public static final class Thumbnails { public List<Image> c = List.of(new Image()); }
    public static final class Image { public String c = "https://i.ytimg.com/vi/abcdefghijk/default.jpg"; public int d = 120; public int e = 90; }
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
