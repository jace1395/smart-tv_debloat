package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FootballScheduleServiceTest {

    @Test
    public void tierPriorityClassification() {
        assertEquals(FootballScheduleService.TIER_ISL, FootballScheduleService.classifyTier(
                true, "Mohun Bagan Super Giant", "Kerala Blasters",
                "Mohun Bagan Super Giant vs Kerala Blasters", "ind.1", ""));

        assertEquals(FootballScheduleService.TIER_ISL, FootballScheduleService.classifyTier(
                false, "East Bengal FC", "Bengaluru FC",
                "East Bengal FC vs Bengaluru FC", "", ""));

        assertEquals(FootballScheduleService.TIER_INDIA, FootballScheduleService.classifyTier(
                false, "India", "Maldives",
                "India vs Maldives", "group-stage", "s:600~l:18914~e:401932726"));

        assertEquals(FootballScheduleService.TIER_INDIA, FootballScheduleService.classifyTier(
                false, "New Zealand", "India",
                "New Zealand vs India", "2026-international-friendly", ""));

        assertEquals(FootballScheduleService.TIER_OTHER_FOOTBALL, FootballScheduleService.classifyTier(
                false, "Bangladesh", "Sri Lanka",
                "Bangladesh vs Sri Lanka", "group-stage", "s:600~l:18914~e:401932727"));
    }

    @Test
    public void substringFalsePositivesExcluded() {
        assertFalse(FootballScheduleService.isIndiaTeam("Independiente Petrolero"));
        assertFalse(FootballScheduleService.isIndiaTeam("Indiana Hoosiers"));
        assertTrue(FootballScheduleService.isIndiaTeam("India"));
        assertTrue(FootballScheduleService.isIndiaTeam("India Women"));

        assertFalse(FootballScheduleService.isIslText("Carlisle United vs Isle of Man"));
        assertFalse(FootballScheduleService.isIslText("Iceland vs Israel"));
        assertTrue(FootballScheduleService.isIslText("Football || ISL Live"));
        assertTrue(FootballScheduleService.isIslText("Mumbai City vs FC Goa"));
    }

    @Test
    public void kickoffWindowOnlyMatchesLiveOrActivePre() {
        long kickoff = 1_700_000_000_000L;
        FootballScheduleService.FootballMatch preIndia = new FootballScheduleService.FootballMatch(
                "1", "India vs Maldives", "India", "Maldives", "group-stage",
                kickoff, "pre", FootballScheduleService.TIER_INDIA, 4);

        assertFalse(preIndia.isWithinKickoffWindow(kickoff - 300_000L)); // 5 min early
        assertTrue(preIndia.isWithinKickoffWindow(kickoff + 120_000L));  // 2 min after kickoff
        assertFalse(preIndia.isWithinKickoffWindow(kickoff + 130 * 60_000L)); // way past match

        FootballScheduleService.FootballMatch postIndia = new FootballScheduleService.FootballMatch(
                "1", "India vs Maldives", "India", "Maldives", "group-stage",
                kickoff, "post", FootballScheduleService.TIER_INDIA, 4);
        assertFalse(postIndia.isWithinKickoffWindow(kickoff + 120_000L));
    }

    @Test
    public void parseIsoTimeSupportsMinutePrecisionAndOffsets() {
        long tShort = FootballScheduleService.parseIsoTime("2026-11-11T09:30Z");
        long tFull = FootballScheduleService.parseIsoTime("2026-11-11T09:30:00Z");
        long tIst = FootballScheduleService.parseIsoTime("2026-11-11T15:00:00+05:30");
        assertTrue(tShort > 0L);
        assertEquals(tFull, tShort);
        assertEquals(tFull, tIst);
        assertEquals(0L, FootballScheduleService.parseIsoTime("invalid"));
    }
}

