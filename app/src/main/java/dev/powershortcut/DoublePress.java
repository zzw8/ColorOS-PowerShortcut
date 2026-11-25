package dev.powershortcut;

final class DoublePress {
    static final long DEFAULT_WINDOW_MS = 300;
    private final long windowMs;
    private long previousDown = -1;
    private long releasedDown = -1;
    private int presses;
    private boolean previousDownWasScreenOn;

    DoublePress() { this(DEFAULT_WINDOW_MS); }

    DoublePress(long windowMs) { this.windowMs = windowMs; }

    boolean record(PowerKeyEvent event) {
        if (!event.down) {
            if (event.downTime == previousDown) releasedDown = event.canceled ? -1 : previousDown;
            return false;
        }
        long gap = event.time - previousDown;
        if (previousDown >= 0 && gap < 50) return false;
        boolean sameSequence = previousDown >= 0 && gap <= windowMs;
        boolean previousReleased = releasedDown == previousDown;
        presses = sameSequence ? presses + 1 : 1;
        previousDown = event.time;
        previousDownWasScreenOn = event.screenOn;
        if ((sameSequence && !previousReleased) || event.canceled) presses = 3;
        return presses == 2;
    }

    boolean ready(long secondDown) {
        return isDoubleCandidate(secondDown) && releasedDown == secondDown;
    }

    boolean isDoubleCandidate(long secondDown) {
        return previousDown == secondDown && presses == 2;
    }

    boolean isSingleOnAwakeScreen(long down) {
        return presses == 1 && previousDownWasScreenOn && previousDown == down && releasedDown == down;
    }
}
