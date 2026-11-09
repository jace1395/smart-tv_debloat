package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * SmartMatchDirector:
 * Automates seamless HDMI (Tata Sky) switching and sports match playback for Uncle.
 *
 * Rules:
 * 1. Never switch if the user is actively on the custom launcher (isLauncherForeground == true).
 * 2. Only switch away from HDMI / Tata Sky when a live match is starting or queued.
 * 3. ISL is top priority: during an ISL match day and match time slot, Sportzfy is completely suppressed.
 *    The TV switches directly to the high quality ISL stream in full screen.
 * 4. Outside ISL match slots: queued football matches stream in succession.
 *    When match 1 ends, the director automatically cues and opens the next match.
 * 5. Hands-off automated playback: Uncle doesn't need to navigate menus or click links.
 */
public class SmartMatchDirector {

    private static final String TAG = "SmartMatchDirector";
    private static final String PREF_NAME = "smart_director_prefs";
    private static final String KEY_LAST_HDMI = "last_hdmi_input";
    private static final String KEY_LAST_STREAMED_MATCH = "last_streamed_match_id";
    private static final String KEY_MATCH_QUEUE = "persisted_match_queue";

    private static SmartMatchDirector sInstance;

    private final Context appContext;
    private final SharedPreferences prefs;
    private final Handler directorHandler = new Handler(Looper.getMainLooper());

    private volatile boolean isLauncherForeground = true;
    private volatile long userManualHdmiCooldownUntil = 0;
    private boolean isMonitoringActive = false;

    private final List<QueuedMatch> matchQueue = new ArrayList<>();

    public static class QueuedMatch {
        public String id;
        public String title;
        public String league;
        public String streamUrl;
        public long startTimeEpochMs;
        public boolean isIsl;
        public boolean isCompleted;

