package uz.nasos;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

public class PollService extends Service {
    private PowerManager.WakeLock wl;

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Engine.init(this);
        Engine.channels();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mango:poll");
        wl.acquire();
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        Notification n = Engine.notif(Engine.LIVE, "Mango Monitor", "Ulanmoqda...");
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, n);
        }
        Engine.start(false);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Engine.stop();
        if (wl != null && wl.isHeld()) wl.release();
        super.onDestroy();
    }
}
