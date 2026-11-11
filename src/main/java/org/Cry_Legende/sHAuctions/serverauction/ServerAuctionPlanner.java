package org.Cry_Legende.sHAuctions.serverauction;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Calcul du planning des enchères et des ventes du serveur, sans dépendance au serveur.
 *
 * <p>Le planning couvre une semaine (lundi 00:00 au lundi suivant, fuseau du serveur). Chaque jour reçoit un nombre
 * tiré entre un minimum et un maximum ; les débuts sont tirés dans la plage horaire du jour, un par tranche égale de
 * la plage (ils ne se tassent pas), avec un écart minimal entre eux et avec ceux de l'autre type.</p>
 */
public final class ServerAuctionPlanner {

    private static final int ATTEMPTS = 500;

    private ServerAuctionPlanner() {
    }

    /**
     * Réglages du planning d'un type (section encheres-serveur ou ventes-serveur de config.yml).
     *
     * @param minPerDay          nombre minimal par jour
     * @param maxPerDay          nombre maximal par jour
     * @param windowStart        début de la plage horaire des débuts
     * @param windowEnd          fin de la plage horaire (avant le début : la plage passe minuit)
     * @param minGapMinutes      écart minimal entre deux débuts de ce type, et avec les débuts de l'autre type
     * @param minDurationMinutes durée minimale
     * @param maxDurationMinutes durée maximale
     */
    public record Settings(int minPerDay, int maxPerDay, LocalTime windowStart, LocalTime windowEnd, int minGapMinutes,
                           int minDurationMinutes, int maxDurationMinutes) {

        public Settings {
            minPerDay = Math.max(0, minPerDay);
            maxPerDay = Math.max(minPerDay, maxPerDay);
            minGapMinutes = Math.max(0, minGapMinutes);
            minDurationMinutes = Math.max(1, minDurationMinutes);
            maxDurationMinutes = Math.max(minDurationMinutes, maxDurationMinutes);
        }

        /** Longueur de la plage horaire, en minutes. */
        public int windowMinutes() {
            int length = toMinutes(windowEnd) - toMinutes(windowStart);
            return length <= 0 ? length + 1440 : length;
        }

        /** Empreinte des réglages : un planning fait avec d'autres réglages est refait. */
        public String signature() {
            return minPerDay + "-" + maxPerDay + "|" + windowStart + "|" + windowEnd + "|" + minGapMinutes + "|" + minDurationMinutes + "|" + maxDurationMinutes;
        }
    }

    /** Un début prévu : instant (secondes epoch) et durée (secondes). */
    public record Slot(long start, long durationSeconds) {
    }

    /** Plage d'un jour : bornes complètes et partie encore utilisable (secondes epoch). */
    record Window(long fullStart, long fullEnd, long from, long to) {
    }

