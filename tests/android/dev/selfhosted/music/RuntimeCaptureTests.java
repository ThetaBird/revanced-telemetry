package dev.selfhosted.music;

import android.app.Instrumentation;
import android.content.Intent;
import android.database.Cursor;
import android.view.View;
import android.widget.FrameLayout;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Actual Android UI, protobuf boundaries, and callback-to-SQLite regression checks. */
final class RuntimeCaptureTests {
    private static final String TARGET = "target_-123";
    private static final String CONTEXT = "context1234";
    private RuntimeCaptureTests() {}

    static void run(Instrumentation instrumentation) throws Exception {
        protobufBoundaries();
        instrumentation.getTargetContext().deleteDatabase("selfhosted_music_telemetry.db");
        ScheduledThreadPoolExecutor worker = (ScheduledThreadPoolExecutor) field("WORKER").get(null);
        CaptureActivity activity = (CaptureActivity) instrumentation.startActivitySync(
                new Intent(instrumentation.getTargetContext(), CaptureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
        try (EventStore store = new EventStore(instrumentation.getTargetContext())) {
            worker.submit(() -> {
                field("store").set(null, store);
                field("enabled").setBoolean(null, true);
                field("sourcePackage").set(null, "com.google.android.apps.youtube.music");
                field("flushScheduled").setBoolean(null, true);
                return null;
            }).get(30, TimeUnit.SECONDS);
            Telemetry.onTrack(CONTEXT);
            Telemetry.onRating(TARGET, 1);
            Telemetry.onRating(TARGET, -1);
            Telemetry.onRating(TARGET, 0);
            Telemetry.onRating(TARGET, 99);
            Telemetry.onRating("invalid", 1);
            Telemetry.onInAppSkipNext();
            Telemetry.onInAppSkipPrevious();
            Object callback = new Object();
            FixtureEndpoint endpoint = new FixtureEndpoint(watch(TARGET));
            instrumentation.runOnMainSync(() -> {
                check(activity.title.isShown(), "fixture heading is not visible");
                Telemetry.bindCarouselItem(callback, activity.item);
                Telemetry.onCarouselDispatch(callback, endpoint);
                Telemetry.onCarouselDispatch(new Object(), endpoint);
                Object outside = new Object();
                Telemetry.bindCarouselItem(outside, activity.outside);
                Telemetry.onCarouselDispatch(outside, endpoint);
                activity.title.setVisibility(View.GONE);
                Telemetry.onCarouselDispatch(callback, endpoint);
                activity.title.setVisibility(View.VISIBLE);
                activity.shelf.removeView(activity.header);
                FrameLayout unrelated = new FrameLayout(activity);
                unrelated.addView(activity.header);
                activity.shelf.addView(unrelated);
                Telemetry.onCarouselDispatch(callback, endpoint);
                unrelated.removeView(activity.header);
                activity.shelf.removeView(unrelated);
                activity.shelf.addView(activity.header, 0);
                Telemetry.onCarouselDispatch(callback, new FixtureEndpoint(new byte[]{10, 127}));
            });
            // Calls away from the main thread must not inspect a UI tree or produce events.
            Telemetry.onCarouselDispatch(callback, endpoint);
            worker.submit(() -> {}).get(30, TimeUnit.SECONDS);
            List<JSONObject> events = read(store);
            check(events.size() == 8, "unexpected runtime event count: " + events.size());
            for (int i = 1; i <= 3; i++) {
                JSONObject rating = events.get(i);
                check(TARGET.equals(rating.getString("videoId")), "rating lost exact request target");
                check(CONTEXT.equals(rating.getString("contextVideoId")), "rating context conflated with target");
                check("rating_request".equals(rating.getString("videoIdBasis")), "rating basis incorrect");
                check("request_built".equals(rating.getString("observation")), "rating claims success");
            }
            check("like".equals(events.get(1).getString("action")), "like mismatch");
            check("dislike".equals(events.get(2).getString("action")), "dislike mismatch");
            check("remove_rating".equals(events.get(3).getString("action")), "remove mismatch");
            check(events.get(4).isNull("videoId"), "invalid target became exact rating target");
            check("unresolved_rating_target".equals(events.get(4).getString("videoIdBasis")), "invalid target basis");
            check("skip_requested".equals(events.get(5).getString("action")), "in-app next mismatch");
            check("previous_requested".equals(events.get(6).getString("action")), "in-app previous mismatch");
            check("player_controls".equals(events.get(5).getString("origin")), "in-app next origin");
            check("player_controls".equals(events.get(6).getString("origin")), "in-app previous origin");
            JSONObject selection = events.get(7);
            check("carousel_song_selected".equals(selection.getString("action")), "carousel action missing");
            check(TARGET.equals(selection.getString("videoId")), "carousel target mismatch");
            check("Quick picks".equals(selection.getString("sourceTitle")), "observed heading missing");
            check("music_carousel".equals(selection.getString("origin")), "invented carousel origin");
            check("watch_endpoint".equals(selection.getString("videoIdBasis")), "carousel target basis");
            check(!selection.has("endpoint") && !selection.has("endpointProto"), "raw endpoint leaked");
        } finally {
            worker.submit(() -> { field("store").set(null, null); field("enabled").setBoolean(null, false); return null; }).get(30, TimeUnit.SECONDS);
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    private static void protobufBoundaries() {
        byte[] valid = watch(TARGET);
        check(TARGET.equals(CarouselCapture.videoId(valid)), "valid WatchEndpoint rejected");
        check(CarouselCapture.videoId(null) == null, "null accepted");
        check(CarouselCapture.videoId(new byte[65537]) == null, "oversized accepted");
        check(CarouselCapture.videoId(watch("short")) == null, "short ID accepted");
        check(CarouselCapture.videoId(watch("bad!target12")) == null, "invalid character accepted");
        check(CarouselCapture.videoId(fieldBytes(2, TARGET.getBytes(StandardCharsets.UTF_8))) == null, "unrelated field accepted");
        check(CarouselCapture.videoId(concat(valid, valid)) == null, "duplicate WatchEndpoint accepted");
        byte[] id = fieldBytes(1, TARGET.getBytes(StandardCharsets.UTF_8));
        check(CarouselCapture.videoId(fieldBytes(48687757, concat(id, id))) == null, "duplicate video ID accepted");
        for (int length = 0; length < valid.length; length++) {
            check(CarouselCapture.videoId(Arrays.copyOf(valid, length)) == null, "truncated endpoint accepted at " + length);
        }
        check(CarouselCapture.videoId(concat(valid, new byte[]{0})) == null, "zero field accepted");
        byte[] overflow = new byte[11];
        Arrays.fill(overflow, (byte) 0xff);
        check(CarouselCapture.videoId(overflow) == null, "overflowing varint accepted");
        check(TARGET.equals(CarouselCapture.videoId(concat(new byte[]{8, 1}, valid))), "unknown varint field rejected");
    }

    public static final class FixtureEndpoint {
        private final byte[] bytes;
        FixtureEndpoint(byte[] bytes) { this.bytes = bytes; }
        public byte[] toByteArray() { return bytes.clone(); }
    }
    private static byte[] watch(String id) { return fieldBytes(48687757, fieldBytes(1, id.getBytes(StandardCharsets.UTF_8))); }
    private static byte[] fieldBytes(int number, byte[] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        varint(out, ((long) number << 3) | 2);
        varint(out, bytes.length);
        out.write(bytes, 0, bytes.length);
        return out.toByteArray();
    }
    private static void varint(ByteArrayOutputStream out, long number) {
        while ((number & ~127L) != 0) { out.write((int) (number & 127) | 128); number >>>= 7; }
        out.write((int) number);
    }
    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
    private static List<JSONObject> read(EventStore store) throws Exception {
        List<JSONObject> events = new ArrayList<>();
        try (Cursor cursor = store.getReadableDatabase().rawQuery("SELECT payload FROM events ORDER BY sequence", null)) {
            while (cursor.moveToNext()) events.add(new JSONObject(cursor.getString(0)));
        }
        return events;
    }
    private static Field field(String name) throws Exception {
        Field value = Telemetry.class.getDeclaredField(name);
        value.setAccessible(true);
        return value;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
