package dev.selfhosted.music;

import android.content.Context;
import android.util.Log;
import org.json.JSONObject;
import java.io.OutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Injected entry points. Database and HTTP work run on one background thread. */
public final class Telemetry {
    private static final String TAG = "MusicTelemetry";
    private static final ScheduledThreadPoolExecutor WORKER = createWorker();
    private static final AtomicInteger PENDING = new AtomicInteger();
    private static final AtomicLong DROPPED_CALLBACKS = new AtomicLong();
    private static EventStore store;
    private static Context applicationContext;
    private static boolean enabled;
    private static ScheduledFuture<?> flushTask;
    private static URL url;
    private static String token;
    private static String sourcePackage;
    private static String videoId;
    private static String sessionId;
    private static long positionMs = -1;
    private static long lastProgressNanos;
    private static boolean flushScheduled;
    private static int failures;

    private Telemetry() {}

    private static ScheduledThreadPoolExecutor createWorker() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "music-telemetry");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static void submit(Runnable runnable) {
        if (PENDING.incrementAndGet() > 1024) {
            PENDING.decrementAndGet();
            DROPPED_CALLBACKS.incrementAndGet();
            return;
        }
        try {
            WORKER.execute(() -> {
                try { runnable.run(); }
                catch (Throwable failure) { warn("Event capture failed", failure); }
                finally { PENDING.decrementAndGet(); }
            });
        } catch (Throwable failure) {
            PENDING.decrementAndGet();
            DROPPED_CALLBACKS.incrementAndGet();
            warn("Cannot schedule capture", failure);
        }
    }

    public static void init(Context context) {
        try {
            if (context == null) return;
            final Context application = context.getApplicationContext();
            if (application == null) return;
            submit(() -> {
                if (applicationContext != null) return;
                applicationContext = application;
                sourcePackage = application.getPackageName();
                try { applyConfiguration(TelemetryConfig.load(application)); }
                catch (Exception failure) { warn("Telemetry initialization failed", failure); }
            });
        } catch (Throwable failure) { warn("Telemetry initialization failed", failure); }
    }

    interface SettingsCallback {
        void complete(TelemetryConfig config, String error);
    }

    // Settings use the same executor as capture/upload, so a completed Save is the
    // configuration boundary. A request already in progress may finish before it.
    static void loadSettings(Context context, SettingsCallback callback) {
        Context application = context.getApplicationContext();
        WORKER.execute(() -> {
            TelemetryConfig config = null;
            String error = null;
            try { config = TelemetryConfig.load(application); }
            catch (Exception failure) { error = "Cannot load telemetry settings. Save to replace them."; }
            notifySettings(callback, config, error);
        });
    }

    static void saveSettings(Context context, TelemetryConfig config, SettingsCallback callback) {
        String invalid = config.validationError();
        if (invalid != null) { notifySettings(callback, null, invalid); return; }
        Context application = context.getApplicationContext();
        WORKER.execute(() -> {
            try {
                config.save(application);
                applicationContext = application;
                sourcePackage = application.getPackageName();
                applyConfiguration(config);
                notifySettings(callback, config, null);
            } catch (Exception failure) {
                enabled = false;
                warn("Cannot save telemetry settings", failure);
                notifySettings(callback, null, "Cannot apply settings. Telemetry is paused; try saving again.");
            }
        });
    }

    private static void notifySettings(SettingsCallback callback, TelemetryConfig config, String error) {
        new Handler(Looper.getMainLooper()).post(() -> callback.complete(config, error));
    }

    private static void applyConfiguration(TelemetryConfig config) throws Exception {
        boolean wasEnabled = enabled;
        enabled = false;
        if (flushTask != null) flushTask.cancel(false);
        flushTask = null;
        flushScheduled = false;
        failures = 0;
        if (store == null) store = new EventStore(applicationContext);
        // Never deliver an old destination's backlog to a newly configured server.
        store.setDestination(config.endpoint);
        url = config.endpoint.isEmpty() ? null : new URL(config.endpoint);
        token = config.token;
        if (!wasEnabled || !config.enabled) {
            videoId = null;
            sessionId = null;
            positionMs = -1;
            lastProgressNanos = 0;
        }
        enabled = config.enabled;
        if (enabled) scheduleFlush(0);
    }

    public static void onTrack(String id) {
        final long time = System.currentTimeMillis();
        submit(() -> {
            if (!enabled || store == null || id == null || id.isEmpty() || id.equals(videoId)) return;
            videoId = id;
            sessionId = UUID.randomUUID().toString();
            positionMs = -1;
            lastProgressNanos = 0;
            record("track_loaded", time, null);
        });
    }

    /** Position argument is milliseconds, not seconds. */
    public static void onPosition(long milliseconds) {
        final long time = System.currentTimeMillis();
        final long monotonic = System.nanoTime();
        submit(() -> {
            if (!enabled || store == null || milliseconds < 0) return;
            positionMs = milliseconds;
            if (videoId == null || (lastProgressNanos != 0 && monotonic - lastProgressNanos < TimeUnit.SECONDS.toNanos(15))) return;
            lastProgressNanos = monotonic;
            record("playback_progress", time, null);
        });
    }

    public static void onRating(int rating) {
        final long time = System.currentTimeMillis();
        submit(() -> {
            if (rating < -1 || rating > 1) return;
            try {
                // The shared rating request can concern a library item other than the player.
                // Retain player context without claiming it is the request's target.
                JSONObject details = new JSONObject().put("rating", rating)
                        .put("videoId", JSONObject.NULL)
                        .put("contextVideoId", videoId == null ? JSONObject.NULL : videoId)
                        .put("videoIdBasis", "unresolved_rating_target");
                record(rating == 1 ? "like" : rating == -1 ? "dislike" : "remove_rating", time, details);
            }
            catch (Exception failure) { warn("Cannot capture rating", failure); }
        });
    }

    /** Target comes from the rating request payload, independently of the active player. */
    public static void onRating(String targetVideoId, int rating) {
        final long time = System.currentTimeMillis();
        submit(() -> {
            if (rating < -1 || rating > 1) return;
            try {
                boolean known = targetVideoId != null && targetVideoId.matches("[A-Za-z0-9_-]{11}");
                JSONObject details = new JSONObject().put("rating", rating)
                        .put("videoId", known ? targetVideoId : JSONObject.NULL)
                        .put("contextVideoId", videoId == null ? JSONObject.NULL : videoId)
                        .put("videoIdBasis", known ? "rating_request" : "unresolved_rating_target")
                        .put("observation", "request_built");
                record(rating == 1 ? "like" : rating == -1 ? "dislike" : "remove_rating", time, details);
            } catch (Exception failure) { warn("Cannot capture rating request", failure); }
        });
    }

    /** Only call from positively identified user command handlers. */
    public static void onAction(String action) {
        final long time = System.currentTimeMillis();
        submit(() -> {
            if ("skip_requested".equals(action) || "previous_requested".equals(action)
                    || "play_requested".equals(action) || "pause_requested".equals(action)
                    || "quick_play_requested".equals(action)) record(action, time, null);
        });
    }

    public static void onSkipNext() { mediaAction("skip_requested"); }
    public static void onSkipPrevious() { mediaAction("previous_requested"); }
    public static void onPlay() { mediaAction("play_requested"); }
    public static void onPause() { mediaAction("pause_requested"); }
    public static void onInAppSkipNext() { commandAction("skip_requested", "player_controls"); }
    public static void onInAppSkipPrevious() { commandAction("previous_requested", "player_controls"); }

    public static void bindCarouselItem(Object callback, android.view.View item) {
        try { CarouselCapture.bind(callback, item); }
        catch (Throwable failure) { warn("Cannot associate carousel item", failure); }
    }

    public static void onCarouselDispatch(Object callback, Object endpoint) {
        try { CarouselCapture.dispatch(callback, endpoint); }
        catch (Throwable failure) { warn("Cannot capture carousel selection", failure); }
    }

    static void carouselSelected(String targetVideoId, String heading) {
        final long time = System.currentTimeMillis();
        submit(() -> {
            try {
                record("carousel_song_selected", time, new JSONObject()
                        .put("videoId", targetVideoId).put("sourceTitle", heading)
                        .put("videoIdBasis", "watch_endpoint").put("origin", "music_carousel")
                        .put("contextVideoId", videoId == null ? JSONObject.NULL : videoId));
            } catch (Exception failure) { warn("Cannot persist carousel selection", failure); }
        });
    }

    private static void mediaAction(String action) {
        commandAction(action, "media_session");
    }

    private static void commandAction(String action, String origin) {
        final long time = System.currentTimeMillis();
        submit(() -> {
            try { record(action, time, new JSONObject().put("origin", origin)); }
            catch (Exception failure) { warn("Cannot capture media command", failure); }
        });
    }

    private static void record(String type, long time, JSONObject data) {
        if (!enabled || store == null) return;
        try {
            SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
            JSONObject event = new JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("observedAt", formatter.format(new Date(time))).put("event", "music_action").put("action", type)
                    .put("sourcePackage", sourcePackage)
                    .put("videoId", videoId == null ? JSONObject.NULL : videoId)
                    .put("playbackSessionId", sessionId == null ? JSONObject.NULL : sessionId)
                    .put("positionMs", positionMs).put("estimatedStartedAt", JSONObject.NULL)
                    .put("origin", JSONObject.NULL).put("droppedCallbacks", DROPPED_CALLBACKS.get());
            if (data != null) {
                java.util.Iterator<String> keys = data.keys();
                while (keys.hasNext()) { String key = keys.next(); event.put(key, data.get(key)); }
            }
            store.append(event);
            scheduleFlush(1);
        } catch (Exception failure) { warn("Cannot persist event", failure); }
    }

    private static void scheduleFlush(long delaySeconds) {
        if (!enabled || flushScheduled) return;
        flushScheduled = true;
        flushTask = WORKER.schedule(() -> {
            flushScheduled = false;
            try { flush(); }
            catch (Throwable failure) {
                warn("Upload deferred", failure);
                failures = Math.min(failures + 1, 8);
                scheduleFlush(Math.min(300, 1L << failures));
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    private static void flush() throws Exception {
        if (!enabled || store == null || url == null) return;
        List<JSONObject> batch = store.batch();
        if (batch.isEmpty()) { failures = 0; return; }
        // Yield to queued captures after each request, even when there is a large backlog.
        JSONObject event = batch.get(0);
        post(event.toString());
        List<String> ids = new ArrayList<>();
        ids.add(event.getString("id"));
        store.acknowledge(ids);
        failures = 0;
        scheduleFlush(1);
    }

    // Listen EventUploader contract: full configured URL, one object, any 2xx acknowledges id.
    private static void post(String payload) throws Exception {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new UploadFailure(status);
        } finally { connection.disconnect(); }
    }

    private static void warn(String message, Throwable failure) {
        // Avoid exception messages/stack traces, which can contain endpoint or credentials.
        String status = failure instanceof UploadFailure
                ? ", HTTP " + ((UploadFailure) failure).status : "";
        try { Log.w(TAG, message + " (" + failure.getClass().getSimpleName() + status + ")"); }
        catch (Throwable ignored) { /* Never interfere with the host app. */ }
    }

    private static final class UploadFailure extends IOException {
        private static final long serialVersionUID = 1L;
        final int status;
        UploadFailure(int status) { this.status = status; }
    }
}
