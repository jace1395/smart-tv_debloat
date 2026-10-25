package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

/**
 * BroadcastReceiver for match automation:
 * 1) ACTION_MATCH_AUTO_WAKE (5 minutes before kickoff):
 *    Wakes the Fire TV Stick, powers on the display, and brings MainActivity to front,
 *    triggering an HDMI-CEC <Active Source> command to switch the TV from HDMI 2 to HDMI 1.
 *
 * 2) ACTION_MATCH_AUTO_OFF (2-5 minutes after match ends):
 *    Closes playback and puts Fire TV into sleep / inactive state (releasing HDMI-CEC active source),
 *    allowing Sony BRAVIA to revert to HDMI 2 (Tata Sky) or stay cleanly inactive.
 */
public class MatchAutoWakeReceiver extends BroadcastReceiver {

    private static final String TAG = "MatchAutoWakeReceiver";

    public static final String ACTION_MATCH_AUTO_WAKE = "com.gathin.tvhome.ACTION_MATCH_AUTO_WAKE";
    public static final String ACTION_MATCH_AUTO_OFF = "com.gathin.tvhome.ACTION_MATCH_AUTO_OFF";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;

        // Ensure accessibility service is active across any boot or wake event
        ensureAccessibilityEnabled(context);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            Log.i(TAG, "Device booted. Scheduling match auto-wake events and checking active match window...");
            SportsCalendarSync.syncCalendarAsync(context, null);
            Config cfg = Config.load(context);
            if (cfg != null && cfg.raceCalendar != null) {
                MatchAutoWakeScheduler.scheduleNextMatchEvents(context, cfg.raceCalendar);
            }

            // Check if boot happened during an active live match window
            boolean matchInProgress = false;
            if (cfg != null && cfg.raceCalendar != null) {
                long nowMs = System.currentTimeMillis();
                for (RaceWeekend.Race r : cfg.raceCalendar) {
                    if (r.isPostponed || r.sessions == null) continue;
                    for (RaceWeekend.Session s : r.sessions) {
                        long kMs = s.start.toEpochMilli();
                        if (nowMs >= (kMs - 5 * 60 * 1000L) && nowMs <= (kMs + 125 * 60 * 1000L)) {
                            matchInProgress = true;
                            break;
                        }
                    }
                    if (matchInProgress) break;
                }
            }

