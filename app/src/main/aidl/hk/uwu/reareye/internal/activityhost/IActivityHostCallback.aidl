package hk.uwu.reareye.internal.activityhost;
import android.graphics.Rect;
oneway interface IActivityHostCallback {
    void onLayoutApplied(long generation, long sequence, in Rect bounds);
    void onTaskCreated(long generation, int taskId, int displayId);
    void onClosed(long generation, String reason);
}
