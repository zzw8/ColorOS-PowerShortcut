package dev.powershortcut;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import rikka.shizuku.Shizuku;

public final class MonitorService extends Service {
    private static final String CHANNEL = "power_monitor";
    static boolean active;
    static String info = "监听未开启";
    static String lastLaunch = "";
    private static MonitorService runningService;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Shizuku.OnBinderDeadListener binderDead = () -> disconnect("等待 Shizuku 重新运行");
    private final Shizuku.OnBinderReceivedListener binderReceived = this::connect;
    private final SharedPreferences.OnSharedPreferenceChangeListener targetChanged = (preferences, key) -> {
        if ("target".equals(key) || "wallet_auto_nfc".equals(key) || "wallet_defer_sleep".equals(key)) configureTarget();
    };
    private Shizuku.UserServiceArgs args;
    private IPowerReader reader;
    private ServiceConnection connection;

    static boolean isRequested(Context context) {
        return Targets.settings(context).getBoolean("monitor_requested", false);
    }

    static void setRequested(Context context, boolean requested) {
        Targets.settings(context).edit().putBoolean("monitor_requested", requested).apply();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isRequested(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (active) {
            connect();
            return START_STICKY;
        }
        startForeground(1, notification());
        runningService = this;
        active = true;
        info = "等待 Shizuku 运行";
        lastLaunch = "";
        args = new Shizuku.UserServiceArgs(new ComponentName(this, PowerReader.class))
                .processNameSuffix("power_reader").daemon(false).version(8);
        Targets.settings(this).registerOnSharedPreferenceChangeListener(targetChanged);
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        return START_STICKY;
    }

    private void connect() {
        if (!active || connection != null || !Shizuku.pingBinder()) return;
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                info = "等待 Shizuku 授权";
                return;
            }
        } catch (RuntimeException problem) {
            info = "等待 Shizuku 重新运行";
            return;
        }

        ServiceConnection attempt = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (!active || connection != this) return;
                handler.removeCallbacksAndMessages(null);
                reader = IPowerReader.Stub.asInterface(binder);
                try {
                    applyWalletSettings();
                    reader.start();
                    refreshReader();
                } catch (RemoteException exception) {
                    retryConnection("Shizuku 监听服务启动失败，正在重连");
                }
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                if (active && connection == this) retryConnection("Shizuku 监听服务已断开，正在重连");
            }
        };
        connection = attempt;
        info = "正在连接 Shizuku";
        try {
            Shizuku.bindUserService(args, attempt);
            handler.postDelayed(() -> {
                if (connection == attempt && reader == null) retryConnection("连接 Shizuku 超时，正在重试");
            }, 12000);
        } catch (RuntimeException problem) {
            retryConnection("Shizuku 监听服务暂时不可用");
        }
    }

    private Notification notification() {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "电源键监听", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        // Android requires this object even when the app does not request notification permission.
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_power)
                .setContentTitle("电源快捷键").setContentText("后台监听已开启")
                .setOngoing(true).build();
    }

    private void applyWalletSettings() throws RemoteException {
        SharedPreferences settings = Targets.settings(this);
        reader.setWalletShortcut(Targets.isWalletPreset(settings.getString("target", "")),
                settings.getBoolean("wallet_auto_nfc", true), settings.getBoolean("wallet_defer_sleep", false));
    }

    private void configureTarget() {
        if (reader == null) return;
        try {
            applyWalletSettings();
        } catch (RemoteException exception) {
            retryConnection("Shizuku 监听服务已断开，正在重连");
        }
    }

    private void refreshReader() {
        if (!active || reader == null) return;
        try {
            info = reader.status();
        } catch (RemoteException exception) {
            retryConnection("Shizuku 监听服务已断开，正在重连");
        }
    }

    static void refreshVisibleStatus() {
        if (runningService != null) runningService.refreshReader();
    }

    private void retryConnection(String message) {
        disconnect(message);
        if (active) handler.postDelayed(this::connect, 5000);
    }

    private void disconnect(String message) {
        handler.removeCallbacksAndMessages(null);
        info = message;
        ServiceConnection oldConnection = connection;
        connection = null;
        reader = null;
        if (oldConnection != null) {
            try { Shizuku.unbindUserService(args, oldConnection, true); }
            catch (RuntimeException ignored) { /* The remote process can already be gone. */ }
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        active = false;
        runningService = null;
        Shizuku.removeBinderDeadListener(binderDead);
        Shizuku.removeBinderReceivedListener(binderReceived);
        Targets.settings(this).unregisterOnSharedPreferenceChangeListener(targetChanged);
        disconnect("监听未开启");
        super.onDestroy();
    }
}
