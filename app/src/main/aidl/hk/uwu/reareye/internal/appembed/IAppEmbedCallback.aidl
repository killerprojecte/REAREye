package hk.uwu.reareye.internal.appembed;

import android.os.Bundle;

/** Receives lifecycle events for one remote TaskView embedding session. */
oneway interface IAppEmbedCallback {
    /** Delivers a Bundle containing the SurfacePackage for the host SurfaceView. */
    void onSurfaceReady(long sessionId, in Bundle surfacePackageBundle);

    /** Reports the task id after ShellTaskOrganizer has bound the launched task. */
    void onTaskCreated(long sessionId, int taskId);

    /** Reports that the embedded task is being removed. */
    void onTaskRemovalStarted(long sessionId);

    /** Reports a terminal session error. */
    void onError(long sessionId, int errorCode, String message);

    /** Returns touch ownership to MAML while the native task has no visible surface. */
    void onTaskVisibilityChanged(long sessionId, boolean visible);
}
