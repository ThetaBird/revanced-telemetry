package dev.selfhosted.music;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import org.json.JSONObject;

/** Immutable configuration; all file access is on Telemetry's worker. */
final class TelemetryConfig {
    final String endpoint;
    final String token;
    final boolean enabled;

    TelemetryConfig(String endpoint, String token, boolean enabled) {
        this.endpoint = endpoint.trim();
        this.token = token;
        this.enabled = enabled;
    }

    String validationError() {
        if (endpoint.isEmpty() && token.isEmpty() && !enabled) return null;
        try {
            URI uri = new URI(endpoint);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !uri.getPath().endsWith("/api/events") || uri.getPort() == 0 || uri.getPort() > 65535) {
                return "Enter a full HTTPS URL ending in /api/events, without credentials, a query or a fragment.";
            }
        } catch (Exception invalid) {
            return "Enter a valid HTTPS URL ending in /api/events.";
        }
        if (token.trim().isEmpty()) return "Enter your Listen write token.";
        for (int i = 0; i < token.length(); i++) {
            if (token.charAt(i) < 32 || token.charAt(i) > 126) return "The token must contain only printable ASCII characters.";
        }
        return null;
    }

    private static AtomicFile file(Context context) {
        return new AtomicFile(new File(context.getNoBackupFilesDir(), "music-telemetry-settings.json"));
    }

    static TelemetryConfig load(Context context) throws Exception {
        AtomicFile file = file(context);
        if (!file.getBaseFile().exists()) return new TelemetryConfig("", "", false);
        JSONObject data = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
        TelemetryConfig config = new TelemetryConfig(data.getString("endpoint"), data.getString("token"), data.getBoolean("enabled"));
        if (config.validationError() != null) throw new IllegalStateException("Invalid saved telemetry settings");
        return config;
    }

    void save(Context context) throws Exception {
        if (validationError() != null) throw new IllegalArgumentException("Invalid telemetry settings");
        byte[] bytes = new JSONObject().put("endpoint", endpoint).put("token", token)
                .put("enabled", enabled).toString().getBytes(StandardCharsets.UTF_8);
        AtomicFile file = file(context);
        FileOutputStream stream = file.startWrite();
        try {
            stream.write(bytes);
            file.finishWrite(stream);
        } catch (Exception failure) {
            file.failWrite(stream);
            throw failure;
        }
    }
}
