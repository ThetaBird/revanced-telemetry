package dev.selfhosted.music;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Used only by Telemetry's single background executor. */
final class EventStore extends SQLiteOpenHelper {
    private static final int MAX_EVENTS = 10000;

    EventStore(Context context) { super(context, "selfhosted_music_telemetry.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE metadata (name TEXT PRIMARY KEY, value TEXT NOT NULL)");
        db.execSQL("CREATE TABLE events (sequence INTEGER PRIMARY KEY, event_id TEXT UNIQUE NOT NULL, payload TEXT NOT NULL)");
        put(db, "device_id", UUID.randomUUID().toString());
        put(db, "sequence", "0");
        put(db, "dropped_events", "0");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported telemetry database version");
    }

    private static String get(SQLiteDatabase db, String key) {
        try (Cursor c = db.query("metadata", new String[]{"value"}, "name=?", new String[]{key}, null, null, null)) {
            if (!c.moveToFirst()) throw new IllegalStateException("Missing telemetry metadata");
            return c.getString(0);
        }
    }

    private static void put(SQLiteDatabase db, String key, String value) {
        ContentValues values = new ContentValues();
        values.put("name", key);
        values.put("value", value);
        db.insertWithOnConflict("metadata", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    void setDestination(String endpoint) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String previous = null;
            try (Cursor cursor = db.rawQuery("SELECT value FROM metadata WHERE name='destination'", null)) {
                if (cursor.moveToFirst()) previous = cursor.getString(0);
            }
            if (!endpoint.equals(previous)) db.delete("events", null, null);
            put(db, "destination", endpoint);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    void append(JSONObject event) throws Exception {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long sequence = Long.parseLong(get(db, "sequence")) + 1;
            long count;
            try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM events", null)) {
                c.moveToFirst(); count = c.getLong(0);
            }
            if (count >= MAX_EVENTS) {
                long remove = count - MAX_EVENTS + 1;
                db.execSQL("DELETE FROM events WHERE sequence IN (SELECT sequence FROM events ORDER BY sequence LIMIT ?)", new Object[]{remove});
                put(db, "dropped_events", Long.toString(Long.parseLong(get(db, "dropped_events")) + remove));
            }
            event.put("sequence", sequence);
            event.put("deviceId", get(db, "device_id"));
            event.put("droppedEvents", Long.parseLong(get(db, "dropped_events")));
            ContentValues values = new ContentValues();
            values.put("sequence", sequence);
            values.put("event_id", event.getString("id"));
            values.put("payload", event.toString());
            db.insertOrThrow("events", null, values);
            put(db, "sequence", Long.toString(sequence));
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    List<JSONObject> batch() throws Exception {
        List<JSONObject> events = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("events", new String[]{"payload"}, null, null, null, null, "sequence ASC", "1")) {
            while (c.moveToNext()) events.add(new JSONObject(c.getString(0)));
        }
        return events;
    }

    void acknowledge(List<String> ids) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (String id : ids) db.delete("events", "event_id=?", new String[]{id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
}
