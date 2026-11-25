package dev.powershortcut;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowInsets;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import rikka.shizuku.Shizuku;

public final class MainActivity extends Activity {
    private static final String APPEARANCE_KEY = "appearance";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Shizuku.OnRequestPermissionResultListener permissionResult = (requestCode, result) -> {
        if (result == PackageManager.PERMISSION_GRANTED && MonitorService.isRequested(this)) {
            startService(new Intent(this, MonitorService.class));
        }
        refresh();
    };
    private SharedPreferences preferences;
    private TextView shizukuStatus;
    private TextView targetStatus;
    private TextView diagnostics;
    private TextView monitorStatus;
    private ImageView targetIcon;
    private String displayedTarget;
    private ScrollView scroll;
    private LinearLayout shortcutPage;
    private LinearLayout toolsPage;
    private Button shortcutTab;
    private Button toolsTab;
    private Button connect;
    private Button monitor;
    private Button exitToBackground;
    private Button test;
    private LinearLayout nfcOptions;

    private static final class AppChoice {
        final String id;
        final String label;
        final String packageName;
        final String searchText;

        AppChoice(String id, String label) {
            this.id = id;
            this.label = label;
            this.packageName = id.startsWith("preset:") ? id.substring(7) : id;
            this.searchText = (label + " " + packageName).toLowerCase(Locale.ROOT);
        }

        @Override public String toString() {
            return label + "\n" + packageName;
        }
    }

    @Override protected void attachBaseContext(Context base) {
        String appearance = Targets.settings(base).getString(APPEARANCE_KEY, "system");
        if ("light".equals(appearance) || "dark".equals(appearance)) {
            Configuration configuration = new Configuration(base.getResources().getConfiguration());
            int nightMode = "dark".equals(appearance) ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
            configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | nightMode;
            base = base.createConfigurationContext(configuration);
        }
        super.attachBaseContext(base);
    }

