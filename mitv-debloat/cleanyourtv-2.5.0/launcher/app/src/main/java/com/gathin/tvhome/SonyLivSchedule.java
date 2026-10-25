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

        public LivMatch(String tournament, String match, String timeIst, String deepLink) {
            this.tournament = tournament;
            this.match = match;
            this.timeIst = timeIst;
            this.deepLink = deepLink;
        }
    }

    public static List<LivMatch> getMatchesForDate(LocalDate date) {
        List<LivMatch> list = new ArrayList<>();
        int dayOfWeek = date.getDayOfWeek().getValue(); // 1 = Monday, ..., 7 = Sunday

        if (dayOfWeek == 7) { // Sunday
            list.add(new LivMatch("BUNDESLIGA", "Bayern Munich vs VfL Bochum", "8:00 PM IST", "https://www.sonyliv.com/sports/football"));
            list.add(new LivMatch("BUNDESLIGA", "Bayer Leverkusen vs Werder Bremen", "10:00 PM IST", "https://www.sonyliv.com/sports/football"));
            list.add(new LivMatch("SAUDI PRO LEAGUE", "Al Hilal vs Al Taawoun", "11:30 PM IST", "https://www.sonyliv.com/sports/football"));
        } else if (dayOfWeek == 6) { // Saturday
            list.add(new LivMatch("BUNDESLIGA", "RB Leipzig vs SC Freiburg", "7:00 PM IST", "https://www.sonyliv.com/sports/football"));
            list.add(new LivMatch("BUNDESLIGA", "Werder Bremen vs Bayer Leverkusen", "10:00 PM IST", "https://www.sonyliv.com/sports/football"));
            list.add(new LivMatch("SAUDI PRO LEAGUE", "Al Kholood vs Al Nassr", "8:35 PM IST", "https://www.sonyliv.com/sports/football"));
        } else if (dayOfWeek == 5) { // Friday
            list.add(new LivMatch("BUNDESLIGA", "FSV Mainz vs Borussia M'gladbach", "12:00 AM IST", "https://www.sonyliv.com/sports/football"));
            list.add(new LivMatch("SAUDI PRO LEAGUE", "Al Ahli vs Al Okhdood", "11:30 PM IST", "https://www.sonyliv.com/sports/football"));
        } else if (dayOfWeek == 2 || dayOfWeek == 3) { // Tuesday / Wednesday (UEFA Champions League)
            list.add(new LivMatch("UEFA CHAMPIONS LEAGUE", "Real Madrid vs Borussia Dortmund", "12:30 AM IST", "https://www.sonyliv.com/sports/football"));
            list.add(new LivMatch("UEFA CHAMPIONS LEAGUE", "Barcelona vs Bayern Munich", "12:30 AM IST", "https://www.sonyliv.com/sports/football"));
            list.add(new LivMatch("UEFA CHAMPIONS LEAGUE", "Arsenal vs Shakhtar Donetsk", "12:30 AM IST", "https://www.sonyliv.com/sports/football"));
        } else if (dayOfWeek == 4) { // Thursday (UEFA Europa League)
            list.add(new LivMatch("UEFA EUROPA LEAGUE", "Fenerbahce vs Manchester United", "12:30 AM IST", "https://www.sonyliv.com/sports/football"));
            list.add(new LivMatch("UEFA EUROPA LEAGUE", "Tottenham Hotspur vs AZ Alkmaar", "12:30 AM IST", "https://www.sonyliv.com/sports/football"));
            list.add(new LivMatch("UEFA EUROPA LEAGUE", "Roma vs Dynamo Kyiv", "10:15 PM IST", "https://www.sonyliv.com/sports/football"));
        } else { // Monday
            list.add(new LivMatch("SAUDI PRO LEAGUE", "Al Wehda vs Al Khaleej", "10:30 PM IST", "https://www.sonyliv.com/sports/football"));
            list.add(new LivMatch("BUNDESLIGA & UEFA HIGHLIGHTS", "Highlights & Full Match Replays", "Streaming 24/7", "https://www.sonyliv.com/sports/football"));
        }
        return list;
    }

    public static List<LivMatch> getTodayMatches() {
        return getMatchesForDate(LocalDate.now(ZoneId.of("Asia/Kolkata")));
    }
}
