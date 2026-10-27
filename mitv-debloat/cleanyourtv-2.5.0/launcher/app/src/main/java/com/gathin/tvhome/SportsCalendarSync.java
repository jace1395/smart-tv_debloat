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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Syncs ISL Football and Formula 1 calendar dynamically from official ESPN feeds.
 * Keeps Mi TV updated with live fixtures, P1-P4 driver results, and upcoming events
 * without manual intervention or auto-streaming interruptions.
 */
public final class SportsCalendarSync {
    private static final String TAG = "SportsCalendarSync";
    private static final String F1_SCOREBOARD_URL = "https://site.api.espn.com/apis/site/v2/sports/racing/f1/scoreboard";
    private static final String JOLPICA_CURRENT_URL = "https://api.jolpi.ca/ergast/f1/current.json";
    private static final String JOLPICA_SEASON_URL = "https://api.jolpi.ca/ergast/f1/%d.json";
    private static final String ISL_SCOREBOARD_URL = "https://site.api.espn.com/apis/site/v2/sports/soccer/ind.1/scoreboard";
    private static final String CALENDAR_FILE = "dynamic_sports_calendar.json";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static long sLastSyncMs = 0;
    private static final long SYNC_COOLDOWN_MS = 3 * 60 * 1000; // 3 minutes

    private SportsCalendarSync() {}

    public static void syncCalendarAsync(Context context, Runnable onComplete) {
        if (context == null) return;
        Context appCtx = context.getApplicationContext();
        long now = System.currentTimeMillis();
        if (now - sLastSyncMs < SYNC_COOLDOWN_MS) {
            if (onComplete != null) {
                MAIN_HANDLER.post(onComplete);
            }
            return;
        }

        EXECUTOR.execute(() -> {
            try {
                boolean success = performSync(appCtx);
                if (success) {
                    sLastSyncMs = System.currentTimeMillis();
                }
            } catch (Exception e) {
                Log.w(TAG, "Error performing sports calendar sync: " + e.getMessage());
            } finally {
                if (onComplete != null) {
                    MAIN_HANDLER.post(onComplete);
                }
            }
        });
    }

