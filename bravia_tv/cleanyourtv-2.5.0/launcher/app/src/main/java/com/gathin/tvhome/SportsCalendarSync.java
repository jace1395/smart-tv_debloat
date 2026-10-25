package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dynamically synchronizes Indian Super League (ISL) fixtures directly from ESPN's live API.
 * Automatically handles:
 * 1. Rescheduled match dates, days, and kickoff times.
 * 2. Postponed or suspended matches (marks is_postponed=true, preventing false wakes/switches).
 * 3. 2nd leg fixtures (January - April) as soon as the league announces and publishes them.
 * 4. Local persistent caching to dynamic_sports_calendar.json so offline operation never breaks.
 */
public class SportsCalendarSync {

    private static final String TAG = "SportsCalendarSync";
    private static final String ESPN_ISL_SCOREBOARD_URL = "https://site.api.espn.com/apis/site/v2/sports/soccer/ind.1/scoreboard";
    private static final String DYNAMIC_FILE_NAME = "dynamic_sports_calendar.json";

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static volatile long sLastFullSyncMs = 0;
    private static final long FULL_SYNC_INTERVAL_MS = 2 * 3600 * 1000L; // 2 hours

    public static void syncCalendarAsync(Context context, Runnable onComplete) {
        if (context == null) return;
        Context appCtx = context.getApplicationContext();
        executor.execute(() -> {
            try {
                syncCalendarDirect(appCtx);
            } catch (Exception e) {
                Log.w(TAG, "Dynamic calendar sync error: " + e.getMessage());
            }
            if (onComplete != null) {
                mainHandler.post(onComplete);
            }
        });
    }

