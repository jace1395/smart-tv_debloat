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
    public static volatile String sCurrentStreamingMatch = null;
    private final android.os.Handler profileHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable profileCheckRunnable = this::checkAndSelectPreferredYtProfile;

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

        SmartMatchDirector.getInstance(this).attachAccessibilityService(this);
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
        profileHandler.removeCallbacks(profileCheckRunnable);
        SmartMatchDirector.getInstance(this).getSportzfyAutoPilot().detach();
        if (instance == this) instance = null;
    }

    public static boolean isStreamingAppInForeground(android.content.Context context) {
        // 1. Check AccessibilityService root window if connected
        if (instance != null) {
            try {
                android.view.accessibility.AccessibilityNodeInfo root = instance.getRootInActiveWindow();
                if (root != null && root.getPackageName() != null) {
                    String rootPkg = root.getPackageName().toString().toLowerCase();
                    if (rootPkg.contains("youtube") || rootPkg.contains("sportzfy") || rootPkg.contains("webstream")) {
                        sCurrentPackage = rootPkg;
                        return true;
                    }
                }
            } catch (Exception ignored) {}
        }

        // 2. Check sCurrentPackage cached from window state changes
        if (sCurrentPackage != null) {
            String current = sCurrentPackage.toLowerCase();
            if (current.contains("youtube") || current.contains("sportzfy") || current.contains("webstream")) {
                return true;
            }
        }

        // 3. Check ActivityManager running processes for foreground importance
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) context.getSystemService(android.content.Context.ACTIVITY_SERVICE);
            if (am != null) {
                java.util.List<android.app.ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
                if (procs != null) {
                    for (android.app.ActivityManager.RunningAppProcessInfo proc : procs) {
                        if (proc.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                            String pName = proc.processName != null ? proc.processName.toLowerCase() : "";
                            if (pName.contains("youtube") || pName.contains("sportzfy") || pName.contains("webstream")) {
                                sCurrentPackage = pName;
                                return true;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        return false;
    }

    public static boolean performSleep() {
        if (instance != null) {
            Log.i(TAG, "Performing GLOBAL_ACTION_LOCK_SCREEN to sleep Fire TV & release HDMI 1...");
            return instance.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
        }
        Log.w(TAG, "HomeKeyService instance is null, cannot perform global lock screen action.");
        return false;
    }

    public static boolean performHome() {
        if (instance != null) {
            Log.i(TAG, "Performing GLOBAL_ACTION_HOME to wake/assert HDMI 1 Active Source via system...");
            return instance.performGlobalAction(GLOBAL_ACTION_HOME);
        }
        Log.w(TAG, "HomeKeyService instance is null, cannot perform global home action.");
        return false;
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
            String pkgStr = pkg.toString();

            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                // Only update sCurrentPackage on true window changes, ignoring system toast/overlay
                if (!"android".equals(pkgStr) && !"com.android.systemui".equals(pkgStr)) {
                    sCurrentPackage = pkgStr;
                }

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
                        if (SmartMatchDirector.getInstance(this).getSportzfyAutoPilot().isNavigating()
                                || isStreamingAppInForeground(this)) {
                            Log.i(TAG, "Streaming app or Auto-Pilot active, skipping home redirect");
                            return;
                        }
                        Log.i(TAG, "Fire OS stock launcher appeared (" + pkgStr + " / " + clsStr + ") -> Redirecting to custom launcher");
                        launchHome();
                        return;
                    }
                }
            }

            // Every time YouTube opens or window/content updates, verify if it's asking for a profile
            if (pkgStr.contains("youtube")) {
                scheduleProfileCheck();
            }

            if (SportzfyAutoPilot.PKG_SPORTZFY.equals(pkgStr)) {
                boolean windowChanged = (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
                SmartMatchDirector.getInstance(this).getSportzfyAutoPilot()
                        .onAccessibilityActivityOrContent(this, windowChanged);
            }
        }
    }

    private void scheduleProfileCheck() {
        profileHandler.removeCallbacks(profileCheckRunnable);
        profileHandler.post(profileCheckRunnable);
        profileHandler.postDelayed(profileCheckRunnable, 400L);
        profileHandler.postDelayed(profileCheckRunnable, 1000L);
        profileHandler.postDelayed(profileCheckRunnable, 2000L);
        profileHandler.postDelayed(profileCheckRunnable, 3500L);
        profileHandler.postDelayed(profileCheckRunnable, 5000L);
    }

    private static long sLastProfileClickMs = 0;

    private static final String[] PREFERRED_YT_PROFILE_QUERIES = new String[] {
            "@spider-jace",
            "Jace-13",
            "spider-jace",
            "Jace"
    };
    private static final String[] YT_PICKER_PROMPT_QUERIES = new String[] {
            "Who's watching",
            "Who is watching",
            "Choose an account",
            "Select an account",
            "Switch account"
    };
    private static final String[] YT_PICKER_OPTION_QUERIES = new String[] {
            "Add account",
            "Add another account",
            "Use YouTube signed out"
    };
    private static final String[] SKIP_PROFILE_CARD_QUERIES = new String[] {
            "add account",
            "add another account",
            "guest",
            "use youtube signed out",
            "sign in",
            "kids",
            "youtube kids",
            "olavo",
            "olavoh18"
    };

    public void checkAndSelectPreferredYtProfile() {
        try {
            android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;

            CharSequence activePkg = root.getPackageName();
            if (activePkg == null || !activePkg.toString().toLowerCase().contains("youtube")) {
                return;
            }

            long now = System.currentTimeMillis();
            if (now - sLastProfileClickMs < 3000L) {
                // Rate limit profile clicks to prevent repeat taps
                return;
            }

            boolean isPickerScreen = containsAnyNodeText(root, YT_PICKER_PROMPT_QUERIES)
                    || containsAnyNodeText(root, YT_PICKER_OPTION_QUERIES);
            if (!isPickerScreen) {
                // Avoid clicking profile badges or video titles when already on normal YouTube Home/player
                return;
            }

            // 1. Direct text search for Jace's active profile (Jace-13 / @spider-jace)
            for (String query : PREFERRED_YT_PROFILE_QUERIES) {
                java.util.List<android.view.accessibility.AccessibilityNodeInfo> nodes =
                        root.findAccessibilityNodeInfosByText(query);
                if (nodes != null) {
                    for (android.view.accessibility.AccessibilityNodeInfo node : nodes) {
                        if (!nodeMatchesSkipList(node) && activateNode(node)) {
                            sLastProfileClickMs = now;
                            Log.i(TAG, "Auto-selected active YouTube profile ('" + query + "')!");
                            return;
                        }
                    }
                }
            }

            // 2. Recursive content-description & text scan for Jace-13 / @spider-jace
            if (searchAndClickProfile(root, PREFERRED_YT_PROFILE_QUERIES)) {
                sLastProfileClickMs = now;
                return;
            }

            // 3. Fallback when YouTube/Cobalt hides profile labels behind anonymous clickable cards:
            // only select the focused/first card if it is not Guest, Kids, Add account, or stale Olavo.
            Log.i(TAG, "YouTube profile picker detected without exposed Jace label; selecting safe active profile card.");
            android.view.accessibility.AccessibilityNodeInfo focused =
                    root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused == null) {
                focused = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            }
            if (focused != null && !nodeMatchesSkipList(focused)) {
                android.graphics.Rect rect = new android.graphics.Rect();
                focused.getBoundsInScreen(rect);
                if (rect.width() > 0 && rect.width() < 1800 && rect.height() > 0 && rect.height() < 1000) {
                    if (activateNode(focused)) {
                        pressEnterKey();
                        sLastProfileClickMs = now;
                        Log.i(TAG, "Activated focused safe YouTube profile card.");
                        return;
                    }
                }
            }

            if (clickFirstSafeProfileCard(root)) {
                sLastProfileClickMs = now;
                Log.i(TAG, "Selected first non-skipped YouTube profile card.");
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not check YouTube profile: " + e.getMessage());
        }
    }

    private void pressEnterKey() {
        try {
            Runtime.getRuntime().exec("input keyevent 23"); // KEYCODE_DPAD_CENTER
        } catch (Exception ignored) {}
        try {
            Runtime.getRuntime().exec("input keyevent 66"); // KEYCODE_ENTER
        } catch (Exception ignored) {}
    }

    public boolean dispatchTap(float x, float y) {
        if (x <= 0 || y <= 0) return false;
        try {
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(x, y);
            android.accessibilityservice.GestureDescription.StrokeDescription stroke =
                    new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50);
            android.accessibilityservice.GestureDescription.Builder builder =
                    new android.accessibilityservice.GestureDescription.Builder();
            builder.addStroke(stroke);
            return dispatchGesture(builder.build(), null, null);
        } catch (Exception e) {
            Log.w(TAG, "dispatchTap failed: " + e.getMessage());
            return false;
        }
    }

    private boolean containsAnyNodeText(android.view.accessibility.AccessibilityNodeInfo root, String[] queries) {
        if (root == null) return false;
        for (String q : queries) {
            java.util.List<android.view.accessibility.AccessibilityNodeInfo> found =
                    root.findAccessibilityNodeInfosByText(q);
            if (found != null && !found.isEmpty()) {
                return true;
            }
        }
        return hasTextRecursive(root, queries);
    }

    private static String extractNodeText(android.view.accessibility.AccessibilityNodeInfo node) {
        if (node == null) return "";
        CharSequence txt = node.getText();
        CharSequence desc = node.getContentDescription();
        return ((txt != null ? txt.toString() : "") + " " + (desc != null ? desc.toString() : ""))
                .toLowerCase();
    }

    private boolean nodeMatchesSkipList(android.view.accessibility.AccessibilityNodeInfo node) {
        if (node == null) return false;
        String own = extractNodeText(node);
        for (String skip : SKIP_PROFILE_CARD_QUERIES) {
            if (own.contains(skip)) return true;
        }
        return hasTextRecursive(node, SKIP_PROFILE_CARD_QUERIES);
    }

    private boolean activateNode(android.view.accessibility.AccessibilityNodeInfo node) {
        if (node == null) return false;
        boolean acted = clickNodeOrParent(node);
        if (node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SELECT)) {
            acted = true;
        }
        android.graphics.Rect rect = new android.graphics.Rect();
        node.getBoundsInScreen(rect);
        if (rect.width() > 0 && rect.width() < 1800 && rect.height() > 0 && rect.height() < 1000) {
            if (dispatchTap(rect.centerX(), rect.centerY())) {
                acted = true;
            }
        }
        return acted;
    }

    private boolean searchAndClickProfile(android.view.accessibility.AccessibilityNodeInfo node, String[] queries) {
        if (node == null) return false;
        String nodeText = extractNodeText(node);

        for (String q : queries) {
            if (nodeText.contains(q.toLowerCase()) && !nodeMatchesSkipList(node)) {
                if (activateNode(node)) {
                    Log.i(TAG, "Successfully auto-selected YouTube profile matching '" + q + "' via deep scan!");
                    return true;
                }
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            if (searchAndClickProfile(node.getChild(i), queries)) return true;
        }
        return false;
    }

    private boolean hasTextRecursive(android.view.accessibility.AccessibilityNodeInfo node, String[] queries) {
        if (node == null) return false;
        String nodeText = extractNodeText(node);

        for (String q : queries) {
            if (nodeText.contains(q.toLowerCase())) return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (hasTextRecursive(node.getChild(i), queries)) return true;
        }
        return false;
    }

    private boolean clickFirstSafeProfileCard(android.view.accessibility.AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isClickable() && (node.isFocusable() || "android.widget.Button".contentEquals(node.getClassName()))) {
            if (!nodeMatchesSkipList(node)) {
                android.graphics.Rect rect = new android.graphics.Rect();
                node.getBoundsInScreen(rect);
                if (rect.width() > 0 && rect.width() < 1800 && rect.height() > 0 && rect.height() < 1000) {
                    if (activateNode(node)) return true;
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (clickFirstSafeProfileCard(node.getChild(i))) return true;
        }
        return false;
    }

    private boolean clickNodeOrParent(android.view.accessibility.AccessibilityNodeInfo node) {
        if (node == null) return false;
        android.view.accessibility.AccessibilityNodeInfo curr = node;
        for (int depth = 0; depth < 5 && curr != null; depth++) {
            if (curr.isClickable()) {
                return curr.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
            }
            curr = curr.getParent();
        }
        if (node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
            return true;
        }
        android.view.accessibility.AccessibilityNodeInfo p = node.getParent();
        if (p != null) {
            return p.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
        }
        return false;
    }

    @Override
    public void onInterrupt() {
    }
}

