package hk.uwu.reareye.internal.activityhost;
import hk.uwu.reareye.internal.activityhost.IActivityHostCallback;
import hk.uwu.reareye.internal.activityhost.IActivityHostSession;
interface IActivityHostBroker {
    int getProtocolVersion();
    IActivityHostSession openSession(int displayId, long generation, IActivityHostCallback callback);
}
