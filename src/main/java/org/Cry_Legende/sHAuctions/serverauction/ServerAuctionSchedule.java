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
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Planning de la semaine en cours (enchères et ventes du serveur), gardé dans encheres-serveur.yml : il survit aux
 * redémarrages et n'est refait qu'à la semaine suivante ou quand les réglages du planning changent.
 */
public final class ServerAuctionSchedule {

    /** Enchère ou vente du serveur. */
    public enum Kind {
        ENCHERE,
        VENTE;

        /** Nom court (fichier, commandes) : enchere, vente. */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** État d'une entrée du planning. */
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

    /** Une entrée du planning. */
    public static final class Entry {
        private final Kind kind;
        private final long start;
        private final long duration;
        private Status status;
        private String lot;
        private long auctionId = -1;
        private final boolean forced;

        Entry(Kind kind, long start, long duration, Status status, boolean forced) {
            this.kind = kind;
            this.start = start;
            this.duration = duration;
            this.status = status;
            this.forced = forced;
        }

        public Kind kind() {
            return kind;
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

        /** Mise en ligne. */
        public void launched(String lotId, long id) {
            this.status = Status.LANCEE;
            this.lot = lotId;
            this.auctionId = id;
        }

        /** N'a pas pu être lancée. */
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

    /** Toutes les entrées de la semaine, dans l'ordre. */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    private static String signature(Settings auctions, Settings sales) {
        return (auctions == null ? "-" : auctions.signature()) + "#" + (sales == null ? "-" : sales.signature());
    }

    /**
     * Fait le planning d'une nouvelle semaine, ou refait la suite de la semaine si les réglages ont changé.
     *
     * @param auctions réglages des enchères, null si elles sont désactivées
     * @param sales    réglages des ventes, null si elles sont désactivées
     * @return vrai si le planning a changé (à enregistrer)
     */
    public boolean refresh(Settings auctions, Settings sales, ZonedDateTime now, Random random) {
        ZonedDateTime weekStart = ServerAuctionPlanner.weekStart(now);
        String key = ServerAuctionPlanner.weekKey(weekStart);
        String wanted = signature(auctions, sales);
        if (key.equals(week) && wanted.equals(signature)) {
            return false;
        }
        long from = now.toEpochSecond();
        if (!key.equals(week)) {
            week = key;
            entries.clear();
        } else {
            // Réglages changés : ce qui est à venir est retiré et replanifié, le reste demeure
            entries.removeIf(e -> e.status == Status.PREVUE && e.start >= from);
        }
        signature = wanted;
        if (auctions != null) {
            add(Kind.ENCHERE, ServerAuctionPlanner.plan(auctions, weekStart, from, planned(Kind.ENCHERE), planned(Kind.VENTE), random));
        }
        if (sales != null) {
            add(Kind.VENTE, ServerAuctionPlanner.plan(sales, weekStart, from, planned(Kind.VENTE), planned(Kind.ENCHERE), random));
        }
        return true;
    }

    /** Débuts du planning (hors lancements à la main) d'un type, sauf ceux retirés. */
    private List<Long> planned(Kind kind) {
        List<Long> starts = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.kind == kind && !entry.forced && entry.status != Status.ANNULEE) {
                starts.add(entry.start);
            }
        }
        return starts;
    }

    private void add(Kind kind, List<Slot> slots) {
        for (Slot slot : slots) {
            entries.add(new Entry(kind, slot.start(), slot.durationSeconds(), Status.PREVUE, false));
        }
        entries.sort(Comparator.comparingLong(Entry::start));
    }

    /** Entrées dont l'heure est arrivée : à lancer maintenant, ou manquées. */
    public record Due(List<Entry> toLaunch, List<Entry> missed) {
    }

    /**
     * Entrées prévues dont l'heure est arrivée. Celle dont la plage horaire est déjà finie (serveur éteint à ce
     * moment-là), ou dont le type a été désactivé, passe en {@link Status#MANQUEE} ; les autres sont à lancer.
     */
    public Due due(Settings auctions, Settings sales, ZonedDateTime now) {
        long nowSeconds = now.toEpochSecond();
        List<Entry> toLaunch = new ArrayList<>();
        List<Entry> missed = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.status != Status.PREVUE || entry.start > nowSeconds) {
                continue;
            }
            Settings settings = entry.kind == Kind.ENCHERE ? auctions : sales;
            long end = settings == null ? -1 : ServerAuctionPlanner.windowEndContaining(settings, entry.start, now.getZone());
            if (end > nowSeconds) {
                toLaunch.add(entry);
            } else {
                entry.status = Status.MANQUEE;
                missed.add(entry);
            }
        }
        return new Due(toLaunch, missed);
    }

    /** Prochaine entrée prévue (du type donné, ou de n'importe quel type si null), ou null. */
    public Entry next(Kind kind) {
        for (Entry entry : entries) {
            if (entry.status == Status.PREVUE && (kind == null || entry.kind == kind)) {
                return entry;
            }
        }
        return null;
    }

    /** Retire la prochaine entrée prévue (du type donné, ou de n'importe quel type si null). @return l'entrée, ou null */
    public Entry skipNext(Kind kind) {
        Entry entry = next(kind);
        if (entry != null) {
            entry.status = Status.ANNULEE;
        }
        return entry;
    }

    /** Ajoute une enchère ou une vente lancée à la main (hors planning, ne compte pas dans le nombre par jour). */
    public Entry addForced(Kind kind, long start, long duration, String lotId, long auctionId) {
        Entry entry = new Entry(kind, start, duration, Status.PREVUE, true);
        entry.launched(lotId, auctionId);
        entries.add(entry);
        entries.sort(Comparator.comparingLong(Entry::start));
        return entry;
    }

    // ------------------------------------------------------------------ fichier

    public String toYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("Planning des enchères et des ventes du serveur pour la semaine en cours (écrit par le plugin)."));
        yaml.set("semaine", week);
        yaml.set("reglages", signature);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Entry entry : entries) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", entry.kind.id());
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
        yaml.set("planning", list);
        return yaml.saveToString();
    }

    public static ServerAuctionSchedule fromYaml(String text) throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        ServerAuctionSchedule schedule = new ServerAuctionSchedule();
        schedule.week = yaml.getString("semaine");
        schedule.signature = yaml.getString("reglages");
        for (Map<?, ?> map : yaml.getMapList("planning")) {
            Status status;
            Kind kind;
            try {
                status = Status.valueOf(String.valueOf(map.get("etat")));
                kind = Kind.valueOf(String.valueOf(map.get("type")).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                continue;
            }
            Entry entry = new Entry(kind, number(map.get("debut")), number(map.get("duree")), status, Boolean.TRUE.equals(map.get("forcee")));
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
