package org.Cry_Legende.sHAuctions.serverauction;

import org.Cry_Legende.sHAuctions.serverauction.ServerAuctionPlanner.Settings;
import org.Cry_Legende.sHAuctions.serverauction.ServerAuctionPlanner.Slot;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Planning de la semaine en cours, gardé dans encheres-serveur.yml : il survit aux redémarrages et n'est refait qu'à
 * la semaine suivante ou quand les réglages du planning changent.
 */
public final class ServerAuctionSchedule {

    /** État d'une enchère du planning. */
    public enum Status {
        /** pas encore lancée */
        PREVUE,
        /** mise en ligne dans l'HDV */
        LANCEE,
        /** retirée du planning par /shauctions skip */
        ANNULEE,
        /** heure passée pendant que le serveur était éteint, plage horaire terminée */
        MANQUEE
    }

    /** Une enchère du planning. */
    public static final class Entry {
        private final long start;
        private final long duration;
        private Status status;
        private String lot;
        private long auctionId = -1;
        private final boolean forced;

        Entry(long start, long duration, Status status, boolean forced) {
            this.start = start;
            this.duration = duration;
            this.status = status;
            this.forced = forced;
        }

        public long start() {
            return start;
        }

        public long duration() {
            return duration;
        }

        public Status status() {
            return status;
        }

        public String lot() {
            return lot;
        }

        public long auctionId() {
            return auctionId;
        }

        /** Lancée à la main (/shauctions launch), hors planning. */
        public boolean forced() {
            return forced;
        }

        /** Enchère mise en ligne. */
        public void launched(String lotId, long id) {
            this.status = Status.LANCEE;
            this.lot = lotId;
            this.auctionId = id;
        }

        /** Enchère qui n'a pas pu être lancée. */
        public void missed() {
            this.status = Status.MANQUEE;
        }
    }

    private String week;
    private String signature;
    private final List<Entry> entries = new ArrayList<>();

    public String week() {
        return week;
    }

    /** Toutes les enchères de la semaine, dans l'ordre. */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /**
     * Fait le planning d'une nouvelle semaine, ou refait la suite de la semaine si les réglages ont changé.
     *
     * @return vrai si le planning a changé (à enregistrer)
     */
    public boolean refresh(Settings settings, ZonedDateTime now, Random random) {
        ZonedDateTime weekStart = ServerAuctionPlanner.weekStart(now);
        String key = ServerAuctionPlanner.weekKey(weekStart);
        long from = now.toEpochSecond();
        if (!key.equals(week)) {
            week = key;
            signature = settings.signature();
            entries.clear();
            add(ServerAuctionPlanner.plan(settings, weekStart, from, ServerAuctionPlanner.countFor(settings, weekStart, from), List.of(), random));
            return true;
        }
        if (settings.signature().equals(signature)) {
            return false;
        }
        // Réglages changés : les enchères à venir sont retirées et replanifiées, les autres restent
        signature = settings.signature();
        entries.removeIf(e -> e.status == Status.PREVUE && e.start >= from);
        int consumed = 0;
        List<Long> taken = new ArrayList<>();
        for (Entry entry : entries) {
            if (!entry.forced) {
                consumed++;
            }
            if (entry.status == Status.LANCEE || entry.status == Status.PREVUE) {
                taken.add(entry.start);
            }
        }
        int count = Math.min(Math.max(0, settings.perWeek() - consumed), ServerAuctionPlanner.countFor(settings, weekStart, from));
        add(ServerAuctionPlanner.plan(settings, weekStart, from, count, taken, random));
        return true;
    }

    private void add(List<Slot> slots) {
        for (Slot slot : slots) {
            entries.add(new Entry(slot.start(), slot.durationSeconds(), Status.PREVUE, false));
        }
        entries.sort(Comparator.comparingLong(Entry::start));
    }

    /** Enchères dont l'heure est arrivée : à lancer maintenant, ou manquées. */
    public record Due(List<Entry> toLaunch, List<Entry> missed) {
    }