    private static int toMinutes(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    /** Lundi 00:00 de la semaine qui contient {@code time}. */
    public static ZonedDateTime weekStart(ZonedDateTime time) {
        return time.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(time.getZone());
    }

    /** Clé d'une semaine : la date de son lundi (2026-11-09). */
    public static String weekKey(ZonedDateTime weekStart) {
        return weekStart.toLocalDate().toString();
    }

    /** Plage horaire du jour {@code day} (secondes epoch, fin exclue). */
    private static long[] windowOf(Settings settings, LocalDate day, ZoneId zone) {
        long start = day.atTime(settings.windowStart()).atZone(zone).toEpochSecond();
        LocalDate endDay = toMinutes(settings.windowEnd()) <= toMinutes(settings.windowStart()) ? day.plusDays(1) : day;
        long end = endDay.atTime(settings.windowEnd()).atZone(zone).toEpochSecond();
        return new long[]{start, end};
    }

    /** Plages des jours de la semaine encore utilisables à partir de {@code from}, sans déborder sur la semaine suivante. */
    static List<Window> windows(Settings settings, ZonedDateTime weekStart, long from) {
        List<Window> result = new ArrayList<>();
        long weekEnd = weekStart.plusWeeks(1).toEpochSecond();
        // Début arrondi à la minute suivante : les heures du planning tombent sur des minutes rondes
        long first = (from + 59) / 60 * 60;
        for (int d = 0; d < 7; d++) {
            long[] window = windowOf(settings, weekStart.toLocalDate().plusDays(d), weekStart.getZone());
            long a = Math.max(window[0], first);
            long b = Math.min(window[1], weekEnd);
            if (b - a >= 60) {
                result.add(new Window(window[0], window[1], a, b));
            }
        }
        return result;
    }

    /**
     * Tire les débuts d'un type pour chaque jour restant de la semaine : entre {@code minPerDay} et {@code maxPerDay}
     * par jour (au prorata pour le jour déjà entamé). Si l'écart minimal ne permet pas d'en placer autant dans un jour,
     * ce jour en reçoit moins.
     *
     * @param sameKind  débuts déjà pris de ce type : ils comptent dans le nombre de leur jour et pour l'écart
     * @param otherKind débuts de l'autre type : l'écart minimal est aussi gardé avec eux
     * @return les débuts prévus, dans l'ordre
     */
    public static List<Slot> plan(Settings settings, ZonedDateTime weekStart, long from, List<Long> sameKind, List<Long> otherKind, Random random) {
        List<Long> taken = new ArrayList<>(sameKind);
        List<Slot> slots = new ArrayList<>();
        for (Window window : windows(settings, weekStart, from)) {
            int drawn = settings.minPerDay() + random.nextInt(settings.maxPerDay() - settings.minPerDay() + 1);
            double share = (double) (window.to() - window.from()) / (window.fullEnd() - window.fullStart());
            int count = share >= 1 ? drawn : (int) Math.round(drawn * share);
            for (long start : sameKind) {
                if (start >= window.fullStart() && start < window.fullEnd()) {
                    count--;
                }
            }
            List<Long> starts = placeInDay(settings, window, Math.max(0, count), taken, otherKind, random);
            for (long start : starts) {
                int minutes = settings.minDurationMinutes()
                    + random.nextInt(settings.maxDurationMinutes() - settings.minDurationMinutes() + 1);
                slots.add(new Slot(start, minutes * 60L));
                taken.add(start);
            }
        }
        return slots;
    }

    /** Débuts d'un jour : un par tranche égale de ce qui reste de la plage, écarts vérifiés, moins s'il le faut. */
    private static List<Long> placeInDay(Settings settings, Window window, int count, List<Long> taken, List<Long> others, Random random) {
        for (int n = count; n > 0; n--) {
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                List<Long> starts = new ArrayList<>();
                long length = window.to() - window.from();
                for (int i = 0; i < n; i++) {
                    Long start = randomMinute(window.from() + length * i / n, window.from() + length * (i + 1) / n, random);
                    if (start == null) {
                        break;
                    }
                    starts.add(start);
                }
                if (starts.size() == n && fits(starts, taken, others, settings.minGapMinutes())) {
                    return starts;
                }
            }
        }
        return List.of();
    }

    /** Minute ronde tirée dans [from, to), ou null s'il n'y en a aucune. */
    private static Long randomMinute(long from, long to, Random random) {
        long first = (from + 59) / 60 * 60;
        long last = (to - 1) / 60 * 60;
        if (last < first) {
            return null;
        }
        return first + 60L * random.nextInt((int) ((last - first) / 60 + 1));
    }

    /** Écart minimal gardé entre les nouveaux débuts, avec ceux déjà pris et avec ceux de l'autre type. */
    static boolean fits(List<Long> starts, List<Long> taken, List<Long> others, int gapMinutes) {
        List<Long> same = new ArrayList<>(starts);
        same.addAll(taken);
        if (!respectsGap(same, gapMinutes)) {
            return false;
        }
        for (long start : starts) {
            for (long other : others) {
                if (Math.abs(start - other) < gapMinutes * 60L) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Vrai si deux débuts consécutifs sont toujours séparés d'au moins {@code gapMinutes}. */
    static boolean respectsGap(List<Long> starts, int gapMinutes) {
        List<Long> sorted = new ArrayList<>(starts);
        Collections.sort(sorted);
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i) - sorted.get(i - 1) < gapMinutes * 60L) {
                return false;
            }
        }
        return true;
    }

    /**
     * Fin de la plage horaire qui contient {@code epoch}, ou -1 s'il est hors plage. Un début passé pendant que le
     * serveur était éteint n'est lancé en retard que dans la même plage.
     */
    public static long windowEndContaining(Settings settings, long epoch, ZoneId zone) {
        LocalDate day = ZonedDateTime.ofInstant(Instant.ofEpochSecond(epoch), zone).toLocalDate();
        for (LocalDate candidate : new LocalDate[]{day, day.minusDays(1)}) {
            long[] window = windowOf(settings, candidate, zone);
            if (epoch >= window[0] && epoch < window[1]) {
                return window[1];
            }
        }
        return -1;
    }
}
