package dev.selfhosted.music;

import android.app.Instrumentation;
import android.database.Cursor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

final class PlaylistEventsTests {
    private PlaylistEventsTests() {}

    static void run(Instrumentation instrumentation) throws Exception {
        instrumentation.getTargetContext().deleteDatabase("selfhosted_music_telemetry.db");
        ScheduledThreadPoolExecutor worker = (ScheduledThreadPoolExecutor) field("WORKER").get(null);
        try (EventStore store = new EventStore(instrumentation.getTargetContext())) {
            worker.submit(() -> {
                field("store").set(null, store);
                field("enabled").setBoolean(null, true);
                field("flushScheduled").setBoolean(null, true);
                field("sourcePackage").set(null, "com.google.android.apps.youtube.music");
                for (String name : new String[]{"videoId", "contextVideoId", "contextPlaylistId",
                        "activePlaylistId", "lastOpenedPlaylist"}) field(name).set(null, null);
                return null;
            }).get(30, TimeUnit.SECONDS);
            Telemetry.onPlaylistContext("abcdefghijk", "PL_first", 2);
            drain(worker);
            check(read(store).isEmpty(), "queue context alone claimed playback");
            Telemetry.onTrack("abcdefghijk");
            Telemetry.onPlaylistContext("abcdefghijk", "PL_first", 2);
            Telemetry.onPlaylistContext("123456789_-", "PL_first", 3);
            Telemetry.onTrack("123456789_-");
            drain(worker);
            List<JSONObject> events = read(store);
            check(events.size() == 3, "same playlist emitted repeated starts");
            JSONObject started = events.get(1);
            check("playlist_playback_started".equals(started.getString("action")), "start missing");
            check("PL_first".equals(started.getString("playlistId")), "playlist ID lost");
            check(started.getInt("playlistIndex") == 2, "playlist index lost");
            check("track_loaded".equals(started.getString("observation")), "overclaimed audible playback");
            Telemetry.onPlaylistContext(null, null, -1);
            Telemetry.onPlaylistContext("123456789_-", "PL_first", 0);
            drain(worker);
            check(read(store).size() == 4, "re-entering playlist not observed");
            String tracks = "[{\"videoId\":\"abcdefghijk\",\"setVideoId\":\"occurrence\",\"title\":\"Song\"}]";
            String metadata = "{\"description\":\"Playlist description\",\"requestToken\":\"private\","
                    + "\"metadataRuns\":[{\"text\":\"Owner\",\"browseId\":\"UC_owner\"}]}";
            Telemetry.onOpenedPlaylistMetadata("PL_first", "Playlist", tracks, metadata);
            Telemetry.onOpenedPlaylistMetadata("PL_first", "Playlist", tracks, metadata);
            drain(worker);
            events = read(store);
            check(events.size() == 5, "opened snapshot duplicate suppression");
            JSONObject snapshot = events.get(4);
            check("playlist_snapshot".equals(snapshot.getString("event")), "opened playlist event type");
            check(!snapshot.getBoolean("complete"), "partial page claimed complete");
            check("loaded_playlist".equals(snapshot.getString("snapshotScope")), "page scope lost");
            JSONObject playlistMetadata = snapshot.getJSONObject("playlistMetadata");
            check("Playlist description".equals(playlistMetadata.getString("description")), "playlist description lost");
            check("UC_owner".equals(playlistMetadata.getJSONArray("metadataRuns").getJSONObject(0).getString("browseId")), "owner link lost");
            check(!playlistMetadata.has("requestToken"), "request token leaked");
            check("occurrence".equals(snapshot.getJSONArray("tracks").getJSONObject(0).getString("setVideoId")), "occurrence ID lost");
            Telemetry.onPlaylistOpened();
            Telemetry.onOpenedPlaylist("PL_first", "Playlist", tracks);
            drain(worker);
            check(read(store).size() == 6, "reopened identical playlist suppressed");
            worker.submit(() -> { field("enabled").setBoolean(null, false); return null; }).get(30, TimeUnit.SECONDS);
            Telemetry.onOpenedPlaylist("PL_second", "Second", "[]");
            Telemetry.onPlaylistContext("123456789_-", "PL_second", 0);
            drain(worker);
            check(read(store).size() == 6, "disabled playlist events leaked");
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
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
