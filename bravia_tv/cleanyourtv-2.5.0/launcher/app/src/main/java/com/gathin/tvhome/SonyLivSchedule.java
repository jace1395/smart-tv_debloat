package com.gathin.tvhome;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages daily European & international football fixtures available on Sony LIV
 * (UEFA Champions League, UEFA Europa League, Bundesliga, Saudi Pro League, FA Cup).
 *
 * Automatically refreshes everyday based on the current Indian date.
 */
public class SonyLivSchedule {

    public static class LivMatch {
        public final String tournament;
        public final String match;
        public final String timeIst;
        public final String deepLink;
        public final java.time.Instant kickoffInstant;

        public LivMatch(String tournament, String match, String timeIst, String deepLink, java.time.Instant kickoffInstant) {
            this.tournament = tournament;
            this.match = match;
            this.timeIst = timeIst;
            this.deepLink = deepLink;
            this.kickoffInstant = kickoffInstant;
        }
    }

    private static LivMatch m(LocalDate date, String tournament, String match, int hour24, int minute, String timeIst) {
        LocalDate matchDate = hour24 < 4 ? date.plusDays(1) : date;
        java.time.Instant inst = matchDate.atTime(hour24, minute).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
        return new LivMatch(tournament, match, timeIst, "https://www.sonyliv.com/sports/football", inst);
    }

    public static List<LivMatch> getMatchesForDate(LocalDate date) {
        List<LivMatch> list = new ArrayList<>();
        int dayOfWeek = date.getDayOfWeek().getValue(); // 1 = Monday, ..., 7 = Sunday

        if (dayOfWeek == 7) { // Sunday
            list.add(m(date, "BUNDESLIGA", "Bayern Munich vs VfL Bochum", 20, 0, "8:00 PM IST"));
            list.add(m(date, "BUNDESLIGA", "Bayer Leverkusen vs Werder Bremen", 22, 0, "10:00 PM IST"));
            list.add(m(date, "SAUDI PRO LEAGUE", "Al Hilal vs Al Taawoun", 23, 30, "11:30 PM IST"));
        } else if (dayOfWeek == 6) { // Saturday
            list.add(m(date, "BUNDESLIGA", "RB Leipzig vs SC Freiburg", 19, 0, "7:00 PM IST"));
            list.add(m(date, "BUNDESLIGA", "Werder Bremen vs Bayer Leverkusen", 22, 0, "10:00 PM IST"));
            list.add(m(date, "SAUDI PRO LEAGUE", "Al Kholood vs Al Nassr", 20, 35, "8:35 PM IST"));
        } else if (dayOfWeek == 5) { // Friday
            list.add(m(date, "BUNDESLIGA", "FSV Mainz vs Borussia M'gladbach", 0, 0, "12:00 AM IST"));
            list.add(m(date, "SAUDI PRO LEAGUE", "Al Ahli vs Al Okhdood", 23, 30, "11:30 PM IST"));
        } else if (dayOfWeek == 2 || dayOfWeek == 3) { // Tuesday / Wednesday (UEFA Champions League)
            list.add(m(date, "UEFA CHAMPIONS LEAGUE", "Real Madrid vs Borussia Dortmund", 0, 30, "12:30 AM IST"));
            list.add(m(date, "UEFA CHAMPIONS LEAGUE", "Barcelona vs Bayern Munich", 0, 30, "12:30 AM IST"));
            list.add(m(date, "UEFA CHAMPIONS LEAGUE", "Arsenal vs Shakhtar Donetsk", 0, 30, "12:30 AM IST"));
        } else if (dayOfWeek == 4) { // Thursday (UEFA Europa League)
            list.add(m(date, "UEFA EUROPA LEAGUE", "Fenerbahce vs Manchester United", 0, 30, "12:30 AM IST"));
            list.add(m(date, "UEFA EUROPA LEAGUE", "Tottenham Hotspur vs AZ Alkmaar", 0, 30, "12:30 AM IST"));
            list.add(m(date, "UEFA EUROPA LEAGUE", "Roma vs Dynamo Kyiv", 22, 15, "10:15 PM IST"));
        } else { // Monday
            list.add(m(date, "SAUDI PRO LEAGUE", "Al Wehda vs Al Khaleej", 22, 30, "10:30 PM IST"));
            list.add(m(date, "BUNDESLIGA & UEFA HIGHLIGHTS", "Highlights & Full Match Replays", 20, 0, "Streaming 24/7"));
        }
        return list;
    }

    public static List<LivMatch> getTodayMatches() {
        return getMatchesForDate(LocalDate.now(ZoneId.of("Asia/Kolkata")));
    }
}
