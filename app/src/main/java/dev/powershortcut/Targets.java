package dev.powershortcut;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.Toast;

final class Targets {
    static final String WALLET = "com.google.android.apps.walletnfcrel";
    static final String QUICKDRAW = "com.google.android.apps.wallet.main.QUICKDRAW";
    private static final String WALLET_PRESET = "preset:" + WALLET;

    static SharedPreferences settings(Context context) {
        return context.getSharedPreferences("shortcut", Context.MODE_PRIVATE);
    }

    static boolean open(Activity activity, String target) {
        if (target.startsWith("preset:")) {
            String app = target.substring("preset:".length());
            if (target.equals(WALLET_PRESET)) {
                if (openWalletQuickDraw(activity)) return true;
            } else if (tryOpen(activity, new Intent(Intent.ACTION_ASSIST).setPackage(app))) {
                return true;
            }
            target = app;
        }
        Intent launcher = target.isEmpty() ? null
                : activity.getPackageManager().getLaunchIntentForPackage(target);
        if (launcher != null && tryOpen(activity, launcher)) return true;
        Toast.makeText(activity, "无法打开目标，请检查应用是否已安装", Toast.LENGTH_LONG).show();
        return false;
    }

    static boolean isWalletPreset(String target) {
        return WALLET_PRESET.equals(target);
    }

    static boolean openWalletQuickDraw(Activity activity) {
        return tryOpen(activity, new Intent(QUICKDRAW).setPackage(WALLET));
    }

    private static boolean tryOpen(Activity activity, Intent intent) {
        try {
            activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException problem) {
            return false;
        }
    }
}