    @Override protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        preferences = Targets.settings(this);
        getWindow().setStatusBarColor(getColor(R.color.background));
        getWindow().setNavigationBarColor(getColor(R.color.background));
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(getColor(R.color.background));
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout column = vertical(20);
        column.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(dp(20) + bars.left, dp(14) + bars.top, dp(20) + bars.right, dp(20) + bars.bottom);
            return WindowInsets.CONSUMED;
        });
        scroll.addView(column);
        setContentView(scroll);
        column.addView(label("电源快捷键", 32, R.color.ink, true));
        LinearLayout tabs = row(column);
        tabs.setBackground(background(getColor(R.color.surface_tint), 20));
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        shortcutTab = action(tabs, "快捷键", () -> showTools(false), true);
        toolsTab = action(tabs, "设置与诊断", () -> showTools(true), false);
        shortcutPage = vertical(0);
        toolsPage = vertical(0);
        column.addView(shortcutPage);
        column.addView(toolsPage);
        buildShortcutPage();
        buildToolsPage();
        showTools(savedState != null && savedState.getBoolean("tools"));
        Shizuku.addRequestPermissionResultListener(permissionResult);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("tools", toolsPage.getVisibility() == View.VISIBLE);
    }

    private void buildShortcutPage() {
        LinearLayout hero = card(shortcutPage, null);
        GradientDrawable heroBackground = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{getColor(R.color.hero_start), getColor(R.color.hero_end)});
        heroBackground.setCornerRadius(dp(25));
        hero.setBackground(heroBackground);
        hero.addView(label("双击电源键", 24, R.color.hero_ink, true));
        monitorStatus = label("监听未开启", 15, R.color.hero_muted, false);
        hero.addView(monitorStatus);
        shizukuStatus = label("正在检查 Shizuku", 13, R.color.hero_muted, false);
        hero.addView(shizukuStatus);
        LinearLayout monitorActions = row(hero);
        monitor = action(monitorActions, "开启监听", this::toggleMonitor, true);
        exitToBackground = action(monitorActions, "退出到后台", this::leaveInterface, false);
        paintButton(monitor, R.color.hero_button, R.color.hero_button_ink);
        paintButton(exitToBackground, R.color.hero_end, R.color.hero_ink);
        connect = action(hero, "授权 Shizuku", this::connect, false);

        LinearLayout target = card(shortcutPage, "快捷目标");
        LinearLayout targetRow = row(target);
        targetIcon = new ImageView(this);
        targetIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        iconParams.rightMargin = dp(12);
        targetRow.addView(targetIcon, iconParams);
        targetStatus = label("未选择应用", 19, R.color.ink, true);
        targetRow.addView(targetStatus, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout targetActions = row(target);
        action(targetActions, "选择应用", this::pickTarget, true);
        test = action(targetActions, "试打开", () -> Targets.open(this, preferences.getString("target", "")), false);

        nfcOptions = card(shortcutPage, "Google 钱包");
        addToggle(nfcOptions, "自动开关 NFC", "wallet_auto_nfc", true);
        nfcOptions.addView(label("只在本次双击打开 NFC 时，退出钱包后才关回去。", 14, R.color.muted, false));
        addToggle(nfcOptions, "防止双击先息屏", "wallet_defer_sleep", false);
        nfcOptions.addView(label("单击仍会息屏，开启后会先等约 0.3 秒确认是否双击。", 14, R.color.muted, false));
        nfcOptions.addView(label("锁屏时直达快捷刷卡入口。是否需要解锁由 Google 钱包决定。", 14, R.color.muted, false));
        action(nfcOptions, "默认付款应用设置", this::openNfcPaymentSettings, false);
    }

    private void addToggle(LinearLayout parent, String title, String key, boolean defaultValue) {
        Switch toggle = new Switch(this);
        toggle.setText(title);
        toggle.setTextColor(getColor(R.color.ink));
        toggle.setMinHeight(dp(48));
        toggle.setChecked(preferences.getBoolean(key, defaultValue));
        toggle.setOnCheckedChangeListener((view, checked) -> preferences.edit().putBoolean(key, checked).apply());
        parent.addView(toggle);
    }

    private void buildToolsPage() {
        LinearLayout appearance = card(toolsPage, "外观");
        LinearLayout choices = row(appearance);
        appearanceChoice(choices, "跟随系统", "system");
        appearanceChoice(choices, "浅色", "light");
        appearanceChoice(choices, "深色", "dark");

        LinearLayout status = card(toolsPage, "监听诊断");
        diagnostics = label("监听未开启", 15, R.color.ink, false);
        diagnostics.setTextIsSelectable(true);
        status.addView(diagnostics);
        action(status, "后台运行设置", () -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null))), false);

        LinearLayout wallet = card(toolsPage, "ColorOS 原厂钱包");
        wallet.addView(label("若双击时先弹出自带钱包，可在原厂钱包的「我的 → 设置」关闭双击快捷服务。", 15, R.color.muted, false));
        action(wallet, "打开原厂钱包", this::openWalletSettings, false);

        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            if (version != null) {
                TextView versionLabel = label("版本 " + version, 13, R.color.muted, false);
                versionLabel.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                params.topMargin = dp(14);
                toolsPage.addView(versionLabel, params);
            }
        } catch (PackageManager.NameNotFoundException unavailable) {
            // Package metadata is normally available for the current application.
        }
    }

    private void appearanceChoice(LinearLayout row, String title, String value) {
        boolean selected = value.equals(preferences.getString(APPEARANCE_KEY, "system"));
        Button choice = action(row, title, () -> {
            if (selected) return;
            preferences.edit().putString(APPEARANCE_KEY, value).apply();
            recreate();
        }, selected);
        choice.setSelected(selected);
    }

    private void showTools(boolean tools) {
        shortcutPage.setVisibility(tools ? View.GONE : View.VISIBLE);
        toolsPage.setVisibility(tools ? View.VISIBLE : View.GONE);
        shortcutTab.setSelected(!tools);
        toolsTab.setSelected(tools);
        paintButton(shortcutTab, tools ? R.color.surface_tint : R.color.surface, R.color.accent);
        paintButton(toolsTab, tools ? R.color.surface : R.color.surface_tint, R.color.accent);
        scroll.scrollTo(0, 0);
    }

    @Override protected void onResume() {
        super.onResume();
        tick();
    }

    @Override protected void onPause() {
        handler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    @Override protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionResult);
        super.onDestroy();
    }

    private void tick() {
        if (toolsPage.getVisibility() == View.VISIBLE) MonitorService.refreshVisibleStatus();
        refresh();
        handler.postDelayed(this::tick, 750);
    }

    private void refresh() {
        boolean running = Shizuku.pingBinder();
        boolean allowed = running && authorized();
        setText(shizukuStatus, allowed ? "Shizuku · 已授权" : running ? "Shizuku · 等待授权" : "Shizuku · 未连接");
        connect.setVisibility(allowed ? View.GONE : View.VISIBLE);
        setText(connect, running ? "授权 Shizuku" : "打开 Shizuku");
        String target = preferences.getString("label", "");
        setText(targetStatus, target.isEmpty() ? "未选择应用" : target.replace(" · 预设", ""));
        test.setEnabled(!target.isEmpty());
        String targetId = preferences.getString("target", "");
        nfcOptions.setVisibility(Targets.isWalletPreset(targetId) ? View.VISIBLE : View.GONE);
        if (!targetId.equals(displayedTarget)) {
            displayedTarget = targetId;
            String packageName = targetId.startsWith("preset:") ? targetId.substring(7) : targetId;
            try {
                Drawable icon = getPackageManager().getApplicationIcon(packageName);
                targetIcon.setImageDrawable(icon);
            } catch (PackageManager.NameNotFoundException missing) {
                targetIcon.setImageResource(R.drawable.ic_power);
            }
        }
        boolean canMonitor = MonitorService.active || (allowed && !target.isEmpty());
        monitor.setEnabled(canMonitor);
        exitToBackground.setEnabled(canMonitor);
        setText(monitor, MonitorService.active ? "关闭监听" : "开启监听");
        setText(monitorStatus, MonitorService.active ? "后台监听已开启" : "监听未开启");
        String last = MonitorService.lastLaunch;
        String info = MonitorService.info + (last.isEmpty() ? "" : "\n最近一次 · " + last);
        setText(diagnostics, info);
    }

    private void setText(TextView view, String value) {
        if (!value.contentEquals(view.getText())) view.setText(value);
    }

    private boolean authorized() {
        try {
            return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void connect() {
        try {
            if (Shizuku.pingBinder()) {
                Shizuku.requestPermission(10);
                return;
            }
            Intent app = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (app != null) {
                startActivity(app);
                return;
            }
        } catch (RuntimeException exception) {
            Toast.makeText(this, "Shizuku 暂时无法连接", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "请先安装并启动 Shizuku", Toast.LENGTH_LONG).show();
    }

    private void toggleMonitor() {
        if (MonitorService.active) {
            MonitorService.setRequested(this, false);
            stopService(new Intent(this, MonitorService.class));
        } else {
            startMonitoring();
        }
        refresh();
    }

    private void leaveInterface() {
        if (MonitorService.active || startMonitoring()) finishAndRemoveTask();
    }

    private boolean startMonitoring() {
        if (!authorized() || preferences.getString("target", "").isEmpty()) return false;
        try {
            MonitorService.setRequested(this, true);
            startForegroundService(new Intent(this, MonitorService.class));
            return true;
        } catch (RuntimeException exception) {
            MonitorService.setRequested(this, false);
            Toast.makeText(this, "系统拒绝启动监听服务", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void openWalletSettings() {
        PackageManager packages = getPackageManager();
        Intent wallet = packages.getLaunchIntentForPackage("com.finshell.wallet");
        if (wallet == null) wallet = packages.getLaunchIntentForPackage("com.heytap.wallet");
        startActivity(wallet != null ? wallet : new Intent(Settings.ACTION_SETTINGS));
    }

    private void openNfcPaymentSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_NFC_PAYMENT_SETTINGS));
        } catch (ActivityNotFoundException unavailable) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void pickTarget() {
        List<AppChoice> all = new ArrayList<>();
        all.add(new AppChoice("preset:com.openai.chatgpt", "ChatGPT · 预设"));
        all.add(new AppChoice("preset:com.google.android.apps.bard", "Gemini · 预设"));
        all.add(new AppChoice("preset:com.google.android.apps.walletnfcrel", "Google 钱包 · 预设"));
        LinearLayout content = vertical(16);
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("应用名称或包名");
        content.addView(search);
        ListView list = new ListView(this);
        int listHeight = Math.min(360, getResources().getConfiguration().screenHeightDp / 2);
        content.addView(list, new LinearLayout.LayoutParams(-1, dp(listHeight)));
        ArrayAdapter<AppChoice> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>(all));
        list.setAdapter(adapter);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("选择快捷目标")
                .setView(content).setNegativeButton("取消", null).create();
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                filterTargets(all, text, adapter);
            }
            @Override public void afterTextChanged(Editable text) {}
        });
        list.setOnItemClickListener((parent, row, position, id) -> {
            AppChoice chosen = adapter.getItem(position);
            if (chosen == null) return;
            preferences.edit().putString("target", chosen.id).putString("label", chosen.label).apply();
            dialog.dismiss();
            refresh();
        });
        dialog.show();
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        new Thread(() -> {
            List<AppChoice> installed = loadInstalledApps();
            runOnUiThread(() -> {
                if (!dialog.isShowing() || isDestroyed()) return;
                all.addAll(installed);
                filterTargets(all, search.getText(), adapter);
            });
        }, "shortcut-app-list").start();
    }

    private List<AppChoice> loadInstalledApps() {
        PackageManager packages = getPackageManager();
        LinkedHashMap<String, AppChoice> installed = new LinkedHashMap<>();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo result : packages.queryIntentActivities(query, 0)) {
            String pkg = result.activityInfo.packageName;
            if (!getPackageName().equals(pkg) && !installed.containsKey(pkg)) {
                installed.put(pkg, new AppChoice(pkg, result.loadLabel(packages).toString()));
            }
        }
        List<AppChoice> sorted = new ArrayList<>(installed.values());
        sorted.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return sorted;
    }

    private void filterTargets(List<AppChoice> all, CharSequence text, ArrayAdapter<AppChoice> adapter) {
        String filter = text.toString().trim().toLowerCase(Locale.ROOT);
        adapter.setNotifyOnChange(false);
        adapter.clear();
        for (AppChoice app : all) {
            if (app.searchText.contains(filter)) adapter.add(app);
        }
        adapter.notifyDataSetChanged();
    }

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout card = vertical(18);
        card.setBackground(background(getColor(R.color.surface), 24));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(16);
        parent.addView(card, params);
        if (title != null) card.addView(label(title, 16, R.color.accent, true));
        return card;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(8);
        parent.addView(row, params);
        return row;
    }

    private Button action(LinearLayout parent, String title, Runnable action, boolean primary) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(9), dp(4), dp(9), dp(4));
        button.setMinHeight(dp(46));
        button.setMinimumHeight(dp(46));
        button.setStateListAnimator(null);
        paintButton(button, primary ? R.color.accent : R.color.surface_tint,
                primary ? R.color.on_accent : R.color.accent);
        button.setOnClickListener(view -> action.run());
        boolean horizontal = parent.getOrientation() == LinearLayout.HORIZONTAL;
        LinearLayout.LayoutParams params = horizontal ? new LinearLayout.LayoutParams(0, -2, 1)
                : new LinearLayout.LayoutParams(-1, -2);
        if (horizontal && parent.getChildCount() > 0) params.leftMargin = dp(8);
        if (!horizontal) params.topMargin = dp(9);
        parent.addView(button, params);
        return button;
    }

    private void paintButton(Button button, int fill, int foreground) {
        int color = getColor(foreground);
        button.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{(color & 0x00ffffff) | 0x88000000, color}));
        int ripple = (color & 0x00ffffff) | 0x22000000;
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple),
                background(getColor(fill), 17), null));
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(getColor(color));
        label.setPadding(0, dp(4), 0, dp(4));
        if (bold) label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return label;
    }

    private GradientDrawable background(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private LinearLayout vertical(int padding) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int inset = dp(padding);
        layout.setPadding(inset, inset, inset, inset);
        return layout;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
