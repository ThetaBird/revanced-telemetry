package dev.selfhosted.music;

import android.app.Instrumentation;
import android.database.Cursor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Real SQLite payload checks for UI commands and independent rating/queue targets. */
final class ControlEventsTests {
    private static final String CURRENT = "context1234";
    private static final String TARGET = "abcdefghijk";
    private enum Mode { LOOP_OFF, LOOP_ALL, LOOP_ONE, LOOP_DISABLED, UNKNOWN }
    private ControlEventsTests() {}

    static void run(Instrumentation instrumentation) throws Exception {
        instrumentation.getTargetContext().deleteDatabase("selfhosted_music_telemetry.db");
        ScheduledThreadPoolExecutor worker = (ScheduledThreadPoolExecutor) field("WORKER").get(null);
        try (EventStore store = new EventStore(instrumentation.getTargetContext())) {
            worker.submit(() -> {
                field("store").set(null, store);
                field("enabled").setBoolean(null, true);
                field("flushScheduled").setBoolean(null, true);
                field("sourcePackage").set(null, "com.google.android.apps.youtube.music");
                field("videoId").set(null, CURRENT);
                field("contextVideoId").set(null, CURRENT);
                field("contextPlaylistId").set(null, "PL_current");
                field("positionMs").setLong(null, 123);
                return null;
            }).get(30, TimeUnit.SECONDS);
            for (Mode mode : Mode.values()) Telemetry.onRepeatMode(mode);
            Telemetry.onRepeatMode(null);
            Telemetry.onRepeatMode("LOOP_ONE");
            Telemetry.onQueueSongSelected(TARGET, "9223372036854775807", "PL_target", 7);
            Telemetry.onQueueSongSelected(TARGET, "second-occurrence", "PL_target", 8);
            Telemetry.onQueueSongSelected("invalid", null, null, -1);
            Telemetry.onRating(TARGET, 1);
            Telemetry.onRating(TARGET, -1);
            Telemetry.onRating(TARGET, 0);
            drain(worker);
            List<JSONObject> events = read(store);
            check(events.size() == 9, "missing command or accepted unknown repeat mode");
            String[] modes = {"off", "all", "one"};
            String[] scopes = {"off", "queue", "song"};
            int[] modeCodes = {0, 2, 1};
            for (int i = 0; i < 3; i++) {
                JSONObject repeat = events.get(i);
                check("repeat_mode_changed".equals(repeat.getString("action")), "repeat action");
                check(modes[i].equals(repeat.getString("repeatModeName")), "repeat mode mapping");
                check(repeat.getInt("repeatMode") == modeCodes[i], "numeric receiver repeat mode");
                check(scopes[i].equals(repeat.getString("repeatScope")), "repeat scope mapping");
                check("PL_current".equals(repeat.getString("playlistId")), "repeat playlist context");
                check(CURRENT.equals(repeat.getString("videoId")), "repeat song context");
                check("command_applied".equals(repeat.getString("observation")), "repeat boundary");
            }
            for (int i = 3; i <= 4; i++) {
                JSONObject selected = events.get(i);
                check("queue_song_selected".equals(selected.getString("action")), "queue click action");
                check(TARGET.equals(selected.getString("videoId")), "clicked target replaced by player context");
                check(CURRENT.equals(selected.getString("contextVideoId")), "player context lost");
                check("PL_target".equals(selected.getString("playlistId")), "clicked playlist replaced by player context");
                check(selected.getInt("playlistIndex") == i + 4, "clicked index lost");
                check("playback_queue".equals(selected.getString("origin")), "queue origin");
            }
            check("9223372036854775807".equals(events.get(3).getString("queueId")), "queue ID precision lost");
            check(!events.get(3).getString("id").equals(events.get(4).getString("id")), "distinct clicks deduplicated");
            check(events.get(5).isNull("videoId"), "unresolved click claimed current song");
            String[] ratings = {"like", "dislike", "remove_rating"};
            for (int i = 0; i < 3; i++) {
                check(ratings[i].equals(events.get(i + 6).getString("action")), "rating action mapping");
                check(TARGET.equals(events.get(i + 6).getString("videoId")), "rating target lost");
            }
            check(CURRENT.equals(field("videoId").get(null)) && field("positionMs").getLong(null) == 123,
                    "commands changed player context");
            check(events.get(0).toString().equals(store.batch().get(0).toString()), "retry payload changed");
            worker.submit(() -> { field("contextVideoId").set(null, TARGET); return null; }).get(30, TimeUnit.SECONDS);
            Telemetry.onRepeatMode(Mode.LOOP_ALL);
            drain(worker);
            check(read(store).get(9).isNull("playlistId"), "stale playlist context attached to repeat");
            worker.submit(() -> { field("enabled").setBoolean(null, false); return null; }).get(30, TimeUnit.SECONDS);
            Telemetry.onRepeatMode(Mode.LOOP_ONE);
            Telemetry.onQueueSongSelected(TARGET, "disabled", "PL_target", 0);
            Telemetry.onRating(TARGET, 1);
            drain(worker);
            check(read(store).size() == 10, "disabled command capture leaked");
        } finally {
            worker.submit(() -> {
                field("store").set(null, null);
                field("enabled").setBoolean(null, false);
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
        Field value = Telemetry.class.getDeclaredField(name);
        value.setAccessible(true);
        return value;
    }
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
