package dev.powershortcut;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PowerKeyEvent {
    private static final Pattern EVENT = Pattern.compile(
            "action=ACTION_(DOWN|UP), keyCode=KEYCODE_POWER, .*?repeatCount=0, "
                    + "eventTime=(\\d+), downTime=(\\d+), deviceId=(\\d+),");
    private static final Pattern WALLET = Pattern.compile("\\bWalletSwitchState\\s*=\\s*(\\d{1,3})\\b");

    final boolean down;
    final long time;
    final long downTime;
    final boolean canceled;
    final boolean keyguardLocked;
    final boolean screenOn;
    final int walletState;

    private PowerKeyEvent(boolean down, long time, long downTime, boolean canceled,
            boolean keyguardLocked, boolean screenOn, int walletState) {
        this.down = down;
        this.time = time;
        this.downTime = downTime;
        this.canceled = canceled;
        this.keyguardLocked = keyguardLocked;
        this.screenOn = screenOn;
        this.walletState = walletState;
    }

    static PowerKeyEvent parse(String line, long uptime, long startedAt) {
        if (!line.contains("overrideInterceptKeyBeforeQueueing:")) return null;
        Matcher event = EVENT.matcher(line);
        if (!event.find()) return null;
        try {
            long time = Long.parseLong(event.group(2));
            long downTime = Long.parseLong(event.group(3));
            if (time > uptime) {
                time /= 1_000_000;
                downTime /= 1_000_000;
            }
            if (time < startedAt || time > uptime || uptime - time > 1500 || downTime > time) return null;
            Matcher wallet = WALLET.matcher(line);
            int walletState = wallet.find() ? Integer.parseInt(wallet.group(1)) : -1;
            return new PowerKeyEvent("DOWN".equals(event.group(1)), time, downTime,
                    line.contains("canceled = true"), line.contains("keyguardOn = true"),
                    line.contains("screenOn = true"), walletState);
        } catch (NumberFormatException invalidTime) {
            return null;
        }
    }
}
