package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.Collections;
import java.util.Map;

/**
 * Intercepts Fire TV OS Home button and stock launcher switches,
 * immediately redirecting back to our custom launcher (MainActivity).
 *
 * Enable over adb:
 *   settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
public class HomeKeyService extends AccessibilityService {

    private static final String TAG = "HomeKeyService";

    private static HomeKeyService instance;

    /** Remote buttons that should open Settings (config.xml, settings_key_scancodes and settings_key_keycodes). */
    private int[] settingsScancodes = new int[0];
    private int[] settingsKeycodes = new int[0];
    private boolean settingsPanel;
    /** app_keys, the same map InputKeyService reads, for setups that run only this service. */
    private Map<Integer, String> appKeys = Collections.emptyMap();

    public static volatile String sCurrentPackage = "";
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "HomeKeyService connected and monitoring for Home events.");
        settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
        Config config = Config.load(this);
        settingsKeycodes = config.settingsKeycodes;
        settingsPanel = config.settingsKeyPanel;
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (instance == this) {
            instance = null;
        }
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (instance == this) instance = null;
    }

    private boolean isSettingsKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) return true;
        for (int code : settingsScancodes) if (event.getScanCode() == code) return true;
        for (int code : settingsKeycodes) if (event.getKeyCode() == code) return true;
        return false;
    }

    private void launchHome() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                    this,
                    0,
                    home,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M ? android.app.PendingIntent.FLAG_IMMUTABLE : 0)
            );
            pi.send();
        } catch (Exception e) {
            try {
                Intent direct = new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(direct);
            } catch (Exception ignored) {}
        }
    }

    public static volatile boolean sAllowStockSettings = false;
    public static volatile long sAllowStockSettingsUntil = 0;

    public static void allowStockSettings() {
        sAllowStockSettings = true;
        sAllowStockSettingsUntil = System.currentTimeMillis() + 600000; // 10 minutes
        Log.i(TAG, "Stock settings allowed by user action");
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (isSettingsKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                allowStockSettings();
                Intent open = settingsPanel ? new Intent(this, QuickSettingsActivity.class)
                        : new Intent(Settings.ACTION_SETTINGS);
                try {
                    startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e) {
                    Log.w(TAG, "Failed to launch settings", e);
                }
            }
            return true;
        }
        String app = appKeys.get(event.getKeyCode());
        if (app != null) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) RemoteKeys.launch(this, app);
            return true;
        }

        // Intercept Fire TV Remote HOME button and Xiaomi ALL_APPS button
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME || event.getKeyCode() == KeyEvent.KEYCODE_ALL_APPS) {
            sAllowStockSettings = false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                launchHome();
            }
            return true;
        }

        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int eventType = event.getEventType();
        CharSequence pkg = event.getPackageName();
        if (pkg != null) {
            sCurrentPackage = pkg.toString();
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pkg != null) {
                String pkgStr = pkg.toString();
                // Check if Fire TV stock launcher or Fire Home Starter appears
                if ("com.amazon.tv.launcher".equals(pkgStr) || "com.amazon.firehomestarter".equals(pkgStr)) {
                    if (sAllowStockSettings && System.currentTimeMillis() < sAllowStockSettingsUntil) {
                        Log.i(TAG, "Amazon Settings / launcher allowed by user action, skipping home redirect");
                        return;
                    }
                    CharSequence cls = event.getClassName();
                    String clsStr = cls != null ? cls.toString() : "";
                    if (clsStr.contains("Settings") || clsStr.contains("settings")) {
                        Log.i(TAG, "Amazon Settings opened (" + clsStr + "), allowing user to stay in settings");
                        return;
                    }
                    if ("com.amazon.firehomestarter".equals(pkgStr) || clsStr.contains("HomeActivity") || clsStr.contains("BuellerApplication")) {
                        Log.i(TAG, "Fire OS stock launcher appeared (" + pkgStr + " / " + clsStr + ") -> Redirecting to custom launcher");
                        launchHome();
                        return;
                    }
                }
            }
        }

    }

    @Override
    public void onInterrupt() {
    }
}

