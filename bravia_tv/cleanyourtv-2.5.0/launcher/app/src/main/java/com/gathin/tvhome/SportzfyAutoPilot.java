package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Automates Sportzfy (v32+) TV UI + pre-stream ad countdowns + player failover via
 * AccessibilityService so Uncle never needs to touch the Fire Stick remote.
 *
 * Verified Sportzfy v32 view IDs (AFTSSS / 1920x1080):
 *  - MainActivity:
 *      com.blaze.sportzfy:id/tv_nav_home
 *      com.blaze.sportzfy:id/event_type_rv  (index 2 = Football)
 *      com.blaze.sportzfy:id/chipLive / com.blaze.sportzfy:id/chipAll
 *      com.blaze.sportzfy:id/recycler_view -> event_card (event_details, teamAName, teamBName, liveText)
 *      com.blaze.sportzfy:id/refresh
 *  - SupportActivity ("Support the App" 12h ad gate):
 *      com.blaze.sportzfy:id/btnClickHere ("WATCH AD NOW")
 *  - CustomBrowserActivity (12s countdown ad overlay):
 *      com.blaze.sportzfy:id/browser_closing_pill ("Closing in 11s")
 *      com.blaze.sportzfy:id/browser_close (X close button)
 *  - PlayerActivity:
 *      com.blaze.sportzfy:id/player_view
 *      com.blaze.sportzfy:id/link_recycler_view -> player_links_layout_btn (SP - 1, SP - 2, ...)
 *      com.blaze.sportzfy:id/progress, tvStatus, exo_error_message, exo_play_pause, title_text
 */
public final class SportzfyAutoPilot {
    private static final String TAG = "SportzfyAutoPilot";
    public static final String PKG_SPORTZFY = "com.blaze.sportzfy";
    private static final String ID_PREFIX = PKG_SPORTZFY + ":id/";

    private static final long TICK_INTERVAL_MS = 950L;
    private static final long NAVIGATE_TIMEOUT_MS = 120_000L;
    private static final long AD_WAIT_MAX_MS = 24_000L;
    private static final long PLAYER_STUCK_TIMEOUT_MS = 14_000L;

    private static final Pattern COUNTDOWN_DIGIT = Pattern.compile("\\b[1-9]\\d*\\s*s?\\b");

    public interface Listener {
        void onSportzfyPlaying(String matchedCardTitle);
        void onSportzfyFailed(String reason);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AccessibilityService service;
    private Listener listener;

    private volatile boolean navigating = false;
    private volatile boolean browseFootballOnly = false;
    private volatile long navigateDeadlineMs = 0L;
    private String targetHome = "";
    private String targetAway = "";
    private String targetLabel = "";
    private final Set<String> rejectedCardKeys = new HashSet<>();

    private long lastActionAtMs = 0L;
    private long mainListFirstSeenAtMs = 0L;
    private long customBrowserSeenAtMs = 0L;
    private boolean sawClosingPillCounting = false;

    private long playerEnteredAtMs = 0L;
    private long lastLinkSwitchAtMs = 0L;
    private int currentLinkIndex = 0;
    private int scrollAttempts = 0;
    private boolean refreshedOnce = false;
    private boolean reportedPlaying = false;
    private String lastClickedCardTitle = "";

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            AccessibilityService svc = service;
            if (svc == null) return;

