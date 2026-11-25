package dev.powershortcut;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class RestoreReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!MonitorService.isRequested(context)) return;
        try {
            context.startForegroundService(new Intent(context, MonitorService.class));
        } catch (IllegalStateException | SecurityException rejected) {
            Log.w("PowerShortcut", "ColorOS prevented restoring the foreground listener", rejected);
        }
    }
}
