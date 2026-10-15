package hk.uwu.reareye.internal.activityhost;
import android.graphics.Rect;
import android.view.SurfaceControl;
interface IActivityHostSession {
    void setSurface(in SurfaceControl surface, in Rect screenBounds, in Rect visibleBounds, long sequence);
    void updateLayout(in Rect screenBounds, in Rect visibleBounds, long sequence);
    void launch(String component, int dpi);
    void setVisible(boolean visible);
    void moveToFullscreen();
    void close();
}
