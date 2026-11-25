package dev.powershortcut;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.TextView;

public final class LaunchActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private KeyguardManager keyguard;
    private boolean requesting;
    private boolean completed;
    private boolean triedWallet;
    private String target;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!MonitorService.active) {
            finish();
            return;
        }
        target = Targets.settings(this).getString("target", "");
        if (target.isEmpty()) {
            finish();
            return;
        }
        keyguard = getSystemService(KeyguardManager.class);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        TextView message = new TextView(this);
        message.setGravity(Gravity.CENTER);
        message.setTextSize(18);
        message.setText("解锁后打开快捷应用");
        setContentView(message);
        handler.postDelayed(() -> finishWithStatus("等待解锁超时"), 30_000);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Keep a single system unlock prompt in flight.
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (!focus || requesting || completed || isFinishing() || keyguard == null) return;
        if (!MonitorService.active) {
            finish();
            return;
        }
        if (Targets.isWalletPreset(target) && !triedWallet) {
            triedWallet = true;
            if (Targets.openWalletQuickDraw(this)) {
                completed = true;
                MonitorService.lastLaunch = "已请求 Google 钱包快捷刷卡页";
                finish();
                return;
            }
        }
        if (isUnlocked()) {
            openWhenUnlocked();
            return;
        }
        requesting = true;
        keyguard.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
            @Override public void onDismissSucceeded() { waitForUnlock(30); }
            @Override public void onDismissCancelled() { finishWithStatus("已取消解锁"); }
            @Override public void onDismissError() { waitForUnlock(15); }
        });
    }

    private void waitForUnlock(int attempts) {
        if (isFinishing() || isDestroyed()) return;
        if (isUnlocked()) openWhenUnlocked();
        else if (attempts > 0) handler.postDelayed(() -> waitForUnlock(attempts - 1), 100);
        else finishWithStatus("系统仍处于锁定状态");
    }

    private void openWhenUnlocked() {
        if (completed || isFinishing() || isDestroyed()) return;
        completed = true;
        if (MonitorService.active && isUnlocked()) {
            MonitorService.lastLaunch = Targets.open(this, target) ? "已请求打开目标应用" : "目标应用启动失败";
        }
        finish();
    }

    private boolean isUnlocked() {
        return !keyguard.isKeyguardLocked() && !keyguard.isDeviceLocked();
    }

    private void finishWithStatus(String status) {
        if (isFinishing() || isDestroyed() || completed) return;
        MonitorService.lastLaunch = status;
        finish();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
