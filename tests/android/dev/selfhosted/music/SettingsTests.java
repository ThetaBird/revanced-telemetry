package dev.selfhosted.music;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Exercise the real settings form and production uploader, using an in-memory HTTPS transport. */
final class SettingsTests {
    private static final BlockingQueue<Request> REQUESTS = new LinkedBlockingQueue<>();
    private static volatile int response = 201;
    private SettingsTests() {}

    static void run(Instrumentation instrumentation) throws Exception {
        validation();
        URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) {
                return new HttpURLConnection(url) {
                    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    @Override public void connect() {}
                    @Override public void disconnect() {}
                    @Override public boolean usingProxy() { return false; }
                    @Override public OutputStream getOutputStream() { return bytes; }
                    @Override public int getResponseCode() {
                        REQUESTS.add(new Request(url.toString(), getRequestProperty("Authorization"),
                                bytes.toString(StandardCharsets.UTF_8)));
                        return response;
                    }
                };
            }
        } : null);
        ScheduledThreadPoolExecutor worker = (ScheduledThreadPoolExecutor) field("WORKER").get(null);
        File configFile = new File(instrumentation.getTargetContext().getNoBackupFilesDir(), "music-telemetry-settings.json");
        check(!configFile.exists() || configFile.delete(), "cannot reset configuration fixture");
        instrumentation.getTargetContext().deleteDatabase("selfhosted_music_telemetry.db");
        CaptureActivity activity = (CaptureActivity) instrumentation.startActivitySync(
                new Intent(instrumentation.getTargetContext(), CaptureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            Telemetry.init(activity);
            barrier(worker);
            check(!field("enabled").getBoolean(null), "unconfigured app captured events");
            check(field("url").get(null) == null, "built-in endpoint exists");
            Telemetry.onTrack("initial1234");
            barrier(worker);
            EventStore store = (EventStore) field("store").get(null);
            check(store.batch().isEmpty() && REQUESTS.isEmpty(), "unconfigured telemetry emitted an event");
            instrumentation.runOnMainSync(() -> {
                check(!TelemetrySettings.onPreferenceClick(activity, "unrelated"), "unrelated preference consumed");
                check(TelemetrySettings.onPreferenceClick(activity, "selfhosted_music_telemetry"), "settings entry unhandled");
            });
            barrier(worker);
            instrumentation.waitForIdleSync();
            AccessibilityNodeInfo endpoint = find(instrumentation, "Endpoint URL", false);
            AccessibilityNodeInfo token = find(instrumentation, "Listen write token", false);
            check(token.isPassword(), "token input is not masked");
            check(endpoint.getText() == null || !endpoint.getText().toString().startsWith("https://"), "endpoint prefilled");
            set(endpoint, "http://example.invalid/api/events");
            set(token, "test-token-one");
            find(instrumentation, "Enable telemetry", true).performAction(AccessibilityNodeInfo.ACTION_CLICK);
            find(instrumentation, "Save", true).performAction(AccessibilityNodeInfo.ACTION_CLICK);
            instrumentation.waitForIdleSync();
            check(!configFile.exists(), "invalid settings persisted");
            set(find(instrumentation, "Endpoint URL", false), "https://one.invalid/api/events");
            find(instrumentation, "Save", true).performAction(AccessibilityNodeInfo.ACTION_CLICK);
            barrier(worker);
            instrumentation.waitForIdleSync();
            TelemetryConfig first = TelemetryConfig.load(activity);
            check(first.enabled && first.endpoint.equals("https://one.invalid/api/events")
                    && first.token.equals("test-token-one"), "UI settings not persisted");
            Telemetry.onTrack("firsttrack1");
            Request request = take();
            check(request.url.equals(first.endpoint) && request.authorization.equals("Bearer test-token-one"), "saved settings not used for upload");
            check(new JSONObject(request.body).getString("videoId").equals("firsttrack1"), "wrong uploaded payload");
            barrier(worker);

            // A failed upload remains queued. Token rotation retries it with the new token.
            response = 503;
            Telemetry.onTrack("secondtrack");
            Request failed = take();
            barrier(worker);
            check(!store.batch().isEmpty(), "failed upload lost queue");
            response = 201;
            save(instrumentation, new TelemetryConfig(first.endpoint, "test-token-two", true));
            Request retried = take();
            check(retried.authorization.equals("Bearer test-token-two"), "token change requires restart");
            check(new JSONObject(failed.body).getString("id").equals(new JSONObject(retried.body).getString("id")), "token rotation changed retry identity");
            barrier(worker);

            Telemetry.onPosition(15000);
            Request progress = take();
            JSONObject progressPayload = new JSONObject(progress.body);
            check(progressPayload.getString("videoId").equals("secondtrack")
                    && progressPayload.getString("playbackSessionId").equals(new JSONObject(retried.body).getString("playbackSessionId")),
                    "token rotation lost active playback context");
            barrier(worker);
            save(instrumentation, new TelemetryConfig(first.endpoint, "test-token-two", false));
            Telemetry.onTrack("disabled123");
            barrier(worker);
            check(store.batch().isEmpty() && REQUESTS.isEmpty(), "disabled capture or upload continued");
            // Existing backlog must never be redirected to another configured server.
            worker.submit(() -> { store.append(new JSONObject().put("id", "old-server-event")); return null; }).get(30, TimeUnit.SECONDS);
            save(instrumentation, new TelemetryConfig("https://two.invalid/api/events", "test-token-three", true));
            barrier(worker);
            check(store.batch().isEmpty() && REQUESTS.isEmpty(), "old destination queue leaked");
            Telemetry.onTrack("thirdtrack1");
            Request changed = take();
            check(changed.url.equals("https://two.invalid/api/events") && changed.authorization.equals("Bearer test-token-three"), "endpoint change requires restart");
            barrier(worker);

            // Reinitialize runtime state from disk, as after application startup.
            worker.submit(() -> {
                field("enabled").setBoolean(null, false);
                store.close();
                field("store").set(null, null);
                field("applicationContext").set(null, null);
                return null;
            }).get(30, TimeUnit.SECONDS);
            Telemetry.init(activity);
            barrier(worker);
            check(field("enabled").getBoolean(null) && field("url").get(null).toString().equals("https://two.invalid/api/events"), "saved configuration not restored on init");
            save(instrumentation, new TelemetryConfig("", "", false));
            check(TelemetryConfig.load(activity).token.isEmpty(), "credentials cannot be cleared");
        } finally {
            worker.submit(() -> {
                field("enabled").setBoolean(null, false);
                EventStore store = (EventStore) field("store").get(null);
                if (store != null) store.close();
                field("store").set(null, null);
                field("applicationContext").set(null, null);
                return null;
            }).get(30, TimeUnit.SECONDS);
            instrumentation.runOnMainSync(activity::finish);
            check(!configFile.exists() || configFile.delete(), "cannot remove fixture credentials");
        }
    }

    private static void validation() {
        String[] invalid = {"http://host/api/events", "https://user@host/api/events", "https://host/api/events?token=x",
                "https://host/api/events#x", "https://host/wrong", "https://host:0/api/events", "https://host:65536/api/events"};
        for (String endpoint : invalid) check(new TelemetryConfig(endpoint, "token", true).validationError() != null, "invalid endpoint accepted");
        check(new TelemetryConfig("", "", true).validationError() != null, "empty enabled config accepted");
        check(new TelemetryConfig("https://host/api/events", "bad\nheader", true).validationError() != null, "header injection accepted");
        check(new TelemetryConfig("https://host/api/events", " ", true).validationError() != null, "blank token accepted");
        check(new TelemetryConfig("", "", false).validationError() == null, "empty disabled config rejected");
    }

    private static void save(Instrumentation instrumentation, TelemetryConfig config) throws Exception {
        BlockingQueue<String> result = new LinkedBlockingQueue<>();
        Telemetry.saveSettings(instrumentation.getTargetContext(), config, (saved, error) -> result.add(error == null ? "ok" : error));
        check("ok".equals(result.poll(30, TimeUnit.SECONDS)), "settings save failed");
    }
    private static Request take() throws Exception {
        Request request = REQUESTS.poll(15, TimeUnit.SECONDS);
        check(request != null, "expected upload missing");
        return request;
    }
    private static AccessibilityNodeInfo find(Instrumentation instrumentation, String name, boolean text) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            AccessibilityNodeInfo found = search(instrumentation.getUiAutomation().getRootInActiveWindow(), name, text);
            if (found != null && found.isEnabled()) return found;
            Thread.sleep(100);
        }
        throw new AssertionError("settings control not found: " + name);
    }
    private static AccessibilityNodeInfo search(AccessibilityNodeInfo node, String name, boolean text) {
        if (node == null) return null;
        CharSequence label = text ? node.getText() : node.getContentDescription();
        if (label != null && name.equalsIgnoreCase(label.toString())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = search(node.getChild(i), name, text);
            if (child != null) return child;
        }
        return null;
    }
    private static void set(AccessibilityNodeInfo node, String text) {
        Bundle arguments = new Bundle();
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments), "could not edit setting");
    }
    private static void barrier(ScheduledThreadPoolExecutor worker) throws Exception { worker.submit(() -> {}).get(30, TimeUnit.SECONDS); }
    private static Field field(String name) throws Exception { Field field = Telemetry.class.getDeclaredField(name); field.setAccessible(true); return field; }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static final class Request {
        final String url;
        final String authorization;
        final String body;
        Request(String url, String authorization, String body) { this.url = url; this.authorization = authorization; this.body = body; }
    }
}
