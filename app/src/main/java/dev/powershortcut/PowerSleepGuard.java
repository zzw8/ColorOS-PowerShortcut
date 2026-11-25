package dev.powershortcut;

import android.os.SystemClock;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/** Holds the system short-press setting only for the lifetime of a live shell pipe. */
final class PowerSleepGuard {
    private static final String SCRIPT = """
            umask 077
            journal=/data/local/tmp/dev.powershortcut-power-sleep
            setting=power_button_short_press

            restore() {
                current=$(settings get global "$setting") || return 1
                if [ "$current" = 0 ]; then
                    if [ "$original" = null ]; then
                        settings delete global "$setting" >/dev/null || return 1
                    else
                        settings put global "$setting" "$original" || return 1
                    fi
                fi
                rm -f "$journal"
            }

            if [ -f "$journal" ]; then
                read -r owner original < "$journal" || exit 1
                case "$owner" in ''|*[!0-9]*) exit 1;; esac
                case "$original" in null|1) ;; *) exit 1;; esac
                if kill -0 "$owner" 2>/dev/null; then exit 1; fi
                restore || exit 1
            fi
            [ "$1" = acquire ] || exit 0

            original=$(settings get global "$setting") || exit 1
            case "$original" in
                null) [ "$(cmd overlay lookup android android:integer/config_shortPressOnPowerBehavior)" = 1 ] || exit 1;;
                1) ;;
                *) exit 1;;
            esac
            printf '%s %s\\n' "$$" "$original" > "$journal" || exit 1
            trap 'restore' EXIT
            trap 'exit 1' HUP INT TERM
            settings put global "$setting" 0 || exit 1
            [ "$(settings get global "$setting")" = 0 ] || exit 1
            printf 'Y'
            read -r done
            """;

    private Process holder;

    boolean isActive() {
        return holder != null && holder.isAlive();
    }

    boolean acquire() {
        if (isActive()) return true;
        try {
            holder = run("acquire");
            InputStream ready = holder.getInputStream();
            long deadline = SystemClock.uptimeMillis() + 1500;
            while (holder.isAlive() && SystemClock.uptimeMillis() < deadline) {
                if (ready.available() > 0) {
                    if (ready.read() == 'Y') return true;
                    break;
                }
                Thread.sleep(20);
            }
        } catch (IOException | SecurityException unavailable) {
            // Leave normal system sleep enabled if the policy cannot be acquired.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        release();
        return false;
    }

    void release() {
        Process process = holder;
        holder = null;
        if (process == null) return;
        try {
            // EOF also releases the policy if the Shizuku user process dies unexpectedly.
            process.getOutputStream().close();
            process.waitFor(1500, TimeUnit.MILLISECONDS);
        } catch (IOException unavailable) {
            // The shell may have already exited and restored the setting.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    void recover() {
        try {
            run("recover").waitFor(1500, TimeUnit.MILLISECONDS);
        } catch (IOException | SecurityException unavailable) {
            // Recovery is tried again when the listener reconnects.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private Process run(String mode) throws IOException {
        return new ProcessBuilder("/system/bin/sh", "-c", SCRIPT, "power-shortcut", mode)
                .redirectErrorStream(true).start();
    }
}
