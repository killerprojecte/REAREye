package hk.uwu.reareye.internal.activityhost;
import android.graphics.Rect;
interface IActivityHostTaskRegistry {
    int getProtocolVersion();
    void registerLaunch(IBinder cookie, IBinder lifetime, String component, int displayId, int dpi, in Rect bounds);
    boolean isLaunchConfigured(IBinder cookie);
    void unregisterLaunch(IBinder cookie);
}