    /**
     * Enchères prévues dont l'heure est arrivée. Celle dont la plage horaire est déjà finie (serveur éteint à ce
     * moment-là) passe en {@link Status#MANQUEE} ; les autres sont rendues pour être lancées.
     */
    public Due due(Settings settings, ZonedDateTime now) {
        long nowSeconds = now.toEpochSecond();
        List<Entry> toLaunch = new ArrayList<>();
        List<Entry> missed = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.status != Status.PREVUE || entry.start > nowSeconds) {
                continue;
            }
            long end = ServerAuctionPlanner.windowEndContaining(settings, entry.start, now.getZone());
            if (end > nowSeconds) {
                toLaunch.add(entry);
            } else {
                entry.status = Status.MANQUEE;
                missed.add(entry);
            }
        }
        return new Due(toLaunch, missed);
    }

    /** Prochaine enchère prévue, ou null. */
    public Entry next() {
        for (Entry entry : entries) {
            if (entry.status == Status.PREVUE) {
                return entry;
            }
        }
        return null;
    }

    /** Retire la prochaine enchère prévue. @return l'enchère retirée, ou null s'il n'y en a plus */
    public Entry skipNext() {
        Entry entry = next();
        if (entry != null) {
            entry.status = Status.ANNULEE;
        }
        return entry;
    }

    /** Ajoute une enchère lancée à la main (hors planning, ne compte pas dans le nombre par semaine). */
    public Entry addForced(long start, long duration, String lotId, long auctionId) {
        Entry entry = new Entry(start, duration, Status.PREVUE, true);
        entry.launched(lotId, auctionId);
        entries.add(entry);
        entries.sort(Comparator.comparingLong(Entry::start));
        return entry;
    }

    // ------------------------------------------------------------------ fichier

    public String toYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("Planning des enchères du serveur pour la semaine en cours (écrit par le plugin)."));
        yaml.set("semaine", week);
        yaml.set("reglages", signature);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Entry entry : entries) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("debut", entry.start);
            map.put("duree", entry.duration);
            map.put("etat", entry.status.name());
            if (entry.lot != null) {
                map.put("lot", entry.lot);
            }
            if (entry.auctionId >= 0) {
                map.put("annonce", entry.auctionId);
            }
            if (entry.forced) {
                map.put("forcee", true);
            }
            list.add(map);
        }
        yaml.set("encheres", list);
        return yaml.saveToString();
    }

    public static ServerAuctionSchedule fromYaml(String text) throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        ServerAuctionSchedule schedule = new ServerAuctionSchedule();
        schedule.week = yaml.getString("semaine");
        schedule.signature = yaml.getString("reglages");
        for (Map<?, ?> map : yaml.getMapList("encheres")) {
            Status status;
            try {
                status = Status.valueOf(String.valueOf(map.get("etat")));
            } catch (IllegalArgumentException e) {
                continue;
            }
            Entry entry = new Entry(number(map.get("debut")), number(map.get("duree")), status, Boolean.TRUE.equals(map.get("forcee")));
            if (map.get("lot") != null) {
                entry.lot = String.valueOf(map.get("lot"));
            }
            if (map.get("annonce") != null) {
                entry.auctionId = number(map.get("annonce"));
            }
            schedule.entries.add(entry);
        }
        schedule.entries.sort(Comparator.comparingLong(Entry::start));
        return schedule;
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    /** Écrit le planning (fichier temporaire puis renommage : jamais de fichier à moitié écrit). */
    public void save(File file) throws IOException {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        Files.writeString(tmp.toPath(), toYaml(), StandardCharsets.UTF_8);
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Lit le planning ; un fichier absent donne un planning vide (refait au premier passage). */
    public static ServerAuctionSchedule load(File file) throws IOException, InvalidConfigurationException {
        if (!file.isFile()) {
            return new ServerAuctionSchedule();
        }
        return fromYaml(Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }
}
