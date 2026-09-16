package dev.selfhosted.music;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Accepted Up Next row clicks on APK 8.40.54, before command dispatch. */
public final class QueueSelectionCapture {
    private QueueSelectionCapture() {}

    public static void capture(Object listener) {
        try {
            Field gateField = listener.getClass().getDeclaredField("e");
            gateField.setAccessible(true);
            Object gate = gateField.get(listener);
            // bccr serves many surfaces: only qhr binds native playback-queue rows.
            if (gate == null || !"qhr".equals(gate.getClass().getName())) return;
            Object item = gate.getClass().getField("k").get(gate);
            if (item == null) return;
            Selection selected = selection(item);
            Telemetry.onQueueSongSelected(selected.videoId, selected.queueId,
                    selected.playlistId, selected.playlistIndex);
        } catch (Throwable failure) {
            Telemetry.captureFailure("Queue selection capture failed", failure);
        }
    }

    static Selection selection(Object item) throws Exception {
        String videoId = (String) call(item, "t");
        Long queueId = (Long) call(item, "p");
        Object descriptor = call(item, "m");
        String playlistId = descriptor == null ? null : (String) call(descriptor, "t");
        int index = descriptor == null ? -1 : (Integer) call(descriptor, "a");
        return new Selection(videoId, queueId == null ? null : queueId.toString(), playlistId, index);
    }

    static final class Selection {
        final String videoId, queueId, playlistId;
        final int playlistIndex;
        Selection(String videoId, String queueId, String playlistId, int playlistIndex) {
            this.videoId = videoId;
            this.queueId = queueId;
            this.playlistId = playlistId;
            this.playlistIndex = playlistIndex;
        }
    }

    private static Object call(Object target, String name) throws Exception {
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }
}
