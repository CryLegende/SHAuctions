package org.Cry_Legende.sHAuctions.serverauction;

import org.Cry_Legende.sHAuctions.serverauction.ServerAuctionPlanner.Settings;
import org.Cry_Legende.sHAuctions.serverauction.ServerAuctionPlanner.Slot;
import org.Cry_Legende.sHAuctions.serverauction.ServerAuctionSchedule.Entry;
import org.Cry_Legende.sHAuctions.serverauction.ServerAuctionSchedule.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerAuctionScheduleTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    /** Lundi 9 novembre 2026, 00:00. */
    private static final ZonedDateTime MONDAY = LocalDate.of(2026, 11, 9).atStartOfDay(ZONE);
    private static final Settings DEFAULTS = new Settings(7, LocalTime.of(12, 0), LocalTime.of(23, 30), 240, 180, 360);

    private static LocalDateTime local(long epoch) {
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epoch), ZONE);
    }

    private static void assertInWindow(Settings settings, long start) {
        LocalTime time = local(start).toLocalTime();
        assertFalse(time.isBefore(settings.windowStart()), "début avant la plage : " + local(start));
        assertTrue(time.isBefore(settings.windowEnd()), "début après la plage : " + local(start));
    }

    @Test
    void fullWeekHasTheWeeklyCountInsideTheWindowWithDurationsInRange() {
        for (int seed = 0; seed < 200; seed++) {
            List<Slot> slots = ServerAuctionPlanner.plan(DEFAULTS, MONDAY, MONDAY.toEpochSecond(), 7, List.of(), new Random(seed));
            assertEquals(7, slots.size());
            for (Slot slot : slots) {
                assertInWindow(DEFAULTS, slot.start());
                assertTrue(slot.start() >= MONDAY.toEpochSecond() && slot.start() < MONDAY.plusWeeks(1).toEpochSecond());
                assertEquals(0, slot.start() % 60, "début sur une minute ronde");
                assertTrue(slot.durationSeconds() >= 180 * 60 && slot.durationSeconds() <= 360 * 60);
            }
        }
    }

    @Test
    void auctionsAreSpreadOverDaysAndTimesOfDay() {
        int bandMinutes = DEFAULTS.windowMinutes() / 7;
        for (int seed = 0; seed < 200; seed++) {
            List<Slot> slots = ServerAuctionPlanner.plan(DEFAULTS, MONDAY, MONDAY.toEpochSecond(), 7, List.of(), new Random(seed));
            Set<LocalDate> days = new HashSet<>();
            Set<Integer> bands = new HashSet<>();
            for (Slot slot : slots) {
                LocalDateTime time = local(slot.start());
                days.add(time.toLocalDate());
                int minutesIntoWindow = (time.getHour() * 60 + time.getMinute()) - 12 * 60;
                bands.add(Math.min(6, minutesIntoWindow / (bandMinutes + 1)));
            }
            assertEquals(7, days.size(), "une enchère par jour quand il y en a 7");
            assertTrue(bands.size() >= 6, "moments de la journée variés, seed " + seed + " : " + bands);
        }
    }

    @Test
    void minimumGapIsKeptWhenSeveralAuctionsShareADay() {
        Settings busy = new Settings(20, LocalTime.of(12, 0), LocalTime.of(23, 30), 120, 60, 120);
        for (int seed = 0; seed < 200; seed++) {
            List<Slot> slots = ServerAuctionPlanner.plan(busy, MONDAY, MONDAY.toEpochSecond(), 20, List.of(), new Random(seed));
            assertEquals(20, slots.size());
            for (int i = 1; i < slots.size(); i++) {
                assertTrue(slots.get(i).start() - slots.get(i - 1).start() >= 120 * 60, "écart minimal, seed " + seed);
            }
            slots.forEach(slot -> assertInWindow(busy, slot.start()));
        }
    }

    @Test
    void impossibleGapGivesFewerAuctionsNeverACloserOne() {
        Settings tight = new Settings(30, LocalTime.of(12, 0), LocalTime.of(23, 30), 600, 60, 60);
        List<Slot> slots = ServerAuctionPlanner.plan(tight, MONDAY, MONDAY.toEpochSecond(), 30, List.of(), new Random(1));
        assertFalse(slots.isEmpty());
        assertTrue(slots.size() <= 14);
        for (int i = 1; i < slots.size(); i++) {
            assertTrue(slots.get(i).start() - slots.get(i - 1).start() >= 600 * 60);
        }
    }

    @Test
    void windowCrossingMidnightIsSupported() {
        Settings night = new Settings(7, LocalTime.of(20, 0), LocalTime.of(2, 0), 240, 60, 60);
        List<Slot> slots = ServerAuctionPlanner.plan(night, MONDAY, MONDAY.toEpochSecond(), 7, List.of(), new Random(3));
        assertEquals(7, slots.size());
        for (Slot slot : slots) {
            LocalTime time = local(slot.start()).toLocalTime();
            assertTrue(!time.isBefore(LocalTime.of(20, 0)) || time.isBefore(LocalTime.of(2, 0)), "hors plage : " + time);
        }
    }

    @Test
    void firstWeekStartedOnThursdayGetsItsShareOnly() {
        ZonedDateTime thursday = MONDAY.plusDays(3).withHour(6);
        assertEquals(4, ServerAuctionPlanner.countFor(DEFAULTS, MONDAY, thursday.toEpochSecond()));
        ServerAuctionSchedule schedule = new ServerAuctionSchedule();
        assertTrue(schedule.refresh(DEFAULTS, thursday, new Random(5)));
        assertEquals(4, schedule.entries().size());
        for (Entry entry : schedule.entries()) {
            assertTrue(entry.start() > thursday.toEpochSecond());
            assertEquals(Status.PREVUE, entry.status());
        }
    }

    @Test
    void planIsKeptAcrossRestartAndRedoneTheNextWeek(@TempDir File dir) throws Exception {
        File file = new File(dir, "encheres-serveur.yml");
        ZonedDateTime monday = MONDAY.plusMinutes(1);
        ServerAuctionSchedule schedule = new ServerAuctionSchedule();
        assertTrue(schedule.refresh(DEFAULTS, monday, new Random(7)));
        assertEquals(7, schedule.entries().size());
        Entry first = schedule.entries().get(0);
        first.launched("cle-ordinaire", 42);
        schedule.skipNext();
        schedule.addForced(first.start() + 60, 120, "cle-rare", 43);
        schedule.save(file);

        // Redémarrage le jeudi à 06:00 : même planning, rien n'est retiré
        ServerAuctionSchedule reloaded = ServerAuctionSchedule.load(file);
        ZonedDateTime thursday = MONDAY.plusDays(3).withHour(6);
        assertFalse(reloaded.refresh(DEFAULTS, thursday, new Random(99)));
        assertEquals(schedule.entries().size(), reloaded.entries().size());
        for (int i = 0; i < schedule.entries().size(); i++) {
            Entry a = schedule.entries().get(i);
            Entry b = reloaded.entries().get(i);
            assertEquals(a.start(), b.start());
            assertEquals(a.duration(), b.duration());
            assertEquals(a.status(), b.status());
            assertEquals(a.lot(), b.lot());
            assertEquals(a.auctionId(), b.auctionId());
            assertEquals(a.forced(), b.forced());
        }
        assertEquals(1, reloaded.entries().stream().filter(e -> e.status() == Status.ANNULEE).count());

        // Semaine suivante : nouveau planning complet
        assertTrue(reloaded.refresh(DEFAULTS, MONDAY.plusWeeks(1).plusMinutes(1), new Random(8)));
        assertEquals("2026-11-16", reloaded.week());
        assertEquals(7, reloaded.entries().size());
        assertTrue(reloaded.entries().stream().allMatch(e -> e.status() == Status.PREVUE && !e.forced()));
    }

    @Test
    void changedSettingsKeepWhatAlreadyHappenedAndNeverExceedTheWeeklyCount() {
        ServerAuctionSchedule schedule = new ServerAuctionSchedule();
        schedule.refresh(DEFAULTS, MONDAY.plusMinutes(1), new Random(11));
        // Les trois premières ont eu lieu, on est le jeudi midi moins une
        List<Entry> entries = schedule.entries();
        ZonedDateTime thursday = MONDAY.plusDays(3).withHour(11).withMinute(59);
        int happened = 0;
        for (Entry entry : entries) {
            if (entry.start() < thursday.toEpochSecond()) {
                entry.launched("cle-ordinaire", 100 + happened);
                happened++;
            }
        }
        Settings more = new Settings(10, LocalTime.of(12, 0), LocalTime.of(23, 30), 240, 180, 360);
        assertTrue(schedule.refresh(more, thursday, new Random(12)));
        long launched = schedule.entries().stream().filter(e -> e.status() == Status.LANCEE).count();
        long planned = schedule.entries().stream().filter(e -> e.status() == Status.PREVUE).count();
        assertEquals(happened, launched);
        assertTrue(launched + planned <= 10);
        assertTrue(planned >= 1);
        assertTrue(schedule.entries().stream().filter(e -> e.status() == Status.PREVUE).allMatch(e -> e.start() >= thursday.toEpochSecond()));
        assertFalse(schedule.refresh(more, thursday.plusHours(1), new Random(13)), "mêmes réglages : planning inchangé");
    }

    @Test
    void lateStartIsLaunchedInsideItsWindowAndMissedAfterIt() {
        ServerAuctionSchedule schedule = new ServerAuctionSchedule();
        schedule.refresh(DEFAULTS, MONDAY.plusMinutes(1), new Random(21));
        Entry first = schedule.entries().get(0);
        ZonedDateTime start = Instant.ofEpochSecond(first.start()).atZone(ZONE);

        assertTrue(schedule.due(DEFAULTS, start.minusMinutes(1)).toLaunch().isEmpty());
        assertEquals(List.of(first), schedule.due(DEFAULTS, start.plusMinutes(5)).toLaunch());

        // Serveur éteint jusqu'au lendemain 06:00 : la plage de ce jour est finie, enchère manquée
        ServerAuctionSchedule.Due late = schedule.due(DEFAULTS, start.toLocalDate().plusDays(1).atTime(6, 0).atZone(ZONE));
        assertTrue(late.toLaunch().isEmpty());
        assertEquals(List.of(first), late.missed());
        assertEquals(Status.MANQUEE, first.status());
    }

    @Test
    void skipRemovesTheNextPlannedOneOnly() {
        ServerAuctionSchedule schedule = new ServerAuctionSchedule();
        schedule.refresh(DEFAULTS, MONDAY.plusMinutes(1), new Random(31));
        Entry next = schedule.next();
        assertNotNull(next);
        assertEquals(next, schedule.skipNext());
        assertEquals(Status.ANNULEE, next.status());
        assertEquals(6, schedule.entries().stream().filter(e -> e.status() == Status.PREVUE).count());
    }

    @Test
    void weeklyWinCapCountsWinsAndAuctionsAlreadyLed() {
        assertTrue(ServerAuctionPlanner.mayBid(0, 0, 2));
        assertTrue(ServerAuctionPlanner.mayBid(1, 0, 2));
        assertTrue(ServerAuctionPlanner.mayBid(0, 1, 2));
        assertFalse(ServerAuctionPlanner.mayBid(1, 1, 2));
        assertFalse(ServerAuctionPlanner.mayBid(2, 0, 2));
        assertFalse(ServerAuctionPlanner.mayBid(0, 2, 2));
        assertTrue(ServerAuctionPlanner.mayBid(10, 10, 0), "0 = sans limite");
    }
}
