package org.Cry_Legende.sHAuctions.serverauction;

import java.time.DayOfWeek;
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
 * Calcul du planning des enchères du serveur, sans dépendance au serveur.
 *
 * <p>Une semaine va du lundi 00:00 au lundi suivant, dans le fuseau du serveur. Les débuts sont tirés dans la plage
 * horaire de chaque jour, répartis sur les jours de la semaine, et chaque enchère reçoit une tranche différente de la
 * plage horaire : deux enchères de la même semaine ne tombent pas au même moment de la journée.</p>
 */
public final class ServerAuctionPlanner {

    private static final int ATTEMPTS = 500;

    private ServerAuctionPlanner() {
    }

    /**
     * Réglages du planning (section encheres-serveur de config.yml).
     *
     * @param perWeek            enchères par semaine
     * @param windowStart        début de la plage horaire des débuts
     * @param windowEnd          fin de la plage horaire (avant le début : la plage passe minuit)
     * @param minGapMinutes      écart minimal entre deux débuts
     * @param minDurationMinutes durée minimale d'une enchère
     * @param maxDurationMinutes durée maximale d'une enchère
     */
    public record Settings(int perWeek, LocalTime windowStart, LocalTime windowEnd, int minGapMinutes,
                           int minDurationMinutes, int maxDurationMinutes) {

        public Settings {
            perWeek = Math.max(0, perWeek);
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
            return perWeek + "|" + windowStart + "|" + windowEnd + "|" + minGapMinutes + "|" + minDurationMinutes + "|" + maxDurationMinutes;
        }
    }

    /** Une enchère prévue : début (secondes epoch) et durée (secondes). */
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

    /** Enchères à prévoir de {@code from} à la fin de la semaine : le nombre par semaine au prorata de la plage restante. */
    public static int countFor(Settings settings, ZonedDateTime weekStart, long from) {
        if (settings.perWeek() <= 0) {
            return 0;
        }
        long remaining = 0;
        for (Window window : windows(settings, weekStart, from)) {
            remaining += window.to() - window.from();
        }
        double full = 7.0 * settings.windowMinutes() * 60;
        return (int) Math.min(settings.perWeek(), Math.round(settings.perWeek() * remaining / full));
    }

    /**
     * Tire les débuts de {@code count} enchères entre {@code from} et la fin de la semaine. Si l'écart minimal ne
     * permet pas d'en placer autant, le planning en contient moins.
     *
     * @param taken débuts déjà pris cette semaine : l'écart minimal est aussi respecté avec eux
     * @return les enchères prévues, dans l'ordre
     */
    public static List<Slot> plan(Settings settings, ZonedDateTime weekStart, long from, int count, List<Long> taken, Random random) {
        List<Window> windows = windows(settings, weekStart, from);
        for (int n = count; n > 0 && !windows.isEmpty(); n--) {
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                List<Long> starts = draw(settings, windows, n, random);
                List<Long> all = new ArrayList<>(starts);
                all.addAll(taken);
                if (starts.size() == n && respectsGap(all, settings.minGapMinutes())) {
                    List<Slot> slots = new ArrayList<>();
                    for (long start : starts) {
                        int minutes = settings.minDurationMinutes()
                            + random.nextInt(settings.maxDurationMinutes() - settings.minDurationMinutes() + 1);
                        slots.add(new Slot(start, minutes * 60L));
                    }
                    return slots;
                }
            }
        }
        return List.of();
    }

    /**
     * Un tirage : les enchères sont réparties sur les jours (au plus une par jour tant qu'il y a plus de jours que
     * d'enchères, réparties sur toute la semaine), puis chacune reçoit sa propre tranche de la plage horaire.
     */
    private static List<Long> draw(Settings settings, List<Window> windows, int count, Random random) {
        int days = windows.size();
        int[] perDay = new int[days];
        if (count <= days) {
            // Jours découpés en `count` groupes consécutifs, un jour tiré dans chaque groupe
            for (int g = 0; g < count; g++) {
                int lo = g * days / count;
                int hi = (g + 1) * days / count;
                perDay[lo + random.nextInt(hi - lo)]++;
            }
        } else {
            List<Integer> order = new ArrayList<>();
            for (int d = 0; d < days; d++) {
                perDay[d] = count / days;
                order.add(d);
            }
            Collections.shuffle(order, random);
            for (int i = 0; i < count % days; i++) {
                perDay[order.get(i)]++;
            }
        }

        // Tranches de la plage horaire, une par enchère. Chaque enchère a une place idéale dans la plage : celles d'un
        // même jour sont réparties sur toute la plage, avec un décalage tiré au hasard pour chaque jour. Les tranches
        // sont distribuées dans l'ordre de ces places : toutes différentes, et espacées pour celles d'un même jour.
        List<double[]> places = new ArrayList<>();
        for (int d = 0; d < days; d++) {
            double offset = random.nextDouble();
            for (int k = 0; k < perDay[d]; k++) {
                places.add(new double[]{(k + offset) / perDay[d], d, random.nextDouble()});
            }
        }
        places.sort((x, y) -> x[0] != y[0] ? Double.compare(x[0], y[0]) : Double.compare(x[2], y[2]));

        long windowSeconds = settings.windowMinutes() * 60L;
        List<Long> starts = new ArrayList<>();
        for (int band = 0; band < places.size(); band++) {
            Window window = windows.get((int) places.get(band)[1]);
            long bandFrom = window.fullStart() + windowSeconds * band / count;
            long bandTo = window.fullStart() + windowSeconds * (band + 1) / count;
            Long start = randomMinute(Math.max(bandFrom, window.from()), Math.min(bandTo, window.to()), random);
            if (start == null) {
                // Tranche déjà passée (premier jour entamé) : n'importe où dans ce qui reste du jour
                start = randomMinute(window.from(), window.to(), random);
            }
            if (start == null) {
                return List.of();
            }
            starts.add(start);
        }
        Collections.sort(starts);
        return starts;
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
     * Fin de la plage horaire qui contient {@code epoch}, ou -1 s'il est hors plage. Une enchère dont l'heure est
     * passée pendant que le serveur était éteint n'est lancée en retard que dans la même plage.
     */
    public static long windowEndContaining(Settings settings, long epoch, ZoneId zone) {
        LocalDate day = ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(epoch), zone).toLocalDate();
        for (LocalDate candidate : new LocalDate[]{day, day.minusDays(1)}) {
            long[] window = windowOf(settings, candidate, zone);
            if (epoch >= window[0] && epoch < window[1]) {
                return window[1];
            }
        }
        return -1;
    }

    /**
     * Plafond hebdomadaire : un joueur peut miser sur une enchère du serveur tant que ses enchères remportées cette
     * semaine plus celles où il est déjà en tête restent sous le plafond (0 = sans plafond).
     */
    public static boolean mayBid(int wonThisWeek, int leadingElsewhere, int cap) {
        return cap <= 0 || wonThisWeek + leadingElsewhere < cap;
    }
}