    private static boolean performSync(Context context) {
        try {
            JSONArray mergedList = new JSONArray();
            Set<String> seenEvents = new HashSet<>();

            // 1. Fetch ISL matches
            fetchIslMatches(mergedList, seenEvents);

            // 2. Fetch F1 events & P1-P4 top drivers
            fetchF1Events(mergedList, seenEvents);

            if (mergedList.length() == 0) {
                Log.w(TAG, "Sync returned 0 events, keeping existing calendar");
                return false;
            }

            // Save to dynamic_sports_calendar.json atomically
            JSONObject root = new JSONObject();
            root.put("match_calendar", mergedList);
            String jsonStr = root.toString(2);

            File targetFile = new File(context.getFilesDir(), CALENDAR_FILE);
            File tempFile = new File(context.getFilesDir(), CALENDAR_FILE + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(jsonStr.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
            if (tempFile.renameTo(targetFile)) {
                Log.i(TAG, "Dynamic sports calendar updated successfully (" + mergedList.length() + " events)");
                return true;
            } else {
                // Fallback direct write
                try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                    fos.write(jsonStr.getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                }
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to perform sports calendar sync", e);
            return false;
        }
    }

    private static void fetchIslMatches(JSONArray targetArray, Set<String> seenEvents) {
        try {
            String todayResp = fetchUrl(ISL_SCOREBOARD_URL);
            if (todayResp == null) return;
            JSONObject todayJson = new JSONObject(todayResp);

            // Parse today's matches
            parseIslEvents(todayJson.optJSONArray("events"), targetArray, seenEvents);

            // Parse upcoming match dates from league calendar
            JSONArray leagues = todayJson.optJSONArray("leagues");
            if (leagues != null && leagues.length() > 0) {
                JSONObject league = leagues.optJSONObject(0);
                JSONArray calendar = league != null ? league.optJSONArray("calendar") : null;
                if (calendar != null) {
                    LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
                    int fetchedDatesCount = 0;
                    for (int i = 0; i < calendar.length() && fetchedDatesCount < 8; i++) {
                        String dateEntry = calendar.optString(i, "");
                        if (dateEntry.isEmpty()) continue;
                        String datePart = dateEntry.length() >= 10 ? dateEntry.substring(0, 10) : dateEntry;
                        try {
                            LocalDate calDate = LocalDate.parse(datePart);
                            if (!calDate.isBefore(today)) {
                                String dateParam = datePart.replace("-", "");
                                String dateUrl = ISL_SCOREBOARD_URL + "?dates=" + dateParam;
                                String dateResp = fetchUrl(dateUrl);
                                if (dateResp != null) {
                                    JSONObject dateJson = new JSONObject(dateResp);
                                    parseIslEvents(dateJson.optJSONArray("events"), targetArray, seenEvents);
                                }
                                fetchedDatesCount++;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error fetching ISL matches: " + e.getMessage());
        }
    }

    private static void parseIslEvents(JSONArray events, JSONArray targetArray, Set<String> seenEvents) {
        if (events == null) return;
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event == null) continue;
            String eventId = event.optString("id", "");
            if (!eventId.isEmpty() && seenEvents.contains("isl_" + eventId)) continue;

            JSONArray competitions = event.optJSONArray("competitions");
            if (competitions == null || competitions.length() == 0) continue;
            JSONObject comp = competitions.optJSONObject(0);
            if (comp == null) continue;

            String kickoff = comp.optString("date", event.optString("date", ""));
            if (kickoff.isEmpty()) continue;

            JSONArray competitors = comp.optJSONArray("competitors");
            String homeTeam = "", awayTeam = "";
            if (competitors != null && competitors.length() >= 2) {
                JSONObject c0 = competitors.optJSONObject(0);
                JSONObject c1 = competitors.optJSONObject(1);
                String t0 = c0 != null ? c0.optJSONObject("team").optString("displayName", "") : "";
                String t1 = c1 != null ? c1.optJSONObject("team").optString("displayName", "") : "";
                boolean c0Home = c0 != null && "home".equalsIgnoreCase(c0.optString("homeAway", ""));
                homeTeam = c0Home ? t0 : t1;
                awayTeam = c0Home ? t1 : t0;
            }
            String matchName = !homeTeam.isEmpty() && !awayTeam.isEmpty()
                    ? "ISL: " + homeTeam + " vs " + awayTeam
                    : "ISL: " + event.optString("name", "Match");

            JSONObject venueObj = comp.optJSONObject("venue");
            String venue = venueObj != null ? venueObj.optString("fullName", "") : "";

            JSONObject statusObj = comp.optJSONObject("status");
            JSONObject statusType = statusObj != null ? statusObj.optJSONObject("type") : null;
            String statusState = statusType != null ? statusType.optString("state", "") : "";
            String statusName = statusType != null ? statusType.optString("name", "") : "";
            boolean isPostponed = "postponed".equalsIgnoreCase(statusState)
                    || statusName.toUpperCase(Locale.ROOT).contains("POSTPONED");

            try {
                JSONObject matchObj = new JSONObject();
                matchObj.put("matchday", 0);
                matchObj.put("name", matchName);
                matchObj.put("venue", venue);
                matchObj.put("sport", "football");
                matchObj.put("kickoff", kickoff);
                matchObj.put("is_postponed", isPostponed);

                JSONArray sessions = new JSONArray();
                JSONObject session = new JSONObject();
                session.put("name", "MATCH");
                session.put("start", kickoff);
                sessions.put(session);
                matchObj.put("sessions", sessions);

                targetArray.put(matchObj);
                if (!eventId.isEmpty()) seenEvents.add("isl_" + eventId);
            } catch (Exception ignored) {}
        }
    }

    private static void fetchF1Events(JSONArray targetArray, Set<String> seenEvents) {
        String liveTopDrivers = "";
        String activeEspnEventName = "";
        try {
            String f1Resp = fetchUrl(F1_SCOREBOARD_URL);
            if (f1Resp != null) {
                JSONObject f1Json = new JSONObject(f1Resp);
                JSONArray events = f1Json.optJSONArray("events");
                if (events != null && events.length() > 0) {
                    JSONObject event = events.optJSONObject(0);
                    activeEspnEventName = cleanGrandPrixName(event.optString("name", ""));
                    liveTopDrivers = extractEspnTopDrivers(event);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error checking ESPN F1 scoreboard: " + e.getMessage());
        }

        boolean jolpicaSuccess = false;
        try {
            int currentYear = LocalDate.now(ZoneId.of("UTC")).getYear();
            jolpicaSuccess = fetchJolpicaSeason(JOLPICA_CURRENT_URL, targetArray, seenEvents, liveTopDrivers, activeEspnEventName);

            // Multi-season auto rollover:
            // When current season's races are all finished (e.g. after December 2026),
            // automatically fetch the 2027 calendar so the TV never runs dry!
            if (shouldFetchNextSeason(targetArray)) {
                String nextSeasonUrl = String.format(Locale.ROOT, JOLPICA_SEASON_URL, currentYear + 1);
                fetchJolpicaSeason(nextSeasonUrl, targetArray, seenEvents, "", "");
            }
        } catch (Exception e) {
            Log.w(TAG, "Error fetching Jolpica F1 calendar: " + e.getMessage());
        }

        // Fallback: If Jolpica was unreachable, parse from ESPN scoreboard directly
        if (!jolpicaSuccess) {
            try {
                String f1Resp = fetchUrl(F1_SCOREBOARD_URL);
                if (f1Resp != null) {
                    JSONObject f1Json = new JSONObject(f1Resp);
                    JSONArray events = f1Json.optJSONArray("events");
                    if (events != null && events.length() > 0) {
                        parseF1Event(events.optJSONObject(0), targetArray, seenEvents);
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private static boolean shouldFetchNextSeason(JSONArray targetArray) {
        if (targetArray == null || targetArray.length() == 0) return true;
        Instant now = Instant.now();
        int upcomingCount = 0;
        for (int i = 0; i < targetArray.length(); i++) {
            JSONObject obj = targetArray.optJSONObject(i);
            if (obj != null && "f1".equalsIgnoreCase(obj.optString("sport", ""))) {
                JSONArray sessions = obj.optJSONArray("sessions");
                if (sessions != null && sessions.length() > 0) {
                    JSONObject lastSession = sessions.optJSONObject(sessions.length() - 1);
                    String start = lastSession != null ? lastSession.optString("start", "") : "";
                    try {
                        Instant end = Instant.parse(start).plus(java.time.Duration.ofHours(3));
                        if (end.isAfter(now)) {
                            upcomingCount++;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        return upcomingCount == 0;
    }

    private static boolean fetchJolpicaSeason(String url, JSONArray targetArray, Set<String> seenEvents,
                                              String liveTopDrivers, String activeEventName) {
        String resp = fetchUrl(url);
        if (resp == null) return false;
        try {
            JSONObject root = new JSONObject(resp);
            JSONObject mrData = root.optJSONObject("MRData");
            if (mrData == null) return false;
            JSONObject raceTable = mrData.optJSONObject("RaceTable");
            if (raceTable == null) return false;
            JSONArray races = raceTable.optJSONArray("Races");
            if (races == null || races.length() == 0) return false;

            for (int i = 0; i < races.length(); i++) {
                JSONObject r = races.optJSONObject(i);
                if (r == null) continue;
                String roundStr = r.optString("round", "0");
                int round = 0;
                try { round = Integer.parseInt(roundStr); } catch (Exception ignored) {}
                String raceName = r.optString("raceName", "Grand Prix");
                String cleanedName = cleanGrandPrixName(raceName);

                String eventKey = "f1_" + r.optString("season", "") + "_" + round + "_" + cleanedName;
                if (seenEvents.contains(eventKey)) continue;

                JSONObject circuit = r.optJSONObject("Circuit");
                JSONObject loc = circuit != null ? circuit.optJSONObject("Location") : null;
                String place = loc != null ? loc.optString("locality", "") : "";
                if (place.isEmpty() && circuit != null) {
                    place = circuit.optString("circuitName", "");
                }

                JSONArray sessions = new JSONArray();
                addJolpicaSession(sessions, r.optJSONObject("FirstPractice"), "FP1");
                addJolpicaSession(sessions, r.optJSONObject("SecondPractice"), "FP2");
                addJolpicaSession(sessions, r.optJSONObject("ThirdPractice"), "FP3");
                addJolpicaSession(sessions, r.optJSONObject("SprintQualifying"), "SPRINT QUALI");
                addJolpicaSession(sessions, r.optJSONObject("Sprint"), "SPRINT");
                addJolpicaSession(sessions, r.optJSONObject("Qualifying"), "QUALIFYING");

                String date = r.optString("date", "");
                String time = r.optString("time", "15:00:00Z");
                if (!date.isEmpty()) {
                    if (time.isEmpty()) time = "15:00:00Z";
                    JSONObject raceSession = new JSONObject();
                    raceSession.put("name", "RACE");
                    raceSession.put("start", date + "T" + time);
                    sessions.put(raceSession);
                }

                if (sessions.length() == 0) continue;

                List<JSONObject> sessionList = new ArrayList<>();
                for (int s = 0; s < sessions.length(); s++) {
                    sessionList.add(sessions.getJSONObject(s));
                }
                Collections.sort(sessionList, Comparator.comparing(a -> a.optString("start", "")));
                JSONArray sortedSessions = new JSONArray();
                for (JSONObject sObj : sessionList) {
                    sortedSessions.put(sObj);
                }

                String raceDrivers = "";
                if (!liveTopDrivers.isEmpty() && !activeEventName.isEmpty()
                        && (cleanedName.toLowerCase(Locale.ROOT).contains(activeEventName.toLowerCase(Locale.ROOT))
                        || activeEventName.toLowerCase(Locale.ROOT).contains(cleanedName.toLowerCase(Locale.ROOT)))) {
                    raceDrivers = liveTopDrivers;
                }

                JSONObject raceObj = new JSONObject();
                raceObj.put("round", round);
                raceObj.put("name", cleanedName);
                raceObj.put("place", place);
                raceObj.put("sport", "f1");
                raceObj.put("top_drivers", raceDrivers);
                raceObj.put("is_postponed", false);
                raceObj.put("sessions", sortedSessions);

                targetArray.put(raceObj);
                seenEvents.add(eventKey);
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse Jolpica JSON from " + url, e);
            return false;
        }
    }

    private static void addJolpicaSession(JSONArray sessions, JSONObject sObj, String sessionName) {
        if (sObj == null) return;
        String date = sObj.optString("date", "");
        String time = sObj.optString("time", "12:00:00Z");
        if (date.isEmpty()) return;
        if (time.isEmpty()) time = "12:00:00Z";
        try {
            JSONObject s = new JSONObject();
            s.put("name", sessionName);
            s.put("start", date + "T" + time);
            sessions.put(s);
        } catch (Exception ignored) {}
    }

    private static String extractEspnTopDrivers(JSONObject event) {
        if (event == null) return "";
        JSONArray competitions = event.optJSONArray("competitions");
        if (competitions == null || competitions.length() == 0) return "";
        JSONObject latestCompletedComp = null;
        for (int i = 0; i < competitions.length(); i++) {
            JSONObject comp = competitions.optJSONObject(i);
            if (comp == null) continue;
            JSONObject statusObj = comp.optJSONObject("status");
            JSONObject statusType = statusObj != null ? statusObj.optJSONObject("type") : null;
            String state = statusType != null ? statusType.optString("state", "") : "";
            if ("post".equalsIgnoreCase(state)) {
                latestCompletedComp = comp;
            }
        }
        if (latestCompletedComp == null) return "";

        JSONObject typeObj = latestCompletedComp.optJSONObject("type");
        String compName = typeObj != null ? typeObj.optString("abbreviation", typeObj.optString("text", "")) : "";
        String prefix = normalizeSessionPrefix(compName);

        JSONArray competitors = latestCompletedComp.optJSONArray("competitors");
        if (competitors != null && competitors.length() > 0) {
            StringBuilder sb = new StringBuilder();
            if (!prefix.isEmpty()) {
                sb.append(prefix).append(": ");
            }
            int count = Math.min(4, competitors.length());
            for (int c = 0; c < count; c++) {
                JSONObject compObj = competitors.optJSONObject(c);
                if (compObj == null) continue;
                int order = compObj.optInt("order", c + 1);
                JSONObject athlete = compObj.optJSONObject("athlete");
                String driverName = "";
                if (athlete != null) {
                    driverName = athlete.optString("shortName", athlete.optString("displayName", ""));
                }
                if (driverName.isEmpty()) {
                    driverName = compObj.optString("displayName", "Driver " + order);
                }
                if (c > 0) sb.append(" \u00B7 ");
                sb.append("P").append(order).append(" ").append(driverName);
            }
            return sb.toString();
        }
        return "";
    }

    private static void parseF1Event(JSONObject event, JSONArray targetArray, Set<String> seenEvents) {
        if (event == null) return;
        String eventId = event.optString("id", event.optString("name", ""));
        if (!eventId.isEmpty() && seenEvents.contains("f1_" + eventId)) return;

        String rawName = event.optString("name", "Grand Prix");
        // Simplify sponsor prefixes e.g. "MSC Cruises United States Grand Prix" -> "United States Grand Prix"
        String cleanedName = cleanGrandPrixName(rawName);

        JSONArray competitions = event.optJSONArray("competitions");
        if (competitions == null || competitions.length() == 0) return;

        JSONArray sessions = new JSONArray();
        JSONObject latestCompletedComp = null;
        String venuePlace = "";
        JSONObject circuitObj = event.optJSONObject("circuit");
        if (circuitObj != null) {
            JSONObject address = circuitObj.optJSONObject("address");
            if (address != null) {
                venuePlace = address.optString("city", "");
            }
            if (venuePlace.isEmpty()) {
                venuePlace = circuitObj.optString("fullName", "");
            }
        }

        for (int i = 0; i < competitions.length(); i++) {
            JSONObject comp = competitions.optJSONObject(i);
            if (comp == null) continue;

            JSONObject typeObj = comp.optJSONObject("type");
            String abbrev = typeObj != null ? typeObj.optString("abbreviation", typeObj.optString("text", "")) : "";
            String sessionName = normalizeSessionName(abbrev);

            String dateStr = comp.optString("date", "");
            if (dateStr.isEmpty()) continue;

            if (venuePlace.isEmpty()) {
                JSONObject venueObj = comp.optJSONObject("venue");
                JSONObject address = venueObj != null ? venueObj.optJSONObject("address") : null;
                venuePlace = address != null ? address.optString("city", "") : "";
            }

            JSONObject statusObj = comp.optJSONObject("status");
            JSONObject statusType = statusObj != null ? statusObj.optJSONObject("type") : null;
            String state = statusType != null ? statusType.optString("state", "") : "";
            if ("post".equalsIgnoreCase(state)) {
                latestCompletedComp = comp;
            }

            try {
                JSONObject s = new JSONObject();
                s.put("name", sessionName);
                s.put("start", dateStr);
                sessions.put(s);
            } catch (Exception ignored) {}
        }

        if (sessions.length() == 0) return;

        // Extract P1, P2, P3, P4 from the latest completed session (Qualifying, Practice, or Race)
        String topDrivers = "";
        if (latestCompletedComp != null) {
            JSONObject typeObj = latestCompletedComp.optJSONObject("type");
            String compName = typeObj != null ? typeObj.optString("abbreviation", typeObj.optString("text", "")) : "";
            String prefix = normalizeSessionPrefix(compName);

            JSONArray competitors = latestCompletedComp.optJSONArray("competitors");
            if (competitors != null && competitors.length() > 0) {
                StringBuilder sb = new StringBuilder();
                if (!prefix.isEmpty()) {
                    sb.append(prefix).append(": ");
                }
                int count = Math.min(4, competitors.length());
                for (int c = 0; c < count; c++) {
                    JSONObject compObj = competitors.optJSONObject(c);
                    if (compObj == null) continue;
                    int order = compObj.optInt("order", c + 1);
                    JSONObject athlete = compObj.optJSONObject("athlete");
                    String driverName = "";
                    if (athlete != null) {
                        driverName = athlete.optString("shortName", athlete.optString("displayName", ""));
                    }
                    if (driverName.isEmpty()) {
                        driverName = compObj.optString("displayName", "Driver " + order);
                    }
                    if (c > 0) sb.append(" \u00B7 ");
                    sb.append("P").append(order).append(" ").append(driverName);
                }
                topDrivers = sb.toString();
            }
        }

        try {
            JSONObject raceObj = new JSONObject();
            raceObj.put("round", 0);
            raceObj.put("name", cleanedName);
            raceObj.put("place", venuePlace);
            raceObj.put("sport", "f1");
            raceObj.put("top_drivers", topDrivers);
            raceObj.put("is_postponed", false);
            raceObj.put("sessions", sessions);

            targetArray.put(raceObj);
            if (!eventId.isEmpty()) seenEvents.add("f1_" + eventId);
        } catch (Exception ignored) {}
    }

    private static String cleanGrandPrixName(String raw) {
        if (raw == null) return "Grand Prix";
        int gpIdx = raw.indexOf("Grand Prix");
        if (gpIdx > 0) {
            String sub = raw.substring(0, gpIdx + 10).trim();
            // Remove common sponsor prefixes
            String[] sponsors = {"MSC Cruises ", "Heineken ", "Aramco ", "Gulf Air ", "STC ", "Crypto.com ", "Lenovo ", "Qatar Airways ", "Pirelli "};
            for (String sp : sponsors) {
                if (sub.startsWith(sp)) {
                    sub = sub.substring(sp.length()).trim();
                }
            }
            return sub;
        }
        return raw.trim();
    }

    private static String normalizeSessionName(String raw) {
        if (raw == null) return "SESSION";
        String u = raw.trim().toUpperCase(Locale.ROOT);
        if (u.contains("QUAL")) return u.contains("SPRINT") ? "SPRINT QUALI" : "QUALIFYING";
        if (u.contains("SPRINT")) return "SPRINT";
        if (u.contains("RACE")) return "RACE";
        if (u.equals("FP1") || u.contains("PRACTICE 1")) return "FP1";
        if (u.equals("FP2") || u.contains("PRACTICE 2")) return "FP2";
        if (u.equals("FP3") || u.contains("PRACTICE 3")) return "FP3";
        return u;
    }

    private static String normalizeSessionPrefix(String raw) {
        if (raw == null) return "";
        String u = raw.trim().toUpperCase(Locale.ROOT);
        if (u.contains("QUAL")) return "Quali";
        if (u.contains("RACE")) return "Race";
        if (u.contains("SPRINT")) return "Sprint";
        if (u.equals("FP3")) return "FP3";
        if (u.equals("FP2")) return "FP2";
        if (u.equals("FP1")) return "FP1";
        return raw;
    }

    private static String fetchUrl(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android TV; CleanYourTv)");
            conn.setRequestProperty("Accept", "application/json");

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "HTTP " + code + " for " + urlStr);
                return null;
            }

            try (InputStream in = conn.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                return sb.toString();
            }
        } catch (Exception e) {
            Log.w(TAG, "Network error fetching " + urlStr + ": " + e.getMessage());
            return null;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }
}
