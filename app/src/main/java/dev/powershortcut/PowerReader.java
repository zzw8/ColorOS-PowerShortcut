package dev.powershortcut;

import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Runs under the Shizuku identity and reads filtered power-key and wallet lifecycle events. */
public final class PowerReader extends IPowerReader.Stub {
    private DoublePress gesture = new DoublePress();
    private long pressWindow = DoublePress.DEFAULT_WINDOW_MS;
    private long longPressTimeout = 500;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final WalletNfcSession walletNfc = new WalletNfcSession(scheduler);
    private final PowerSleepGuard sleepGuard = new PowerSleepGuard();
    private Process logcat;
    private Process launch;
    private ScheduledFuture<?> pendingLaunch;
    private long pendingDoubleDown = -1;
    private long preparedNfcDown = -1;
    private boolean started;
    private boolean stopped;
    private boolean walletShortcut;
    private boolean automaticNfc;
    private boolean deferSleepRequested;
    private String sleepStatus = "";
    private int walletState = -1;
    private int detected;
    private int dispatched;
    private String state = "正在连接";

    // Shizuku instantiates this class in its user-service process.
    public PowerReader() {}

    @Override public synchronized void start() {
        if (started || stopped) return;
        started = true;
        new Thread(this::read, "coloros-power-log").start();
    }

    @Override public synchronized String status() {
        String wallet = walletState < 0 ? "等待按键状态" : walletState == 0 ? "已关闭" : "开启";
        String nfc = walletNfc.status();
        return state + "\n原厂钱包快捷动作 · " + wallet
                + "\n检测到的双击 " + detected + " · 已交给系统 " + dispatched
                + (nfc.isEmpty() ? "" : "\n" + nfc)
                + (sleepStatus.isEmpty() ? "" : "\n" + sleepStatus);
    }

    @Override public synchronized void setWalletShortcut(boolean enabled, boolean useNfc, boolean deferSleep) {
        walletShortcut = enabled;
        automaticNfc = enabled && useNfc;
        deferSleepRequested = enabled && deferSleep;
        if (!automaticNfc) {
            preparedNfcDown = -1;
            walletNfc.restore();
        }
        if (!stopped) scheduler.execute(this::applySleepPolicy);
    }

    @Override public synchronized void destroy() {
        stopped = true;
        if (logcat != null) logcat.destroy();
        if (launch != null) launch.destroy();
        sleepGuard.release();
        walletNfc.restore();
        scheduler.shutdownNow();
        System.exit(0);
    }

