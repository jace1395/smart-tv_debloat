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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
import java.util.regex.Pattern;

/**
 * Fetches live & scheduled football matches across ISL, Indian National Team (SAFF/FIFA/AFC),
 * and global pro football competitions using ESPN public scoreboards, ranking them strictly by:
 *   Tier 1: ISL teams
 *   Tier 2: Indian football team
 *   Tier 3: Other football teams (continuous queue)
 */
public final class FootballScheduleService {
    private static final String TAG = "FootballScheduleSvc";

    public static final int TIER_ISL = 1;
    public static final int TIER_INDIA = 2;
    public static final int TIER_OTHER_FOOTBALL = 3;

    private static final String ISL_SCOREBOARD =
            "https://site.api.espn.com/apis/site/v2/sports/soccer/ind.1/scoreboard";
    private static final String ALL_SCOREBOARD_FMT =
            "https://site.api.espn.com/apis/site/v2/sports/soccer/all/scoreboard?dates=%s";

    public static final long PRE_KICKOFF_LEAD_MS = 7 * 60 * 1000L; // Exactly 7 minutes before kickoff
    public static final long MATCH_WINDOW_MS = 115L * 60L * 1000L; // ~115 mins from kickoff
    private static final Pattern INDIA_WORD_PATTERN =
            Pattern.compile("\\bindia(n)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ISL_WORD_PATTERN =
            Pattern.compile("\\bisl\\b", Pattern.CASE_INSENSITIVE);

    private static final String[] ISL_TEAM_KEYWORDS = {
            "mohun bagan", "east bengal", "mumbai city", "kerala blasters",
            "bengaluru", "fc goa", "odisha", "northeast united", "jamshedpur",
            "chennaiyin", "punjab", "mohammedan", "inter kashi", "sporting club delhi",
            "hyderabad", "indian super league"
    };

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    public static final class FootballMatch {
        public final String eventId;
        public final String title;
        public final String homeTeam;
        public final String awayTeam;
        public final String competitionHint;
        public final long startTimeMs;
        public final String state; // "pre", "in", "post"
        public final int priorityTier;
        public final int competitionRank; // higher = major league/international

        public FootballMatch(String eventId, String title, String homeTeam, String awayTeam,
                             String competitionHint, long startTimeMs, String state,
                             int priorityTier, int competitionRank) {
            this.eventId = eventId;
            this.title = title;
            this.homeTeam = homeTeam;
            this.awayTeam = awayTeam;
            this.competitionHint = competitionHint;
            this.startTimeMs = startTimeMs;
            this.state = state;
            this.priorityTier = priorityTier;
            this.competitionRank = competitionRank;
        }

        public static final long PRE_KICKOFF_LEAD_MS = 7 * 60 * 1000L; // 7 minutes before kickoff
        public static final long MATCH_DURATION_MS = 115L * 60L * 1000L; // ~115 mins match duration

        public String queueKey() {
            return "T" + priorityTier + ":" + title.toLowerCase(Locale.ROOT).trim();
        }

        public boolean isExplicitlyLive() {
            return "in".equalsIgnoreCase(state);
        }

        public boolean isFinished() {
            return "post".equalsIgnoreCase(state);
        }

        /**
         * Match is in the active streaming window:
         * Starts exactly 7 minutes before kickoff and stays eligible until full match duration ends.
         */
        public boolean isEligibleToStream(long nowMs) {
            if (isFinished()) return false;
            if (isExplicitlyLive()) return true;
            if (startTimeMs <= 0) return false;
            return nowMs >= (startTimeMs - PRE_KICKOFF_LEAD_MS)
                    && nowMs <= (startTimeMs + MATCH_DURATION_MS);
        }

        /**
         * Some regional competitions (such as SAFF Championship) may keep ESPN state="pre"
         * right up to or past kickoff while streaming live on Sportzfy/FanCode.
         */
        public boolean isWithinKickoffWindow(long nowMs) {
            if (isFinished()) return false;
            if (isExplicitlyLive()) return true;
            if (startTimeMs <= 0) return false;
            return "pre".equalsIgnoreCase(state)
                    && nowMs >= (startTimeMs - PRE_KICKOFF_LEAD_MS)
                    && nowMs <= (startTimeMs + MATCH_DURATION_MS);
        }
    }

    public interface QueueCallback {
        void onQueueUpdated(List<FootballMatch> matches);
    }

    private FootballScheduleService() {}

    public static void fetchPriorityQueueAsync(QueueCallback callback) {
        if (callback == null) return;
        EXECUTOR.execute(() -> {
            List<FootballMatch> result = fetchPriorityQueueSync();
            new Handler(Looper.getMainLooper()).post(() -> callback.onQueueUpdated(result));
        });
    }

    public static List<FootballMatch> fetchPriorityQueueSync() {
        Map<String, FootballMatch> byId = new LinkedHashMap<>();

        // 1. Authoritative ISL endpoint (Tier 1)
        fetchUrlIntoMap(ISL_SCOREBOARD, true, byId);

        // 2. Global soccer scoreboard for yesterday + today IST (captures late UTC + today's matches)
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        LocalDate todayIst = LocalDate.now(ist);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US);
        fetchUrlIntoMap(String.format(Locale.US, ALL_SCOREBOARD_FMT, todayIst.minusDays(1).format(fmt)), false, byId);
        fetchUrlIntoMap(String.format(Locale.US, ALL_SCOREBOARD_FMT, todayIst.format(fmt)), false, byId);

        List<FootballMatch> list = new ArrayList<>(byId.values());
        long now = System.currentTimeMillis();
        Collections.sort(list, (a, b) -> {
            if (a.priorityTier != b.priorityTier) {
                return Integer.compare(a.priorityTier, b.priorityTier);
            }
            boolean aLive = a.isExplicitlyLive();
            boolean bLive = b.isExplicitlyLive();
            if (aLive != bLive) return aLive ? -1 : 1;

            boolean aWin = a.isWithinKickoffWindow(now);
            boolean bWin = b.isWithinKickoffWindow(now);
            if (aWin != bWin) return aWin ? -1 : 1;

            if (a.competitionRank != b.competitionRank) {
                return Integer.compare(b.competitionRank, a.competitionRank);
            }
            return Long.compare(a.startTimeMs, b.startTimeMs);
        });
        return list;
    }

