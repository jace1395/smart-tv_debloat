package com.gathin.tvhome;

import android.content.Context;
import java.util.List;

/**
 * Auto-wake scheduler disabled on Mi TV to keep launcher simple and quiet.
 */
public final class MatchAutoWakeScheduler {
    private MatchAutoWakeScheduler() {}

    public static void scheduleNextMatchWake(Context context, List<RaceWeekend.Race> calendar) {
        // Disabled on Mi TV
    }

    public static void scheduleNextMatchEvents(Context context, List<RaceWeekend.Race> calendar) {
        // Disabled on Mi TV
    }

    public static void markWakeCompleted(Context context, long kickoffEpochMs) {
        // Disabled on Mi TV
    }

    public static void markSleepCompleted(Context context, long kickoffEpochMs) {
        // Disabled on Mi TV
    }
}