            String pkg = HomeKeyService.sCurrentPackage;
            if (PKG_SPORTZFY.equals(pkg)) {
                try {
                    step(svc);
                } catch (Throwable t) {
                    Log.w(TAG, "Tick error: " + t.getMessage());
                }
                if (navigating && System.currentTimeMillis() < navigateDeadlineMs) {
                    handler.postDelayed(this, TICK_INTERVAL_MS);
                } else if (navigating) {
                    navigating = false;
                    notifyFailure("timeout_waiting_for_playback");
                }
            } else if (navigating && System.currentTimeMillis() < navigateDeadlineMs) {
                // Sportzfy still launching or transient window transition
                handler.postDelayed(this, TICK_INTERVAL_MS);
            } else {
                navigating = false;
            }
        }
    };

    public void attach(AccessibilityService service, Listener listener) {
        this.service = service;
        this.listener = listener;
    }

    public void detach() {
        handler.removeCallbacks(tickRunnable);
        this.service = null;
    }

    public synchronized void startNavigation(String homeTeam, String awayTeam, String fullTitle) {
        this.targetHome = homeTeam != null ? homeTeam.trim() : "";
        this.targetAway = awayTeam != null ? awayTeam.trim() : "";
        this.targetLabel = fullTitle != null ? fullTitle.trim() : "";
        this.browseFootballOnly = false;
        this.navigating = true;
        this.navigateDeadlineMs = System.currentTimeMillis() + NAVIGATE_TIMEOUT_MS;
        this.lastActionAtMs = 0L;
        this.mainListFirstSeenAtMs = 0L;
        this.customBrowserSeenAtMs = 0L;
        this.sawClosingPillCounting = false;
        this.playerEnteredAtMs = 0L;
        this.lastLinkSwitchAtMs = 0L;
        this.currentLinkIndex = 0;
        this.scrollAttempts = 0;
        this.refreshedOnce = false;
        this.reportedPlaying = false;
        this.lastClickedCardTitle = "";

        handler.removeCallbacks(tickRunnable);
        handler.postDelayed(tickRunnable, 800L);
        Log.i(TAG, "Auto-Pilot started for: " + this.targetLabel);
    }

    /**
     * Opens Sportzfy directly to the Football section ("All" filter showing Live & Upcoming games)
     * without auto-clicking a specific card or polling playback.
     */
    public synchronized void startBrowseFootball() {
        this.targetHome = "";
        this.targetAway = "";
        this.targetLabel = "BROWSE_FOOTBALL";
        this.browseFootballOnly = true;
        this.navigating = true;
        this.navigateDeadlineMs = System.currentTimeMillis() + 60_000L;
        this.lastActionAtMs = 0L;
        this.mainListFirstSeenAtMs = 0L;
        this.customBrowserSeenAtMs = 0L;
        this.sawClosingPillCounting = false;
        this.playerEnteredAtMs = 0L;
        this.lastLinkSwitchAtMs = 0L;
        this.currentLinkIndex = 0;
        this.scrollAttempts = 0;
        this.refreshedOnce = false;
        this.reportedPlaying = false;
        this.lastClickedCardTitle = "";

        handler.removeCallbacks(tickRunnable);
        handler.postDelayed(tickRunnable, 800L);
        Log.i(TAG, "Auto-Pilot started in browseFootballOnly mode (Football -> Live & Upcoming)");
    }

    public synchronized void stopNavigation() {
        navigating = false;
        browseFootballOnly = false;
        handler.removeCallbacks(tickRunnable);
    }

    public synchronized void markCardRejected(String cardKey) {
        if (cardKey != null && !cardKey.isEmpty()) {
            rejectedCardKeys.add(cardKey.toLowerCase(Locale.ROOT));
        }
    }

    public synchronized void clearRejectedCards() {
        rejectedCardKeys.clear();
    }

    public boolean isNavigating() {
        return navigating && System.currentTimeMillis() < navigateDeadlineMs;
    }

    /**
     * Always safe to invoke on Accessibility events when Sportzfy is foreground:
     * closes pre-stream ads / SupportActivity even if Uncle/user opened Sportzfy manually,
     * and drives full Football match + server selection while {@link #navigating} is active.
     */
    public void onAccessibilityActivityOrContent(AccessibilityService svc, boolean isWindowStateChanged) {
        if (svc == null) return;
        this.service = svc;
        if (!SmartMatchDirector.isUncleTvDevice()) return;
        if (isWindowStateChanged || navigating) {
            handler.removeCallbacks(tickRunnable);
            handler.post(tickRunnable);
        }
    }

    private void step(AccessibilityService svc) {
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) return;
        try {
            long now = System.currentTimeMillis();

            // 1. Handle Sportzfy "Support the App" 12h ad prompt -> MUST click WATCH AD NOW
            if (handleSupportActivity(root, now)) return;

            // 2. Handle CustomBrowserActivity 12s countdown overlay -> wait timer, then click X
            if (handleCustomBrowserAd(root, now)) return;
            customBrowserSeenAtMs = 0L;
            sawClosingPillCounting = false;

            // 3. Handle PlayerActivity -> verify playback & switch dead server links
            if (findFirstById(root, "player_view") != null || findFirstById(root, "link_recycler_view") != null) {
                handlePlayerScreen(svc, root, now);
                return;
            }
            playerEnteredAtMs = 0L;

            // 4. Handle generic WebView / ad / interstitial popups (Skip / Close Ad / Later)
            if (handleGenericPopupOrAd(root, now)) return;

            // 5. Only select tabs/matches if Auto-Pilot was intentionally started for a target/queue
            if (!navigating) return;

            handleMainMatchList(root, now);
        } finally {
            root.recycle();
        }
    }

    private boolean handleSupportActivity(AccessibilityNodeInfo root, long now) {
        AccessibilityNodeInfo watchBtn = findFirstById(root, "btnClickHere");
        if (watchBtn == null) return false;
        if (now - lastActionAtMs < 3500L) return true;
        if (performClickOnNode(watchBtn)) {
            lastActionAtMs = now;
            Log.i(TAG, "SupportActivity detected -> clicked WATCH AD NOW");
        }
        return true;
    }

    private boolean handleCustomBrowserAd(AccessibilityNodeInfo root, long now) {
        AccessibilityNodeInfo closeBtn = findFirstById(root, "browser_close");
        AccessibilityNodeInfo toolbar = findFirstById(root, "browser_toolbar");
        if (closeBtn == null && toolbar == null) return false;

        if (customBrowserSeenAtMs == 0L) {
            customBrowserSeenAtMs = now;
            Log.i(TAG, "CustomBrowserActivity ad opened; monitoring closing countdown...");
        }

        AccessibilityNodeInfo pill = findFirstById(root, "browser_closing_pill");
        String pillText = pill != null && pill.isVisibleToUser() && pill.getText() != null
                ? pill.getText().toString().trim() : "";

        boolean stillCounting = !pillText.isEmpty()
                && !pillText.startsWith("0")
                && COUNTDOWN_DIGIT.matcher(pillText).find();

        if (stillCounting) {
            sawClosingPillCounting = true;
            // Extend deadline while dutifully waiting out ad timer
            navigateDeadlineMs = Math.max(navigateDeadlineMs, now + 45_000L);
            return true;
        }

        long elapsed = now - customBrowserSeenAtMs;
        boolean timerFinished = (sawClosingPillCounting && pillText.isEmpty())
                || elapsed >= 13_500L
                || (elapsed >= 4_500L && pillText.isEmpty());

        if ((timerFinished || elapsed >= AD_WAIT_MAX_MS) && closeBtn != null && now - lastActionAtMs >= 2000L) {
            if (performClickOnNode(closeBtn)) {
                lastActionAtMs = now;
                Log.i(TAG, "CustomBrowser countdown complete (" + elapsed + "ms) -> clicked browser_close (X)");
            }
        }
        return true;
    }

    private boolean handleGenericPopupOrAd(AccessibilityNodeInfo root, long now) {
        // Only dismiss popup overlays when MainActivity's sport filter bar isn't the screen we want
        boolean onMain = findFirstById(root, "event_type_rv") != null;
        if (onMain) {
            // Dismiss minor dialogs only if an explicit Later/No thanks/Skip button is present
            if (clickExactText(root, now, "LATER", "Later", "NO THANKS", "No thanks", "Not now", "Skip", "SKIP")) {
                return true;
            }
            return false;
        }

        // If on WebActivity or unknown ad overlay inside Sportzfy: look for skip/close controls
        for (String id : new String[]{"exo_ad_skip", "btn_close", "close_btn", "iv_close", "skip_btn"}) {
            AccessibilityNodeInfo node = findFirstById(root, id);
            if (node != null && node.isVisibleToUser() && node.isEnabled() && now - lastActionAtMs >= 2000L) {
                if (performClickOnNode(node)) {
                    lastActionAtMs = now;
                    Log.i(TAG, "Clicked overlay ad close/skip id=" + id);
                    return true;
                }
            }
        }
        return clickExactText(root, now,
                "Skip Ad", "Skip Video", "SKIP AD", "Close Ad", "LATER", "No thanks",
                "Close", "CLOSE", "X", "×", "✕");
    }

    private void handlePlayerScreen(AccessibilityService svc, AccessibilityNodeInfo root, long now) {
        if (playerEnteredAtMs == 0L) {
            playerEnteredAtMs = now;
            lastLinkSwitchAtMs = now;
            Log.i(TAG, "Entered Sportzfy PlayerActivity");
        }

        // Handle Dish Channels drawer if accidental back revealed it -> stay focused on player unless empty
        AccessibilityNodeInfo titleNode = findFirstById(root, "title_text");
        String title = titleNode != null && titleNode.getText() != null
                ? titleNode.getText().toString().trim() : lastClickedCardTitle;

        AccessibilityNodeInfo errorNode = findFirstById(root, "exo_error_message");
        boolean hasExoError = errorNode != null && errorNode.isVisibleToUser()
                && errorNode.getText() != null && errorNode.getText().length() > 0;

        AccessibilityNodeInfo statusNode = findFirstById(root, "tvStatus");
        String statusText = statusNode != null && statusNode.isVisibleToUser() && statusNode.getText() != null
                ? statusNode.getText().toString().toLowerCase(Locale.ROOT) : "";
        boolean hasStatusError = statusText.contains("fail") || statusText.contains("error")
                || statusText.contains("offline") || statusText.contains("not available");
        boolean statusConnecting = statusText.contains("connecting") || statusText.contains("loading")
                || statusText.contains("wait");

        AccessibilityNodeInfo progressNode = findFirstById(root, "progress");
        AccessibilityNodeInfo exoBufferingNode = findFirstById(root, "exo_buffering");
        boolean buffering = (progressNode != null && progressNode.isVisibleToUser())
                || (exoBufferingNode != null && exoBufferingNode.isVisibleToUser())
                || statusConnecting;

        AccessibilityNodeInfo shutterNode = findFirstById(root, "exo_shutter");
        boolean shutterBlocking = shutterNode == null || shutterNode.isVisibleToUser();

        String posText = readNodeText(findFirstById(root, "exo_position"));
        boolean posAdvancing = !posText.isEmpty()
                && !"00:00".equals(posText)
                && !"0:00".equals(posText)
                && !"00:00:00".equals(posText);

        boolean audioActive = false;
        AudioManager am = (AudioManager) svc.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            audioActive = am.isMusicActive();
        }

        // Check paused state (only click Play if description literally says Play, never Pause!)
        AccessibilityNodeInfo playPause = findFirstById(root, "exo_play_pause");
        if (playPause != null && playPause.isVisibleToUser() && playPause.getContentDescription() != null) {
            String desc = playPause.getContentDescription().toString().toLowerCase(Locale.ROOT);
            if ("play".equals(desc) && !buffering && now - lastActionAtMs > 2500L) {
                performClickOnNode(playPause);
                lastActionAtMs = now;
                return;
            }
        }

        boolean mediaActive = audioActive || posAdvancing || !shutterBlocking;
        if (!hasExoError && !hasStatusError && !buffering && mediaActive && (now - playerEnteredAtMs >= 3000L)) {
            // Stream is alive and rendering frames/audio!
            if (navigating && !reportedPlaying) {
                reportedPlaying = true;
                navigating = false;
                Log.i(TAG, "Verified live Sportzfy playback: " + title
                        + " (audio=" + audioActive + ", pos=" + posText + ", shutter=" + shutterBlocking + ")");
                if (listener != null) listener.onSportzfyPlaying(title);
            }
            return;
        }

        // Check if links failed / stuck -> try next server chip in link_recycler_view
        AccessibilityNodeInfo linkRv = findFirstById(root, "link_recycler_view");
        int chipCount = linkRv != null ? linkRv.getChildCount() : 0;

        boolean timedOutWaitingPlay = (now - lastLinkSwitchAtMs) >= PLAYER_STUCK_TIMEOUT_MS;
        if (hasExoError || hasStatusError || timedOutWaitingPlay || (chipCount == 0 && (now - playerEnteredAtMs) >= 10_000L)) {
            if (chipCount == 0 && (now - playerEnteredAtMs) >= 10_000L) {
                // Pre-match card had no stream links yet
                navigating = false;
                Log.w(TAG, "PlayerActivity has 0 links / dead source for: " + title);
                notifyFailure("no_links_available:" + title);
                return;
            }
            int nextIdx = currentLinkIndex + 1;
            if (linkRv != null && nextIdx < chipCount && now - lastActionAtMs >= 2000L) {
                AccessibilityNodeInfo chip = linkRv.getChild(nextIdx);
                if (chip != null && performClickOnNode(chip)) {
                    currentLinkIndex = nextIdx;
                    lastLinkSwitchAtMs = now;
                    lastActionAtMs = now;
                    Log.i(TAG, "Switching Sportzfy server link chip -> index " + nextIdx);
                    return;
                }
            } else if (chipCount > 0 && nextIdx >= chipCount && (now - lastLinkSwitchAtMs) >= PLAYER_STUCK_TIMEOUT_MS) {
                navigating = false;
                notifyFailure("all_server_links_failed:" + title);
            }
        }
    }

    private void handleMainMatchList(AccessibilityNodeInfo root, long now) {
        if (now - lastActionAtMs < 1100L) return;
        if (mainListFirstSeenAtMs == 0L) {
            mainListFirstSeenAtMs = now;
        }

        AccessibilityNodeInfo eventTypeRv = findFirstById(root, "event_type_rv");
        if (eventTypeRv == null) {
            // Navigate back to Home events tab via left TV rail
            AccessibilityNodeInfo navHome = findFirstById(root, "tv_nav_home");
            if (navHome != null && performClickOnNode(navHome)) {
                lastActionAtMs = now;
                Log.i(TAG, "Clicked tv_nav_home");
            }
            return;
        }

        // Wait up to 6s for Sportzfy's initial API load before touching chips/refresh!
        if (eventTypeRv.getChildCount() == 0 && (now - mainListFirstSeenAtMs) < 6_000L) {
            return;
        }

        // Ensure Sport category index 2 (Football) is selected
        if (eventTypeRv.getChildCount() >= 3) {
            AccessibilityNodeInfo footballTab = eventTypeRv.getChild(2);
            if (footballTab != null && !isSelectedInSubtree(footballTab)) {
                AccessibilityNodeInfo card = findFirstById(footballTab, "card");
                if (performClickOnNode(card != null ? card : footballTab)) {
                    lastActionAtMs = now;
                    scrollAttempts = 0;
                    Log.i(TAG, "Selected Football sport tab (index 2)");
                    return;
                }
            }
        }

        // Never leave filter stuck on "Recent" (it hides Upcoming/other Football cards);
        // in browseFootballOnly mode ensure "All" is selected so both Live & Upcoming games are visible.
        AccessibilityNodeInfo chipRecent = findFirstById(root, "chipRecent");
        AccessibilityNodeInfo chipAll = findFirstById(root, "chipAll");
        boolean recentSelected = chipRecent != null && chipRecent.isVisibleToUser()
                && (chipRecent.isChecked() || chipRecent.isSelected());
        boolean needAllInBrowse = browseFootballOnly && chipAll != null && chipAll.isVisibleToUser()
                && !chipAll.isChecked() && !chipAll.isSelected();
        if (recentSelected || needAllInBrowse) {
            if (chipAll != null && chipAll.isVisibleToUser() && performClickOnNode(chipAll)) {
                lastActionAtMs = now;
                Log.i(TAG, "Switched Football filter -> All (Live & Upcoming)");
                return;
            }
        }

        if (browseFootballOnly) {
            navigating = false;
            browseFootballOnly = false;
            handler.removeCallbacks(tickRunnable);
            Log.i(TAG, "Football section + All filter ready (browseFootballOnly complete)");
            return;
        }

        AccessibilityNodeInfo chipLive = findFirstById(root, "chipLive");
        boolean hasLiveCount = false;
        if (chipLive != null && chipLive.getText() != null) {
            String txt = chipLive.getText().toString(); // e.g. "Live (0)" or "Live (2)"
            hasLiveCount = !txt.contains("(0)") && COUNTDOWN_DIGIT.matcher(txt).find();
        }

        // Inspect visible event_card nodes cleanly inside recycler_view
        AccessibilityNodeInfo recyclerView = findFirstById(root, "recycler_view");
        if (recyclerView == null) return;

        // If feed is still empty during initial 6s warm-up, wait instead of interrupting with Refresh
        if (recyclerView.getChildCount() == 0 && (now - mainListFirstSeenAtMs) < 6_000L) {
            return;
        }

        AccessibilityNodeInfo bestCard = null;
        int bestScore = Integer.MIN_VALUE;
        String bestTitle = "";

        int count = recyclerView.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo card = recyclerView.getChild(i);
            if (card == null) continue;

            String details = readNodeText(findFirstById(card, "event_details"));
            String teamA = readNodeText(findFirstById(card, "teamAName"));
            String teamB = readNodeText(findFirstById(card, "teamBName"));
            String matchTitle = (teamA + " vs " + teamB).trim();
            if (teamA.isEmpty() && teamB.isEmpty()) continue;

            AccessibilityNodeInfo liveTextNode = findFirstById(card, "liveText");
            AccessibilityNodeInfo liveContainer = findFirstById(card, "live_container");
            boolean isCardLive = (liveTextNode != null && liveTextNode.isVisibleToUser())
                    || (liveContainer != null && liveContainer.isVisibleToUser());

            // Must either have visible LIVE badge OR specifically match the target ESPN match
            boolean matchesTarget = matchesTargetTeams(teamA + " " + teamB + " " + details);
            if (!isCardLive && !matchesTarget) {
                continue;
            }
            if (rejectedCardKeys.contains(matchTitle.toLowerCase(Locale.ROOT))) {
                continue;
            }

            int score = 0;
            String combined = details + " " + matchTitle;
            boolean isIsl = FootballScheduleService.isIslText(combined);
            boolean isIndia = FootballScheduleService.isIndiaTeam(teamA) || FootballScheduleService.isIndiaTeam(teamB);

            if (isIsl && (isCardLive || matchesTarget)) score += 30_000; // Priority 1: ISL teams
            if (matchesTarget) score += 20_000;
            if (isCardLive) score += 10_000;

            if (isIsl) {
                score += 3_000;
            } else if (isIndia) {
                score += 2_000; // Priority 2: Indian football team
            } else {
                score += 1_000; // Priority 3: Other football match
            }

            if (score > bestScore) {
                bestScore = score;
                bestCard = card;
                bestTitle = matchTitle;
            }
        }

        if (bestCard != null && performClickOnNode(bestCard)) {
            lastClickedCardTitle = bestTitle;
            lastActionAtMs = now;
            playerEnteredAtMs = 0L;
            currentLinkIndex = 0;
            Log.i(TAG, "Clicked Football card [" + bestTitle + "] score=" + bestScore);
            return;
        }

        // If target wasn't visible yet on All, switch to Live filter when Live count > 0
        if (hasLiveCount && chipLive != null && !chipLive.isChecked() && !chipLive.isSelected()) {
            if (performClickOnNode(chipLive)) {
                lastActionAtMs = now;
                Log.i(TAG, "Selected Football Live filter: " + chipLive.getText());
                return;
            }
        }

        // Scroll once or refresh feed if target hasn't appeared yet
        if (scrollAttempts < 2 && recyclerView.isScrollable()) {
            scrollAttempts++;
            recyclerView.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            lastActionAtMs = now;
            return;
        }

        if (!refreshedOnce && (now - mainListFirstSeenAtMs) >= 6_000L && (now - lastActionAtMs) >= 4_500L) {
            refreshedOnce = true;
            scrollAttempts = 0;
            AccessibilityNodeInfo refreshBtn = findFirstById(root, "refresh");
            if (refreshBtn != null && performClickOnNode(refreshBtn)) {
                lastActionAtMs = now + 2000L;
                Log.i(TAG, "Refreshed Sportzfy feed looking for match");
                return;
            }
        }

        if (!hasLiveCount && targetHome.isEmpty() && targetAway.isEmpty()) {
            navigating = false;
            notifyFailure("no_live_football_matches_in_sportzfy");
        }
    }

    private boolean matchesTargetTeams(String cardText) {
        if (cardText == null) return false;
        String lower = cardText.toLowerCase(Locale.ROOT);
        boolean hasHome = !targetHome.isEmpty() && containsTeamToken(lower, targetHome);
        boolean hasAway = !targetAway.isEmpty() && containsTeamToken(lower, targetAway);
        if (!targetHome.isEmpty() && !targetAway.isEmpty()) {
            return hasHome && hasAway;
        }
        return hasHome || hasAway;
    }

    private static boolean containsTeamToken(String haystackLower, String team) {
        String clean = team.toLowerCase(Locale.ROOT)
                .replace("fc", "").replace("sc", "").replace("af", "").trim();
        if (clean.length() < 3) return false;
        if (haystackLower.contains(clean)) return true;
        // Fallback: match longest word (e.g., "Maldives", "Mohun", "Bengaluru")
        String[] parts = clean.split("\\s+");
        for (String p : parts) {
            if (p.length() >= 4 && haystackLower.contains(p)) return true;
        }
        return false;
    }

    private void notifyFailure(String reason) {
        Log.w(TAG, "Auto-Pilot failed: " + reason);
        if (listener != null) listener.onSportzfyFailed(reason);
    }

    private boolean clickExactText(AccessibilityNodeInfo root, long now, String... labels) {
        if (now - lastActionAtMs < 2000L) return false;
        Set<String> wanted = new HashSet<>();
        for (String l : labels) wanted.add(l.toLowerCase(Locale.ROOT));
        for (String label : labels) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            if (nodes == null) continue;
            for (AccessibilityNodeInfo node : nodes) {
                if (node == null || !node.isVisibleToUser()) continue;
                CharSequence txt = node.getText();
                if (txt != null && wanted.contains(txt.toString().trim().toLowerCase(Locale.ROOT))) {
                    if (performClickOnNode(node)) {
                        lastActionAtMs = now;
                        Log.i(TAG, "Clicked dialog/overlay button: " + txt);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static AccessibilityNodeInfo findFirstById(AccessibilityNodeInfo root, String simpleId) {
        if (root == null) return null;
        List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByViewId(ID_PREFIX + simpleId);
        if (list == null || list.isEmpty()) return null;
        return list.get(0);
    }

    private static String readNodeText(AccessibilityNodeInfo node) {
        if (node == null || node.getText() == null) return "";
        return node.getText().toString().trim();
    }

    private static boolean isSelectedInSubtree(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isSelected()) return true;
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (isSelectedInSubtree(child)) return true;
        }
        return false;
    }

    public static boolean performClickOnNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        while (current != null) {
            if (current.isClickable() && current.isEnabled()) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            current = current.getParent();
        }
        return false;
    }
}
