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
 * Real-time Formula 1 live score / race status service using ESPN's public racing scoreboard feed.
 * Provides live session state ("in"), current status (e.g. Lap, session name), and leader standings (P1, P2, P3)
 * without requiring any API keys.
 */
public class F1LiveScoreService {

    private static final String TAG = "F1LiveScoreService";
    private static final String ESPN_F1_SCOREBOARD_URL = "https://site.api.espn.com/apis/site/v2/sports/racing/f1/scoreboard";

    public static class F1LiveInfo {
        public boolean isLive;
        public boolean isFinished;
        public String eventName = "";
        public String circuitName = "";
        public String sessionName = "";
        public String statusDetail = "";
        public String top3Summary = "";
        public String p1Driver = "";
        public String p2Driver = "";
        public String p3Driver = "";
        public String statusState = ""; // "in", "pre", "post"
    }

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static volatile F1LiveInfo cachedF1Info = null;
    private static volatile long lastFetchTime = 0;
    private static final long CACHE_DURATION_MS = 25 * 1000; // 25 seconds cache

    public interface Callback {
        void onResult(F1LiveInfo info);
    }

    public static void fetchF1Live(Callback callback) {
        if (cachedF1Info != null && (System.currentTimeMillis() - lastFetchTime < CACHE_DURATION_MS)) {
            callback.onResult(cachedF1Info);
            return;
        }

        executor.execute(() -> {
            F1LiveInfo info = queryScoreboard();
            if (info != null) {
                cachedF1Info = info;
                lastFetchTime = System.currentTimeMillis();
            }
            final F1LiveInfo res = cachedF1Info;
            mainHandler.post(() -> callback.onResult(res));
        });
    }

    private static F1LiveInfo queryScoreboard() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(ESPN_F1_SCOREBOARD_URL);
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

            JSONObject event = events.optJSONObject(0);
            if (event == null) return null;

            F1LiveInfo info = new F1LiveInfo();
            info.eventName = event.optString("name", "Formula 1 Grand Prix");

            JSONObject circuit = event.optJSONObject("circuit");
            if (circuit != null) {
                String full = circuit.optString("fullName", "");
                JSONObject addr = circuit.optJSONObject("address");
                String city = addr != null ? addr.optString("city", "") : "";
                info.circuitName = full + (city.isEmpty() ? "" : ", " + city);
            }

            JSONObject status = event.optJSONObject("status");
            if (status != null) {
                JSONObject stype = status.optJSONObject("type");
                if (stype != null) {
                    info.statusState = stype.optString("state", "");
                    info.statusDetail = stype.optString("shortDetail", stype.optString("detail", ""));
                }
            }
            info.isLive = "in".equalsIgnoreCase(info.statusState);
            info.isFinished = "post".equalsIgnoreCase(info.statusState);

            JSONArray competitions = event.optJSONArray("competitions");
            if (competitions != null && competitions.length() > 0) {
                JSONObject comp = competitions.optJSONObject(0);
                if (comp != null) {
                    JSONArray competitors = comp.optJSONArray("competitors");
                    if (competitors != null && competitors.length() > 0) {
                        StringBuilder top3 = new StringBuilder();
                        for (int i = 0; i < Math.min(3, competitors.length()); i++) {
                            JSONObject c = competitors.optJSONObject(i);
                            if (c == null) continue;
                            JSONObject athlete = c.optJSONObject("athlete");
                            String driver = athlete != null ? athlete.optString("shortName", athlete.optString("displayName", "")) : "";
                            int order = c.optInt("order", i + 1);
                            if (top3.length() > 0) top3.append("   ");
                            top3.append("P").append(order).append(" ").append(driver);
                            if (i == 0) info.p1Driver = driver;
                            else if (i == 1) info.p2Driver = driver;
                            else if (i == 2) info.p3Driver = driver;
                        }
                        info.top3Summary = top3.toString();
                    }
                }
            }

            return info;
        } catch (Exception e) {
            Log.e(TAG, "Failed to query ESPN F1 scoreboard: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
        return null;
    }
}
