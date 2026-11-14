package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SmartMatchDirector (Uncle's Sony Bravia + Fire TV Stick Edition):
 *
 * Orchestrates 100% hands-free football streaming strictly in user priority order:
 *   1. ISL teams
 *   2. Indian National Football Team (e.g. SAFF Championship, Friendlies)
 *   3. Other live Football matches (continuous queue: next match starts when current finishes)
 *
 * Scoped ONLY to Uncle's Fire TV Stick setup (AFT* / Amazon), leaving Mi TV untouched.
 * Tracks external TV HDMI state via sticky HDMI_PLUGGED broadcasts so we:
 *  - Switch TV from Tata Sky -> Fire Stick ONLY when NOT already viewing Fire Stick Home,
 *  - Respect manual switching back to Tata Sky for the active match,
 *  - Auto-navigate Sportzfy Football + clear countdown ads / Support prompts via SportzfyAutoPilot.
 */
public class SmartMatchDirector implements SportzfyAutoPilot.Listener {

    private static final String TAG = "SmartMatchDirector";
    private static final long CHECK_INTERVAL_MS = 45_000L;
    private static final long RETRY_FAILED_PRE_MS = 150_000L;  // 2.5 min retry if 0 links right around kickoff
    private static final long RETRY_FAILED_LIVE_MS = 480_000L; // 8 min skip if all servers failed
    private static final long USER_DISMISS_MATCH_MS = 60L * 60_000L; // 60 min respect user switch back to Tata Sky
    private static final long HOME_SETTLE_DELAY_MS = 1_800L;

    private static SmartMatchDirector sInstance;

    private final Context appContext;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SportzfyAutoPilot autoPilot = new SportzfyAutoPilot();

    private boolean isMonitoring = false;
    private boolean receiverRegistered = false;
    private volatile boolean isLauncherForeground = false;
    private volatile boolean isHdmiActive = true;
    private boolean pendingFailureFallbackReturn = false;

    private HomeKeyService attachedService;

    // Currently active / queued stream tracking
    private String activeMatchKey = null;
    private int activeMatchTier = Integer.MAX_VALUE;
    private long activeLaunchTimeMs = 0L;
    private String activePlatform = ""; // "web" or "sportzfy"

    // Respect Uncle switching back to Tata Sky or pressing Back to Home
    private String userDismissedMatchKey = null;
    private int userDismissedMatchTier = Integer.MAX_VALUE;
    private long userDismissedUntilMs = 0L;

    // Cooldown map for matches whose streams weren't up or failed temporarily
    private final Map<String, Long> matchRetryCooldownUntil = new HashMap<>();

    private final BroadcastReceiver hdmiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if ("android.intent.action.HDMI_PLUGGED".equals(intent.getAction())) {
                boolean plugged = intent.getBooleanExtra("state", true);
                handleHdmiPluggedState(plugged);
            }
        }
    };

    private final Runnable checkLoopRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isMonitoring) return;
            evaluateAndDirect();
            handler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    public static synchronized SmartMatchDirector getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new SmartMatchDirector(context.getApplicationContext());
        }
        return sInstance;
    }

    private SmartMatchDirector(Context context) {
        this.appContext = context.getApplicationContext();
        registerHdmiReceiver();
    }

    /**
     * Scopes automated HDMI switching & Sportzfy auto-pilot strictly to Uncle's Fire TV Stick.
     */
    public static boolean isUncleTvDevice() {
        String mfg = Build.MANUFACTURER != null ? Build.MANUFACTURER : "";
        String model = Build.MODEL != null ? Build.MODEL : "";
        return "Amazon".equalsIgnoreCase(mfg) || model.toUpperCase(Locale.ROOT).startsWith("AFT");
    }

    public SportzfyAutoPilot getSportzfyAutoPilot() {
        return autoPilot;
    }

    public boolean isHdmiActive() {
        return isHdmiActive;
    }

    public void attachAccessibilityService(HomeKeyService service) {
        if (service == null) return;
        this.attachedService = service;
        this.autoPilot.attach(service, this);
        registerHdmiReceiver();
        if (isUncleTvDevice() && !isMonitoring) {
            startMonitoring();
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private synchronized void registerHdmiReceiver() {
        if (receiverRegistered) return;
        try {
            IntentFilter filter = new IntentFilter("android.intent.action.HDMI_PLUGGED");
            Intent sticky = appContext.registerReceiver(hdmiReceiver, filter);
            if (sticky != null) {
                isHdmiActive = sticky.getBooleanExtra("state", true);
            }
            receiverRegistered = true;
            Log.i(TAG, "HDMI_PLUGGED monitor active (initial isHdmiActive=" + isHdmiActive + ")");
        } catch (Exception e) {
            Log.w(TAG, "Could not register HDMI_PLUGGED receiver: " + e.getMessage());
        }
    }

    private void handleHdmiPluggedState(boolean plugged) {
        boolean wasActive = isHdmiActive;
        isHdmiActive = plugged;
        Log.i(TAG, "HDMI_PLUGGED changed: " + wasActive + " -> " + plugged);

        if (wasActive && !plugged && activeMatchKey != null) {
            // TV switched away to Tata Sky while we had an active auto-directed match -> stop fighting Uncle!
            long elapsedSinceLaunch = System.currentTimeMillis() - activeLaunchTimeMs;
            if (elapsedSinceLaunch < 3 * 60 * 60_000L) {
                userDismissedMatchKey = activeMatchKey;
                userDismissedMatchTier = activeMatchTier;
                userDismissedUntilMs = System.currentTimeMillis() + USER_DISMISS_MATCH_MS;
                autoPilot.stopNavigation();
                activeMatchKey = null;
                activeMatchTier = Integer.MAX_VALUE;
                activePlatform = "";
                Log.i(TAG, "Uncle switched TV away -> respecting Tata Sky until new/higher match for key=" + userDismissedMatchKey);
            }
        }
    }

    public void onLauncherResumed() {
        isLauncherForeground = true;
        if (!isUncleTvDevice()) return;

        // If user backed straight out of an auto-launched stream back into our launcher, snooze it
        if (activeMatchKey != null && "sportzfy".equals(activePlatform) && !autoPilot.isNavigating()) {
            String curPkg = HomeKeyService.sCurrentPackage;
            if ("com.gathin.tvhome".equals(curPkg) && (System.currentTimeMillis() - activeLaunchTimeMs) > 6000L) {
                userDismissedMatchKey = activeMatchKey;
                userDismissedMatchTier = activeMatchTier;
                userDismissedUntilMs = System.currentTimeMillis() + 15 * 60_000L;
                activeMatchKey = null;
                activeMatchTier = Integer.MAX_VALUE;
                activePlatform = "";
            }
        }

        if (!isMonitoring) {
            startMonitoring();
        }
    }

    public void onLauncherPaused() {
        isLauncherForeground = false;
    }

    public void recordLastHdmiInput(String inputId) {
        if (inputId != null && !inputId.isEmpty()) {
            Log.d(TAG, "Recorded passthrough HDMI input hint: " + inputId);
        }
    }

    public void startMonitoring() {
        if (isMonitoring || !isUncleTvDevice()) return;
        isMonitoring = true;
        Log.i(TAG, "Starting SmartMatchDirector queue loop (" + (CHECK_INTERVAL_MS / 1000) + "s)");
        handler.removeCallbacks(checkLoopRunnable);
        handler.postDelayed(checkLoopRunnable, 5_000L);
    }

    public void evaluateAndDirect() {
        if (!isUncleTvDevice()) return;
        if (autoPilot.isNavigating()) {
            Log.d(TAG, "SportzfyAutoPilot currently navigating; skipping overlapping check");
            return;
        }

        // Refresh ISL summary cache + evaluate priority football queue live from ESPN APIs
        IslLiveScoreService.fetchLiveScore(info -> {});
        FootballScheduleService.fetchPriorityQueueAsync(this::processFootballQueue);
    }

    private void processFootballQueue(List<FootballScheduleService.FootballMatch> queue) {
        if (queue == null || queue.isEmpty()) {
            Log.i(TAG, "Queue evaluated: 0 matches returned");
            return;
        }

        long now = System.currentTimeMillis();
        FootballScheduleService.FootballMatch nextUpcoming = null;
        for (FootballScheduleService.FootballMatch m : queue) {
            if (!m.isFinished()) {
                nextUpcoming = m;
                break;
            }
        }
        if (nextUpcoming != null) {
            long minsToGo = Math.round((nextUpcoming.startTimeMs - now) / 60_000.0);
            Log.i(TAG, "Queue evaluated: total=" + queue.size()
                    + " top=T" + nextUpcoming.priorityTier + " [" + nextUpcoming.title
                    + "] state=" + nextUpcoming.state + " deltaMin=" + minsToGo
                    + " isHdmiActive=" + isHdmiActive);
        }

        // Check if currently tracked match explicitly ended ("post") or duration elapsed -> clear it so queue advances!
        if (activeMatchKey != null) {
            FootballScheduleService.FootballMatch activeState = findMatchByKey(queue, activeMatchKey);
            boolean finished = isCurrentMatchOver(now, activeState);
            if (finished) {
                Log.i(TAG, "Active match finished: " + activeMatchKey + " -> Advancing queue to next match");
                matchRetryCooldownUntil.put(activeMatchKey, now + 6 * 3600_000L);
                activeMatchKey = null;
                activeMatchTier = Integer.MAX_VALUE;
                activePlatform = "";
            }
        }

        // Select best eligible live/kickoff-window match obeying Priority 1 > 2 > 3
        FootballScheduleService.FootballMatch candidate = pickBestCandidate(queue, now);
        if (candidate == null) {
            if (pendingFailureFallbackReturn) {
                pendingFailureFallbackReturn = false;
                returnToLauncherIfStrandedInSportzfy();
            }
            return;
        }
        pendingFailureFallbackReturn = false;

        String candidateKey = candidate.queueKey();

        // Already playing this match smoothly
        if (candidateKey.equals(activeMatchKey)) {
            return;
        }

        // Do not preempt an ongoing match!
        // "don't do tht when a match is playing let it get over and then only switch to the next football match!"
        if (activeMatchKey != null) {
            // If the active match is ISL (Tier 1) or Indian Football Team (Tier 2), NOTHING can preempt it!
            if (activeMatchTier <= FootballScheduleService.TIER_INDIA) {
                Log.d(TAG, "Active Priority " + activeMatchTier + " match is playing (" + activeMatchKey + "). Holding stream until match is over.");
                return;
            }
            // If the active match is Tier 3 (other football), only a strictly higher tier (Tier 1 ISL or Tier 2 India)
            // can preempt it; never switch to another Tier 3 match while one is playing!
            if (candidate.priorityTier >= activeMatchTier) {
                Log.d(TAG, "Current match playing (" + activeMatchKey + "). Letting it finish before next T" + candidate.priorityTier + " match.");
                return;
            }
        }

        // Check if Uncle explicitly switched away from this exact match back to Tata Sky
        if (candidateKey.equals(userDismissedMatchKey) && now < userDismissedUntilMs) {
            if (candidate.priorityTier >= userDismissedMatchTier) {
                return;
            }
        }

        Log.i(TAG, "Directing Priority " + candidate.priorityTier + " match: " + candidate.title
                + " (state=" + candidate.state + ", isHdmiActive=" + isHdmiActive + ")");
        launchPriorityMatch(candidate);
    }

    private FootballScheduleService.FootballMatch pickBestCandidate(
            List<FootballScheduleService.FootballMatch> queue, long now) {
        for (FootballScheduleService.FootballMatch m : queue) {
            if (m.isFinished()) continue;

            // Must be within 7 minutes before kickoff or actively live
            if (!m.isEligibleToStream(now)) continue;

            Long cd = matchRetryCooldownUntil.get(m.queueKey());
            if (cd != null && now < cd) continue;

            // If this is a Tier 3 ("other football teams") match, ensure a higher priority Tier 1 (ISL)
            // or Tier 2 (Indian football team) match is not active or scheduled to start within the next 45 minutes!
            if (m.priorityTier >= FootballScheduleService.TIER_OTHER_FOOTBALL) {
                if (isHigherPriorityMatchActiveOrUpcoming(queue, now, m.priorityTier)) {
                    Log.d(TAG, "Holding queue: Higher priority match (ISL/India) active or kicking off soon. Suppressing T3: " + m.title);
                    continue;
                }
            }

            return m;
        }
        return null;
    }

    private boolean isHigherPriorityMatchActiveOrUpcoming(
            List<FootballScheduleService.FootballMatch> queue, long now, int currentTier) {
        for (FootballScheduleService.FootballMatch m : queue) {
            if (m.priorityTier < currentTier && !m.isFinished()) {
                if (m.isExplicitlyLive() || m.isEligibleToStream(now)) {
                    return true;
                }
                if (m.startTimeMs > 0) {
                    long delta = m.startTimeMs - now;
                    if (delta > 0 && delta <= 45 * 60_000L) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isCurrentMatchOver(long now, FootballScheduleService.FootballMatch activeState) {
        if (activeMatchKey == null) return true;
        // 1. Confirmed completed by ESPN scoreboard
        if (activeState != null && activeState.isFinished()) {
            return true;
        }
        // 2. Full match duration elapsed (115 minutes from launch / kickoff)
        long elapsedSinceLaunch = now - activeLaunchTimeMs;
        long durationMs = FootballScheduleService.FootballMatch.MATCH_DURATION_MS; // 115 mins
        if (elapsedSinceLaunch >= durationMs) {
            if (activeState == null || activeState.startTimeMs <= 0 || now >= (activeState.startTimeMs + durationMs)) {
                return true;
            }
        }
        return false;
    }

    private FootballScheduleService.FootballMatch findMatchByKey(
            List<FootballScheduleService.FootballMatch> queue, String key) {
        for (FootballScheduleService.FootballMatch m : queue) {
            if (key.equals(m.queueKey())) return m;
        }
        return null;
    }

    private void launchPriorityMatch(FootballScheduleService.FootballMatch match) {
        activeMatchKey = match.queueKey();
        activeMatchTier = match.priorityTier;
        activeLaunchTimeMs = System.currentTimeMillis();

        // 7 minutes before match starts: switch TV input from Tata Sky to Fire Stick and immediately open stream
        Log.i(TAG, "Directing 7-minute pre-match stream for: " + match.title + " (T" + match.priorityTier + ")");
        switchTvInputToFireStick();

        // Delay 1200ms for HDMI CEC input switch to settle, then IMMEDIATELY open Sportzfy/stream
        handler.postDelayed(() -> dispatchMatchStream(match), 1200L);
    }

    @SuppressWarnings("deprecation")
    private void wakeFireStickScreen() {
        try {
            PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                PowerManager.WakeLock wl = pm.newWakeLock(
                        PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE,
                        "TvHome:MatchWakeLock");
                wl.acquire(5_000L);
            }
        } catch (Exception e) {
            Log.w(TAG, "WakeLock failed: " + e.getMessage());
        }
    }

    private void switchTvInputToFireStick() {
        Log.i(TAG, "Asserting HDMI 1 Active Source (switching TV from Tata Sky -> Fire Stick)");
        wakeFireStickScreen();
        MatchAutoWakeReceiver.assertHdmiActiveSourceSilent(appContext);
    }

    private void dispatchMatchStream(FootballScheduleService.FootballMatch match) {
        // Priority 1 (ISL): Prefer zero-ad official English @IndianSuperLeague YouTube stream in WebStreamActivity;
        // if not live on YouTube, seamlessly fall back to Sportzfy Auto-Pilot!
        if (match.priorityTier == FootballScheduleService.TIER_ISL) {
            IslStreamResolver.checkLiveStreamStatus(match.title, (isLive, streamUrl) -> {
                if (isLive && streamUrl != null && !streamUrl.contains("/results?")) {
                    openWebStreamPlayer(match, streamUrl);
                } else {
                    Log.i(TAG, "Official YouTube ISL stream not live; trying Sportzfy for " + match.title);
                    openSportzfyWithAutoPilot(match);
                }
            });
            return;
        }

        // Priority 2 (Indian Football Team) & Priority 3 (Other Football):
        // Use Sportzfy Auto-Pilot (verified Football cards + pre-stream ad/X closer + multi-server player failover).
        openSportzfyWithAutoPilot(match);
    }

    private void openWebStreamPlayer(FootballScheduleService.FootballMatch match, String url) {
        activePlatform = "web";
        activeLaunchTimeMs = System.currentTimeMillis();
        Intent webIntent = new Intent(appContext, WebStreamActivity.class);
        webIntent.putExtra(WebStreamActivity.EXTRA_URL, url);
        webIntent.putExtra(WebStreamActivity.EXTRA_TITLE, match.title);
        webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        appContext.startActivity(webIntent);
        Log.i(TAG, "Launched WebStreamActivity for: " + match.title);
    }

    private void openSportzfyWithAutoPilot(FootballScheduleService.FootballMatch match) {
        if (!isPackageInstalled(SportzfyAutoPilot.PKG_SPORTZFY)) {
            Log.w(TAG, "Sportzfy not installed on device");
            matchRetryCooldownUntil.put(match.queueKey(), System.currentTimeMillis() + RETRY_FAILED_LIVE_MS);
            activeMatchKey = null;
            return;
        }

        activePlatform = "sportzfy";
        activeLaunchTimeMs = System.currentTimeMillis();
        autoPilot.startNavigation(match.homeTeam, match.awayTeam, match.title);

        Intent sportzfy = appContext.getPackageManager().getLaunchIntentForPackage(SportzfyAutoPilot.PKG_SPORTZFY);
        if (sportzfy != null) {
            sportzfy.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            appContext.startActivity(sportzfy);
            Log.i(TAG, "Launched Sportzfy Auto-Pilot for: " + match.title);
        } else {
            autoPilot.stopNavigation();
            activeMatchKey = null;
        }
    }

    @Override
    public void onSportzfyPlaying(String matchedCardTitle) {
        activeLaunchTimeMs = System.currentTimeMillis();
        Log.i(TAG, "Sportzfy Auto-Pilot confirmed playback: " + matchedCardTitle);
    }

    @Override
    public void onSportzfyFailed(String reason) {
        Log.w(TAG, "Sportzfy Auto-Pilot failed (" + reason + ") for activeMatch=" + activeMatchKey);
        if (activeMatchKey != null) {
            long delay;
            if (activeMatchTier <= FootballScheduleService.TIER_INDIA) {
                // Priority 1 (ISL) and Priority 2 (India): retry quickly (45s) instead of long cooldown!
                delay = 45_000L;
            } else {
                delay = reason != null && reason.startsWith("no_links_available")
                        ? RETRY_FAILED_PRE_MS : RETRY_FAILED_LIVE_MS;
            }
            matchRetryCooldownUntil.put(activeMatchKey, System.currentTimeMillis() + delay);
            activeMatchKey = null;
            activeMatchTier = Integer.MAX_VALUE;
            activePlatform = "";
        }
        // Immediately check next queued football match so screen never stays dead!
        pendingFailureFallbackReturn = true;
        handler.postDelayed(this::evaluateAndDirect, 1_500L);
    }

    private void returnToLauncherIfStrandedInSportzfy() {
        if (!SportzfyAutoPilot.PKG_SPORTZFY.equals(HomeKeyService.sCurrentPackage) || autoPilot.isNavigating()) {
            return;
        }
        // Back out of Sportzfy PlayerActivity first so Android doesn't shrink PlayerActivity into PiP
        if (attachedService != null) {
            attachedService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        }
        handler.postDelayed(() -> {
            try {
                Intent home = new Intent(appContext, MainActivity.class);
                home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                appContext.startActivity(home);
                Log.i(TAG, "Returned to custom launcher cleanly (no fallback Sportzfy stream available)");
            } catch (Exception e) {
                Log.w(TAG, "Failed returning to MainActivity: " + e.getMessage());
            }
        }, 450L);
    }

    private boolean isPackageInstalled(String pkg) {
        try {
            appContext.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
