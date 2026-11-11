package org.Cry_Legende.sHAuctions.serverauction;

import net.md_5.bungee.api.chat.TextComponent;
import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.config.ConfigManager;
import org.Cry_Legende.sHAuctions.config.MessageManager;
import org.Cry_Legende.sHAuctions.database.dao.AuctionDAO;
import org.Cry_Legende.sHAuctions.event.AuctionItemListedEvent;
import org.Cry_Legende.sHAuctions.manager.AuctionManager;
import org.Cry_Legende.sHAuctions.manager.SharedFlows;
import org.Cry_Legende.sHAuctions.model.AuctionItem;
import org.Cry_Legende.sHAuctions.serverauction.ServerAuctionPlanner.Settings;
import org.Cry_Legende.sHAuctions.serverauction.ServerAuctionSchedule.Entry;
import org.Cry_Legende.sHAuctions.serverauction.ServerAuctionSchedule.Kind;
import org.Cry_Legende.sHAuctions.serverauction.ServerAuctionSchedule.Status;
import org.Cry_Legende.sHAuctions.util.ItemUtil;
import org.Cry_Legende.sHAuctions.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
import java.time.LocalDate;
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
 * Enchères et ventes du serveur : chaque jour, le serveur met lui-même des lots aux enchères (page Enchères) et en
 * vente à prix fixe (page Ventes).
 *
 * <ul>
 *   <li>Ce sont des annonces ordinaires dont le vendeur est {@link AuctionItem#SERVER_SELLER} : affichage, mises,
 *   remboursements, anti-snipe et achat sont ceux de l'HDV.</li>
 *   <li>L'objet affiché porte son lot (identifiant, type, commandes, incrément) : n'importe quel serveur du réseau sait
 *   le régler, même après un reload qui a changé la liste des lots.</li>
 *   <li>L'argent de l'acheteur ou du gagnant n'est versé à personne. Le lot est remis par ses commandes si le joueur est
 *   là avec une place libre, sinon l'objet affiché part dans ses invendus et le lot est remis quand il le récupère
 *   (l'objet affiché n'est jamais donné).</li>
 * </ul>
 *
 * <p>Seul le serveur où {@code encheres-serveur.actif} ou {@code ventes-serveur.actif} vaut true tient le planning et
 * lance les annonces de ce type.</p>
 */
public final class ServerAuctionManager {

    private static final long TICK_PERIOD = 20L * 20;
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("H:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE dd/MM HH:mm", Locale.FRENCH);

    /** Un lot de config.yml (prix : départ d'une enchère, ou prix fixe d'une vente). */
    public record Lot(Kind kind, String id, int weight, double price, double increment, Material material, int amount, String name,
                      List<String> lore, String texture, boolean glint, List<String> commands) {
    }

    /** Lot porté par l'objet d'une annonce du serveur. */
    public record LotData(Kind kind, String id, List<String> commands, double increment) {
    }

    private final SHAuctions plugin;
    private final NamespacedKey lotKey;
    private final NamespacedKey kindKey;
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
        this.kindKey = new NamespacedKey(plugin, "lot-type");
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

    private static String section(Kind kind) {
        return kind == Kind.ENCHERE ? "encheres-serveur." : "ventes-serveur.";
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

    /** Vrai si ce serveur lance les annonces de ce type. */
    public boolean isActive(Kind kind) {
        return config().getBoolean(section(kind) + "actif", false);
    }

    /** Vrai si ce serveur tient un planning (enchères ou ventes). */
    public boolean isActive() {
        return isActive(Kind.ENCHERE) || isActive(Kind.VENTE);
    }

    private void checkLots() {
        for (Kind kind : Kind.values()) {
            List<Lot> lots = parseLots(kind, true);
            if (isActive(kind)) {
                long drawable = lots.stream().filter(lot -> lot.weight() > 0).count();
                plugin.getLogger().info((kind == Kind.ENCHERE ? "Enchères" : "Ventes") + " du serveur actives : " + lots.size()
                    + " lot(s), dont " + drawable + " tirable(s).");
                if (drawable == 0) {
                    plugin.getLogger().warning(section(kind) + "lots : aucun lot avec un poids positif, rien ne sera lancé.");
                }
            }
        }
    }

    // ------------------------------------------------------------------ réglages

    /** Réglages du planning d'un type, ou null s'il est désactivé sur ce serveur. */
    Settings settings(Kind kind) {
        if (!isActive(kind)) {
            return null;
        }
        String s = section(kind);
        boolean auction = kind == Kind.ENCHERE;
        return new Settings(config().getInt(s + "par-jour.min", 1), config().getInt(s + "par-jour.max", 3),
            time(s + "plage-horaire.debut", "12:00"), time(s + "plage-horaire.fin", "23:30"),
            config().getInt(s + "ecart-minimal-minutes", auction ? 180 : 60),
            config().getInt(s + "duree-minutes.min", auction ? 120 : 360), config().getInt(s + "duree-minutes.max", auction ? 240 : 720));
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

    private String sellerName(Kind kind) {
        return config().getString(section(kind) + "vendeur", "SkyHand");
    }

    /** Lots de config.yml d'un type ; un lot incomplet est ignoré (signalé dans la console si {@code warn}). */
    public List<Lot> parseLots(Kind kind, boolean warn) {
        String s = section(kind);
        String priceKey = kind == Kind.ENCHERE ? "prix-depart" : "prix";
        List<Lot> lots = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        double globalIncrement = config().getDouble("bids.min_bid_increment", 1.0);
        int index = 0;
        for (Map<?, ?> raw : config().getRawConfig().getMapList(s + "lots")) {
            index++;
            String id = text(raw.get("id"));
            if (id == null || id.isBlank()) {
                lotWarning(warn, s, "lot n°" + index + " sans id, ignoré.");
                continue;
            }
            if (!ids.add(id.toLowerCase(Locale.ROOT))) {
                lotWarning(warn, s, "lot " + id + " en double, seul le premier est gardé.");
                continue;
            }
            Map<?, ?> item = raw.get("objet") instanceof Map<?, ?> map ? map : Map.of();
            String materialName = text(item.get("materiau"));
            Material material = materialName == null ? null : Material.matchMaterial(materialName);
            if (material == null || !material.isItem() || material.isAir()) {
                lotWarning(warn, s, "lot " + id + " : objet.materiau « " + materialName + " » inconnu, lot ignoré.");
                continue;
            }
            List<String> commands = texts(raw.get("commandes"));
            if (commands.isEmpty()) {
                lotWarning(warn, s, "lot " + id + " sans commandes, ignoré.");
                continue;
            }
            double price = number(raw.get(priceKey), 0);
            if (price <= 0) {
                lotWarning(warn, s, "lot " + id + " : " + priceKey + " manquant ou nul, lot ignoré.");
                continue;
            }
            double increment = number(raw.get("increment-min"), 0);
            int amount = (int) Math.max(1, Math.min(material.getMaxStackSize(), number(item.get("quantite"), 1)));
            lots.add(new Lot(kind, id, (int) Math.max(0, number(raw.get("poids"), 0)), price, increment > 0 ? increment : globalIncrement,
                material, amount, text(item.get("nom")), texts(item.get("description")), text(item.get("texture")),
                Boolean.TRUE.equals(item.get("brillant")), commands));
        }
        return lots;
    }

    private void lotWarning(boolean warn, String section, String message) {
        if (warn) {
            plugin.getLogger().warning("config.yml " + section + "lots : " + message);
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

    /** Passage périodique (thread principal) : planning de la semaine, puis lancement de ce qui est arrivé à l'heure. */
    void tick() {
        if (!isActive()) {
            return;
        }
        Settings auctions = settings(Kind.ENCHERE);
        Settings sales = settings(Kind.VENTE);
        ZonedDateTime now = ZonedDateTime.now(zone());
        boolean changed = refresh(auctions, sales, now);
        ServerAuctionSchedule.Due due = schedule.due(auctions, sales, now);
        for (Entry entry : due.missed()) {
            plugin.getLogger().warning(label(entry.kind()) + " du serveur de " + format(entry.start()) + " manquée : serveur éteint pendant sa plage horaire.");
            changed = true;
        }
        for (Entry entry : due.toLaunch()) {
            Lot lot = drawLot(parseLots(entry.kind(), false));
            long id = lot == null ? -1 : create(lot, entry.duration());
            if (id < 0) {
                plugin.getLogger().warning(label(entry.kind()) + " du serveur de " + format(entry.start()) + " non lancée (aucun lot valide ou erreur base).");
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

    private static String label(Kind kind) {
        return kind == Kind.ENCHERE ? "Enchère" : "Vente";
    }

    private boolean refresh(Settings auctions, Settings sales, ZonedDateTime now) {
        String before = schedule.week();
        if (!schedule.refresh(auctions, sales, now, random)) {
            return false;
        }
        long planned = schedule.entries().stream().filter(e -> e.kind() == Kind.ENCHERE && e.status() == Status.PREVUE).count();
        long plannedSales = schedule.entries().stream().filter(e -> e.kind() == Kind.VENTE && e.status() == Status.PREVUE).count();
        plugin.getLogger().info((schedule.week().equals(before) ? "Planning du serveur refait (réglages changés) : "
            : "Planning du serveur, semaine du " + schedule.week() + " : ") + planned + " enchère(s) et " + plannedSales + " vente(s) à venir.");
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

    /** Objet affiché dans l'HDV : apparence du lot, plus le lot lui-même (identifiant, type, commandes, incrément). */
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
            pdc.set(kindKey, PersistentDataType.STRING, lot.kind().id());
            pdc.set(commandsKey, PersistentDataType.STRING, String.join("\n", lot.commands()));
            if (lot.kind() == Kind.ENCHERE) {
                pdc.set(incrementKey, PersistentDataType.DOUBLE, lot.increment());
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Met le lot aux enchères ou en vente, l'annonce et prévient les autres serveurs. @return l'id de l'annonce, ou -1 */
    private long create(Lot lot, long durationSeconds) {
        ItemStack item = buildItem(lot);
        boolean auction = lot.kind() == Kind.ENCHERE;
        String seller = sellerName(lot.kind());
        long id = auction
            ? auctions().addBidAuction(AuctionItem.SERVER_SELLER, seller, item, lot.price(), durationSeconds)
            : auctions().addAuction(AuctionItem.SERVER_SELLER, seller, item, lot.price(), durationSeconds);
        if (id == -1) {
            plugin.getLogger().severe(label(lot.kind()) + " du serveur (lot " + lot.id() + ") non enregistrée en base.");
            return -1;
        }
        AuctionManager am = plugin.getAuctionManager();
        String name = plain(label(item));
        if (auction) {
            am.writeLog("encheres-serveur-lancement", "encheres-serveur", "%lot% -> mis aux enchères par le serveur : %item% (départ %price% SHCoins, durée %duration%)",
                "%lot%", lot.id(), "%item%", name, "%price%", AuctionManager.logPrice(lot.price()), "%duration%", TimeUtil.formatDuration(durationSeconds));
        } else {
            am.writeLog("ventes-serveur-mise-en-vente", "ventes-serveur", "%lot% -> mis en vente par le serveur : %item% (%price% SHCoins, durée %duration%)",
                "%lot%", lot.id(), "%item%", name, "%price%", AuctionManager.logPrice(lot.price()), "%duration%", TimeUtil.formatDuration(durationSeconds));
        }
        plugin.getLogger().info((auction ? "ENCHÈRE" : "VENTE") + " DU SERVEUR: lot " + lot.id() + " (" + name + ") à "
            + AuctionManager.logPrice(lot.price()) + ", durée " + TimeUtil.formatDuration(durationSeconds) + " (ID: " + id + ").");
        AuctionItem listed = auctions().getAuctionById(id);
        if (listed != null) {
            sendAnnouncement(listed);
            SharedFlows shared = plugin.getSharedFlows();
            if (shared != null) {
                shared.serverListed(listed);
            }
            Bukkit.getPluginManager().callEvent(new AuctionItemListedEvent(listed));
        }
        if (auction) {
            plugin.getGuiManager().refreshOpenBidMenus();
        }
        return id;
    }

    /** Nom affiché d'un lot : « 3 x Clé Ordinaire », ou le nom seul pour un exemplaire. */
    public String label(ItemStack item) {
        String name = plugin.getAuctionManager().getItemNameForMessage(item);
        return item != null && item.getAmount() > 1 ? item.getAmount() + " x " + name : name;
    }

    /** Texte sans codes couleur, pour les logs. */
    private static String plain(String text) {
        return ChatColor.stripColor(text);
    }

    // ------------------------------------------------------------------ annonces

    /**
     * Annonce d'une nouvelle enchère ou vente du serveur aux joueurs de ce serveur : cliquable (page Enchères ou
     * Ventes), objet survolable.
     */
    public void sendAnnouncement(AuctionItem listed) {
        boolean auction = listed.isBid();
        String key = auction ? "server_auction_start" : "server_sale_start";
        if (messages().getRaw(key, "").isBlank()) {
            return;
        }
        ItemStack item = listed.getItemStack();
        Map<String, String> ph = new HashMap<>();
        ph.put("seller", listed.getSellerName());
        ph.put("price", plugin.getEconomyProvider().formatCurrencyDetailed(listed.getPrice()));
        long remaining = listed.getExpireTimestamp() - System.currentTimeMillis() / 1000L;
        ph.put("duration", TimeUtil.formatDuration(Math.max(0, remaining)));
        String template = messages().getMessage(key, false, ph);
        TextComponent line = plugin.getAuctionManager().itemAnnouncement(template, item, label(item), auction ? "/hdv encheres" : "/hdv",
            messages().getMessage(key + "_hover", false));
        Sound sound = ConfigManager.sound(config().getString(section(auction ? Kind.ENCHERE : Kind.VENTE) + "son-annonce", ""));
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.spigot().sendMessage(line);
            if (sound != null) {
                online.playSound(online.getLocation(), sound, 1.0f, 1.0f);
            }
        }
    }

    /** Annonce le gagnant d'une enchère du serveur à ce serveur et, en mode réseau, aux autres. */
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

    // ------------------------------------------------------------------ règlement

    /** Lot porté par l'objet d'une annonce du serveur, ou null pour un objet ordinaire. */
    public LotData lotOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String commands = pdc.get(commandsKey, PersistentDataType.STRING);
        if (commands == null) {
            return null;
        }
        Kind kind = "vente".equals(pdc.get(kindKey, PersistentDataType.STRING)) ? Kind.VENTE : Kind.ENCHERE;
        return new LotData(kind, pdc.getOrDefault(lotKey, PersistentDataType.STRING, "?"), List.of(commands.split("\n")),
            pdc.getOrDefault(incrementKey, PersistentDataType.DOUBLE, -1.0));
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
                    plugin.getLogger().severe("Lot " + lot.id() + " (annonce " + auctionId + ") : commande inconnue « " + line + " ». À lancer à la main pour " + player.getName() + ".");
                }
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Lot " + lot.id() + " (annonce " + auctionId + ") : la commande « " + line + " » a échoué pour " + player.getName() + ".", e);
            }
        }
        String prefix = lot.kind() == Kind.ENCHERE ? "encheres-serveur" : "ventes-serveur";
        plugin.getAuctionManager().writeLog(prefix + "-livraison", prefix, "%player% -> reçoit le lot %lot% (%item%)",
            "%player%", player.getName(),
            "%lot%", lot.id(),
            "%item%", plain(label(item)));
        plugin.getLogger().info(label(lot.kind()).toUpperCase(Locale.ROOT) + " DU SERVEUR: lot " + lot.id() + " remis à " + player.getName() + " (ID: " + auctionId + ").");
        return true;
    }

    /** Commandes d'un lot pour un joueur, pour une remise à la main. */
    public String commandsFor(ItemStack item, String player) {
        LotData lot = lotOf(item);
        return lot == null ? null : String.join(" ; ", lot.commands()).replace("%player%", player);
    }

    /** Enchère du serveur terminée sans offre, ou vente du serveur jamais achetée : rien ne bouge, une ligne de log. */
    public void endedWithoutBid(AuctionItem listed, ItemStack item) {
        String name = plain(label(item));
        String price = AuctionManager.logPrice(listed.getPrice());
        if (listed.isBid()) {
            plugin.getAuctionManager().writeLog("encheres-serveur-sans-offre", "encheres-serveur", "%item% -> enchère du serveur terminée sans offre (départ %price% SHCoins)",
                "%item%", name, "%price%", price);
            plugin.getLogger().info("ENCHÈRE DU SERVEUR: " + name + " terminée sans offre (ID: " + listed.getId() + ").");
        } else {
            plugin.getAuctionManager().writeLog("ventes-serveur-invendue", "ventes-serveur", "%item% -> vente du serveur terminée sans acheteur (%price% SHCoins)",
                "%item%", name, "%price%", price);
            plugin.getLogger().info("VENTE DU SERVEUR: " + name + " terminée sans acheteur (ID: " + listed.getId() + ").");
        }
    }

    // ------------------------------------------------------------------ administration

    private static Kind kindArg(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "vente", "ventes" -> Kind.VENTE;
            case "enchere", "enchère", "encheres", "enchères" -> Kind.ENCHERE;
            default -> null;
        };
    }

    /** /shauctions plan */
    public void sendPlan(CommandSender sender) {
        if (!isActive()) {
            messages().sendMessage(sender, "server_auctions_inactive");
            return;
        }
        if (refresh(settings(Kind.ENCHERE), settings(Kind.VENTE), ZonedDateTime.now(zone()))) {
            save();
        }
        List<Entry> entries = schedule.entries();
        messages().sendMessage(sender, "server_plan_header", Map.of(
            "week", schedule.week() == null ? "?" : LocalDate.parse(schedule.week()).format(DateTimeFormatter.ofPattern("dd/MM")),
            "auctions", String.valueOf(entries.stream().filter(e -> e.kind() == Kind.ENCHERE && !e.forced()).count()),
            "sales", String.valueOf(entries.stream().filter(e -> e.kind() == Kind.VENTE && !e.forced()).count())));
        if (entries.isEmpty()) {
            messages().sendMessage(sender, "server_plan_empty");
            return;
        }
        for (Entry entry : entries) {
            String statusKey = entry.forced() ? "forcee" : entry.status().name().toLowerCase(Locale.ROOT);
            String status = messages().getMessage("server_plan_status." + statusKey, false, Map.of("lot", entry.lot() == null ? "?" : entry.lot()));
            sender.sendMessage(messages().getMessage("server_plan_line", false, Map.of(
                "date", format(entry.start()),
                "kind", messages().getMessage("server_plan_kind." + entry.kind().id(), false),
                "duration", TimeUtil.formatDuration(entry.duration()),
                "status", status)));
        }
    }

    /** /shauctions launch [vente|enchere] <lot|random> [durée] (enchère si le type est omis) */
    public void launchNow(CommandSender sender, String[] args) {
        int first = 0;
        Kind kind = kindArg(args.length > 0 ? args[0] : null);
        if (kind != null) {
            first = 1;
        } else {
            kind = Kind.ENCHERE;
        }
        if (args.length <= first) {
            messages().sendMessage(sender, "server_launch_usage");
            return;
        }
        String lotArg = args[first];
        String durationArg = args.length > first + 1 ? args[first + 1] : null;
        List<Lot> lots = parseLots(kind, false);
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
            messages().sendMessage(sender, "server_launch_unknown_lot", Map.of("lot", lotArg, "lots", String.join(", ", lotIds(kind))));
            return;
        }
        long duration;
        if (durationArg != null) {
            duration = TimeUtil.parseDurationFlexible(durationArg);
        } else {
            boolean auction = kind == Kind.ENCHERE;
            int min = config().getInt(section(kind) + "duree-minutes.min", auction ? 120 : 360);
            int max = Math.max(min, config().getInt(section(kind) + "duree-minutes.max", auction ? 240 : 720));
            duration = 60L * (min + random.nextInt(max - min + 1));
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
            schedule.addForced(kind, System.currentTimeMillis() / 1000L, duration, lot.id(), id);
            save();
        }
        messages().sendMessage(sender, "server_launch_done", Map.of(
            "kind", messages().getMessage("server_plan_kind." + kind.id(), false),
            "lot", lot.id(),
            "duration", TimeUtil.formatDuration(duration)));
    }

    /** /shauctions skip [vente|enchere] */
    public void skipNext(CommandSender sender, String kindRaw) {
        if (!isActive()) {
            messages().sendMessage(sender, "server_auctions_inactive");
            return;
        }
        refresh(settings(Kind.ENCHERE), settings(Kind.VENTE), ZonedDateTime.now(zone()));
        Entry skipped = schedule.skipNext(kindArg(kindRaw));
        save();
        if (skipped == null) {
            messages().sendMessage(sender, "server_skip_none");
        } else {
            messages().sendMessage(sender, "server_skip_done", Map.of(
                "kind", messages().getMessage("server_plan_kind." + skipped.kind().id(), false),
                "date", format(skipped.start())));
            plugin.getLogger().info(label(skipped.kind()) + " du serveur de " + format(skipped.start()) + " retirée du planning par " + sender.getName() + ".");
        }
    }

    /** Identifiants des lots d'un type, pour la complétion. */
    public List<String> lotIds(Kind kind) {
        return parseLots(kind, false).stream().map(Lot::id).toList();
    }
}
