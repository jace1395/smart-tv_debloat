package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Disabled on Mi TV to keep launcher simple and quiet.
 */
public class MatchAutoWakeReceiver extends BroadcastReceiver {
    public static final String ACTION_MATCH_AUTO_WAKE = "com.gathin.tvhome.ACTION_MATCH_AUTO_WAKE";
    public static final String ACTION_MATCH_AUTO_OFF = "com.gathin.tvhome.ACTION_MATCH_AUTO_OFF";

    @Override
    public void onReceive(Context context, Intent intent) {
        // Disabled on Mi TV
    }
}
