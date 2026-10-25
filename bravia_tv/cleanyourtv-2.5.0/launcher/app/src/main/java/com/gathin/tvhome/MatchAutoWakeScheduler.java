package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.util.List;

/**
 * Schedules:
 * 1) Automatic wakeup 5 minutes before match kickoff (wakes screen & asserts HDMI 1 active source).
 * 2) Automatic sleep / release 3 minutes after match ends (puts HDMI 1 into inactive state so TV switches to HDMI 2 Tata Sky).
 */
public class MatchAutoWakeScheduler {

    private static final String TAG = "MatchAutoWakeScheduler";
    private static final String PREFS_NAME = "match_auto_wake";
    private static final String KEY_LAST_WAKE_KICKOFF = "last_wake_kickoff";
    private static final String KEY_LAST_SLEEP_KICKOFF = "last_sleep_kickoff";

    private static final int WAKE_REQUEST_CODE = 7788;
    private static final int SLEEP_REQUEST_CODE = 7789;

    public static final long LEAD_TIME_MS = 5 * 60 * 1000;         // 5 minutes before kickoff
    public static final long MATCH_DURATION_MS = 120 * 60 * 1000;  // 2 hours estimated match time
    public static final long POST_MATCH_DELAY_MS = 3 * 60 * 1000;  // 3 minutes after match ends

    public static void scheduleNextMatchWake(Context context, List<RaceWeekend.Race> calendar) {
        scheduleNextMatchEvents(context, calendar);
    }

    public static void scheduleNextMatchEvents(Context context, List<RaceWeekend.Race> calendar) {
        if (calendar == null || calendar.isEmpty()) return;

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lastWokenKickoff = prefs.getLong(KEY_LAST_WAKE_KICKOFF, 0);
        long lastSleptKickoff = prefs.getLong(KEY_LAST_SLEEP_KICKOFF, 0);

        long nowMs = System.currentTimeMillis();
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        int scheduledCount = 0;
        for (RaceWeekend.Race r : calendar) {
            if (r.isPostponed || r.sessions == null) continue;
            for (RaceWeekend.Session s : r.sessions) {
                long kickoffMs = s.start.toEpochMilli();
                long cycleEndMs = kickoffMs + MATCH_DURATION_MS + POST_MATCH_DELAY_MS;
                // Schedule all upcoming matches within the next 36 hours whose full cycle has not ended
                if (cycleEndMs > nowMs && kickoffMs < (nowMs + 36 * 3600 * 1000L)) {
                    int baseCode = (int) ((kickoffMs / 60000L) % 100000L);
                    int wakeCode = baseCode * 10 + 1;

                    // 1. Schedule Pre-Match Hardware Wake Alarm (5 minutes before kickoff)
                    long wakeEpochMs = kickoffMs - LEAD_TIME_MS;
                    if (wakeEpochMs > nowMs) {
                        Intent wakeIntent = new Intent(context, MatchAutoWakeReceiver.class);
                        wakeIntent.setAction(MatchAutoWakeReceiver.ACTION_MATCH_AUTO_WAKE);
                        wakeIntent.putExtra("kickoff_epoch_ms", kickoffMs);
                        PendingIntent wakePi = PendingIntent.getBroadcast(
                                context,
                                wakeCode,
                                wakeIntent,
                                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
                        );
                        scheduleExact(am, wakeEpochMs, wakePi);
                        Log.i(TAG, "Scheduled pre-match hardware wake at: " + wakeEpochMs + " (kickoff: " + kickoffMs + ", code: " + wakeCode + ")");
                    } else if (nowMs < (kickoffMs + MATCH_DURATION_MS) && lastWokenKickoff != kickoffMs) {
                        // Current match window active: fire immediate wake
                        Intent wakeIntent = new Intent(context, MatchAutoWakeReceiver.class);
                        wakeIntent.setAction(MatchAutoWakeReceiver.ACTION_MATCH_AUTO_WAKE);
                        wakeIntent.putExtra("kickoff_epoch_ms", kickoffMs);
                        PendingIntent wakePi = PendingIntent.getBroadcast(
                                context,
                                wakeCode,
                                wakeIntent,
                                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
                        );
                        scheduleExact(am, nowMs + 1000, wakePi);
                        Log.i(TAG, "Scheduled immediate in-window wake for kickoff: " + kickoffMs);
                    }

                    scheduledCount++;
                }
            }
        }
        Log.i(TAG, "Processed match auto-wake events for " + scheduledCount + " upcoming match session(s).");
    }

    private static void scheduleExact(AlarmManager am, long triggerAtMillis, PendingIntent pi) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not schedule exact alarm: " + e.getMessage());
        }
    }

    public static void markWakeCompleted(Context context, long kickoffEpochMs) {
        if (kickoffEpochMs > 0) {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putLong(KEY_LAST_WAKE_KICKOFF, kickoffEpochMs).apply();
        }
    }

    public static void markSleepCompleted(Context context, long kickoffEpochMs) {
        if (kickoffEpochMs > 0) {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putLong(KEY_LAST_SLEEP_KICKOFF, kickoffEpochMs).apply();
        }
    }
}
