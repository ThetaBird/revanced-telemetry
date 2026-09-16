package dev.selfhosted.music;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Receiver-compatible parts of the loaded queue, not a complete saved playlist. */
final class PlaylistSnapshot {
    static final int MAX_TRACKS = 50000;
    private static final int TRACK_BYTES_PER_PART = 50 * 1024;
    private static final int TRACKS_PER_PART = 1000;
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    private PlaylistSnapshot() {}

    static void normalize(String[] ids) {
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] != null && !VIDEO_ID.matcher(ids[i]).matches()) ids[i] = null;
        }
    }

    static JSONArray normalize(JSONArray input) throws Exception {
        if (input.length() > MAX_TRACKS) throw new IllegalArgumentException("Queue exceeds track limit");
        JSONArray tracks = new JSONArray();
        for (int i = 0; i < input.length(); i++) {
            JSONObject source = input.optJSONObject(i);
            JSONObject track = new JSONObject().put("position", i).put("videoId", JSONObject.NULL);
            if (source != null) {
                Object id = source.opt("videoId");
                if (id instanceof String && VIDEO_ID.matcher((String) id).matches()) track.put("videoId", id);
                for (String key : new String[]{"title", "subtitle", "secondaryText", "browseId", "setVideoId", "playlistId", "likeStatus"}) {
                    Object value = source.opt(key);
                    if (value instanceof String) track.put(key, value);
                }
                JSONArray runs = source.optJSONArray("metadataRuns");
                if (runs != null) {
                    JSONArray retained = new JSONArray();
                    for (int j = 0; j < runs.length(); j++) {
                        JSONObject run = runs.optJSONObject(j);
                        if (run == null) continue;
                        JSONObject metadata = new JSONObject();
                        for (String key : new String[]{"text", "videoId", "browseId"}) {
                            if (run.opt(key) instanceof String) metadata.put(key, run.getString(key));
                        }
                        if (metadata.length() > 0) retained.put(metadata);
                    }
                    track.put("metadataRuns", retained);
                }
                JSONArray columns = source.optJSONArray("displayColumns");
                if (columns != null) {
                    JSONArray retained = new JSONArray();
                    for (int j = 0; j < columns.length(); j++) {
                        if (columns.opt(j) instanceof String) retained.put(columns.getString(j));
                    }
                    track.put("displayColumns", retained);
                }
                Object queueId = source.opt("queueId");
                if (queueId instanceof String || queueId instanceof Number) track.put("queueId", queueId);
                for (String key : new String[]{"durationSeconds", "durationMs", "playlistIndex"}) {
                    Object duration = source.opt(key);
                    if (duration instanceof Number && ((Number) duration).doubleValue() >= 0) track.put(key, duration);
                }
                JSONArray thumbnails = source.optJSONArray("thumbnails");
                if (thumbnails != null) {
                    JSONArray retained = new JSONArray();
                    for (int j = 0; j < thumbnails.length(); j++) {
                        JSONObject thumbnail = thumbnails.optJSONObject(j);
                        if (thumbnail == null || !(thumbnail.opt("url") instanceof String)) continue;
                        JSONObject image = new JSONObject().put("url", thumbnail.getString("url"));
                        for (String key : new String[]{"width", "height"}) {
                            Object size = thumbnail.opt(key);
                            if (size instanceof Number && ((Number) size).doubleValue() >= 0) image.put(key, size);
                        }
                        retained.put(image);
                    }
                    track.put("thumbnails", retained);
                }
                Object available = source.opt("isAvailable");
                if (available instanceof Boolean) track.put("isAvailable", available);
                JSONObject album = source.optJSONObject("album");
                if (album != null) track.put("album", nameAndId(album));
                else if (source.opt("album") instanceof String) track.put("album", source.getString("album"));
                JSONArray artists = source.optJSONArray("artists");
                if (artists != null) {
                    JSONArray retained = new JSONArray();
                    for (int j = 0; j < artists.length(); j++) {
                        Object artist = artists.get(j);
                        if (artist instanceof JSONObject) retained.put(nameAndId((JSONObject) artist));
                        else if (artist instanceof String) retained.put(artist);
                    }
                    track.put("artists", retained);
                }
            }
            tracks.put(track);
        }
        return tracks;
    }

    static JSONObject playlistMetadata(JSONObject source) throws Exception {
        JSONObject metadata = normalize(new JSONArray().put(source)).getJSONObject(0);
        JSONObject result = new JSONObject();
        for (String key : new String[]{"subtitle", "secondaryText", "metadataRuns", "thumbnails"}) {
            if (metadata.has(key)) result.put(key, metadata.get(key));
        }
        if (source.opt("description") instanceof String) result.put("description", source.getString("description"));
        return result;
    }

    private static JSONObject nameAndId(JSONObject source) throws Exception {
        JSONObject result = new JSONObject();
        for (String key : new String[]{"name", "id"}) {
            Object value = source.opt(key);
            if (value instanceof String) result.put(key, value);
        }
        return result;
    }

    static List<JSONObject> events(JSONArray tracks, long time, String sourcePackage,
                                   long droppedCallbacks) throws Exception {
        return events(tracks, time, sourcePackage, droppedCallbacks, "playback_queue",
                "playback_queue", "Up next", "loaded_playback_queue");
    }

    static List<JSONObject> events(JSONArray tracks, long time, String sourcePackage,
                                   long droppedCallbacks, String eventType, String playlistId,
                                   String title, String scope) throws Exception {
        return events(tracks, time, sourcePackage, droppedCallbacks, eventType, playlistId, title, scope, new JSONObject());
    }

    static List<JSONObject> events(JSONArray tracks, long time, String sourcePackage,
                                   long droppedCallbacks, String eventType, String playlistId,
                                   String title, String scope, JSONObject metadata) throws Exception {
        String snapshotId = UUID.randomUUID().toString();
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        String observedAt = formatter.format(new Date(time));
        int metadataBytes = metadata.toString().getBytes(StandardCharsets.UTF_8).length
                + title.getBytes(StandardCharsets.UTF_8).length;
        int trackBudget = Math.min(TRACK_BYTES_PER_PART, 57 * 1024 - metadataBytes);
        if (trackBudget < 1024) throw new IllegalArgumentException("Playlist metadata too large");
        List<JSONArray> chunks = new ArrayList<>();
        JSONArray current = new JSONArray();
        int bytes = 2;
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject track = tracks.getJSONObject(i);
            int size = track.toString().getBytes(StandardCharsets.UTF_8).length + 1;
            if (size + 2 > trackBudget) throw new IllegalArgumentException("Queue track too large");
            if (current.length() > 0 && (bytes + size > trackBudget || current.length() == TRACKS_PER_PART)) {
                chunks.add(current);
                current = new JSONArray();
                bytes = 2;
            }
            current.put(track);
            bytes += size;
        }
        chunks.add(current);
        List<JSONObject> parts = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            JSONObject part = new JSONObject().put("id", snapshotId + ":" + index)
                    .put("event", eventType).put("snapshotId", snapshotId)
                    .put("playlistId", playlistId).put("title", title)
                    .put("origin", eventType.equals("playback_queue") ? "playback_queue" : "playlist_page")
                    .put("snapshotScope", scope).put("complete", false)
                    .put("sourcePackage", sourcePackage).put("observedAt", observedAt)
                    .put("droppedCallbacks", droppedCallbacks).put("partIndex", index)
                    .put("partCount", chunks.size()).put("totalTracks", tracks.length()).put("tracks", chunks.get(index));
            if (metadata.length() > 0) part.put("playlistMetadata", metadata);
            // Leave 1 KiB for EventStore's sequence, deviceId and drop counter.
            if (part.toString().getBytes(StandardCharsets.UTF_8).length > 59 * 1024) {
                throw new IllegalArgumentException("Queue part too large");
            }
            parts.add(part);
        }
        return parts;
    }
}
