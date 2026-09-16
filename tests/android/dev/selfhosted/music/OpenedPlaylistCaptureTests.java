package dev.selfhosted.music;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fixtures model the pinned renderer shapes without bundling host protobuf classes. */
final class OpenedPlaylistCaptureTests {
    private OpenedPlaylistCaptureTests() {}
    static void run() throws Exception {
        buew first = new buew();
        first.e = new boqp("A title");
        first.g = new boqp("");
        first.g.c = Arrays.asList(new boqt("Artist", new Endpoint(field(48687626, field(2, "UC-artist".getBytes(StandardCharsets.UTF_8))))));
        first.i = new Endpoint(field(48687757, field(1, "abcdefghijk".getBytes(StandardCharsets.UTF_8))));
        first.q = new bues("occurrence-one");
        buew unavailable = new buew();
        unavailable.e = new boqp("Unavailable");
        JSONArray tracks = OpenedPlaylistCapture.tracks(new Adapter(new Object(), first, new oij(first), unavailable));
        check(tracks.length() == 3, "must read all loaded songs, excluding decoration");
        check(tracks.getJSONObject(1).getInt("position") == 1, "duplicate position lost");
        check(tracks.getJSONObject(1).getString("videoId").equals("abcdefghijk"), "duplicate ID lost");
        check(tracks.getJSONObject(0).getString("title").equals("A title"), "title missing");
        check(tracks.getJSONObject(0).getString("setVideoId").equals("occurrence-one"), "occurrence missing");
        check(tracks.getJSONObject(0).getString("subtitle").equals("Artist"), "run text missing");
        check(tracks.getJSONObject(0).getJSONArray("metadataRuns").getJSONObject(0).getString("browseId").equals("UC-artist"), "browse ID missing");
        check(PlaylistSnapshot.normalize(tracks).getJSONObject(0).getJSONArray("metadataRuns")
                .getJSONObject(0).getString("browseId").equals("UC-artist"), "normalization dropped browse ID");
        check(tracks.getJSONObject(2).isNull("videoId"), "unavailable item dropped or fabricated");
        first.e.d = "Changed";
        check(tracks.getJSONObject(0).getString("title").equals("A title"), "snapshot retained mutable host object");
        check(OpenedPlaylistCapture.tracks(new Adapter()).length() == 0, "empty adapter");
        try { OpenedPlaylistCapture.tracks(new Oversized()); throw new AssertionError("oversized adapter accepted"); }
        catch (IllegalArgumentException expected) { /* bounded */ }
        JSONArray labels = new JSONArray();
        JSONArray runs = new JSONArray();
        JSONArray images = new JSONArray();
        OpenedPlaylistCapture.rendererMetadata(new blne(first.g), labels, runs, images);
        OpenedPlaylistCapture.rendererMetadata(new bthw(new boqp("3:42")), labels, runs, images);
        OpenedPlaylistCapture.rendererMetadata(new btgt(), labels, runs, images);
        JSONObject enriched = new JSONObject().put("displayColumns", labels).put("metadataRuns", runs).put("thumbnails", images);
        JSONObject normalized = PlaylistSnapshot.normalize(new JSONArray().put(enriched)).getJSONObject(0);
        check(normalized.getJSONArray("displayColumns").getString(1).equals("3:42"), "display label lost");
        check(normalized.getJSONArray("metadataRuns").getJSONObject(0).getString("browseId").equals("UC-artist"), "badge endpoint lost");
        JSONObject image = normalized.getJSONArray("thumbnails").getJSONObject(0);
        check(image.getString("url").equals("https://example.test/art.jpg"), "artwork URL lost");
        check(image.getInt("width") == 640 && image.getInt("height") == 480, "artwork dimensions swapped");
        btio displayed = new btio();
        displayed.c = new boqp("Playlist title");
        displayed.d = first.g;
        displayed.e = new boqp("20 songs");
        btjr editable = new btjr();
        editable.c = new boqp("Fallback title");
        editable.d = new boqp("Edit playlist");
        editable.e = new boqp("Playlist description");
        JSONObject header = OpenedPlaylistCapture.headerMetadata(displayed, editable);
        check(header.getString("title").equals("Playlist title"), "displayed title overridden");
        check(header.getString("subtitle").equals("Artist"), "playlist subtitle missing");
        check(header.getString("secondaryText").equals("20 songs"), "playlist secondary text missing");
        check(header.getString("description").equals("Playlist description"), "playlist description missing");
        check(header.getJSONArray("metadataRuns").getJSONObject(0).getString("browseId").equals("UC-artist"), "header owner link lost");
        JSONObject storedHeader = PlaylistSnapshot.playlistMetadata(header);
        check(storedHeader.getString("description").equals("Playlist description"), "normalizer dropped header description");
        check(storedHeader.getString("subtitle").equals("Artist"), "normalizer dropped header subtitle");
        check(storedHeader.getJSONArray("metadataRuns").getJSONObject(0).getString("browseId").equals("UC-artist"), "normalizer dropped header browse ID");
        check(!header.toString().contains("Edit playlist"), "editor UI label leaked into metadata");
        check(OpenedPlaylistCapture.headerMetadata(null, editable).getString("title").equals("Fallback title"), "editable title fallback lost");
        btjw modern = new btjw(); modern.e = first.g;
        check(OpenedPlaylistCapture.headerMetadata(modern, null).getJSONArray("metadataRuns").getJSONObject(0)
                .getString("browseId").equals("UC-artist"), "modern header navigation missing");
        OpenedPlaylistCapture.capture(new Object(), new Object());
        OpenedPlaylistCapture.opened(new Object(), new Object());
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static final class Adapter {
        private final Object[] rows;
        Adapter(Object... rows) { this.rows = rows; }
        public int a() { return rows.length; }
        public Object d(int index) { return rows[index]; }
    }
    public static final class Oversized {
        public int a() { return PlaylistSnapshot.MAX_TRACKS + 1; }
        public Object d(int index) { throw new AssertionError("read oversized adapter"); }
    }
    public static final class buew {
        public boqp e, g, h;
        public Object c;
        public List<Object> t;
        public Endpoint i, k;
        public bues q;
    }
    public static final class oij { public buew a; oij(buew row) { a = row; } }
    public static final class bues { public int b = 1; public String c; bues(String id) { c = id; } }
    public static final class boqp { public String d; public List<boqt> c; boqp(String text) { d = text; } }
    public static final class boqt { public String c; public Endpoint l; boqt(String text, Endpoint endpoint) { c = text; l = endpoint; } }
    public static final class btio { public boqp c, d, e; }
    public static final class btjr { public boqp c, d, e; public Object o; }
    public static final class btjw { public boqp e; }
    public static final class blne { public boqp c; blne(boqp value) { c = value; } }
    public static final class bthw { public boqp b; bthw(boqp value) { b = value; } }
    public static final class btgt { public List<bytq> b = Arrays.asList(new bytq()); }
    public static final class bytq { public List<bytp> c = Arrays.asList(new bytp()); }
    public static final class bytp { public int b = 7; public String c = "https://example.test/art.jpg"; public int d = 640; public int e = 480; }
    public static final class Endpoint { private final byte[] bytes; Endpoint(byte[] bytes) { this.bytes = bytes; } public byte[] toByteArray() { return bytes.clone(); } }
    private static byte[] field(int number, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        varint(out, ((long) number << 3) | 2); varint(out, value.length); out.write(value, 0, value.length);
        return out.toByteArray();
    }
    private static void varint(ByteArrayOutputStream out, long value) {
        while (value > 127) { out.write((int) (value & 127) | 128); value >>>= 7; }
        out.write((int) value);
    }
}
