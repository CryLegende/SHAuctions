package org.Cry_Legende.sHAuctions.serverauction;

import net.md_5.bungee.api.chat.TextComponent;
import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.config.ConfigManager;
import org.Cry_Legende.sHAuctions.config.MessageManager;
import org.Cry_Legende.sHAuctions.database.dao.AuctionDAO;
import org.Cry_Legende.sHAuctions.manager.AuctionManager;
import org.Cry_Legende.sHAuctions.manager.SharedFlows;
import org.Cry_Legende.sHAuctions.model.AuctionItem;
import org.Cry_Legende.sHAuctions.serverauction.ServerAuctionPlanner.Settings;
import org.Cry_Legende.sHAuctions.serverauction.ServerAuctionSchedule.Entry;
import org.Cry_Legende.sHAuctions.util.ItemUtil;
import org.Cry_Legende.sHAuctions.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;

/**
 * Enchères du serveur : quelques fois par semaine, le serveur met lui-même un lot aux enchères dans la page Enchères.
 *
 * <ul>
 *   <li>L'annonce est une enchère ordinaire dont le vendeur est {@link AuctionItem#SERVER_SELLER} : affichage, mises,
 *   remboursements et anti-snipe sont ceux de l'HDV.</li>
 *   <li>L'objet affiché porte son lot (identifiant, commandes, incrément) : n'importe quel serveur du réseau sait le
 *   régler, même après un reload qui a changé la liste des lots.</li>
 *   <li>Au règlement, l'offre gagnante n'est versée à personne. Le lot est remis par ses commandes si le gagnant est
 *   là avec une place libre, sinon l'objet affiché part dans ses invendus et le lot est remis quand il le récupère
 *   (l'objet affiché n'est jamais donné).</li>
 * </ul>
 *
 * <p>Seul le serveur où {@code encheres-serveur.actif} vaut true tient le planning et lance les enchères.</p>
 */
public final class ServerAuctionManager {

    private static final String S = "encheres-serveur.";
    private static final long TICK_PERIOD = 20L * 20;
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("H:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE dd/MM HH:mm", Locale.FRENCH);

    /** Un lot de config.yml. */
    public record Lot(String id, int weight, double startPrice, double increment, Material material, int amount, String name,
                      List<String> lore, String texture, boolean glint, List<String> commands) {
    }

    /** Lot porté par l'objet d'une enchère du serveur. */
    public record LotData(String id, List<String> commands, double increment) {
    }

    private final SHAuctions plugin;
    private final NamespacedKey lotKey;
    private final NamespacedKey commandsKey;
    private final NamespacedKey incrementKey;
    private final File file;
    private final Random random = new Random();
    private final Set<String> warned = new HashSet<>();
    private ServerAuctionSchedule schedule = new ServerAuctionSchedule();
    private BukkitTask task;

    public ServerAuctionManager(SHAuctions plugin) {
        this.plugin = plugin;
        this.lotKey = new NamespacedKey(plugin, "lot");
        this.commandsKey = new NamespacedKey(plugin, "lot-commandes");
        this.incrementKey = new NamespacedKey(plugin, "lot-increment");
        this.file = new File(plugin.getDataFolder(), "encheres-serveur.yml");
    }

    private ConfigManager config() {
        return plugin.getConfigManager();
    }

    private MessageManager messages() {
        return plugin.getMessageManager();
    }

    private AuctionDAO auctions() {
        return plugin.getDatabaseManager().getAuctionDAO();
    }

    private static ZoneId zone() {
        return ZoneId.systemDefault();
    }

    // ------------------------------------------------------------------ cycle de vie

