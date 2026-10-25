package com.gathin.tvhome;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Race-weekend mode. A theme's config.json can carry an F1 calendar ({@code race_calendar}, written by
 * tools/race_calendar.py); from shortly before a weekend's first session until the race is over, Home shows
 * a small panel with the next session and a countdown. Everything here is the clock against data the owner
 * pushed: no network, no Android types, so it is tested on the JVM.
 */
final class RaceWeekend {

    /** The panel shows from this long before a weekend's first session... */
    static final Duration LEAD = Duration.ofHours(72);
    /** ...until this long after the race starts: a race runs about two hours, then the result settles. */
    static final Duration TAIL = Duration.ofHours(4);

    enum Status { DONE, LIVE, NEXT, LATER }

    static final class Session {
        /** As written in the calendar: FP1, FP2, FP3, SPRINT QUALI, SPRINT, QUALIFYING, RACE, MATCH or KICKOFF. */
        final String name;
        final Instant start;

        Session(String name, Instant start) {
            this.name = name.trim().toUpperCase(Locale.ROOT);
            this.start = start;
        }

        boolean isRace() {
            return name.equals("RACE") || name.equals("MATCH") || name.equals("KICKOFF");
        }

        boolean isMatch() {
            return name.equals("MATCH") || name.equals("KICKOFF");
        }

        /** Roughly how long the session is live; only used to tell live from done. */
        Duration length() {
            if (isRace()) return Duration.ofHours(2);
            if (name.equals("SPRINT QUALI")) return Duration.ofMinutes(45);
            return Duration.ofHours(1);
        }

        /** The name in the countdown line: "Qualifying in 2h 14m" or "Kickoff in 2h 14m". */
        String title() {
            switch (name) {
                case "FP1": return "Practice 1";
                case "FP2": return "Practice 2";
                case "FP3": return "Practice 3";
                case "SPRINT QUALI": return "Sprint qualifying";
                case "SPRINT": return "Sprint";
                case "QUALIFYING": return "Qualifying";
                case "RACE": return "Race";
                case "MATCH": return "Match";
                case "KICKOFF": return "Kickoff";
                default: return name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT);
            }
        }

        /** The name in the session row, kept short so a whole weekend fits on one line. */
        String shortName() {
            switch (name) {
                case "SPRINT QUALI": return "SQ";
                case "QUALIFYING": return "QUALI";
                case "KICKOFF": return "MATCH";
                case "MATCH": return "MATCH";
                default: return name;
            }
        }
    }

    static final class Race {
        final int round;
        final String name;
        final String place;
        final boolean isPostponed;
        /** In start order; the last one is the race. */
        final List<Session> sessions;

        Race(int round, String name, String place, List<Session> sessions) {
            this(round, name, place, false, sessions);
        }

        Race(int round, String name, String place, boolean isPostponed, List<Session> sessions) {
            this.round = round;
            this.name = name;
            this.place = place;
            this.isPostponed = isPostponed;
            List<Session> sorted = new ArrayList<>(sessions);
            sorted.sort((a, b) -> a.start.compareTo(b.start));
            this.sessions = Collections.unmodifiableList(sorted);
        }

        /** "United States Grand Prix" becomes "United States GP", which fits the panel's top line. */
        String shortName() {
            return name.replace("Grand Prix", "GP").trim();
        }
    }

    /** What the panel shows at one moment of a race weekend. */
    static final class State {
        final Race race;
        /** One per session of {@link #race}, same order. */
        final List<Status> statuses;
        /** The session on track now, or null. */
        final Session live;
        /** The next session to start, or null once the race has started. */
        final Session next;
        /** Time until {@link #next} starts; null when there is no next session. */
        final Duration untilNext;

        State(Race race, List<Status> statuses, Session live, Session next, Duration untilNext) {
            this.race = race;
            this.statuses = Collections.unmodifiableList(statuses);
            this.live = live;
            this.next = next;
            this.untilNext = untilNext;
        }

        /** The race is over and the panel is only showing the weekend until {@link #TAIL} runs out. */
        boolean finished() {
            return live == null && next == null;
        }
    }

    private RaceWeekend() {
    }

    /** The sports event (race or match) {@code now} falls in, prioritizing live sessions then nearest upcoming. */
    static State at(List<Race> calendar, Instant now) {
        State best = null;
        for (Race race : calendar) {
            if (race.isPostponed || race.sessions.isEmpty()) continue;
            Instant opens = race.sessions.get(0).start.minus(LEAD);
            Instant closes = race.sessions.get(race.sessions.size() - 1).start.plus(TAIL);
            if (now.isBefore(opens) || !now.isBefore(closes)) continue;

            List<Status> statuses = new ArrayList<>();
            Session live = null, next = null;
            for (Session s : race.sessions) {
                if (now.isBefore(s.start)) {
                    statuses.add(next == null ? Status.NEXT : Status.LATER);
                    if (next == null) next = s;
                } else if (now.isBefore(s.start.plus(s.length()))) {
                    statuses.add(Status.LIVE);
                    live = s;
                } else {
                    statuses.add(Status.DONE);
                }
            }
            State current = new State(race, statuses, live, next, next == null ? null : Duration.between(now, next.start));
            if (best == null) {
                best = current;
            } else if (current.live != null && best.live == null) {
                best = current;
            } else if (best.live == null) {
                if (current.next != null && (best.next == null || current.next.start.isBefore(best.next.start))) {
                    best = current;
                }
            }
        }
        return best;
    }

    /** Nearest upcoming or active sports event, ensuring tabs always display the next scheduled event. */
    static State upcoming(List<Race> calendar, Instant now) {
        Race nearest = null;
        for (Race r : calendar) {
            if (r.isPostponed || r.sessions.isEmpty()) continue;
            Instant end = r.sessions.get(r.sessions.size() - 1).start.plus(r.sessions.get(r.sessions.size() - 1).length());
            if (end.isAfter(now)) {
                if (nearest == null || r.sessions.get(0).start.isBefore(nearest.sessions.get(0).start)) {
                    nearest = r;
                }
            }
        }
        if (nearest == null && !calendar.isEmpty()) {
            for (int i = calendar.size() - 1; i >= 0; i--) {
                if (!calendar.get(i).isPostponed) {
                    nearest = calendar.get(i);
                    break;
                }
            }
            if (nearest == null) nearest = calendar.get(calendar.size() - 1);
        }
        if (nearest == null) return null;
        List<Status> statuses = new ArrayList<>();
        Session live = null, next = null;
        for (Session s : nearest.sessions) {
            if (now.isBefore(s.start)) {
                statuses.add(next == null ? Status.NEXT : Status.LATER);
                if (next == null) next = s;
            } else if (now.isBefore(s.start.plus(s.length()))) {
                statuses.add(Status.LIVE);
                live = s;
            } else {
                statuses.add(Status.DONE);
            }
        }
        return new State(nearest, statuses, live, next, next == null ? null : Duration.between(now, next.start));
    }

    /**
     * "42 min", "2h 14m", "1d 3h". Rounded up to the minute, so a session 20 seconds away still reads
     * "1 min" and the countdown never says 0 before the session is live.
     */
    static String countdown(Duration d) {
        long minutes = Math.max(1, (d.getSeconds() + 59) / 60);
        if (minutes < 60) return minutes + " min";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h " + (minutes % 60) + "m";
        return (hours / 24) + "d " + (hours % 24) + "h";
    }
}