            if (matchInProgress) {
                Log.i(TAG, "Cold boot occurred during active match window! Asserting HDMI 1 Active Source multi-burst...");
                assertHdmiActiveSource(context);
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    Intent matchLaunch = new Intent(context, MainActivity.class);
                    matchLaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    matchLaunch.putExtra("extra_auto_switched_for_match", true);
                    context.startActivity(matchLaunch);
                }, 1500L);
                return;
            }

            Intent bootLaunch = new Intent(context, MainActivity.class);
            bootLaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(bootLaunch);
            return;
        }

        // Post-match switch-off (2-5 minutes after match ends)
        if (ACTION_MATCH_AUTO_OFF.equals(action) || (intent != null && intent.getBooleanExtra("extra_auto_off", false))) {
            Log.i(TAG, "Match ended (+3 mins delay)! Putting HDMI 1 into inactive sleep state...");
            long kickoffEpochMs = intent != null ? intent.getLongExtra("kickoff_epoch_ms", 0) : 0;
            if (kickoffEpochMs > 0) {
                MatchAutoWakeScheduler.markSleepCompleted(context, kickoffEpochMs);
            }
            Config cfg = Config.load(context);
            if (cfg != null && cfg.raceCalendar != null) {
                MatchAutoWakeScheduler.scheduleNextMatchEvents(context, cfg.raceCalendar);
            }
            deactivateHdmiAndSleep(context);
            return;
        }

        // Pre-match auto-wake or late-wake switch assurance
        boolean isLateSwitch = intent != null && intent.getBooleanExtra("extra_late_switch_check", false);
        Log.i(TAG, (isLateSwitch ? "Late-wake switch assurance alarm fired!" : "Pre-match auto-wake alarm fired!") + " Waking screen and asserting HDMI 1 Active Source...");

        long kickoffEpochMs = intent != null ? intent.getLongExtra("kickoff_epoch_ms", 0) : 0;
        if (kickoffEpochMs > 0 && !isLateSwitch) {
            MatchAutoWakeScheduler.markWakeCompleted(context, kickoffEpochMs);
        }

        Config cfg = Config.load(context);
        if (cfg != null && cfg.raceCalendar != null) {
            // Verify if this match was marked postponed
            if (kickoffEpochMs > 0) {
                for (RaceWeekend.Race r : cfg.raceCalendar) {
                    if (r.sessions == null) continue;
                    for (RaceWeekend.Session s : r.sessions) {
                        if (s.start.toEpochMilli() == kickoffEpochMs && r.isPostponed) {
                            Log.i(TAG, "Match " + r.name + " is POSTPONED! Aborting auto-wake and HDMI switch.");
                            return;
                        }
                    }
                }
            }
            MatchAutoWakeScheduler.scheduleNextMatchEvents(context, cfg.raceCalendar);
        }

        // Strict guard: verify a match is ACTUALLY active / in-window right now
        boolean isMatchActive = false;
        if (cfg != null && cfg.raceCalendar != null) {
            long nowMs = System.currentTimeMillis();
            for (RaceWeekend.Race r : cfg.raceCalendar) {
                if (r.isPostponed || r.sessions == null) continue;
                for (RaceWeekend.Session s : r.sessions) {
                    long kMs = s.start.toEpochMilli();
                    if (nowMs >= (kMs - 6 * 60 * 1000L) && nowMs <= (kMs + 130 * 60 * 1000L)) {
                        isMatchActive = true;
                        break;
                    }
                }
                if (isMatchActive) break;
            }
        }

        if (!isMatchActive) {
            Log.i(TAG, "No match active or scheduled right now. Aborting HDMI Active Source switch.");
            return;
        }

        // If the match is already actively streaming in the foreground, switch TV silently without interrupting playback!
        if (HomeKeyService.isStreamingAppInForeground(context)) {
            Log.i(TAG, "Streaming app is already in foreground! Asserting silent active source to switch TV without interrupting playback.");
            assertHdmiActiveSourceSilent(context);
            return;
        }

        // Acquire full wake lock to turn screen on
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE,
                    "bravia:MatchAutoWakeLock"
            );
            wl.acquire(60_000); // 60 seconds
        }

        // Multi-Burst HDMI Active Source switch (switches Sony Bravia from Tata Sky to Fire Stick)
        assertHdmiActiveSource(context);

        // Stream launch after 1.5s delay to allow Sony Bravia HDMI handshake to settle
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            Intent launchIntent = new Intent(context, MainActivity.class);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            launchIntent.putExtra("extra_auto_switched_for_match", true);
            context.startActivity(launchIntent);
        }, 1500L);
    }

    public static void ensureAccessibilityEnabled(Context context) {
        try {
            android.content.ContentResolver cr = context.getContentResolver();
            String enabled = android.provider.Settings.Secure.getString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String target = "com.gathin.tvhome/.HomeKeyService";
            if (enabled == null || !enabled.contains(target)) {
                String newVal = (enabled == null || enabled.isEmpty()) ? target : enabled + ":" + target;
                android.provider.Settings.Secure.putString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newVal);
            }
            android.provider.Settings.Secure.putInt(cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Log.i(TAG, "ensureAccessibilityEnabled: verified and enforced HomeKeyService in secure settings.");
        } catch (Exception e) {
            Log.w(TAG, "ensureAccessibilityEnabled error: " + e.getMessage());
        }
    }

    public static void assertHdmiActiveSource(Context context) {
        // Step 1: Enforce accessibility service active
        ensureAccessibilityEnabled(context);

        // Step 2: Global Action Home via AccessibilityService
        boolean homeTriggered = HomeKeyService.performHome();
        Log.i(TAG, "assertHdmiActiveSource: HomeKeyService.performHome result=" + homeTriggered);

        // Step 3: Fire Native System Home Intent (causes Fire OS PhoneWindowManager to transmit CEC Active Source)
        try {
            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(homeIntent);
            Log.i(TAG, "assertHdmiActiveSource: native CATEGORY_HOME intent launched.");
        } catch (Exception e) {
            Log.w(TAG, "Native CATEGORY_HOME launch failed: " + e.getMessage());
        }

        // Step 4: Fire Amazon Device Control Service Intent (Amazon's proprietary CEC activator)
        try {
            Intent amzIntent = new Intent("com.amazon.tv.devicecontrol.ACTION_ACTIVATE");
            amzIntent.setPackage("com.amazon.tv.devicecontrol");
            context.startService(amzIntent);
            Log.i(TAG, "assertHdmiActiveSource: com.amazon.tv.devicecontrol service intent sent.");
        } catch (Exception ignored) {}

        // Step 5: Direct PlaybackClient.oneTouchPlay reflection
        triggerOneTouchPlay(context);
    }

    /**
     * Silent HDMI-CEC Active Source assertion:
     * Commands Sony Bravia TV to switch its input to HDMI 1 (Fire Stick)
     * WITHOUT pressing Home, starting activities, or touching the playing video.
     */
    public static void assertHdmiActiveSourceSilent(Context context) {
        try {
            Intent amzIntent = new Intent("com.amazon.tv.devicecontrol.ACTION_ACTIVATE");
            amzIntent.setPackage("com.amazon.tv.devicecontrol");
            context.startService(amzIntent);
            Log.d(TAG, "assertHdmiActiveSourceSilent: com.amazon.tv.devicecontrol ACTION_ACTIVATE sent.");
        } catch (Exception ignored) {}

        try {
            Intent turnOnIntent = new Intent("amazon.intent.action.TURN_TV_ON");
            turnOnIntent.setPackage("com.amazon.tv.devicecontrol");
            context.sendBroadcast(turnOnIntent);
            Log.d(TAG, "assertHdmiActiveSourceSilent: TURN_TV_ON broadcast sent.");
        } catch (Exception ignored) {}

        triggerOneTouchPlay(context);
    }

    public static void triggerOneTouchPlay(Context context) {
        try {
            Object hdmiManager = context.getSystemService("hdmi_control");
            if (hdmiManager != null) {
                java.lang.reflect.Method getClient = hdmiManager.getClass().getMethod("getPlaybackClient");
                Object client = getClient.invoke(hdmiManager);
                if (client != null) {
                    for (java.lang.reflect.Method m : client.getClass().getMethods()) {
                        if ("oneTouchPlay".equals(m.getName())) {
                            Class<?>[] params = m.getParameterTypes();
                            if (params.length == 0) {
                                m.invoke(client);
                            } else if (params.length == 1) {
                                m.invoke(client, new Object[]{ null });
                            }
                            Log.i(TAG, "Invoked HdmiPlaybackClient.oneTouchPlay() -> Sent <Active Source>!");
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error invoking oneTouchPlay: " + e.getMessage());
        }
    }

    public static void deactivateHdmiAndSleep(Context context) {
        try {
            Object hdmiManager = context.getSystemService("hdmi_control");
            if (hdmiManager != null) {
                java.lang.reflect.Method getClient = hdmiManager.getClass().getMethod("getPlaybackClient");
                Object client = getClient.invoke(hdmiManager);
                if (client != null) {
                    for (java.lang.reflect.Method m : client.getClass().getMethods()) {
                        String name = m.getName();
                        if ("sendStandby".equals(name) || "sendInactiveSource".equals(name)) {
                            try {
                                Class<?>[] p = m.getParameterTypes();
                                if (p.length == 0) m.invoke(client);
                                Log.i(TAG, "Invoked HdmiPlaybackClient." + name + "()");
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error calling CEC inactive: " + e.getMessage());
        }

        try {
            // First bring MainActivity to front and signal it to stop any active video/playback
            Intent stopIntent = new Intent(context, MainActivity.class);
            stopIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            stopIntent.putExtra("extra_stop_playback", true);
            context.startActivity(stopIntent);
        } catch (Exception e) {
            Log.w(TAG, "Could not send stop playback to MainActivity: " + e.getMessage());
        }

        // Execute sleep via HomeKeyService (Accessibility GLOBAL_ACTION_LOCK_SCREEN)
        boolean slept = HomeKeyService.performSleep();
        Log.i(TAG, "HDMI 1 sleep execution via HomeKeyService result: " + slept);

        if (!slept) {
            try {
                Runtime.getRuntime().exec("input keyevent 223"); // KEYCODE_SLEEP fallback
            } catch (Exception ignored) {}
        }
    }
}
