package dev.selfhosted.music;

import android.app.Instrumentation;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteConstraintException;
import android.os.Bundle;
import org.json.JSONObject;
import java.util.Collections;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Dependency-free tests against Android's actual SQLite implementation. */
public final class QueueInstrumentation extends Instrumentation {
    private static final String DATABASE = "selfhosted_music_telemetry.db";

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            persistenceAndAcknowledgments();
            capacityAndRollback();
            callbackPayloads();
            RuntimeCaptureTests.run(this);
            PlaylistSnapshotTests.run(this);
            NativeQueueCaptureTests.run();
            OpenedPlaylistCaptureTests.run();
            PlaylistEventsTests.run(this);
            ControlEventsTests.run(this);
            QueueSelectionCaptureTests.run();
            SettingsTests.run(this);
            result.putString("stream", "\nPASS: persistence, stable retries, acknowledgments, capacity, eviction counters, transaction rollback, callback payloads, progress throttling, exact rating targets, repeat button modes, queue song selection targets, player commands, carousel protobuf and visible hierarchy; playback queue snapshots, ordered duplicates, immutable capture, chunk bounds, stable retries and destination resets; settings UI, persistence, validation, runtime URL/token changes, disabled capture, destination isolation\n");
            finish(-1, result);
        } catch (Throwable failure) {
            result.putString("stream", "\nFAIL: " + android.util.Log.getStackTraceString(failure));
            finish(1, result);
        } finally { getTargetContext().deleteDatabase(DATABASE); }
    }

    private void persistenceAndAcknowledgments() throws Exception {
        getTargetContext().deleteDatabase(DATABASE);
        String device;
        try (EventStore store = new EventStore(getTargetContext())) {
            store.append(event("first"));
            JSONObject first = store.batch().get(0);
            device = first.getString("deviceId");
            check(!device.isEmpty(), "device identity missing");
            check(first.getLong("sequence") == 1, "initial sequence");
            check(first.toString().equals(store.batch().get(0).toString()), "retry payload changed");
            store.acknowledge(Collections.singletonList("unknown"));
            check(store.batch().get(0).getString("id").equals("first"), "unrelated acknowledgment removed event");
        }
        try (EventStore store = new EventStore(getTargetContext())) {
            check(store.batch().get(0).getString("id").equals("first"), "event lost on reopen");
            store.append(event("second"));
            store.acknowledge(Collections.singletonList("first"));
            JSONObject second = store.batch().get(0);
            check(second.getString("id").equals("second"), "acknowledgment removed wrong row");
            check(second.getString("deviceId").equals(device), "device identity changed");
            check(second.getLong("sequence") == 2, "sequence changed on reopen");
            store.acknowledge(Collections.singletonList("second"));
            check(store.batch().isEmpty(), "acknowledged queue not empty");
        }
        try (EventStore store = new EventStore(getTargetContext())) {
            store.append(event("third"));
            JSONObject third = store.batch().get(0);
            check(third.getLong("sequence") == 3, "empty queue reset sequence");
            check(third.getString("deviceId").equals(device), "empty queue reset identity");
        }
    }

    private void capacityAndRollback() throws Exception {
        getTargetContext().deleteDatabase(DATABASE);
        try (EventStore store = new EventStore(getTargetContext())) {
            // Outer transaction only accelerates fixture construction. Each append still runs
            // the production insertion/eviction logic with nested Android transactions.
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                for (int i = 1; i <= 10000; i++) store.append(event("event-" + i));
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            check(count(db) == 10000, "initial capacity");
            boolean rejected = false;
            try { store.append(event("event-5000")); }
            catch (SQLiteConstraintException expected) { rejected = true; }
            check(rejected, "duplicate id accepted");
            check(count(db) == 10000, "failed insertion changed row count");
            check(store.batch().get(0).getString("id").equals("event-1"), "failed insert committed eviction");
            store.append(event("overflow"));
            check(count(db) == 10000, "queue exceeded capacity");
            check(store.batch().get(0).getString("id").equals("event-2"), "oldest event not evicted");
            try (Cursor c = db.rawQuery("SELECT payload FROM events WHERE event_id='overflow'", null)) {
                check(c.moveToFirst(), "overflow event missing");
                JSONObject overflow = new JSONObject(c.getString(0));
                check(overflow.getLong("sequence") == 10001, "failed insertion consumed sequence");
                check(overflow.getLong("droppedEvents") == 1, "failed insertion committed drop counter");
            }
        }
        try (EventStore store = new EventStore(getTargetContext())) {
            store.append(event("overflow-after-reopen"));
            try (Cursor c = store.getReadableDatabase().rawQuery("SELECT payload FROM events WHERE event_id='overflow-after-reopen'", null)) {
                check(c.moveToFirst(), "new event missing after reopen");
                JSONObject event = new JSONObject(c.getString(0));
                check(event.getLong("sequence") == 10002, "sequence not durable after eviction");
                check(event.getLong("droppedEvents") == 2, "drop counter not durable");
            }
        }
    }

    private void callbackPayloads() throws Exception {
        getTargetContext().deleteDatabase(DATABASE);
        ScheduledThreadPoolExecutor worker = (ScheduledThreadPoolExecutor) field("WORKER").get(null);
        String trackId = "abcdefghijk";
        try (EventStore store = new EventStore(getTargetContext())) {
            // Isolate callback-to-SQLite behavior. This deliberately does not test init or HTTP.
            worker.submit(() -> {
                field("store").set(null, store);
                field("enabled").setBoolean(null, true);
                field("sourcePackage").set(null, "com.google.android.apps.youtube.music");
                field("flushScheduled").setBoolean(null, true);
                return null;
            }).get(30, TimeUnit.SECONDS);
            Telemetry.onTrack(trackId);
            Telemetry.onTrack(trackId);
            Telemetry.onPosition(123);
            Telemetry.onPosition(124);
            Telemetry.onRating(1);
            Telemetry.onRating(99);
            Telemetry.onSkipNext();
            Telemetry.onPlay();
            worker.submit(() -> {}).get(30, TimeUnit.SECONDS);

            List<JSONObject> events = new ArrayList<>();
            try (Cursor cursor = store.getReadableDatabase().rawQuery("SELECT payload FROM events ORDER BY sequence", null)) {
                while (cursor.moveToNext()) events.add(new JSONObject(cursor.getString(0)));
            }
            check(events.size() == 5, "callback deduplication/throttling or invalid-rating filtering failed");
            String deviceId = events.get(0).getString("deviceId");
            for (int index = 0; index < events.size(); index++) {
                JSONObject event = events.get(index);
                check(event.getString("event").equals("music_action"), "Listen event envelope missing");
                UUID.fromString(event.getString("id"));
                UUID.fromString(event.getString("deviceId"));
                Instant.parse(event.getString("observedAt"));
                check(event.getString("deviceId").equals(deviceId), "callback device identity changed");
                check(event.getLong("sequence") == index + 1, "callback ordering lost");
                check(event.getString("sourcePackage").equals("com.google.android.apps.youtube.music"), "wrong source package");
                check(!event.has("event_id") && !event.has("observed_at") && !event.has("device_id"), "obsolete wire fields emitted");
            }
            JSONObject track = events.get(0);
            check(track.getString("action").equals("track_loaded"), "track action missing");
            check(track.getString("videoId").equals(trackId), "track target incorrect");
            check(track.isNull("origin"), "unobserved track origin invented");
            JSONObject progress = events.get(1);
            check(progress.getString("action").equals("playback_progress"), "progress action missing");
            check(progress.getLong("positionMs") == 123, "first progress position incorrect");
            JSONObject rating = events.get(2);
            check(rating.getString("action").equals("like") && rating.getInt("rating") == 1, "rating action incorrect");
            check(rating.isNull("videoId"), "rating incorrectly claims current track as target");
            check(rating.getString("contextVideoId").equals(trackId), "rating lost current player context");
            check(rating.getString("videoIdBasis").equals("unresolved_rating_target"), "rating target uncertainty missing");
            check(events.get(3).getString("action").equals("skip_requested"), "skip callback action incorrect");
            check(events.get(4).getString("action").equals("play_requested"), "play callback action incorrect");
            check(events.get(3).getString("origin").equals("media_session"), "skip dispatch origin missing");
            check(events.get(4).getString("origin").equals("media_session"), "play dispatch origin missing");
        } finally {
            worker.submit(() -> { field("store").set(null, null); field("enabled").setBoolean(null, false); return null; }).get(30, TimeUnit.SECONDS);
        }
    }

    private static Field field(String name) throws Exception {
        Field field = Telemetry.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static JSONObject event(String id) throws Exception {
        return new JSONObject().put("id", id).put("event", "music_action")
                .put("action", "track_loaded").put("observedAt", "2026-09-07T00:00:00Z");
    }

    private static long count(SQLiteDatabase db) {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM events", null)) { c.moveToFirst(); return c.getLong(0); }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