        public QueuedMatch(String id, String title, String league, String streamUrl, long startTimeEpochMs, boolean isIsl) {
            this.id = id;
            this.title = title;
            this.league = league;
            this.streamUrl = streamUrl;
            this.startTimeEpochMs = startTimeEpochMs;
            this.isIsl = isIsl;
            this.isCompleted = false;
        }

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", id);
                obj.put("title", title);
                obj.put("league", league);
                obj.put("streamUrl", streamUrl != null ? streamUrl : "");
                obj.put("startTime", startTimeEpochMs);
                obj.put("isIsl", isIsl);
                obj.put("isCompleted", isCompleted);
            } catch (Exception ignored) {}
            return obj;
        }

        public static QueuedMatch fromJson(JSONObject obj) {
            if (obj == null) return null;
            QueuedMatch q = new QueuedMatch(
                    obj.optString("id", ""),
                    obj.optString("title", ""),
                    obj.optString("league", "Football"),
                    obj.optString("streamUrl", ""),
                    obj.optLong("startTime", System.currentTimeMillis()),
                    obj.optBoolean("isIsl", false)
            );
            q.isCompleted = obj.optBoolean("isCompleted", false);
            return q;
        }
    }

    public static synchronized SmartMatchDirector getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new SmartMatchDirector(context.getApplicationContext());
        }
        return sInstance;
    }

    private SmartMatchDirector(Context context) {
        this.appContext = context;
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        loadQueueFromDisk();
    }

    public void onLauncherResumed() {
        isLauncherForeground = true;
        Log.i(TAG, "Launcher is in foreground. Automated switching is paused to avoid interrupting user.");
    }

    public void onLauncherPaused() {
        isLauncherForeground = false;
        Log.i(TAG, "Launcher paused. TV is on HDMI (Tata Sky) or an active app.");
        startDirectorMonitoring();
    }

    public void recordLastHdmiInput(String inputId) {
        if (inputId != null && !inputId.isEmpty()) {
            prefs.edit().putString(KEY_LAST_HDMI, inputId).apply();
            Log.i(TAG, "Recorded active HDMI input for Tata Sky: " + inputId);
            // Cooldown: Uncle just switched to HDMI, don't immediately bounce him back
            userManualHdmiCooldownUntil = System.currentTimeMillis() + 180000; // 3 min grace
        }
    }

    public String getLastHdmiInput() {
        return prefs.getString(KEY_LAST_HDMI, "");
    }

    public synchronized void enqueueMatch(QueuedMatch match) {
        if (match == null) return;
        for (QueuedMatch existing : matchQueue) {
            if (existing.id.equals(match.id)) return;
        }
        matchQueue.add(match);
        saveQueueToDisk();
        Log.i(TAG, "Enqueued match: " + match.title + " (" + match.league + ")");
    }

    public synchronized List<QueuedMatch> getQueuedMatches() {
        return Collections.unmodifiableList(new ArrayList<>(matchQueue));
    }

    public synchronized void advanceQueue() {
        if (!matchQueue.isEmpty()) {
            QueuedMatch finished = matchQueue.remove(0);
            Log.i(TAG, "Finished match: " + finished.title + ". Advancing queue.");
            saveQueueToDisk();
        }
    }

    public synchronized void startDirectorMonitoring() {
        if (isMonitoringActive) return;
        isMonitoringActive = true;
        Log.i(TAG, "Starting SmartMatchDirector monitoring loop (60s gentle interval)...");
        directorHandler.post(checkRunnable);
    }

    private final Runnable checkRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                evaluateAndDirect();
            } catch (Exception e) {
                Log.e(TAG, "Director evaluation error: " + e.getMessage(), e);
            } finally {
                // Gentle interval: 60 seconds (not a forceful continuous loop)
                directorHandler.postDelayed(this, 60000);
            }
        }
    };

    /**
     * Core decision engine:
     * - Only switches if Uncle is on HDMI (Tata Sky) and NOT on the custom launcher.
     * - Checks ISL priority first.
     * - Checks queued matches next.
     */
    private void evaluateAndDirect() {
        // Condition 1: If user is on our custom launcher, NEVER switch or steal focus
        if (isLauncherForeground) {
            Log.d(TAG, "User on custom launcher. Standing down.");
            return;
        }

        // Condition 2: Check manual switch grace cooldown
        if (System.currentTimeMillis() < userManualHdmiCooldownUntil) {
            Log.d(TAG, "Within user HDMI switch cooldown window. Standing down.");
            return;
        }

        long now = System.currentTimeMillis();
        String lastStreamed = prefs.getString(KEY_LAST_STREAMED_MATCH, "");

        // --- CHECK PRIORITY 1: ISL (Indian Super League) ---
        IslLiveScoreService.LiveMatchInfo islInfo = IslLiveScoreService.getLiveMatchInfo();
        boolean isIslMatchDay = false;
        boolean isIslMatchLive = false;
        long islKickoffTime = 0;

        if (islInfo != null) {
            isIslMatchLive = islInfo.isLive;
            // If live info reports live match, ISL is currently on
            if (isIslMatchLive) {
                isIslMatchDay = true;
            }
        }

        // Check if current time falls into typical ISL slot (evening matches ~19:30 IST)
        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);
        int timeInMinutes = hour * 60 + minute;
        // Typical ISL match slot: 19:15 IST (1155m) to 21:45 IST (1305m)
        boolean isIslTimeSlot = (timeInMinutes >= 1155 && timeInMinutes <= 1305);

        if (isIslMatchLive || (isIslMatchDay && isIslTimeSlot)) {
            Log.i(TAG, "ISL Match Slot active. Priority 1 engaged. Sportzfy is suppressed.");
            String matchId = "isl_live_" + cal.get(Calendar.DAY_OF_YEAR);
            if (!matchId.equals(lastStreamed)) {
                // Trigger automated ISL stream directly in full screen
                prefs.edit().putString(KEY_LAST_STREAMED_MATCH, matchId).apply();
                launchIslStreamForUncle();
            }
            return; // Exit: do nothing with Sportzfy during ISL slot!
        }

        // --- CHECK PRIORITY 2: Queued Football Matches (Outside ISL slot) ---
        synchronized (this) {
            if (matchQueue.isEmpty()) return;

            QueuedMatch currentMatch = matchQueue.get(0);
            if (currentMatch == null) return;

            // Check if current match has concluded (e.g. 120 mins elapsed since start)
            long matchDurationMs = 120 * 60 * 1000L;
            if (currentMatch.startTimeEpochMs > 0 && (now - currentMatch.startTimeEpochMs > matchDurationMs)) {
                Log.i(TAG, "Match " + currentMatch.title + " has reached full time. Moving to next match in queue.");
                advanceQueue();
                if (!matchQueue.isEmpty()) {
                    currentMatch = matchQueue.get(0);
                } else {
                    return;
                }
            }

            // Check if current queued match is active/ready to stream
            boolean isMatchTime = (currentMatch.startTimeEpochMs <= 0) || (now >= currentMatch.startTimeEpochMs - 300000); // 5 min pre-kickoff
            if (isMatchTime && !currentMatch.id.equals(lastStreamed)) {
                prefs.edit().putString(KEY_LAST_STREAMED_MATCH, currentMatch.id).apply();
                launchQueuedFootballForUncle(currentMatch);
            }
        }
    }

    private void launchIslStreamForUncle() {
        Log.i(TAG, "Auto-switching from Tata Sky to ISL Live Stream for Uncle...");
        try {
            IslStreamResolver.resolveEnglishStream(null, streamUrl -> {
                if (streamUrl != null && !streamUrl.trim().isEmpty()) {
                    Intent streamIntent;
                    String ytPkg = null;
                    if (isPackageInstalled("com.amazon.firetv.youtube")) ytPkg = "com.amazon.firetv.youtube";
                    else if (isPackageInstalled("com.google.android.youtube.tv")) ytPkg = "com.google.android.youtube.tv";

                    if (ytPkg != null) {
                        streamIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(streamUrl))
                                .setPackage(ytPkg)
                                .putExtra("force_fullscreen", true)
                                .putExtra("finish_on_ended", true)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    } else {
                        streamIntent = new Intent(appContext, WebStreamActivity.class)
                                .putExtra(WebStreamActivity.EXTRA_URL, streamUrl)
                                .putExtra(WebStreamActivity.EXTRA_TITLE, "ISL Live Stream (English HQ)")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    }
                    appContext.startActivity(streamIntent);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch automated ISL stream: " + e.getMessage(), e);
        }
    }

    private void launchQueuedFootballForUncle(QueuedMatch match) {
        Log.i(TAG, "Auto-switching from Tata Sky to queued football match: " + match.title);
        try {
            if (match.streamUrl != null && !match.streamUrl.trim().isEmpty()) {
                // Direct high-quality web stream
                Intent webIntent = new Intent(appContext, WebStreamActivity.class)
                        .putExtra(WebStreamActivity.EXTRA_URL, match.streamUrl)
                        .putExtra(WebStreamActivity.EXTRA_TITLE, match.title)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(webIntent);
            } else if (isPackageInstalled("com.blaze.sportzfy")) {
                // Engage accessibility auto-pilot so Uncle doesn't have to touch anything
                HomeKeyService.startSportzfyAutoPilot();

                Intent sportzfy = appContext.getPackageManager().getLaunchIntentForPackage("com.blaze.sportzfy");
                if (sportzfy != null) {
                    sportzfy.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    appContext.startActivity(sportzfy);
                }
            } else {
                // Fallback: Westream live football hub
                Intent fallback = new Intent(appContext, WebStreamActivity.class)
                        .putExtra(WebStreamActivity.EXTRA_URL, "https://westreamf1.st/stream")
                        .putExtra(WebStreamActivity.EXTRA_TITLE, match.title)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(fallback);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch queued football match: " + e.getMessage(), e);
        }
    }

    public void returnToTataSky() {
        String lastHdmi = getLastHdmiInput();
        if (lastHdmi != null && !lastHdmi.isEmpty()) {
            try {
                Uri uri = TvContract.buildChannelUriForPassthroughInput(lastHdmi);
                Intent view = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "vnd.android.cursor.item/channel")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(view);
                Log.i(TAG, "Returned to Tata Sky on " + lastHdmi);
            } catch (Exception e) {
                Log.w(TAG, "Could not return to Tata Sky: " + e.getMessage());
            }
        }
    }

    private boolean isPackageInstalled(String pkg) {
        try {
            appContext.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void saveQueueToDisk() {
        try {
            JSONArray arr = new JSONArray();
            synchronized (this) {
                for (QueuedMatch m : matchQueue) {
                    arr.put(m.toJson());
                }
            }
            prefs.edit().putString(KEY_MATCH_QUEUE, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void loadQueueFromDisk() {
        try {
            String jsonStr = prefs.getString(KEY_MATCH_QUEUE, "[]");
            JSONArray arr = new JSONArray(jsonStr);
            synchronized (this) {
                matchQueue.clear();
                for (int i = 0; i < arr.length(); i++) {
                    QueuedMatch m = QueuedMatch.fromJson(arr.optJSONObject(i));
                    if (m != null) matchQueue.add(m);
                }
            }
        } catch (Exception ignored) {}
    }
}
