package hk.uwu.reareye.internal.appembed;

import android.content.Intent;
import android.graphics.Rect;
import android.os.IBinder;
import hk.uwu.reareye.internal.appembed.IAppEmbedCallback;

/**
 * SystemUI-owned broker for embedding a real TaskView into another process.
 *
 * Coordinates and ViewHost dimensions are absolute physical-display pixels. The owner token is
 * linked to death and owns every resource created for the returned session id. A positive density
 * requests a task-level configuration override without scaling the physical host surface.
 */
interface IAppEmbedService {
    long createSession(
        IBinder ownerToken,
        IBinder hostToken,
        int displayId,
        int widthPx,
        int heightPx,
        /** Zero inherits the target display density; a positive value overrides task density. */
        int densityDpi,
        in Rect taskBoundsOnScreen,
        in Intent launchIntent,
        boolean touchable,
        IAppEmbedCallback callback
    );

    /** Relayouts the remote ViewHost and moves the organized task input bounds atomically. */
    void resize(long sessionId, int widthPx, int heightPx, in Rect taskBoundsOnScreen);

    /** Mirrors the MAML element visibility into the remote TaskView. */
    void setVisible(long sessionId, boolean visible);

    /** Requests focus for the remote embedded hierarchy and its task. */
    void requestFocus(long sessionId);

    /** Releases the TaskView, ViewHost and task owned by this session. */
    void release(long sessionId);

    /** Closes this private capability and releases every session created through it. */
    void dispose();
}