    private void read() {
        sleepGuard.recover();
        long systemWindow = readTiming("secure", "multi_press_timeout", DoublePress.DEFAULT_WINDOW_MS);
        long systemLongPress = readTiming("global", "power_button_long_press_duration_ms", 500);
        long startedAt = SystemClock.uptimeMillis();
        try {
            Process source;
            synchronized (this) {
                if (stopped) return;
                pressWindow = systemWindow;
                longPressTimeout = systemLongPress;
                gesture = new DoublePress(pressWindow);
                source = new ProcessBuilder("/system/bin/logcat", "-b", "main", "-b", "system", "-b", "events",
                        "--regex=overrideInterceptKeyBeforeQueueing:.*KEYCODE_POWER|com[.]google[.]android[.]apps[.]walletnfcrel/",
                        "--uid=1000", "-v", "brief", "-T", "1", "KEYLOG_PhoneWindowManagerExtImpl:D",
                        "wm_finish_activity:I", "*:S")
                        .redirectErrorStream(true).start();
                logcat = source;
                state = "等待电源键日志";
                scheduler.execute(this::applySleepPolicy);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    source.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    PowerKeyEvent event = PowerKeyEvent.parse(line, SystemClock.uptimeMillis(), startedAt);
                    if (event != null) onKey(event);
                    else if (line.contains("wm_finish_activity") && line.contains(Targets.WALLET + "/")
                            && line.endsWith(",finish-activity]")) walletNfc.onWalletFinished();
                }
            }
            synchronized (this) { state = "电源键日志已断开，请关闭监听后重试"; }
        } catch (IOException | SecurityException problem) {
            synchronized (this) { state = "无法读取电源键日志，请检查 Shizuku"; }
        } finally {
            synchronized (this) {
                if (logcat != null) logcat.destroy();
                cancelDouble();
                logcat = null;
                sleepGuard.release();
                if (!stopped) {
                    scheduler.schedule(() -> new Thread(this::read, "coloros-power-log").start(),
                            3, TimeUnit.SECONDS);
                }
            }
        }
    }

    private synchronized void onKey(PowerKeyEvent event) {
        if (stopped) return;
        if (event.walletState >= 0) walletState = event.walletState;
        if (event.down) state = "已收到物理电源键事件";
        boolean detectedDouble = gesture.record(event);
        if (pendingDoubleDown >= 0 && (!gesture.isDoubleCandidate(pendingDoubleDown)
                || (event.canceled && event.downTime == pendingDoubleDown))) cancelDouble();
        if (detectedDouble) {
            pendingDoubleDown = event.time;
            // Extra presses invalidate this candidate. A long second press is never a double tap.
            long delay = delayUntilDecision(event.time);
            pendingLaunch = scheduler.schedule(() -> dispatch(event.time, event.keyguardLocked), delay, TimeUnit.MILLISECONDS);
        } else if (!event.down && automaticNfc && event.downTime == pendingDoubleDown
                && gesture.ready(event.downTime)) {
            scheduler.execute(() -> prepareNfc(event.downTime));
        } else if (!event.down && sleepGuard.isActive() && gesture.isSingleOnAwakeScreen(event.downTime)
                && event.time - event.downTime < longPressTimeout) {
            scheduler.schedule(() -> sleepAfterSingle(event.downTime), delayUntilDecision(event.downTime),
                    TimeUnit.MILLISECONDS);
        }
    }

    private synchronized void prepareNfc(long down) {
        if (stopped || !automaticNfc || pendingDoubleDown != down || !gesture.ready(down)
                || preparedNfcDown == down) return;
        walletNfc.beforeLaunch();
        preparedNfcDown = down;
    }

    private void cancelDouble() {
        if (pendingLaunch != null) pendingLaunch.cancel(false);
        pendingLaunch = null;
        pendingDoubleDown = -1;
        if (preparedNfcDown >= 0) {
            preparedNfcDown = -1;
            walletNfc.cancelPreparation();
        }
    }

    private long delayUntilDecision(long down) {
        return Math.max(0, down + pressWindow + 15 - SystemClock.uptimeMillis());
    }

    private synchronized void applySleepPolicy() {
        if (stopped || logcat == null || !deferSleepRequested) {
            sleepGuard.release();
            sleepStatus = "";
            return;
        }
        sleepStatus = sleepGuard.acquire() ? "双击防闪屏已开启" : "系统未启用双击防闪屏";
    }

    private synchronized void sleepAfterSingle(long down) {
        if (stopped || !deferSleepRequested || !sleepGuard.isActive() || !gesture.isSingleOnAwakeScreen(down)) return;
        try {
            new ProcessBuilder("/system/bin/input", "keyevent", "KEYCODE_SLEEP").start();
        } catch (IOException | SecurityException unavailable) {
            sleepGuard.release();
            sleepStatus = "已恢复系统单击息屏";
        }
    }

    private static long readTiming(String namespace, String key, long fallback) {
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/cmd", "settings", "get", namespace, key)
                    .redirectErrorStream(true).start();
            if (process.waitFor(500, TimeUnit.MILLISECONDS) && process.exitValue() == 0) {
                try (BufferedReader output = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    String value = output.readLine();
                    if (value != null) {
                        long millis = Long.parseLong(value.trim());
                        if (millis >= 100 && millis <= 2000) return millis;
                    }
                }
            }
        } catch (IOException | NumberFormatException | SecurityException unavailable) {
            // Older systems may not expose a per-user override.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            if (process != null) process.destroy();
        }
        return fallback;
    }

    private void dispatch(long event, boolean locked) {
        boolean preferWallet;
        boolean manageNfc;
        boolean success = false;
        synchronized (this) {
            if (stopped || pendingDoubleDown != event) return;
            if (!gesture.ready(event)) {
                cancelDouble();
                return;
            }
            pendingDoubleDown = -1;
            pendingLaunch = null;
            detected++;
            preferWallet = walletShortcut;
            manageNfc = automaticNfc;
            if (manageNfc && preparedNfcDown != event) walletNfc.beforeLaunch();
            preparedNfcDown = -1;
        }
        try {
            boolean wallet = preferWallet && requestLaunch("-a", Targets.QUICKDRAW, "-p", Targets.WALLET);
            success = wallet || requestLaunch("-n", "dev.powershortcut/.LaunchActivity");
            synchronized (this) {
                if (success) dispatched++;
                if (wallet) state = locked ? "已从锁屏请求 Google 钱包" : "已请求 Google 钱包快捷刷卡页";
                else state = success ? "已请求系统打开快捷目标" : "系统未接受启动请求";
            }
        } catch (IOException | SecurityException problem) {
            synchronized (this) { state = "无法启动快捷入口"; }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (this) {
                if (manageNfc && !stopped && automaticNfc) walletNfc.afterLaunch(success);
            }
        }
    }

    private boolean requestLaunch(String... target) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("/system/bin/cmd", "activity", "start-activity", "--user", "current"));
        command.addAll(List.of(target));
        Process process;
        synchronized (this) {
            if (stopped) return false;
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            launch = process;
        }
        try {
            if (process.waitFor(4, TimeUnit.SECONDS) && process.exitValue() == 0) {
                try (BufferedReader output = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    boolean started = false;
                    boolean error = false;
                    String line;
                    while ((line = output.readLine()) != null) {
                        started |= line.contains("Starting: Intent");
                        error |= line.contains("Error") || line.contains("Exception");
                    }
                    return started && !error;
                }
            }
            return false;
        } finally {
            process.destroy();
            synchronized (this) { launch = null; }
        }
    }
}
