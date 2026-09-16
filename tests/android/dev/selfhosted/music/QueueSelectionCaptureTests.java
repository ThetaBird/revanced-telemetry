package dev.selfhosted.music;

import java.util.concurrent.atomic.AtomicLong;

final class QueueSelectionCaptureTests {
    private QueueSelectionCaptureTests() {}
    static void run() throws Exception {
        Item item = new Item(Long.MAX_VALUE);
        QueueSelectionCapture.Selection selected = QueueSelectionCapture.selection(item);
        check("abcdefghijk".equals(selected.videoId), "clicked video ID missing");
        check("9223372036854775807".equals(selected.queueId), "queue ID precision lost");
        check("PLfixture".equals(selected.playlistId) && selected.playlistIndex == 3, "playlist metadata missing");
        QueueSelectionCapture.Selection duplicate = QueueSelectionCapture.selection(new Item(Long.MIN_VALUE));
        check(selected.videoId.equals(duplicate.videoId) && !selected.queueId.equals(duplicate.queueId),
                "duplicate video entries conflated");
        QueueSelectionCapture.Selection unresolved = QueueSelectionCapture.selection(new Unknown());
        check(unresolved.videoId == null && unresolved.queueId == null && unresolved.playlistId == null &&
                unresolved.playlistIndex == -1, "unresolved target fabricated");
        java.lang.reflect.Field field = Telemetry.class.getDeclaredField("DROPPED_CALLBACKS");
        field.setAccessible(true);
        AtomicLong dropped = (AtomicLong) field.get(null);
        long before = dropped.get();
        // Shared listener serves other row types. They must return before reading a native item.
        QueueSelectionCapture.capture(new Listener(new Object()));
        QueueSelectionCapture.capture(new Listener(null));
        check(dropped.get() == before, "non-queue click was treated as capture failure");
        QueueSelectionCapture.capture(new Object());
        check(dropped.get() == before + 1, "broken native callback contract silently accepted");
        try { QueueSelectionCapture.selection(new Object()); throw new AssertionError("broken item accepted"); }
        catch (NoSuchMethodException expected) { }
    }
    private static final class Listener {
        private final Object e;
        Listener(Object gate) { this.e = gate; }
    }
    public static final class Item {
        private final Long id;
        Item(Long id) { this.id = id; }
        public String t() { return "abcdefghijk"; }
        public Long p() { return id; }
        public Descriptor m() { return new Descriptor(); }
    }
    public static final class Descriptor {
        public String t() { return "PLfixture"; }
        public int a() { return 3; }
    }
    public static final class Unknown {
        public String t() { return null; }
        public Long p() { return null; }
        public Object m() { return null; }
    }
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
