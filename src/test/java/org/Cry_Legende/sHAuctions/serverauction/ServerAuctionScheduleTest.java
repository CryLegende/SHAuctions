package org.Cry_Legende.sHAuctions.serverauction;

import org.Cry_Legende.sHAuctions.serverauction.ServerAuctionPlanner.Settings;
import org.Cry_Legende.sHAuctions.serverauction.ServerAuctionSchedule.Entry;
import org.Cry_Legende.sHAuctions.serverauction.ServerAuctionSchedule.Kind;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private static final Settings AUCTIONS = new Settings(1, 3, LocalTime.of(12, 0), LocalTime.of(23, 30), 180, 120, 240);
    private static final Settings SALES = new Settings(1, 3, LocalTime.of(12, 0), LocalTime.of(23, 30), 60, 360, 720);

    private static LocalDateTime local(long epoch) {
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epoch), ZONE);
    }

    private static List<Entry> of(ServerAuctionSchedule schedule, Kind kind) {
        return schedule.entries().stream().filter(e -> e.kind() == kind).toList();
    }

    private static void assertInWindow(long start) {
        LocalTime time = local(start).toLocalTime();
        assertFalse(time.isBefore(LocalTime.of(12, 0)), "début avant la plage : " + local(start));
        assertTrue(time.isBefore(LocalTime.of(23, 30)), "début après la plage : " + local(start));
    }

    @Test
    void eachDayGetsOneToThreeAuctionsAndSalesInsideTheWindowKeepingTheGaps() {
        for (int seed = 0; seed < 300; seed++) {
            ServerAuctionSchedule schedule = new ServerAuctionSchedule();
            assertTrue(schedule.refresh(AUCTIONS, SALES, MONDAY, new Random(seed)));
            for (Kind kind : Kind.values()) {
                Map<LocalDate, Integer> perDay = new HashMap<>();
                for (Entry entry : of(schedule, kind)) {
                    assertInWindow(entry.start());
                    assertEquals(0, entry.start() % 60, "début sur une minute ronde");
                    Settings settings = kind == Kind.ENCHERE ? AUCTIONS : SALES;
                    assertTrue(entry.duration() >= settings.minDurationMinutes() * 60L && entry.duration() <= settings.maxDurationMinutes() * 60L);
                    perDay.merge(local(entry.start()).toLocalDate(), 1, Integer::sum);
                }
                assertEquals(7, perDay.size(), kind + " : tous les jours de la semaine, seed " + seed);
                for (int n : perDay.values()) {
                    assertTrue(n >= 1 && n <= 3, kind + " : " + n + " par jour, seed " + seed);
                }
            }
            List<Long> auctions = of(schedule, Kind.ENCHERE).stream().map(Entry::start).toList();
            List<Long> sales = of(schedule, Kind.VENTE).stream().map(Entry::start).toList();
            assertTrue(ServerAuctionPlanner.respectsGap(auctions, 180), "écart entre enchères, seed " + seed);
            assertTrue(ServerAuctionPlanner.respectsGap(sales, 60), "écart entre ventes, seed " + seed);
            for (long sale : sales) {
                for (long auction : auctions) {
                    assertTrue(Math.abs(sale - auction) >= 60 * 60, "une vente tombe sur une enchère, seed " + seed);
                }
            }
        }
    }

    @Test
    void countsAndTimesChangeFromDayToDay() {
        ServerAuctionSchedule schedule = new ServerAuctionSchedule();
        schedule.refresh(AUCTIONS, null, MONDAY, new Random(4));
        Set<Integer> counts = new HashSet<>();
        Set<Integer> hours = new HashSet<>();
        Map<LocalDate, Integer> perDay = new HashMap<>();
        for (Entry entry : schedule.entries()) {
            perDay.merge(local(entry.start()).toLocalDate(), 1, Integer::sum);
            hours.add(local(entry.start()).getHour());
        }
        counts.addAll(perDay.values());
        assertTrue(counts.size() >= 2, "nombre par jour variable : " + perDay);
        assertTrue(hours.size() >= 5, "heures variées : " + hours);
    }

    @Test
    void disabledKindIsNotPlanned() {
        ServerAuctionSchedule schedule = new ServerAuctionSchedule();
        schedule.refresh(null, SALES, MONDAY, new Random(2));
        assertTrue(of(schedule, Kind.ENCHERE).isEmpty());
        assertFalse(of(schedule, Kind.VENTE).isEmpty());
    }

    @Test
    void windowCrossingMidnightIsSupported() {
        Settings night = new Settings(1, 3, LocalTime.of(20, 0), LocalTime.of(2, 0), 90, 60, 60);
        ServerAuctionSchedule schedule = new ServerAuctionSchedule();
        schedule.refresh(night, null, MONDAY, new Random(3));
        assertFalse(schedule.entries().isEmpty());
        for (Entry entry : schedule.entries()) {
            LocalTime time = local(entry.start()).toLocalTime();
            assertTrue(!time.isBefore(LocalTime.of(20, 0)) || time.isBefore(LocalTime.of(2, 0)), "hors plage : " + time);
        }
    }

    @Test
    void firstDayStartedLateGetsItsShareOnly() {
        ZonedDateTime thursdayEvening = MONDAY.plusDays(3).withHour(22);
        for (int seed = 0; seed < 50; seed++) {
            ServerAuctionSchedule schedule = new ServerAuctionSchedule();
            schedule.refresh(AUCTIONS, SALES, thursdayEvening, new Random(seed));
            long thursday = schedule.entries().stream().filter(e -> local(e.start()).toLocalDate().equals(thursdayEvening.toLocalDate())).count();
            assertTrue(thursday <= 1, "1 h 30 restante le jeudi : " + thursday);
            assertTrue(schedule.entries().stream().allMatch(e -> e.start() > thursdayEvening.toEpochSecond()));
            assertTrue(schedule.entries().stream().map(e -> local(e.start()).toLocalDate()).anyMatch(d -> d.getDayOfWeek().getValue() == 7));
        }
    }

    @Test
    void planIsKeptAcrossRestartAndRedoneTheNextWeek(@TempDir File dir) throws Exception {
        File file = new File(dir, "encheres-serveur.yml");
        ServerAuctionSchedule schedule = new ServerAuctionSchedule();
        assertTrue(schedule.refresh(AUCTIONS, SALES, MONDAY.plusMinutes(1), new Random(7)));
        Entry first = schedule.entries().get(0);
        first.launched("cle-ordinaire", 42);
        schedule.skipNext(Kind.VENTE);
        schedule.addForced(Kind.VENTE, first.start() + 60, 120, "cle-rare", 43);
        schedule.save(file);

        // Redémarrage le jeudi à 06:00 : même planning, rien n'est retiré
        ServerAuctionSchedule reloaded = ServerAuctionSchedule.load(file);
        assertFalse(reloaded.refresh(AUCTIONS, SALES, MONDAY.plusDays(3).withHour(6), new Random(99)));
        assertEquals(schedule.entries().size(), reloaded.entries().size());
        for (int i = 0; i < schedule.entries().size(); i++) {
            Entry a = schedule.entries().get(i);
            Entry b = reloaded.entries().get(i);
            assertEquals(a.kind(), b.kind());
            assertEquals(a.start(), b.start());
            assertEquals(a.duration(), b.duration());
            assertEquals(a.status(), b.status());
            assertEquals(a.lot(), b.lot());
            assertEquals(a.auctionId(), b.auctionId());
            assertEquals(a.forced(), b.forced());
        }

        // Semaine suivante : nouveau planning complet
        assertTrue(reloaded.refresh(AUCTIONS, SALES, MONDAY.plusWeeks(1), new Random(8)));
        assertEquals("2026-11-16", reloaded.week());
        assertTrue(reloaded.entries().stream().allMatch(e -> e.status() == Status.PREVUE && !e.forced()));
    }

    @Test
    void changedSettingsKeepWhatAlreadyHappenedAndRespectTheDailyMaximum() {
        ServerAuctionSchedule schedule = new ServerAuctionSchedule();
        schedule.refresh(AUCTIONS, SALES, MONDAY, new Random(11));
        ZonedDateTime wednesday = MONDAY.plusDays(2).withHour(17);
        int happened = 0;
        for (Entry entry : schedule.entries()) {
            if (entry.start() < wednesday.toEpochSecond()) {
                entry.launched("cle-ordinaire", 100 + happened++);
            }
        }
        Settings moreSales = new Settings(3, 3, LocalTime.of(12, 0), LocalTime.of(23, 30), 60, 360, 720);
        assertTrue(schedule.refresh(AUCTIONS, moreSales, wednesday, new Random(12)));
        assertEquals(happened, schedule.entries().stream().filter(e -> e.status() == Status.LANCEE).count());
        assertTrue(schedule.entries().stream().filter(e -> e.status() == Status.PREVUE).allMatch(e -> e.start() >= wednesday.toEpochSecond()));
        // Le mercredi ne dépasse pas 3 ventes, lancées comprises
        long wednesdaySales = schedule.entries().stream()
            .filter(e -> e.kind() == Kind.VENTE && local(e.start()).toLocalDate().equals(wednesday.toLocalDate())).count();
        assertTrue(wednesdaySales <= 3, String.valueOf(wednesdaySales));
        assertFalse(schedule.refresh(AUCTIONS, moreSales, wednesday.plusHours(1), new Random(13)), "mêmes réglages : planning inchangé");
    }

    @Test
    void lateStartIsLaunchedInsideItsWindowAndMissedAfterIt() {
        ServerAuctionSchedule schedule = new ServerAuctionSchedule();
        schedule.refresh(AUCTIONS, null, MONDAY, new Random(21));
        Entry first = schedule.entries().get(0);
        ZonedDateTime start = Instant.ofEpochSecond(first.start()).atZone(ZONE);

        assertTrue(schedule.due(AUCTIONS, null, start.minusMinutes(1)).toLaunch().isEmpty());
        assertEquals(List.of(first), schedule.due(AUCTIONS, null, start.plusMinutes(5)).toLaunch());

        // Serveur éteint jusqu'au lendemain 06:00 : la plage de ce jour est finie, enchère manquée
        ServerAuctionSchedule.Due late = schedule.due(AUCTIONS, null, start.toLocalDate().plusDays(1).atTime(6, 0).atZone(ZONE));
        assertTrue(late.toLaunch().isEmpty());
        assertTrue(late.missed().contains(first));
        assertEquals(Status.MANQUEE, first.status());
    }

    @Test
    void skipRemovesTheNextOneOfTheRequestedKind() {
        ServerAuctionSchedule schedule = new ServerAuctionSchedule();
        schedule.refresh(AUCTIONS, SALES, MONDAY, new Random(31));
        Entry nextSale = schedule.next(Kind.VENTE);
        assertNotNull(nextSale);
        assertEquals(nextSale, schedule.skipNext(Kind.VENTE));
        assertEquals(Status.ANNULEE, nextSale.status());
        Entry next = schedule.next(null);
        assertEquals(next, schedule.skipNext(null));
        assertEquals(Status.ANNULEE, next.status());
    }
}
