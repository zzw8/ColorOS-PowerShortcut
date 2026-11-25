package dev.powershortcut;

import android.nfc.NfcAdapter;
import android.os.IBinder;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Temporarily owns NFC only when the wallet shortcut turned an initially off adapter on. */
final class WalletNfcSession {
    private static final String SHELL_PACKAGE = "com.android.shell";
    private static final long INITIAL_WAIT_MS = 25_000;
    private static final long LEAVE_GRACE_MS = 1100;
    private static final long AUTH_GRACE_MS = 20_000;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> watch;
    private ScheduledFuture<?> finishCheck;
    private Class<?> adapterInterface;
    private Object adapter;
    private boolean ownsNfc;
    private boolean walletSeen;
    private long startedAt;
    private long lastSeen;
    private volatile String status = "";

    WalletNfcSession(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    String status() {
        return status;
    }

    synchronized void beforeLaunch() {
        if (ownsNfc) return;
        try {
            if (getState() != NfcAdapter.STATE_OFF) {
                status = "NFC 已开启或正在切换";
                return;
            }
            ownsNfc = setEnabled(true);
            status = ownsNfc ? "已请求临时开启 NFC" : "系统拒绝开启 NFC";
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            status = "此设备无法自动开启 NFC";
        }
    }

    synchronized void afterLaunch(boolean accepted) {
        if (!ownsNfc) return;
        if (!accepted) {
            restore();
            return;
        }
        if (watch != null) watch.cancel(false);
        if (finishCheck != null) finishCheck.cancel(false);
        walletSeen = false;
        startedAt = SystemClock.elapsedRealtime();
        lastSeen = startedAt;
        watch = scheduler.scheduleWithFixedDelay(() -> checkWallet(false), 400, 1200, TimeUnit.MILLISECONDS);
    }

    synchronized void cancelPreparation() {
        // A canceled gesture must not close an earlier, still-active wallet session.
        if (watch == null) restore();
    }

    synchronized void onWalletFinished() {
        if (watch == null || !ownsNfc) return;
        if (finishCheck != null) finishCheck.cancel(false);
        finishCheck = scheduler.schedule(() -> checkWallet(true), 450, TimeUnit.MILLISECONDS);
    }

    private void checkWallet(boolean finished) {
        String top = topResumed();
        synchronized (this) {
            if (!ownsNfc) return;
            long now = SystemClock.elapsedRealtime();
            if (top.contains(" " + Targets.WALLET + "/")) {
                walletSeen = true;
                lastSeen = now;
                return;
            }
            boolean authentication = top.contains(" com.android.systemui/")
                    || top.contains(" com.google.android.gms/");
            if (finished && walletSeen && !authentication && !top.isEmpty()) {
                restore();
                return;
            }
            long grace = authentication ? AUTH_GRACE_MS : LEAVE_GRACE_MS;
            if (walletSeen ? now - lastSeen >= grace : now - startedAt >= INITIAL_WAIT_MS) restore();
        }
    }

    synchronized void restore() {
        if (watch != null) {
            watch.cancel(false);
            watch = null;
        }
        if (finishCheck != null) {
            finishCheck.cancel(false);
            finishCheck = null;
        }
        if (!ownsNfc) return;
        try {
            // Queue disable even if a recent enable request has not changed the state yet.
            if (setEnabled(false)) {
                ownsNfc = false;
                status = "已恢复 NFC 关闭状态";
                return;
            }
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            // Keep ownership so a later explicit stop or retry can restore the prior state.
        }
        status = "未能自动关闭 NFC，请检查系统开关";
    }

    private int getState() throws ReflectiveOperationException {
        connectAdapter();
        return (int) adapterInterface.getMethod("getState").invoke(adapter);
    }

    private boolean setEnabled(boolean enabled) throws ReflectiveOperationException {
        // Use the real ADB/Shizuku caller identity when invoking the NFC service.
        if (enabled) return (boolean) adapterInterface.getMethod("enable", String.class).invoke(adapter, SHELL_PACKAGE);
        return (boolean) adapterInterface.getMethod("disable", boolean.class, String.class)
                .invoke(adapter, true, SHELL_PACKAGE);
    }

    private void connectAdapter() throws ReflectiveOperationException {
        if (adapter != null) return;
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "nfc");
        if (binder == null) throw new IllegalStateException("NFC service is unavailable");
        adapterInterface = Class.forName("android.nfc.INfcAdapter");
        adapter = Class.forName("android.nfc.INfcAdapter$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    private static String topResumed() {
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/sh", "-c",
                    "dumpsys -t 1 activity activities | grep 'topResumedActivity='")
                    .redirectErrorStream(true).start();
            if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) return "";
            try (BufferedReader result = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String top = result.readLine();
                return top == null ? "" : top;
            }
        } catch (IOException unavailable) {
            return "";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "";
        } finally {
            if (process != null) process.destroy();
        }
    }
}
