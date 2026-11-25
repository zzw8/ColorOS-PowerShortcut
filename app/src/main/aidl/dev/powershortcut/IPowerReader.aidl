package dev.powershortcut;

interface IPowerReader {
    oneway void destroy() = 16777114;
    void start() = 0;
    String status() = 1;
    void setWalletShortcut(boolean enabled, boolean automaticNfc, boolean deferSleep) = 2;
}
