package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Real-time Indian Super League (ISL) live score service using ESPN's public scoreboard feed.
 * Provides live match state ("in"), current clock minute (e.g. 59'), and real-time scores
 * without needing API keys.
 */
public class IslLiveScoreService {

    private static final String TAG = "IslLiveScoreService";
    private static final String ESPN_ISL_SCOREBOARD_URL = "https://site.api.espn.com/apis/site/v2/sports/soccer/ind.1/scoreboard";

    public static class LiveMatchInfo {
        public boolean isLive;
        public boolean isFinished;
        public String matchName = "";
        public String homeTeam = "";
        public String awayTeam = "";
        public String homeScore = "0";
        public String awayScore = "0";
        public String clockDetail = ""; // e.g. "59'", "HT", "FT"
        public String statusState = "";  // "in", "post", "pre"
        public String venue = "";
        public String previousMatchSummary = ""; // e.g. "PREV: Inter Kashi 0 - 3 SC Delhi (FT)"
        public java.util.List<String> past4Matches = new java.util.ArrayList<>();
    }

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static volatile LiveMatchInfo cachedMatchInfo = null;
    private static volatile long lastFetchTime = 0;
    private static final long CACHE_DURATION_MS = 25 * 1000; // 25 seconds for live matches

    public static LiveMatchInfo getLiveMatchInfo() {
        return cachedMatchInfo;
    }

    public interface Callback {
        void onResult(LiveMatchInfo matchInfo);
    }

    public static void fetchLiveScore(Callback callback) {
        if (cachedMatchInfo != null && (System.currentTimeMillis() - lastFetchTime < CACHE_DURATION_MS)) {
            if (callback != null) callback.onResult(cachedMatchInfo);
            return;
        }

        executor.execute(() -> {
            LiveMatchInfo info = queryScoreboard();
            if (info != null) {
                cachedMatchInfo = info;
                lastFetchTime = System.currentTimeMillis();
            }
            final LiveMatchInfo res = cachedMatchInfo;
            if (callback != null) {
                mainHandler.post(() -> callback.onResult(res));
            }
        });
    }

    private static LiveMatchInfo queryScoreboard() {
        LiveMatchInfo todayInfo = queryScoreboardUrl(ESPN_ISL_SCOREBOARD_URL, true);
        if (todayInfo != null && (todayInfo.isLive || "pre".equalsIgnoreCase(todayInfo.statusState))) {
            return todayInfo;
        }

        try {
            // Check tomorrow's match only if no live/upcoming match was already found in default scoreboard
            java.time.LocalDate tomorrow = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).plusDays(1);
            String tomorrowParam = tomorrow.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            LiveMatchInfo tomorrowInfo = queryScoreboardUrl(
                    ESPN_ISL_SCOREBOARD_URL + "?dates=" + tomorrowParam,
                    todayInfo == null || todayInfo.past4Matches.isEmpty());
            if (tomorrowInfo != null) {
                if (tomorrowInfo.past4Matches.isEmpty() && todayInfo != null && !todayInfo.past4Matches.isEmpty()) {
                    tomorrowInfo.past4Matches = todayInfo.past4Matches;
                    tomorrowInfo.previousMatchSummary = todayInfo.previousMatchSummary;
                }
                return tomorrowInfo;
            }
        } catch (Exception ignored) {}

        return todayInfo;
    }

    private static LiveMatchInfo queryScoreboardUrl(String targetUrl, boolean includeHistory) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(targetUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

            if (conn.getResponseCode() != 200) {
                return null;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }

            JSONObject root = new JSONObject(sb.toString());
            JSONArray events = root.optJSONArray("events");
            if (events == null || events.length() == 0) return null;

            // Prioritize an in-progress match ("in"), otherwise a scheduled match ("pre"), otherwise ("post")
            JSONObject selectedEvent = null;
            for (int i = 0; i < events.length(); i++) {
                JSONObject ev = events.optJSONObject(i);
                if (ev == null) continue;
                JSONObject status = ev.optJSONObject("status");
                JSONObject stype = status != null ? status.optJSONObject("type") : null;
                String state = stype != null ? stype.optString("state", "") : "";
                if ("in".equalsIgnoreCase(state)) {
                    selectedEvent = ev;
                    break;
                }
            }

            if (selectedEvent == null) {
                for (int i = 0; i < events.length(); i++) {
                    JSONObject ev = events.optJSONObject(i);
                    if (ev == null) continue;
                    JSONObject status = ev.optJSONObject("status");
                    JSONObject stype = status != null ? status.optJSONObject("type") : null;
                    String state = stype != null ? stype.optString("state", "") : "";
                    if ("pre".equalsIgnoreCase(state)) {
                        selectedEvent = ev;
                        break;
                    }
                }
            }

            // Collect past 4 completed matches for score history (skipping future calendar dates!)
            java.util.List<String> pastMatches = new java.util.ArrayList<>();
            if (includeHistory) {
                for (int i = events.length() - 1; i >= 0 && pastMatches.size() < 4; i--) {
                    JSONObject ev = events.optJSONObject(i);
                    if (ev == null) continue;
                    JSONObject st = ev.optJSONObject("status");
                    JSONObject sty = st != null ? st.optJSONObject("type") : null;
                    String stState = sty != null ? sty.optString("state", "") : "";
                    if ("post".equalsIgnoreCase(stState)) {
                        String s = parseCompletedMatch(ev);
                        if (s != null && !pastMatches.contains(s)) {
                            pastMatches.add(s);
                        }
                    }
                }

                // If less than 4, query recent completed dates <= today from league calendar
                if (pastMatches.size() < 4) {
                    JSONArray leagues = root.optJSONArray("leagues");
                    if (leagues != null && leagues.length() > 0) {
                        JSONArray cal = leagues.optJSONObject(0).optJSONArray("calendar");
                        if (cal != null) {
                            String todayIso = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString();
                            for (int c = cal.length() - 1; c >= 0 && pastMatches.size() < 4; c--) {
                                String calDate = cal.optString(c, "");
                                if (calDate.length() >= 10) {
                                    String dateIso = calDate.substring(0, 10);
                                    if (dateIso.compareTo(todayIso) > 0) continue;
                                    String dateParam = dateIso.replace("-", "");
                                    if (!targetUrl.contains("dates=" + dateParam)) {
                                        queryPastCalendarDate("https://site.api.espn.com/apis/site/v2/sports/soccer/ind.1/scoreboard?dates=" + dateParam, pastMatches);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            String prevSummary = "";
            if (!pastMatches.isEmpty()) {
                StringBuilder sbPast = new StringBuilder("PAST 4: ");
                for (int p = 0; p < pastMatches.size(); p++) {
                    if (p > 0) sbPast.append(" \u00B7 ");
                    sbPast.append(pastMatches.get(p));
                }
                prevSummary = sbPast.toString();
            }

            if (selectedEvent == null) {
                selectedEvent = events.optJSONObject(0);
            }
            if (selectedEvent == null) return null;

            LiveMatchInfo info = new LiveMatchInfo();
            info.previousMatchSummary = prevSummary;
            info.past4Matches = pastMatches;
            JSONObject status = selectedEvent.optJSONObject("status");
            if (status != null) {
                JSONObject stype = status.optJSONObject("type");
                if (stype != null) {
                    info.statusState = stype.optString("state", "");
                    info.clockDetail = stype.optString("shortDetail", stype.optString("detail", ""));
                }
            }
            info.isLive = "in".equalsIgnoreCase(info.statusState);
            info.isFinished = "post".equalsIgnoreCase(info.statusState);

            JSONArray competitions = selectedEvent.optJSONArray("competitions");
            if (competitions != null && competitions.length() > 0) {
                JSONObject comp = competitions.optJSONObject(0);
                if (comp != null) {
                    JSONObject venue = comp.optJSONObject("venue");
                    if (venue != null) {
                        info.venue = venue.optString("fullName", "");
                    }
                    JSONArray competitors = comp.optJSONArray("competitors");
                    if (competitors != null && competitors.length() >= 2) {
                        JSONObject c0 = competitors.optJSONObject(0);
                        JSONObject c1 = competitors.optJSONObject(1);
                        if (c0 != null && c1 != null) {
                            JSONObject t0 = c0.optJSONObject("team");
                            JSONObject t1 = c1.optJSONObject("team");
                            // Determine home vs away correctly
                            boolean c0Home = "home".equalsIgnoreCase(c0.optString("homeAway", ""));
                            JSONObject homeTeamObj = c0Home ? t0 : t1;
                            JSONObject awayTeamObj = c0Home ? t1 : t0;
                            JSONObject homeComp = c0Home ? c0 : c1;
                            JSONObject awayComp = c0Home ? c1 : c0;

                            info.homeTeam = homeTeamObj != null ? homeTeamObj.optString("shortDisplayName", homeTeamObj.optString("displayName", "")) : "";
                            info.homeScore = homeComp != null ? homeComp.optString("score", "0") : "0";
                            info.awayTeam = awayTeamObj != null ? awayTeamObj.optString("shortDisplayName", awayTeamObj.optString("displayName", "")) : "";
                            info.awayScore = awayComp != null ? awayComp.optString("score", "0") : "0";
                            info.matchName = info.homeTeam + " vs " + info.awayTeam;
                        }
                    }
                }
            }

            return info;
        } catch (Exception e) {
            Log.e(TAG, "Failed to query ESPN ISL scoreboard from " + targetUrl + ": " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static String parseCompletedMatch(JSONObject ev) {
        if (ev == null) return null;
        JSONArray comps = ev.optJSONArray("competitions");
        if (comps != null && comps.length() > 0) {
            JSONObject cmp = comps.optJSONObject(0);
            JSONArray cmps = cmp != null ? cmp.optJSONArray("competitors") : null;
            if (cmps != null && cmps.length() >= 2) {
                JSONObject k0 = cmps.optJSONObject(0);
                JSONObject k1 = cmps.optJSONObject(1);
                if (k0 != null && k1 != null) {
                    JSONObject tm0 = k0.optJSONObject("team");
                    JSONObject tm1 = k1.optJSONObject("team");
                    String n0 = tm0 != null ? tm0.optString("shortDisplayName", tm0.optString("displayName", "")) : "";
                    String n1 = tm1 != null ? tm1.optString("shortDisplayName", tm1.optString("displayName", "")) : "";
                    String sc0 = k0.optString("score", "0");
                    String sc1 = k1.optString("score", "0");
                    return n0 + " " + sc0 + " - " + sc1 + " " + n1 + " (FT)";
                }
            }
        }
        return null;
    }

    private static void queryPastCalendarDate(String pastUrl, java.util.List<String> targetList) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(pastUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (conn.getResponseCode() == 200) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line).append('\n');
                }
                JSONObject root = new JSONObject(sb.toString());
                JSONArray evs = root.optJSONArray("events");
                if (evs != null) {
                    for (int i = 0; i < evs.length() && targetList.size() < 4; i++) {
                        JSONObject ev = evs.optJSONObject(i);
                        if (ev == null) continue;
                        JSONObject st = ev.optJSONObject("status");
                        JSONObject sty = st != null ? st.optJSONObject("type") : null;
                        if (sty != null && "post".equalsIgnoreCase(sty.optString("state", ""))) {
                            String s = parseCompletedMatch(ev);
                            if (s != null && !targetList.contains(s)) {
                                targetList.add(s);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }
}
