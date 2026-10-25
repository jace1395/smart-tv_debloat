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
    }

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static volatile LiveMatchInfo cachedMatchInfo = null;
    private static volatile long lastFetchTime = 0;
    private static final long CACHE_DURATION_MS = 25 * 1000; // 25 seconds for live matches

    public interface Callback {
        void onResult(LiveMatchInfo matchInfo);
    }

    public static void fetchLiveScore(Callback callback) {
        if (cachedMatchInfo != null && (System.currentTimeMillis() - lastFetchTime < CACHE_DURATION_MS)) {
            callback.onResult(cachedMatchInfo);
            return;
        }

        executor.execute(() -> {
            LiveMatchInfo info = queryScoreboard();
            if (info != null) {
                cachedMatchInfo = info;
                lastFetchTime = System.currentTimeMillis();
            }
            final LiveMatchInfo res = cachedMatchInfo;
            mainHandler.post(() -> callback.onResult(res));
        });
    }

    private static LiveMatchInfo queryScoreboard() {
        LiveMatchInfo todayInfo = queryScoreboardUrl(ESPN_ISL_SCOREBOARD_URL);
        if (todayInfo != null && todayInfo.isLive) {
            return todayInfo;
        }

        try {
            // Check tomorrow's match if not currently live
            java.time.LocalDate tomorrow = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).plusDays(1);
            String tomorrowParam = tomorrow.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            LiveMatchInfo tomorrowInfo = queryScoreboardUrl(ESPN_ISL_SCOREBOARD_URL + "?dates=" + tomorrowParam);
            if (tomorrowInfo != null) {
                return tomorrowInfo;
            }
        } catch (Exception ignored) {}

        return todayInfo;
    }

    private static LiveMatchInfo queryScoreboardUrl(String targetUrl) {
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

            if (selectedEvent == null) {
                selectedEvent = events.optJSONObject(0);
            }
            if (selectedEvent == null) return null;

            LiveMatchInfo info = new LiveMatchInfo();
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
}