    public static synchronized boolean syncCalendarDirect(Context context) {
        long now = System.currentTimeMillis();
        // Rate limit full sync to once every 2 hours unless forced
        if (now - sLastFullSyncMs < FULL_SYNC_INTERVAL_MS) {
            Log.d(TAG, "Recent full sync within 2h; skipping redundant network queries.");
            return false;
        }

        try {
            Log.i(TAG, "Starting dynamic ISL fixture sync from ESPN feed...");
            // Step 1: Load existing base calendar (from dynamic cache or bundled seed)
            Map<String, JSONObject> matchMap = loadExistingMatches(context);

            // Step 2: Query today's live scoreboard
            JSONObject todayFeed = fetchJson(ESPN_ISL_SCOREBOARD_URL);
            if (todayFeed == null) {
                Log.w(TAG, "Could not reach ESPN ISL scoreboard feed. Retaining cached fixtures.");
                return false;
            }

            // Ingest today's events (real-time status, kickoff, postponements)
            ingestEvents(todayFeed.optJSONArray("events"), matchMap);

            // Step 3: Parse upcoming dates from season calendar
            JSONArray leagues = todayFeed.optJSONArray("leagues");
            if (leagues != null && leagues.length() > 0) {
                JSONObject l0 = leagues.optJSONObject(0);
                JSONArray calendarDates = l0 != null ? l0.optJSONArray("calendar") : null;
                if (calendarDates != null) {
                    LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
                    LocalDate cutoff = today.plusDays(21); // Fetch next 3 weeks of fixtures in depth
                    DateTimeFormatter ymdFormatter = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);

                    for (int i = 0; i < calendarDates.length(); i++) {
                        String dateIso = calendarDates.optString(i, "");
                        if (dateIso.isEmpty()) continue;
                        try {
                            Instant inst = Instant.parse(dateIso);
                            LocalDate matchDate = inst.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();
                            // If date is today, already ingested. If between tomorrow and cutoff, query specific date:
                            if (!matchDate.isBefore(today) && !matchDate.isAfter(cutoff)) {
                                String dateParam = ymdFormatter.format(matchDate);
                                JSONObject dateFeed = fetchJson(ESPN_ISL_SCOREBOARD_URL + "?dates=" + dateParam);
                                if (dateFeed != null) {
                                    ingestEvents(dateFeed.optJSONArray("events"), matchMap);
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }

            // Step 4: Write merged dynamic calendar atomically
            if (!matchMap.isEmpty()) {
                List<JSONObject> sortedMatches = new ArrayList<>(matchMap.values());
                sortedMatches.sort((a, b) -> {
                    String d1 = a.optString("kickoff", "");
                    String d2 = b.optString("kickoff", "");
                    return d1.compareTo(d2);
                });

                JSONArray matchArray = new JSONArray();
                for (JSONObject m : sortedMatches) {
                    matchArray.put(m);
                }

                JSONObject root = new JSONObject();
                root.put("match_calendar", matchArray);
                root.put("last_synced", Instant.now().toString());

                writeDynamicFile(context, root.toString(2));
                sLastFullSyncMs = now;
                Log.i(TAG, "Successfully synced and updated " + sortedMatches.size() + " dynamic fixtures!");

                // Step 5: Refresh hardware alarms with new dynamic schedule
                List<RaceWeekend.Race> freshRaces = Config.raceCalendar(root);
                if (!freshRaces.isEmpty()) {
                    MatchAutoWakeScheduler.scheduleNextMatchEvents(context, freshRaces);
                }
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error performing dynamic fixture sync: " + e.getMessage(), e);
        }
        return false;
    }

    private static Map<String, JSONObject> loadExistingMatches(Context context) {
        Map<String, JSONObject> map = new LinkedHashMap<>();
        try {
            String jsonText = null;
            File dynamicFile = new File(context.getFilesDir(), DYNAMIC_FILE_NAME);
            if (dynamicFile.exists() && dynamicFile.length() > 50) {
                try (InputStream in = new FileInputStream(dynamicFile);
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[4096];
                    int r;
                    while ((r = in.read(buf)) != -1) baos.write(buf, 0, r);
                    jsonText = baos.toString(StandardCharsets.UTF_8.name());
                }
            }
            if (jsonText == null) {
                try (InputStream in = context.getAssets().open("sports_calendar.json");
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[4096];
                    int r;
                    while ((r = in.read(buf)) != -1) baos.write(buf, 0, r);
                    jsonText = baos.toString(StandardCharsets.UTF_8.name());
                }
            }
            if (jsonText != null) {
                if (jsonText.startsWith("\uFEFF")) jsonText = jsonText.substring(1);
                JSONObject root = new JSONObject(jsonText);
                JSONArray arr = root.optJSONArray("match_calendar");
                if (arr == null) arr = root.optJSONArray("race_calendar");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject m = arr.optJSONObject(i);
                        if (m != null) {
                            String key = normalizeMatchKey(m.optString("name", ""));
                            if (!key.isEmpty()) {
                                map.put(key, m);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed loading initial matches: " + e.getMessage());
        }
        return map;
    }

    private static void ingestEvents(JSONArray events, Map<String, JSONObject> map) {
        if (events == null) return;
        for (int i = 0; i < events.length(); i++) {
            JSONObject ev = events.optJSONObject(i);
            if (ev == null) continue;

            String eventName = ev.optString("name", "");
            String dateIso = ev.optString("date", "");
            if (dateIso.isEmpty()) continue;

            // Extract home & away teams
            String homeTeam = "";
            String awayTeam = "";
            JSONArray competitions = ev.optJSONArray("competitions");
            JSONObject venueObj = ev.optJSONObject("venue");
            String venue = venueObj != null ? venueObj.optString("displayName", "") : "";

            if (competitions != null && competitions.length() > 0) {
                JSONObject c0 = competitions.optJSONObject(0);
                if (venue.isEmpty() && c0 != null) {
                    JSONObject cVenue = c0.optJSONObject("venue");
                    if (cVenue != null) venue = cVenue.optString("fullName", cVenue.optString("displayName", ""));
                }
                JSONArray comps = c0 != null ? c0.optJSONArray("competitors") : null;
                if (comps != null) {
                    for (int c = 0; c < comps.length(); c++) {
                        JSONObject comp = comps.optJSONObject(c);
                        if (comp == null) continue;
                        String ha = comp.optString("homeAway", "");
                        JSONObject teamObj = comp.optJSONObject("team");
                        String tName = teamObj != null ? teamObj.optString("displayName", "") : "";
                        if ("home".equalsIgnoreCase(ha)) {
                            homeTeam = tName;
                        } else {
                            awayTeam = tName;
                        }
                    }
                }
            }

            String matchTitle = !homeTeam.isEmpty() && !awayTeam.isEmpty()
                    ? "ISL: " + homeTeam + " vs " + awayTeam
                    : "ISL: " + eventName;

            // Check match status and postponement
            JSONObject statusObj = ev.optJSONObject("status");
            JSONObject typeObj = statusObj != null ? statusObj.optJSONObject("type") : null;
            String state = typeObj != null ? typeObj.optString("state", "") : "";
            String typeName = typeObj != null ? typeObj.optString("name", "") : "";
            String typeDesc = typeObj != null ? typeObj.optString("description", "") : "";
            boolean wasSuspended = ev.optBoolean("wasSuspended", false);

            boolean isPostponed = "postponed".equalsIgnoreCase(state)
                    || typeName.toUpperCase(Locale.ROOT).contains("POSTPONED")
                    || typeDesc.toUpperCase(Locale.ROOT).contains("POSTPONED")
                    || wasSuspended;

            try {
                JSONObject matchJson = new JSONObject();
                matchJson.put("name", matchTitle);
                matchJson.put("venue", venue);
                matchJson.put("sport", "football");
                matchJson.put("kickoff", dateIso);
                matchJson.put("is_postponed", isPostponed);
                matchJson.put("status", state);

                JSONArray sessions = new JSONArray();
                JSONObject s0 = new JSONObject();
                s0.put("name", "MATCH");
                s0.put("start", dateIso);
                sessions.put(s0);
                matchJson.put("sessions", sessions);

                String key = normalizeMatchKey(matchTitle);
                map.put(key, matchJson);
            } catch (Exception e) {
                Log.w(TAG, "Failed ingesting event " + eventName + ": " + e.getMessage());
            }
        }
    }

    private static String normalizeMatchKey(String title) {
        if (title == null) return "";
        return title.toLowerCase(Locale.ROOT)
                .replace("isl:", "")
                .replace("super giant", "sg")
                .replace("football club", "fc")
                .replaceAll("[^a-z0-9]", "");
    }

    private static JSONObject fetchJson(String urlString) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) CleanYourTV/2.5");

            if (conn.getResponseCode() != 200) {
                return null;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    private static void writeDynamicFile(Context context, String content) {
        File file = new File(context.getFilesDir(), DYNAMIC_FILE_NAME);
        File tmp = new File(context.getFilesDir(), DYNAMIC_FILE_NAME + ".tmp");
        try {
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
            if (tmp.renameTo(file) || (file.delete() && tmp.renameTo(file))) {
                Log.d(TAG, "Atomically saved dynamic calendar to: " + file.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error writing dynamic calendar file: " + e.getMessage());
        }
    }
}