    private static void fetchUrlIntoMap(String urlStr, boolean forceIsl, Map<String, FootballMatch> out) {
        String body = httpGet(urlStr);
        if (body == null || body.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(body);
            JSONArray events = root.optJSONArray("events");
            if (events == null) return;

            for (int i = 0; i < events.length(); i++) {
                JSONObject ev = events.optJSONObject(i);
                if (ev == null) continue;

                String eventId = ev.optString("id", "");
                String dateStr = ev.optString("date", "");
                long startMs = parseIsoTime(dateStr);

                JSONObject season = ev.optJSONObject("season");
                String seasonSlug = season != null ? season.optString("slug", "").toLowerCase(Locale.ROOT) : "";
                if (isExcludedCollegeEvent(seasonSlug)) {
                    continue;
                }

                JSONArray comps = ev.optJSONArray("competitions");
                if (comps == null || comps.length() == 0) continue;
                JSONObject comp = comps.optJSONObject(0);
                if (comp == null) continue;

                JSONArray competitors = comp.optJSONArray("competitors");
                if (competitors == null || competitors.length() < 2) continue;

                String home = "";
                String away = "";
                for (int c = 0; c < competitors.length(); c++) {
                    JSONObject teamEntry = competitors.optJSONObject(c);
                    if (teamEntry == null) continue;
                    JSONObject teamObj = teamEntry.optJSONObject("team");
                    String teamName = teamObj != null ? teamObj.optString("displayName", "") : "";
                    if ("home".equalsIgnoreCase(teamEntry.optString("homeAway", ""))) {
                        home = teamName;
                    } else if ("away".equalsIgnoreCase(teamEntry.optString("homeAway", ""))) {
                        away = teamName;
                    } else if (home.isEmpty()) {
                        home = teamName;
                    } else {
                        away = teamName;
                    }
                }
                if (home.isEmpty() || away.isEmpty()) continue;

                JSONObject status = ev.optJSONObject("status");
                JSONObject statusType = status != null ? status.optJSONObject("type") : null;
                String state = statusType != null ? statusType.optString("state", "pre") : "pre";

                String uid = ev.optString("uid", "");
                String title = home + " vs " + away;
                int tier = classifyTier(forceIsl, home, away, title, seasonSlug, uid);
                int rank = scoreCompetitionRank(seasonSlug, uid, home, away);

                FootballMatch match = new FootballMatch(
                        eventId.isEmpty() ? title : eventId,
                        title, home, away, seasonSlug, startMs, state, tier, rank);

                FootballMatch prev = out.get(match.eventId);
                if (prev == null || match.priorityTier < prev.priorityTier || (match.isExplicitlyLive() && !prev.isExplicitlyLive())) {
                    out.put(match.eventId, match);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed parsing scoreboard " + urlStr + ": " + e.getMessage());
        }
    }

    private static boolean isExcludedCollegeEvent(String seasonSlug) {
        return seasonSlug.contains("ncaa")
                || seasonSlug.contains("college")
                || seasonSlug.contains("naia")
                || seasonSlug.contains("club-friendly");
    }

    public static int classifyTier(boolean forceIsl, String home, String away,
                                   String title, String compHint, String uid) {
        String joined = (home + " " + away + " " + title + " " + compHint).toLowerCase(Locale.ROOT);
        if (forceIsl) return TIER_ISL;
        for (String club : ISL_TEAM_KEYWORDS) {
            if (joined.contains(club)) return TIER_ISL;
        }
        if (isIndiaTeam(home) || isIndiaTeam(away) || INDIA_WORD_PATTERN.matcher(title).find()) {
            return TIER_INDIA;
        }
        if (uid != null && uid.contains("~l:18914~") && INDIA_WORD_PATTERN.matcher(joined).find()) {
            return TIER_INDIA;
        }
        return TIER_OTHER_FOOTBALL;
    }

    public static boolean isIslText(String text) {
        if (text == null || text.isEmpty()) return false;
        if (ISL_WORD_PATTERN.matcher(text).find()) {
            return true;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String club : ISL_TEAM_KEYWORDS) {
            if (lower.contains(club)) return true;
        }
        return false;
    }

    public static boolean isIndiaTeam(String text) {
        if (text == null || text.isEmpty()) return false;
        return INDIA_WORD_PATTERN.matcher(text).find();
    }

    private static int scoreCompetitionRank(String seasonSlug, String uid, String home, String away) {
        // Prioritize SAFF (l:18914), FIFA friendlies (international-friendly), UEFA (champions/women), major leagues
        if (uid != null && uid.contains("~l:18914~")) return 4; // SAFF Championship
        if (seasonSlug.contains("international-friendly") || seasonSlug.contains("world-cup")
                || seasonSlug.contains("asian-cup") || seasonSlug.contains("champions")) {
            return 3;
        }
        if (seasonSlug.contains("league-phase") || seasonSlug.contains("round-of-16")
                || seasonSlug.contains("group-stage") || seasonSlug.contains("quarterfinals")
                || seasonSlug.contains("semifinals") || seasonSlug.contains("final")) {
            return 2;
        }
        return 1;
    }

    static long parseIsoTime(String iso) {
        if (iso == null || iso.isEmpty()) return 0L;
        try {
            return OffsetDateTime.parse(iso, DateTimeFormatter.ISO_DATE_TIME).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            try {
                return Instant.parse(iso).toEpochMilli();
            } catch (Exception e) {
                return 0L;
            }
        }
    }

    private static String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (conn.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "HTTP GET failed " + urlStr + ": " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
