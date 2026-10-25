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
    private android.content.BroadcastReceiver screenOnReceiver;
    private final android.os.Handler matchCheckHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private static android.os.PowerManager.WakeLock matchWindowWakeLock;
    private static long sLastAutoSwitchAttemptMs = 0;

    private final Runnable matchCheckRunnable = new Runnable() {
        @Override
        public void run() {
            checkAndTriggerLiveMatchWake(getApplicationContext());
            matchCheckHandler.postDelayed(this, 60_000L); // Check every minute
        }
    };

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

        // Start per-minute live match streaming check
        matchCheckHandler.removeCallbacks(matchCheckRunnable);
        matchCheckHandler.post(matchCheckRunnable);

        // Register receiver for when TV turns ON, screen wakes up, or HDMI connects during a live match
        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction("android.intent.action.HDMI_PLUGGED");
        screenOnReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, Intent intent) {
                Log.i(TAG, "Screen / HDMI woke up or turned ON (" + intent.getAction() + ")");
                checkAndTriggerLiveMatchWake(context);
            }
        };
        registerReceiver(screenOnReceiver, filter);
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
        matchCheckHandler.removeCallbacks(matchCheckRunnable);
        if (matchWindowWakeLock != null && matchWindowWakeLock.isHeld()) {
            try {
                matchWindowWakeLock.release();
            } catch (Exception ignored) {}
            matchWindowWakeLock = null;
        }
        if (screenOnReceiver != null) {
            try {
                unregisterReceiver(screenOnReceiver);
            } catch (Exception ignored) {}
            screenOnReceiver = null;
        }
        if (instance == this) instance = null;
    }

    public static void checkAndTriggerLiveMatchWake(android.content.Context context) {
        try {
            SportsCalendarSync.syncCalendarAsync(context, null);
            Config cfg = Config.load(context);
            if (cfg == null || cfg.raceCalendar == null || cfg.raceCalendar.isEmpty()) return;

            long nowMs = System.currentTimeMillis();
            long windowStartMs = 5 * 60 * 1000L;   // 5 minutes before kickoff
            long matchDurationMs = 125 * 60 * 1000L; // Match window: ~2 hours 5 minutes

            boolean matchActive = false;
            String activeMatchName = null;
            long activeKickoff = 0;

            for (RaceWeekend.Race r : cfg.raceCalendar) {
                if (r.isPostponed || r.sessions == null) continue;
                for (RaceWeekend.Session s : r.sessions) {
                    long kickoffMs = s.start.toEpochMilli();
                    if (nowMs >= (kickoffMs - windowStartMs) && nowMs <= (kickoffMs + matchDurationMs)) {
                        matchActive = true;
                        activeMatchName = r.name;
                        activeKickoff = kickoffMs;
                        break;
                    }
                }
                if (matchActive) break;
            }

            if (!matchActive) {
                // No match active right now. Release match window wakelock if held.
                if (matchWindowWakeLock != null && matchWindowWakeLock.isHeld()) {
                    try {
                        matchWindowWakeLock.release();
                        Log.i(TAG, "No active match window; released matchWindowWakeLock.");
                    } catch (Exception ignored) {}
                    matchWindowWakeLock = null;
                }
                return;
            }

            // A live match IS active! Hold CPU wakelock for the duration of the match window.
            if (matchWindowWakeLock == null) {
                android.os.PowerManager pm = (android.os.PowerManager) context.getSystemService(android.content.Context.POWER_SERVICE);
                if (pm != null) {
                    matchWindowWakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "bravia:MatchActiveWindowLock");
                    matchWindowWakeLock.acquire(130 * 60 * 1000L); // Safety cap 130 min
                    Log.i(TAG, "Acquired matchWindowWakeLock for active match: " + activeMatchName);
                }
            }

            // Check if this specific active match is already streaming
            boolean isMatchChanged = sCurrentStreamingMatch == null || !sCurrentStreamingMatch.equalsIgnoreCase(activeMatchName);
            boolean isStreaming = !isMatchChanged && isStreamingAppInForeground(context);

            if (!isStreaming) {
                // Prevent duplicate rapid re-launches within 45 seconds
                if (nowMs - sLastAutoSwitchAttemptMs < 45_000L) {
                    return;
                }
                sLastAutoSwitchAttemptMs = nowMs;
                sCurrentStreamingMatch = activeMatchName;

                Log.i(TAG, "Per-minute check: Live match in progress (" + activeMatchName + "), but streaming app not in foreground or match changed. Switching to Fire Stick and opening stream!");

                // 1. Wake screen if off or in standby
                android.os.PowerManager pm = (android.os.PowerManager) context.getSystemService(android.content.Context.POWER_SERVICE);
                if (pm != null && !pm.isInteractive()) {
                    android.os.PowerManager.WakeLock wl = pm.newWakeLock(
                            android.os.PowerManager.FULL_WAKE_LOCK | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP | android.os.PowerManager.ON_AFTER_RELEASE,
                            "bravia:MatchAutoWakeScreen"
                    );
                    wl.acquire(15_000);
                }

                // 2. Multi-Burst HDMI 1 Active Source (switches Sony TV from Tata Sky HDMI 2 to Fire Stick HDMI 1)
                MatchAutoWakeReceiver.assertHdmiActiveSource(context);

                // 3. Bring MainActivity to front with auto-switch flag to resolve and open the live stream
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    Intent matchIntent = new Intent(context, MainActivity.class);
                    matchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    matchIntent.putExtra("extra_auto_switched_for_match", true);
                    context.startActivity(matchIntent);
                }, 1500L);
            } else {
                // Stream is already actively in foreground on Fire Stick!
                // Re-assert HDMI Active Source silently (without performHome or starting activities)
                // This guarantees Sony TV switches from Tata Sky to HDMI 1 without touching or minimizing playback!
                MatchAutoWakeReceiver.assertHdmiActiveSourceSilent(context);
                if (instance != null) {
                    instance.checkAndSelectOlavoProfile();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error checking live match wake: " + e.getMessage());
        }
    }

    public static boolean isStreamingAppInForeground(android.content.Context context) {
        // 1. Check AccessibilityService root window if connected
        if (instance != null) {
            try {
                android.view.accessibility.AccessibilityNodeInfo root = instance.getRootInActiveWindow();
                if (root != null && root.getPackageName() != null) {
                    String rootPkg = root.getPackageName().toString().toLowerCase();
                    if (rootPkg.contains("youtube") || rootPkg.contains("sonyliv") || rootPkg.contains("webstream")) {
                        sCurrentPackage = rootPkg;
                        return true;
                    }
                }
            } catch (Exception ignored) {}
        }

        // 2. Check sCurrentPackage cached from window state changes
        if (sCurrentPackage != null) {
            String current = sCurrentPackage.toLowerCase();
            if (current.contains("youtube") || current.contains("sonyliv") || current.contains("webstream")) {
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
                            if (pName.contains("youtube") || pName.contains("sonyliv") || pName.contains("webstream")) {
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
        }
    }

    private void scheduleProfileCheck() {
        matchCheckHandler.removeCallbacks(this::checkAndSelectOlavoProfile);
        matchCheckHandler.post(this::checkAndSelectOlavoProfile);
        matchCheckHandler.postDelayed(this::checkAndSelectOlavoProfile, 400L);
        matchCheckHandler.postDelayed(this::checkAndSelectOlavoProfile, 1000L);
        matchCheckHandler.postDelayed(this::checkAndSelectOlavoProfile, 2000L);
        matchCheckHandler.postDelayed(this::checkAndSelectOlavoProfile, 3500L);
        matchCheckHandler.postDelayed(this::checkAndSelectOlavoProfile, 5000L);
    }

    private static long sLastProfileClickMs = 0;

    public void checkAndSelectOlavoProfile() {
        try {
            android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;

            CharSequence activePkg = root.getPackageName();
            if (activePkg != null && !activePkg.toString().toLowerCase().contains("youtube")) {
                return;
            }

            long now = System.currentTimeMillis();
            if (now - sLastProfileClickMs < 3000L) {
                // Rate limit profile clicks to prevent repeat taps
                return;
            }

            // Match Olavo's name variants or his exact Gmail (olavoh18@gmail.com)
            String[] targetQueries = new String[] {
                    "olavoh18@gmail.com",
                    "Olavo Olav",
                    "Olavo",
                    "olavo",
                    "Olav",
                    "olav",
                    "olavoh18"
            };

            // 1. Direct text search
            for (String query : targetQueries) {
                java.util.List<android.view.accessibility.AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(query);
                if (nodes != null && !nodes.isEmpty()) {
                    for (android.view.accessibility.AccessibilityNodeInfo node : nodes) {
                        if (clickNodeOrParent(node)) {
                            sLastProfileClickMs = now;
                            Log.i(TAG, "Successfully auto-selected Olavo profile matching '" + query + "' in YouTube!");
                            return;
                        }
                    }
                }
            }

            // 2. Recursive content-description & text scan
            if (searchAndClickProfile(root, targetQueries)) {
                sLastProfileClickMs = now;
                return;
            }

            // 3. Fallback: Check if the screen is displaying "Who's watching?" or accounts
            String[] whoIsWatchingQueries = new String[] {
                    "Who's watching",
                    "Who is watching",
                    "Choose an account",
                    "Select an account",
                    "Switch account",
                    "Accounts",
                    "Profiles",
                    "Add account",
                    "Guest"
            };
            boolean isProfileScreen = false;
            for (String q : whoIsWatchingQueries) {
                java.util.List<android.view.accessibility.AccessibilityNodeInfo> promptNodes = root.findAccessibilityNodeInfosByText(q);
                if (promptNodes != null && !promptNodes.isEmpty()) {
                    isProfileScreen = true;
                    break;
                }
            }
            if (!isProfileScreen) {
                isProfileScreen = hasTextRecursive(root, whoIsWatchingQueries);
            }

            if (isProfileScreen) {
                Log.i(TAG, "YouTube profile screen ('Who's watching?') detected. Selecting primary profile (Olavo Olav)...");

                // Step A: Focus-based selection (the 1st profile is focused by default on Android TV)
                android.view.accessibility.AccessibilityNodeInfo focused = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT);
                if (focused == null) focused = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
                if (focused != null) {
                    clickNodeOrParent(focused);
                    focused.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SELECT);
                    android.graphics.Rect rect = new android.graphics.Rect();
                    focused.getBoundsInScreen(rect);
                    if (rect.width() > 0 && rect.height() > 0) {
                        dispatchTap(rect.centerX(), rect.centerY());
                    }
                }

                // Step B: Hierarchy-based first card selection
                clickFirstProfileCard(root);

                // Step C: D-Pad Enter fallback (presses Enter on the pre-selected 1st profile card)
                pressEnterKey();

                sLastProfileClickMs = now;
                Log.i(TAG, "Selected primary profile (Olavo Olav) via focused card & D-Pad Enter.");
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not check Olavo profile: " + e.getMessage());
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

    private boolean searchAndClickProfile(android.view.accessibility.AccessibilityNodeInfo node, String[] queries) {
        if (node == null) return false;
        CharSequence txt = node.getText();
        CharSequence desc = node.getContentDescription();
        String nodeText = (txt != null ? txt.toString() : "") + " " + (desc != null ? desc.toString() : "");
        nodeText = nodeText.toLowerCase();

        for (String q : queries) {
            if (nodeText.contains(q.toLowerCase())) {
                if (clickNodeOrParent(node)) {
                    Log.i(TAG, "Successfully auto-selected Olavo profile matching '" + q + "' via deep scan!");
                    pressEnterKey();
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
        CharSequence txt = node.getText();
        CharSequence desc = node.getContentDescription();
        String nodeText = (txt != null ? txt.toString() : "") + " " + (desc != null ? desc.toString() : "");
        nodeText = nodeText.toLowerCase();

        for (String q : queries) {
            if (nodeText.contains(q.toLowerCase())) return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (hasTextRecursive(node.getChild(i), queries)) return true;
        }
        return false;
    }

    private boolean clickFirstProfileCard(android.view.accessibility.AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isFocused()) {
            if (clickNodeOrParent(node)) return true;
            node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SELECT);
            return true;
        }
        if (node.isClickable() && (node.isFocusable() || "android.widget.Button".contentEquals(node.getClassName()))) {
            android.graphics.Rect rect = new android.graphics.Rect();
            node.getBoundsInScreen(rect);
            if (rect.width() > 0 && rect.width() < 1800 && rect.height() > 0 && rect.height() < 1000) {
                if (clickNodeOrParent(node)) return true;
                node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SELECT);
                dispatchTap(rect.centerX(), rect.centerY());
                return true;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (clickFirstProfileCard(node.getChild(i))) return true;
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