    public void enable() {
        try {
            schedule = ServerAuctionSchedule.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().log(Level.WARNING, "encheres-serveur.yml illisible : planning de la semaine refait.", e);
            schedule = new ServerAuctionSchedule();
        }
        checkLots();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L * 30, TICK_PERIOD);
    }

    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Après /shauctions reload : lots revérifiés, planning refait tout de suite si ses réglages ont changé. */
    public void onReload() {
        warned.clear();
        checkLots();
        tick();
    }

    /** Vrai si ce serveur tient le planning et lance les enchères du serveur. */
    public boolean isActive() {
        return config().getBoolean(S + "actif", false);
    }

    private void checkLots() {
        List<Lot> lots = parseLots(true);
        if (isActive()) {
            long drawable = lots.stream().filter(lot -> lot.weight() > 0).count();
            plugin.getLogger().info("Enchères du serveur actives : " + lots.size() + " lot(s), dont " + drawable + " tirable(s).");
            if (drawable == 0) {
                plugin.getLogger().warning("Enchères du serveur : aucun lot avec un poids positif, aucune ne sera lancée.");
            }
        }
    }

    // ------------------------------------------------------------------ réglages

    Settings settings() {
        return new Settings(config().getInt(S + "par-semaine", 7), time(S + "plage-horaire.debut", "12:00"),
            time(S + "plage-horaire.fin", "23:30"), config().getInt(S + "ecart-minimal-minutes", 240),
            config().getInt(S + "duree-minutes.min", 180), config().getInt(S + "duree-minutes.max", 360));
    }

    private LocalTime time(String path, String def) {
        String raw = config().getString(path, def).trim();
        try {
            return LocalTime.parse(raw, HOUR);
        } catch (DateTimeParseException e) {
            if (warned.add(path)) {
                plugin.getLogger().warning("config.yml " + path + " : heure « " + raw + " » illisible (format HH:mm), " + def + " utilisé.");
            }
            return LocalTime.parse(def, HOUR);
        }
    }

    private String sellerName() {
        return config().getString(S + "vendeur", "SkyHand");
    }

    private int cap() {
        return config().getInt(S + "max-gagnees-par-joueur", 2);
    }

    /** Lots de config.yml ; un lot incomplet est ignoré (signalé dans la console si {@code warn}). */
    public List<Lot> parseLots(boolean warn) {
        List<Lot> lots = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        double globalIncrement = config().getDouble("bids.min_bid_increment", 1.0);
        int index = 0;
        for (Map<?, ?> raw : config().getRawConfig().getMapList(S + "lots")) {
            index++;
            String id = text(raw.get("id"));
            if (id == null || id.isBlank()) {
                lotWarning(warn, "lot n°" + index + " sans id, ignoré.");
                continue;
            }
            if (!ids.add(id.toLowerCase(Locale.ROOT))) {
                lotWarning(warn, "lot " + id + " en double, seul le premier est gardé.");
                continue;
            }
            Map<?, ?> item = raw.get("objet") instanceof Map<?, ?> map ? map : Map.of();
            String materialName = text(item.get("materiau"));
            Material material = materialName == null ? null : Material.matchMaterial(materialName);
            if (material == null || !material.isItem() || material.isAir()) {
                lotWarning(warn, "lot " + id + " : objet.materiau « " + materialName + " » inconnu, lot ignoré.");
                continue;
            }
            List<String> commands = texts(raw.get("commandes"));
            if (commands.isEmpty()) {
                lotWarning(warn, "lot " + id + " sans commandes, ignoré.");
                continue;
            }
            double price = number(raw.get("prix-depart"), 0);
            if (price <= 0) {
                lotWarning(warn, "lot " + id + " : prix-depart manquant ou nul, lot ignoré.");
                continue;
            }
            double increment = number(raw.get("increment-min"), 0);
            int amount = (int) Math.max(1, Math.min(material.getMaxStackSize(), number(item.get("quantite"), 1)));
            lots.add(new Lot(id, (int) Math.max(0, number(raw.get("poids"), 0)), price, increment > 0 ? increment : globalIncrement, material,
                amount, text(item.get("nom")), texts(item.get("description")), text(item.get("texture")), Boolean.TRUE.equals(item.get("brillant")),
                commands));
        }
        return lots;
    }

    private void lotWarning(boolean warn, String message) {
        if (warn) {
            plugin.getLogger().warning("config.yml encheres-serveur.lots : " + message);
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> texts(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object line : list) {
                if (line != null && !String.valueOf(line).isBlank()) {
                    result.add(String.valueOf(line));
                }
            }
        }
        return result;
    }

    private static double number(Object value, double def) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value).replace(" ", ""));
            } catch (NumberFormatException ignored) {
                // valeur illisible : défaut
            }
        }
        return def;
    }

    private Lot drawLot(List<Lot> lots) {
        int total = lots.stream().mapToInt(Lot::weight).sum();
        if (total <= 0) {
            return null;
        }
        int roll = random.nextInt(total);
        for (Lot lot : lots) {
            roll -= lot.weight();
            if (roll < 0) {
                return lot;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ planning

    /** Passage périodique (thread principal) : planning de la semaine, puis lancement des enchères arrivées à l'heure. */
    void tick() {
        if (!isActive()) {
            return;
        }
        Settings settings = settings();
        ZonedDateTime now = ZonedDateTime.now(zone());
        boolean changed = refresh(settings, now);
        ServerAuctionSchedule.Due due = schedule.due(settings, now);
        for (Entry entry : due.missed()) {
            plugin.getLogger().warning("Enchère du serveur de " + format(entry.start()) + " manquée : serveur éteint pendant sa plage horaire.");
            changed = true;
        }
        for (Entry entry : due.toLaunch()) {
            Lot lot = drawLot(parseLots(false));
            long id = lot == null ? -1 : create(lot, entry.duration());
            if (id < 0) {
                plugin.getLogger().warning("Enchère du serveur de " + format(entry.start()) + " non lancée (aucun lot valide ou erreur base).");
                entry.missed();
            } else {
                entry.launched(lot.id(), id);
            }
            changed = true;
        }
        if (changed) {
            save();
        }
    }

    private boolean refresh(Settings settings, ZonedDateTime now) {
        String before = schedule.week();
        if (!schedule.refresh(settings, now, random)) {
            return false;
        }
        long planned = schedule.entries().stream().filter(e -> !e.forced() && e.status() == ServerAuctionSchedule.Status.PREVUE).count();
        plugin.getLogger().info((schedule.week().equals(before) ? "Planning des enchères du serveur refait (réglages changés) : "
            : "Planning des enchères du serveur, semaine du " + schedule.week() + " : ") + planned + " à venir.");
        return true;
    }

    private void save() {
        try {
            schedule.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "encheres-serveur.yml non écrit : le planning sera refait au prochain démarrage.", e);
        }
    }

    private static String format(long epoch) {
        return DATE.format(Instant.ofEpochSecond(epoch).atZone(zone()));
    }

    // ------------------------------------------------------------------ lancement

    /** Objet affiché dans l'HDV : apparence du lot, plus le lot lui-même (identifiant, commandes, incrément). */
    ItemStack buildItem(Lot lot) {
        ItemStack item = ItemUtil.createItemStack(lot.material(), lot.amount(), lot.name(), lot.lore());
        if (lot.texture() != null && !lot.texture().isBlank()) {
            item = plugin.getGuiManager().applyHeadTexture(item, lot.texture());
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (lot.glint()) {
                meta.setEnchantmentGlintOverride(true);
            }
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(lotKey, PersistentDataType.STRING, lot.id());
            pdc.set(commandsKey, PersistentDataType.STRING, String.join("\n", lot.commands()));
            pdc.set(incrementKey, PersistentDataType.DOUBLE, lot.increment());
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Met le lot aux enchères, l'annonce et prévient les autres serveurs. @return l'id de l'annonce, ou -1 */
    private long create(Lot lot, long durationSeconds) {
        ItemStack item = buildItem(lot);
        long id = auctions().addBidAuction(AuctionItem.SERVER_SELLER, sellerName(), item, lot.startPrice(), durationSeconds);
        if (id == -1) {
            plugin.getLogger().severe("Enchère du serveur (lot " + lot.id() + ") non enregistrée en base.");
            return -1;
        }
        AuctionManager am = plugin.getAuctionManager();
        String label = plain(label(item));
        am.writeLog("encheres-serveur-lancement", "encheres-serveur", "%lot% -> mis aux enchères par le serveur : %item% (départ %price% SHCoins, durée %duration%)",
            "%lot%", lot.id(),
            "%item%", label,
            "%price%", AuctionManager.logPrice(lot.startPrice()),
            "%duration%", TimeUtil.formatDuration(durationSeconds));
        plugin.getLogger().info("ENCHÈRE DU SERVEUR: lot " + lot.id() + " (" + label + ") mis aux enchères, départ "
            + AuctionManager.logPrice(lot.startPrice()) + ", durée " + TimeUtil.formatDuration(durationSeconds) + " (ID: " + id + ").");
        AuctionItem auction = auctions().getAuctionById(id);
        if (auction != null) {
            sendStartAnnouncement(auction);
            SharedFlows shared = plugin.getSharedFlows();
            if (shared != null) {
                shared.serverListed(auction);
            }
        }
        plugin.getGuiManager().refreshOpenBidMenus();
        return id;
    }

    /** Nom affiché d'un lot : « 3 x Clé Ordinaire », ou le nom seul pour un exemplaire. */
    public String label(ItemStack item) {
        String name = plugin.getAuctionManager().getItemNameForMessage(item);
        return item != null && item.getAmount() > 1 ? item.getAmount() + " x " + name : name;
    }

    /** Texte sans codes couleur, pour les logs. */
    private static String plain(String text) {
        return org.bukkit.ChatColor.stripColor(text);
    }

    // ------------------------------------------------------------------ annonces

    /** Annonce d'une nouvelle enchère du serveur aux joueurs de ce serveur : cliquable, objet survolable. */
    public void sendStartAnnouncement(AuctionItem auction) {
        if (messages().getRaw("server_auction_start", "").isBlank()) {
            return;
        }
        ItemStack item = auction.getItemStack();
        Map<String, String> ph = new HashMap<>();
        ph.put("seller", auction.getSellerName());
        ph.put("price", plugin.getEconomyProvider().formatCurrencyDetailed(auction.getStartingPrice()));
        long remaining = auction.getExpireTimestamp() - System.currentTimeMillis() / 1000L;
        ph.put("duration", TimeUtil.formatDuration(Math.max(0, remaining)));
        String template = messages().getMessage("server_auction_start", false, ph);
        TextComponent line = plugin.getAuctionManager().itemAnnouncement(template, item, label(item), "/hdv encheres",
            messages().getMessage("server_auction_start_hover", false));
        Sound sound = ConfigManager.sound(config().getString(S + "son-annonce", ""));
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.spigot().sendMessage(line);
            if (sound != null) {
                online.playSound(online.getLocation(), sound, 1.0f, 1.0f);
            }
        }
    }

    /** Annonce le gagnant à ce serveur et, en mode réseau, aux autres. */
    public void announceResult(String winner, ItemStack item, double price) {
        Map<String, String> ph = new HashMap<>();
        ph.put("player", winner);
        ph.put("item_name", label(item));
        ph.put("price", plugin.getEconomyProvider().formatCurrencyDetailed(price));
        broadcastLocal("server_auction_result", ph);
        SharedFlows shared = plugin.getSharedFlows();
        if (shared != null) {
            shared.broadcast("server_auction_result", ph);
        }
    }

    /** Message à tous les joueurs de ce serveur (rien si le message est vide dans messages.yml). */
    public void broadcastLocal(String key, Map<String, String> placeholders) {
        if (messages().getRaw(key, "").isBlank()) {
            return;
        }
        String message = messages().getMessage(key, placeholders);
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(message);
        }
    }

    // ------------------------------------------------------------------ mises et règlement

    /** Lot porté par l'objet d'une enchère du serveur, ou null pour un objet ordinaire. */
    public LotData lotOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String commands = pdc.get(commandsKey, PersistentDataType.STRING);
        if (commands == null) {
            return null;
        }
        return new LotData(pdc.getOrDefault(lotKey, PersistentDataType.STRING, "?"), List.of(commands.split("\n")),
            pdc.getOrDefault(incrementKey, PersistentDataType.DOUBLE, -1.0));
    }

    /**
     * Plafond hebdomadaire : enchères du serveur remportées cette semaine plus celles où le joueur est déjà en tête.
     *
     * @return vrai si la mise peut continuer ; sinon le joueur est prévenu
     */
    public boolean mayBid(Player bidder, AuctionItem auction) {
        if (!capReached(bidder, auction)) {
            return true;
        }
        messages().sendMessage(bidder, "server_auction_cap_reached", Map.of("cap", String.valueOf(cap())));
        return false;
    }

    /** Vrai si ce joueur a atteint le plafond hebdomadaire (toujours faux pour une enchère de joueur). */
    public boolean capReached(Player bidder, AuctionItem auction) {
        if (auction == null || !auction.isServerAuction()) {
            return false;
        }
        int cap = cap();
        if (cap <= 0) {
            return false;
        }
        long since = ServerAuctionPlanner.weekStart(ZonedDateTime.now(zone())).toEpochSecond();
        int won = plugin.getDatabaseManager().getSoldItemDAO().countPurchasesFrom(AuctionItem.SERVER_SELLER, bidder.getUniqueId(), since);
        int leading = 0;
        for (AuctionItem other : auctions().getAuctionsBySeller(AuctionItem.SERVER_SELLER)) {
            if (other.getId() != auction.getId() && other.hasBid() && bidder.getUniqueId().equals(other.getHighestBidderUuid())) {
                leading++;
            }
        }
        return !ServerAuctionPlanner.mayBid(won, leading, cap);
    }

    /**
     * Remet un lot : ses commandes sont lancées par la console pour ce joueur (connecté).
     *
     * @return faux si l'objet ne porte pas de lot (objet ordinaire)
     */
    public boolean deliver(Player player, ItemStack item, long auctionId) {
        LotData lot = lotOf(item);
        if (lot == null) {
            return false;
        }
        for (String command : lot.commands()) {
            String line = command.replace("%player%", player.getName()).trim();
            if (line.startsWith("/")) {
                line = line.substring(1);
            }
            try {
                if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line)) {
                    plugin.getLogger().severe("Lot " + lot.id() + " (enchère " + auctionId + ") : commande inconnue « " + line + " ». À lancer à la main pour " + player.getName() + ".");
                }
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Lot " + lot.id() + " (enchère " + auctionId + ") : la commande « " + line + " » a échoué pour " + player.getName() + ".", e);
            }
        }
        plugin.getAuctionManager().writeLog("encheres-serveur-livraison", "encheres-serveur", "%player% -> reçoit le lot %lot% (%item%)",
            "%player%", player.getName(),
            "%lot%", lot.id(),
            "%item%", plain(label(item)));
        plugin.getLogger().info("ENCHÈRE DU SERVEUR: lot " + lot.id() + " remis à " + player.getName() + " (ID: " + auctionId + ").");
        return true;
    }

    /** Commandes d'un lot pour un joueur, pour une remise à la main. */
    public String commandsFor(ItemStack item, String player) {
        LotData lot = lotOf(item);
        return lot == null ? null : String.join(" ; ", lot.commands()).replace("%player%", player);
    }

    /** Enchère du serveur terminée sans offre : rien ne bouge, une ligne dans les logs. */
    public void endedWithoutBid(AuctionItem auction, ItemStack item) {
        plugin.getAuctionManager().writeLog("encheres-serveur-sans-offre", "encheres-serveur", "%item% -> enchère du serveur terminée sans offre (départ %price% SHCoins)",
            "%item%", plain(label(item)),
            "%price%", AuctionManager.logPrice(auction.getStartingPrice()));
        plugin.getLogger().info("ENCHÈRE DU SERVEUR: " + plain(label(item)) + " terminée sans offre (ID: " + auction.getId() + ").");
    }

    // ------------------------------------------------------------------ administration

    /** /shauctions plan */
    public void sendPlan(CommandSender sender) {
        if (!isActive()) {
            messages().sendMessage(sender, "server_auctions_inactive");
            return;
        }
        if (refresh(settings(), ZonedDateTime.now(zone()))) {
            save();
        }
        List<Entry> entries = schedule.entries();
        long count = entries.stream().filter(e -> !e.forced()).count();
        messages().sendMessage(sender, "server_plan_header", Map.of(
            "week", schedule.week() == null ? "?" : java.time.LocalDate.parse(schedule.week()).format(DateTimeFormatter.ofPattern("dd/MM")),
            "count", String.valueOf(count),
            "cap", cap() <= 0 ? "∞" : String.valueOf(cap())));
        if (entries.isEmpty()) {
            messages().sendMessage(sender, "server_plan_empty");
            return;
        }
        for (Entry entry : entries) {
            String statusKey = entry.forced() ? "forcee" : entry.status().name().toLowerCase(Locale.ROOT);
            String status = messages().getMessage("server_plan_status." + statusKey, false, Map.of("lot", entry.lot() == null ? "?" : entry.lot()));
            sender.sendMessage(messages().getMessage("server_plan_line", false, Map.of(
                "date", format(entry.start()),
                "duration", TimeUtil.formatDuration(entry.duration()),
                "status", status)));
        }
    }

    /** /shauctions launch <lot|random> [durée] */
    public void launchNow(CommandSender sender, String lotArg, String durationArg) {
        List<Lot> lots = parseLots(false);
        Lot lot = null;
        if ("random".equalsIgnoreCase(lotArg)) {
            lot = drawLot(lots);
        } else {
            for (Lot candidate : lots) {
                if (candidate.id().equalsIgnoreCase(lotArg)) {
                    lot = candidate;
                    break;
                }
            }
        }
        if (lot == null) {
            messages().sendMessage(sender, "server_launch_unknown_lot", Map.of("lot", lotArg, "lots", String.join(", ", lotIds())));
            return;
        }
        long duration;
        if (durationArg != null) {
            duration = TimeUtil.parseDurationFlexible(durationArg);
        } else {
            Settings settings = settings();
            duration = 60L * (settings.minDurationMinutes() + random.nextInt(settings.maxDurationMinutes() - settings.minDurationMinutes() + 1));
        }
        if (duration <= 0) {
            messages().sendMessage(sender, "invalid_duration_format");
            return;
        }
        long id = create(lot, duration);
        if (id < 0) {
            messages().sendMessage(sender, "generic_error_occurred", Map.of("error_details", "voir la console"));
            return;
        }
        if (isActive()) {
            schedule.addForced(System.currentTimeMillis() / 1000L, duration, lot.id(), id);
            save();
        }
        messages().sendMessage(sender, "server_launch_done", Map.of("lot", lot.id(), "duration", TimeUtil.formatDuration(duration)));
    }

    /** /shauctions skip */
    public void skipNext(CommandSender sender) {
        if (!isActive()) {
            messages().sendMessage(sender, "server_auctions_inactive");
            return;
        }
        refresh(settings(), ZonedDateTime.now(zone()));
        Entry skipped = schedule.skipNext();
        save();
        if (skipped == null) {
            messages().sendMessage(sender, "server_skip_none");
        } else {
            messages().sendMessage(sender, "server_skip_done", Map.of("date", format(skipped.start())));
            plugin.getLogger().info("Enchère du serveur de " + format(skipped.start()) + " retirée du planning par " + sender.getName() + ".");
        }
    }

    /** Identifiants des lots, pour la complétion. */
    public List<String> lotIds() {
        return parseLots(false).stream().map(Lot::id).toList();
    }
}
