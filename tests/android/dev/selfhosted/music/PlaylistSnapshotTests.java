package dev.selfhosted.music;

import android.app.Instrumentation;
import android.database.Cursor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Queue callback isolation, chunking and durable retry checks on Android SQLite. */
final class PlaylistSnapshotTests {
    private static final String FIRST = "abcdefghijk";
    private static final String SECOND = "123456789_-";
    private PlaylistSnapshotTests() {}

    static void run(Instrumentation instrumentation) throws Exception {
        instrumentation.getTargetContext().deleteDatabase("selfhosted_music_telemetry.db");
        ScheduledThreadPoolExecutor worker = (ScheduledThreadPoolExecutor) field("WORKER").get(null);
        try (EventStore store = new EventStore(instrumentation.getTargetContext())) {
            worker.submit(() -> {
                field("store").set(null, store);
                field("enabled").setBoolean(null, true);
                field("lastPlaybackQueue").set(null, null);
                field("sourcePackage").set(null, "com.google.android.apps.youtube.music");
                field("flushScheduled").setBoolean(null, true);
                return null;
            }).get(30, TimeUnit.SECONDS);
            Object video = field("videoId").get(null);
            Object session = field("sessionId").get(null);
            long position = field("positionMs").getLong(null);
            CountDownLatch release = new CountDownLatch(1);
            worker.submit(() -> { release.await(30, TimeUnit.SECONDS); return null; });
            String[] ids = {FIRST, SECOND, FIRST, null, "invalid"};
            try {
                Telemetry.onPlaybackQueue(ids);
                ids[0] = SECOND;
            } finally { release.countDown(); }
            drain(worker);
            List<JSONObject> events = read(store);
            check(events.size() == 1, "queue not captured");
            JSONObject first = events.get(0);
            check("playback_queue".equals(first.getString("event")), "queue event type");
            check(!first.has("action"), "queue serialized as action");
            check("playback_queue".equals(first.getString("playlistId")), "queue identity");
            check("Up next".equals(first.getString("title")), "queue title");
            check("loaded_playback_queue".equals(first.getString("snapshotScope")), "queue scope");
            check(first.getInt("partCount") == 1 && first.getInt("totalTracks") == 5, "queue counters");
            JSONArray tracks = first.getJSONArray("tracks");
            check(FIRST.equals(tracks.getJSONObject(0).getString("videoId")), "host mutation crossed callback");
            check(SECOND.equals(tracks.getJSONObject(1).getString("videoId")), "queue order lost");
            check(FIRST.equals(tracks.getJSONObject(2).getString("videoId")), "duplicate song lost");
            check(tracks.getJSONObject(3).isNull("videoId") && tracks.getJSONObject(4).isNull("videoId"), "unresolved positions lost");
            check(first.toString().equals(store.batch().get(0).toString()), "retry payload changed");
            Telemetry.onPlaybackQueue(new String[]{FIRST, SECOND, FIRST, null, null});
            Telemetry.onPlaybackQueue(null);
            Telemetry.onPlaybackQueue(new String[50001]);
            drain(worker);
            check(read(store).size() == 1, "duplicate or invalid queue captured");
            Telemetry.onPlaybackQueue(new String[]{SECOND, FIRST});
            Telemetry.onPlaybackQueue(new String[0]);
            Telemetry.onPlaybackQueue(new String[0]);
            drain(worker);
            events = read(store);
            check(events.size() == 3, "changed or empty queue missing");
            check(!first.getString("snapshotId").equals(events.get(1).getString("snapshotId")), "changed queue reused identity");
            check(events.get(2).getInt("totalTracks") == 0 && events.get(2).getInt("partCount") == 1,
                    "empty queue must have one empty part");
            check(java.util.Objects.equals(video, field("videoId").get(null))
                    && java.util.Objects.equals(session, field("sessionId").get(null))
                    && position == field("positionMs").getLong(null), "queue changed player context");
            worker.submit(() -> { field("enabled").setBoolean(null, false); return null; }).get();
            Telemetry.onPlaybackQueue(new String[]{FIRST});
            drain(worker);
            check(read(store).size() == 3, "disabled queue captured");
            worker.submit(() -> { field("enabled").setBoolean(null, true); return null; }).get();
            Telemetry.onPlaybackQueue(new String[0]);
            drain(worker);
            check(read(store).size() == 4, "disable did not reset deduplication");
            store.getWritableDatabase().delete("events", null, null);
            for (int length : new int[]{999, 1000, 1001, 50000}) {
                String[] many = new String[length];
                Arrays.fill(many, FIRST);
                Telemetry.onPlaybackQueue(many);
                drain(worker);
                events = read(store);
                int expected = (length + 999) / 1000;
                check(events.size() == expected, "chunk boundary failed at " + length);
                String snapshot = events.get(0).getString("snapshotId");
                int nextPosition = 0;
                for (int i = 0; i < events.size(); i++) {
                    JSONObject part = events.get(i);
                    check(snapshot.equals(part.getString("snapshotId")), "chunk identity changed");
                    check(part.getString("id").equals(snapshot + ":" + i), "chunk retry identity");
                    check(part.getInt("partIndex") == i && part.getInt("partCount") == expected
                            && part.getInt("totalTracks") == length, "chunk counters");
                    check(part.toString().getBytes(StandardCharsets.UTF_8).length <= 60 * 1024, "chunk too large");
                    JSONArray chunk = part.getJSONArray("tracks");
                    for (int j = 0; j < chunk.length(); j++) {
                        check(chunk.getJSONObject(j).getInt("position") == nextPosition++, "chunk position gap");
                    }
                }
                check(nextPosition == length, "incomplete queue");
                check(events.get(0).toString().equals(store.batch().get(0).toString()), "chunk retry changed");
                store.getWritableDatabase().delete("events", null, null);
            }
            JSONArray rich = new JSONArray();
            char[] titleChars = new char[2000];
            Arrays.fill(titleChars, '\u266b');
            String longTitle = new String(titleChars);
            for (int i = 0; i < 20; i++) {
                rich.put(new JSONObject().put("videoId", FIRST).put("title", longTitle)
                        .put("queueId", i).put("durationSeconds", 123).put("setVideoId", "set-" + i)
                        .put("isAvailable", true).put("likeStatus", "LIKE")
                        .put("artists", new JSONArray().put(new JSONObject().put("name", "Artist")
                                .put("id", "artist-id").put("trackingParams", "secret")))
                        .put("album", new JSONObject().put("name", "Album").put("id", "album-id"))
                        .put("trackingParams", "secret").put("endpoint", "secret"));
            }
            String serialized = rich.toString();
            Telemetry.onPlaybackQueueMetadata(serialized);
            Telemetry.onPlaybackQueueMetadata(serialized);
            Telemetry.onPlaybackQueueMetadata("invalid JSON");
            drain(worker);
            events = read(store);
            check(events.size() == 3, "UTF-8 byte chunking or metadata deduplication failed");
            JSONObject richTrack = events.get(0).getJSONArray("tracks").getJSONObject(0);
            check(longTitle.equals(richTrack.getString("title")), "title lost");
            check(richTrack.getInt("queueId") == 0 && richTrack.getInt("durationSeconds") == 123, "queue metadata lost");
            check("artist-id".equals(richTrack.getJSONArray("artists").getJSONObject(0).getString("id")), "artist ID lost");
            check("album-id".equals(richTrack.getJSONObject("album").getString("id")), "album ID lost");
            for (JSONObject event : events) {
                check(event.toString().getBytes(StandardCharsets.UTF_8).length <= 60 * 1024, "metadata chunk exceeds size limit");
                check(!event.toString().contains("secret"), "unapproved metadata escaped allowlist");
            }
            rich.getJSONObject(0).put("title", "changed");
            Telemetry.onPlaybackQueueMetadata(rich.toString());
            drain(worker);
            check(read(store).size() == 6, "metadata change suppressed");
            // Restore the prior ID-only queue to exercise destination deduplication reset.
            String[] beforeDestination = new String[50000];
            Arrays.fill(beforeDestination, FIRST);
            Telemetry.onPlaybackQueue(beforeDestination);
            drain(worker);
            // Applying a new destination must allow the same loaded queue to be emitted.
            worker.submit(() -> {
                Method apply = Telemetry.class.getDeclaredMethod("applyConfiguration", TelemetryConfig.class);
                apply.setAccessible(true);
                apply.invoke(null, new TelemetryConfig("https://queue.example/api/events", "test", true));
                ((java.util.concurrent.ScheduledFuture<?>) field("flushTask").get(null)).cancel(false);
                field("flushScheduled").setBoolean(null, true);
                return null;
            }).get(30, TimeUnit.SECONDS);
            String[] same = new String[50000];
            Arrays.fill(same, FIRST);
            Telemetry.onPlaybackQueue(same);
            drain(worker);
            check(read(store).size() == 50, "destination change retained deduplication");
        } finally {
            worker.submit(() -> {
                field("store").set(null, null);
                field("enabled").setBoolean(null, false);
                field("lastPlaybackQueue").set(null, null);
                field("url").set(null, null);
                return null;
            }).get(30, TimeUnit.SECONDS);
        }
    }

    private static void drain(ScheduledThreadPoolExecutor worker) throws Exception {
        worker.submit(() -> {}).get(30, TimeUnit.SECONDS);
    }
    private static List<JSONObject> read(EventStore store) throws Exception {
        List<JSONObject> events = new ArrayList<>();
        try (Cursor cursor = store.getReadableDatabase().rawQuery("SELECT payload FROM events ORDER BY sequence", null)) {
            while (cursor.moveToNext()) events.add(new JSONObject(cursor.getString(0)));
        }
        return events;
    }
    private static Field field(String name) throws Exception {
        Field result = Telemetry.class.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
