package org.Cry_Legende.sHAuctions.manager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.config.ConfigManager;
import org.Cry_Legende.sHAuctions.config.MessageManager;
import org.Cry_Legende.sHAuctions.database.dao.AuctionDAO;
import org.Cry_Legende.sHAuctions.database.impl.MariaAuctionDAO;
import org.Cry_Legende.sHAuctions.database.impl.MariaAuctionDAO.BidOutcome;
import org.Cry_Legende.sHAuctions.database.impl.MariaAuctionDAO.ExpiredEntry;
import org.Cry_Legende.sHAuctions.database.impl.MariaAuctionDAO.Sale;
import org.Cry_Legende.sHAuctions.database.impl.MariaAuctionDAO.Settlement;
import org.Cry_Legende.sHAuctions.database.impl.MariaAuctionDAO.SettleKind;
import org.Cry_Legende.sHAuctions.database.impl.MariaAuctionDAO.StaleClaim;
import org.Cry_Legende.sHAuctions.database.impl.MariaAuctionDAO.UncertainCommit;
import org.Cry_Legende.sHAuctions.economy.EconomyProvider;
import org.Cry_Legende.sHAuctions.event.AuctionItemCancelledEvent;
import org.Cry_Legende.sHAuctions.event.AuctionItemExpiredEvent;
import org.Cry_Legende.sHAuctions.event.AuctionItemPurchasedEvent;
import org.Cry_Legende.sHAuctions.model.AuctionItem;
import org.Cry_Legende.sHAuctions.model.ExpiredItem;
import org.Cry_Legende.sHAuctions.network.NetBus;
import org.Cry_Legende.sHAuctions.util.SerializationUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Hôtel des ventes en mode réseau : une seule base MariaDB pour tous les serveurs, et c'est elle qui décide.
 *
 * <ul>
 *   <li><b>Achat</b> : l'annonce est d'abord réservée (UPDATE conditionnel, un seul serveur gagne), puis l'acheteur
 *   paie, le vendeur est payé, et l'annonce est supprimée avec l'écriture de la vente dans une seule transaction.
 *   Un achat qui échoue en route rend l'argent et libère la réservation.</li>
 *   <li><b>Mise</b> : l'argent est mis de côté, puis une transaction verrouille l'enchère, revérifie tout et rend
 *   l'enchérisseur qu'elle remplace : c'est lui, et lui seul, qui est remboursé.</li>
 *   <li><b>Annulation</b> et <b>échéance</b> : transaction qui verrouille, supprime et range l'objet ; le
 *   remboursement ou le paiement se calcule sur la ligne verrouillée. Les échéances ne tournent que sur le
 *   serveur meneur.</li>
 *   <li><b>Joueur sur un autre serveur</b> : les avis (surenchère, vente, enchère gagnée, annulation) lui sont envoyés
 *   par le bus ; les menus ouverts partout se rafraîchissent.</li>
 * </ul>
 *
 * <p>Toutes les méthodes s'exécutent sur le fil principal, comme leurs équivalents SQLite.</p>
 */
public final class SharedFlows {

    /** Rendu par {@link #list} quand l'objet n'était plus entièrement dans l'inventaire. */
    public static final long ITEM_NOT_REMOVED = -2;
    /** Une réservation plus vieille que ce délai est orpheline (serveur arrêté au milieu d'une opération). */
    private static final long STALE_SECONDS = 600;
    private static final String LEADER_TASK = "shauctions-echeances";

    private final SHAuctions plugin;
    private final AuctionManager am;
    private final NetBus bus;
    private final MariaAuctionDAO auctions;
    private final String channel;
    private final Set<Long> staleWarned = new HashSet<>();
    private NetBus.Leader leader;

    public SharedFlows(SHAuctions plugin, AuctionManager am, NetBus bus, MariaAuctionDAO auctions, String channel) {
        this.plugin = plugin;
        this.am = am;
        this.bus = bus;
        this.auctions = auctions;
        this.channel = channel;
    }

    public void enable() {
        bus.listen(channel, this::onMessage);
        leader = bus.leader(LEADER_TASK,
            () -> plugin.getLogger().info("Ce serveur règle les échéances de l'HDV pour tout le réseau."),
            () -> plugin.getLogger().info("Ce serveur ne règle plus les échéances de l'HDV."));
    }

    public void disable() {
        if (leader != null) {
            leader.release();
            leader = null;
        }
    }

    /** Vrai si ce serveur règle les échéances (meneur). */
    public boolean isExpiryOwner() {
        NetBus.Leader current = leader;
        return current != null && current.isLeader();
    }

    private ConfigManager config() {
        return plugin.getConfigManager();
    }

    private MessageManager messages() {
        return plugin.getMessageManager();
    }

    private EconomyProvider economy() {
        return plugin.getEconomyProvider();
    }

    private AuctionDAO dao() {
        return plugin.getDatabaseManager().getAuctionDAO();
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    // ------------------------------------------------------------------ présence

    private static Player local(UUID uuid) {
        Player player = uuid == null ? null : Bukkit.getPlayer(uuid);
        return player != null && player.isOnline() ? player : null;
    }

    /** Connecté sur un autre serveur du réseau. */
    private boolean elsewhere(UUID uuid) {
        if (uuid == null || local(uuid) != null) {
            return false;
        }
        String server = bus.serverOf(uuid);
        return server != null && !server.equals(bus.serverName());
    }

    private boolean onlineAnywhere(UUID uuid) {
        return local(uuid) != null || elsewhere(uuid);
    }

    // ------------------------------------------------------------------ bus

    /**
     * Message à un joueur : direct s'il est ici, par le bus s'il est sur un autre serveur, rien s'il est hors ligne
     * (comme sur un serveur seul).
     *
     * @param soundKey clé de config du son (misc.sound_on_...), ou null
     */
    private void notify(UUID target, String key, Map<String, String> placeholders, String soundKey) {
        Player player = local(target);
        if (player != null) {
            messages().sendMessage(player, key, placeholders);
            if (soundKey != null) {
                am.playSound(player, config().getString(soundKey));
            }
            return;
        }
        if (!elsewhere(target)) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("type", "notice");
        o.addProperty("uuid", target.toString());
        o.addProperty("key", key);
        JsonObject ph = new JsonObject();
        placeholders.forEach(ph::addProperty);
        o.add("ph", ph);
        if (soundKey != null) {
            o.addProperty("sound", soundKey);
        }
        bus.publish(channel, o);
    }

    /** Prévient les autres serveurs qu'une annonce a changé, pour leurs menus ouverts. */
    private void changed(String kind, long id, UUID seller, UUID buyer) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "changed");
        o.addProperty("kind", kind);
        o.addProperty("id", id);
        o.addProperty("seller", seller.toString());
        if (buyer != null) {
            o.addProperty("buyer", buyer.toString());
        }
        bus.publish(channel, o);
    }

    /** Annonce d'une enchère sur les autres serveurs. */
    void announce(String sellerName, AuctionItem auction) {
        String item = auction.getItemSerialized();
        if (item == null) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("type", "announce");
        o.addProperty("seller", sellerName);
        o.addProperty("sellerUuid", auction.getSellerUuid().toString());
        o.addProperty("id", auction.getId());
        o.addProperty("price", auction.getPrice());
        o.addProperty("item", item);
        bus.publish(channel, o);
    }

    private void onMessage(JsonObject o) {
        String type = text(o, "type");
        if (type == null) {
            return;
        }
        try {
            switch (type) {
                case "notice" -> {
                    Player player = local(UUID.fromString(text(o, "uuid")));
                    if (player == null) {
                        return;
                    }
                    Map<String, String> ph = new HashMap<>();
                    if (o.has("ph") && o.get("ph").isJsonObject()) {
                        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("ph").entrySet()) {
                            ph.put(e.getKey(), e.getValue().getAsString());
                        }
                    }
                    messages().sendMessage(player, text(o, "key"), ph);
                    String sound = text(o, "sound");
                    if (sound != null) {
                        am.playSound(player, config().getString(sound));
                    }
                }
                case "changed" -> {
                    String buyer = text(o, "buyer");
                    plugin.getMenuRefreshListener().refreshFromNetwork(text(o, "kind"), o.get("id").getAsLong(),
                        UUID.fromString(text(o, "seller")), buyer == null ? null : UUID.fromString(buyer));
                }
                case "announce" -> {
                    AuctionItem auction = new AuctionItem(o.get("id").getAsLong(), UUID.fromString(text(o, "sellerUuid")), text(o, "seller"),
                        text(o, "item"), o.get("price").getAsDouble(), 0L, 0L, AuctionItem.TYPE_BID, -1, null, null);
                    am.sendBidAnnouncement(text(o, "seller"), auction);
                }
                default -> {
                    // type inconnu : message d'une version plus récente, ignoré
                }
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Message réseau de l'HDV illisible : " + o, e);
        }
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    // ------------------------------------------------------------------ mise en vente

    /**
     * Met en vente : l'annonce est écrite réservée (invisible), l'objet quitte l'inventaire, puis l'annonce devient
     * visible. Personne ne peut donc acheter un objet encore dans l'inventaire du vendeur.
     *
     * @return l'id de l'annonce, -1 si la base a refusé, {@link #ITEM_NOT_REMOVED} si l'objet n'y était plus
     */
    long list(Player seller, ItemStack itemInHand, ItemStack itemToSell, double price, long duration, String type) {
        String token = UUID.randomUUID().toString();
        long id = auctions.insertListing(seller.getUniqueId(), seller.getName(), itemToSell, price, duration, type, token, bus.serverName());
        if (id == -1) {
            return -1;
        }
        ItemStack toRemove = itemInHand.clone();
        toRemove.setAmount(itemToSell.getAmount());
        ItemStack template = toRemove.clone();
        int wanted = toRemove.getAmount();
        int missing = seller.getInventory().removeItem(toRemove).values().stream().mapToInt(ItemStack::getAmount).sum();
        if (missing > 0) {
            int removed = wanted - missing;
            if (removed > 0) {
                template.setAmount(removed);
                am.giveOrDrop(seller, template);
            }
            if (!auctions.deleteClaimed(id, token)) {
                plugin.getLogger().warning("Listing " + id + " by " + seller.getName() + " stays reserved and invisible; it will be deleted by the expiry task.");
            }
            plugin.getLogger().warning("Listing " + id + " by " + seller.getName() + " cancelled: the item was no longer fully in the inventory.");
            return ITEM_NOT_REMOVED;
        }
        if (!auctions.releaseClaim(id, token) && !auctions.releaseClaim(id, token)) {
            // Base injoignable : l'annonce reste invisible, l'objet est rendu et la tâche des échéances la supprimera
            am.giveOrDrop(seller, template);
            auctions.deleteClaimed(id, token);
            plugin.getLogger().severe("Listing " + id + " by " + seller.getName() + " could not be published (database); item given back.");
            return -1;
        }
        changed("listed", id, seller.getUniqueId(), null);
        return id;
    }

    // ------------------------------------------------------------------ achat

    boolean purchase(Player buyer, long auctionId) {
        if (!buyer.hasPermission("shauctions.buy")) {
            messages().sendMessage(buyer, "no_permission");
            return false;
        }
        AuctionItem listed = dao().getAuctionById(auctionId);
        if (listed == null || listed.isBid()) {
            messages().sendMessage(buyer, "item_not_found");
            return false;
        }
        if (listed.getSellerUuid().equals(buyer.getUniqueId())) {
            messages().sendMessage(buyer, "cannot_buy_own_item");
            return false;
        }
        if (listed.isExpired()) {
            messages().sendMessage(buyer, "item_not_found_or_expired");
            return false;
        }
        if (!economy().isEconomyReady()) {
            messages().sendMessage(buyer, "economy_disabled_error");
            return false;
        }
        if (!economy().hasEnough(buyer, listed.getPrice())) {
            messages().sendMessage(buyer, "not_enough_money", Collections.singletonMap("price", economy().formatCurrency(listed.getPrice())));
            return false;
        }
        if (buyer.getInventory().firstEmpty() == -1) {
            messages().sendMessage(buyer, "inventory_full");
            return false;
        }
        if (listed.getItemStack() == null) {
            plugin.getLogger().severe("Critical error: Failed to deserialize item for auction " + auctionId + " during purchase (pre-transaction).");
            messages().sendMessage(buyer, "generic_error_occurred", Collections.singletonMap("error_details", "Item data error before purchase."));
            return false;
        }

        // 1. Réservation : un seul serveur l'obtient
        String token = UUID.randomUUID().toString();
        AuctionItem auction;
        try {
            auction = auctions.claimForPurchase(auctionId, token, bus.serverName());
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Purchase of auction " + auctionId + " by " + buyer.getName() + ": reservation failed", e);
            messages().sendMessage(buyer, "generic_error_occurred", Collections.singletonMap("error_details", "Database error."));
            return false;
        }
        if (auction == null) {
            messages().sendMessage(buyer, "item_not_found");
            return false;
        }
        ItemStack item = auction.getItemStack();
        double price = auction.getPrice();
        if (item == null) {
            auctions.releaseClaim(auctionId, token);
            messages().sendMessage(buyer, "generic_error_occurred", Collections.singletonMap("error_details", "Item data error before purchase."));
            return false;
        }

        // 2. Argent : l'acheteur paie, le vendeur est payé ; un échec rend tout et libère la réservation
        if (!economy().withdraw(buyer, price)) {
            auctions.releaseClaim(auctionId, token);
            messages().sendMessage(buyer, "generic_error_occurred", Collections.singletonMap("error_details", "Economy withdrawal failed."));
            return false;
        }
        OfflinePlayer seller = Bukkit.getOfflinePlayer(auction.getSellerUuid());
        if (!economy().deposit(seller, price)) {
            economy().deposit(buyer, price);
            auctions.releaseClaim(auctionId, token);
            plugin.getLogger().severe("Critical error: Failed to deposit money to seller " + auction.getSellerName() + " (UUID: " + auction.getSellerUuid()
                + ") for auction " + auctionId + ". Buyer " + buyer.getName() + " was refunded.");
            messages().sendMessage(buyer, "generic_error_occurred", Collections.singletonMap("error_details", "Economy deposit to seller failed."));
            return false;
        }

        // 3. Annonce supprimée et vente écrite ensemble
        String itemName = am.getItemNameForMessage(item);
        boolean sellerHere = local(auction.getSellerUuid()) != null;
        boolean sellerElsewhere = !sellerHere && elsewhere(auction.getSellerUuid());
        Sale sale = new Sale(auctionId, auction.getSellerUuid(), auction.getSellerName(), buyer.getUniqueId(), buyer.getName(), itemName,
            auction.getItemSerialized(), price, AuctionItem.TYPE_SALE, sellerHere || sellerElsewhere);
        Boolean finalized = finalizePurchase(auctionId, token, sale);
        if (Boolean.FALSE.equals(finalized)) {
            // La réservation a disparu sans nous : rien n'est livré, les deux virements sont repris
            if (!economy().withdraw(seller, price)) {
                plugin.getLogger().severe("Critical error: auction " + auctionId + " lost its reservation and " + economy().formatCurrency(price)
                    + " could not be taken back from seller " + auction.getSellerUuid() + ". Manual check needed.");
            }
            economy().deposit(buyer, price);
            messages().sendMessage(buyer, "item_not_found");
            return false;
        }
        if (finalized == null) {
            plugin.getLogger().severe("Critical error: auction " + auctionId + " was paid by " + buyer.getName() + " (" + logPrice(price) + ") and is delivered, "
                + "but the database did not confirm its removal: row stays reserved (claim BUY), delete it and add the sale by hand.");
        }

        // 4. Livraison et avis
        am.invalidateDialogs(auctionId);
        am.giveOrDrop(buyer, item.clone());
        am.writeLog("achats", "achats", "%buyer% -> achète %amount% x %item% à %seller% (%price% SHCoins)",
            "%buyer%", buyer.getName(),
            "%seller%", auction.getSellerName(),
            "%amount%", String.valueOf(item.getAmount()),
            "%item%", itemName,
            "%price%", logPrice(price));
        Map<String, String> buyerPh = new HashMap<>();
        buyerPh.put("item_name", itemName);
        buyerPh.put("price", economy().formatCurrency(price));
        buyerPh.put("seller", auction.getSellerName());
        messages().sendMessage(buyer, "item_purchased_successfully", buyerPh);
        am.playSound(buyer, config().getString("misc.sound_on_purchase"));
        Map<String, String> sellerPh = new HashMap<>();
        sellerPh.put("item_name", itemName);
        sellerPh.put("price", economy().formatCurrency(price));
        notify(auction.getSellerUuid(), "item_sold_successfully", sellerPh, "misc.sound_on_sell");
        if (config().getBoolean("misc.log_transactions", true)) {
            plugin.getLogger().info(String.format("ACHAT: %s a acheté %d x %s de %s pour %s. (ID Enchère: %d)", buyer.getName(), item.getAmount(),
                itemName, auction.getSellerName(), economy().formatCurrency(price), auctionId));
        }
        AuctionItem snapshot = new AuctionItem(auctionId, auction.getSellerUuid(), auction.getSellerName(), item.clone(), price,
            auction.getListTimestamp(), auction.getExpireTimestamp());
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(new AuctionItemPurchasedEvent(snapshot, buyer)));
        changed("removed", auctionId, auction.getSellerUuid(), buyer.getUniqueId());
        return true;
    }

    /**
     * @return vrai si l'annonce est supprimée et la vente écrite, faux si la réservation n'est plus la nôtre (rien
     *         n'a été écrit), null si la base n'a pas pu confirmer
     */
    private Boolean finalizePurchase(long auctionId, String token, Sale sale) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return auctions.finalizePurchase(auctionId, token, sale);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Purchase of auction " + auctionId + ": finalization failed (attempt " + (attempt + 1) + ")", e);
                Boolean stillOurs = auctions.isClaimedBy(auctionId, token);
                if (Boolean.FALSE.equals(stillOurs)) {
                    // la réservation n'est plus là : la transaction a bien été enregistrée
                    return Boolean.TRUE.equals(auctions.isGone(auctionId)) ? Boolean.TRUE : null;
                }
                if (stillOurs == null) {
                    return null;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ mise

    boolean placeBid(Player bidder, long auctionId, double amount) {
        AuctionItem auction = dao().getAuctionById(auctionId);
        if (auction == null || !auction.isBid()) {
            messages().sendMessage(bidder, "item_not_found_or_expired");
            return false;
        }
        if (auction.isExpired()) {
            messages().sendMessage(bidder, "bid_auction_ended");
            return false;
        }
        if (auction.getSellerUuid().equals(bidder.getUniqueId())) {
            messages().sendMessage(bidder, "cannot_bid_own_item");
            return false;
        }
        if (auction.hasBid() && bidder.getUniqueId().equals(auction.getHighestBidderUuid())) {
            messages().sendMessage(bidder, "already_highest_bidder");
            return false;
        }
        double minIncrement = config().getDouble("bids.min_bid_increment", 1.0);
        double minRequired = auction.hasBid() ? auction.getHighestBid() + minIncrement : auction.getStartingPrice();
        if (amount < minRequired) {
            messages().sendMessage(bidder, "bid_too_low", Collections.singletonMap("min_bid", economy().formatCurrency(minRequired)));
            return false;
        }
        if (!economy().isEconomyReady()) {
            messages().sendMessage(bidder, "economy_disabled_error");
            return false;
        }
        if (!economy().hasEnough(bidder, amount)) {
            messages().sendMessage(bidder, "not_enough_money", Collections.singletonMap("price", economy().formatCurrency(amount)));
            return false;
        }

        // L'argent est mis de côté avant la transaction ; il est rendu si la mise n'est pas prise
        if (!economy().withdraw(bidder, amount)) {
            messages().sendMessage(bidder, "generic_error_occurred", Collections.singletonMap("error_details", "Economy withdrawal failed."));
            return false;
        }
        String token = UUID.randomUUID().toString();
        BidOutcome outcome;
        try {
            outcome = auctions.placeBid(auctionId, bidder.getUniqueId(), bidder.getName(), amount, minIncrement,
                config().getLong("bids.anti_snipe_seconds", 30), config().getLong("bids.anti_snipe_extension_seconds", 30),
                token, bus.serverName());
        } catch (UncertainCommit e) {
            Boolean recorded = auctions.bidRecorded(token);
            if (Boolean.TRUE.equals(recorded)) {
                outcome = (BidOutcome) e.result();
            } else if (Boolean.FALSE.equals(recorded)) {
                economy().deposit(bidder, amount);
                messages().sendMessage(bidder, "bid_failed_retry");
                return false;
            } else {
                plugin.getLogger().severe("Critical error: bid of " + logPrice(amount) + " by " + bidder.getName() + " on auction " + auctionId
                    + " not confirmed by the database; money kept. Check shauctions_bids token " + token + " by hand.");
                messages().sendMessage(bidder, "generic_error_occurred", Collections.singletonMap("error_details", "Database error."));
                return false;
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Bid on auction " + auctionId + " by " + bidder.getName() + " failed; refunded", e);
            economy().deposit(bidder, amount);
            messages().sendMessage(bidder, "bid_failed_retry");
            return false;
        }

        switch (outcome.status()) {
            case OK -> {
            }
            case GONE -> {
                return refused(bidder, amount, "item_not_found_or_expired", Map.of());
            }
            case ENDED -> {
                return refused(bidder, amount, "bid_auction_ended", Map.of());
            }
            case OWN -> {
                return refused(bidder, amount, "cannot_bid_own_item", Map.of());
            }
            case ALREADY_HIGHEST -> {
                return refused(bidder, amount, "already_highest_bidder", Map.of());
            }
            case TOO_LOW -> {
                return refused(bidder, amount, "bid_too_low", Map.of("min_bid", economy().formatCurrency(outcome.minRequired())));
            }
        }

        AuctionItem row = outcome.auction();
        String itemName = am.getItemNameForMessage(row.getItemStack());
        // L'enchérisseur remplacé est celui de la ligne verrouillée : remboursé une fois, ici
        if (outcome.previousBidder() != null) {
            OfflinePlayer previous = Bukkit.getOfflinePlayer(outcome.previousBidder());
            if (!economy().deposit(previous, outcome.previousBid())) {
                plugin.getLogger().severe("Critical error: outbid " + outcome.previousBidderName() + " (" + outcome.previousBidder() + ") could not be refunded "
                    + logPrice(outcome.previousBid()) + " for auction " + auctionId + ". Refund by hand.");
            }
            Map<String, String> ph = new HashMap<>();
            ph.put("item_name", itemName);
            ph.put("amount", economy().formatCurrency(outcome.previousBid()));
            notify(outcome.previousBidder(), "bid_outbid", ph, null);
        }

        Map<String, String> ph = new HashMap<>();
        ph.put("item_name", itemName);
        ph.put("amount", economy().formatCurrency(amount));
        messages().sendMessage(bidder, "bid_placed_successfully", ph);
        am.playSound(bidder, config().getString("misc.sound_on_sell"));
        if (outcome.extended()) {
            long extension = config().getLong("bids.anti_snipe_extension_seconds", 30);
            messages().sendMessage(bidder, "bid_time_extended", Collections.singletonMap("seconds", String.valueOf(extension)));
            if (config().getBoolean("misc.log_transactions", true)) {
                plugin.getLogger().info(String.format("ANTI-SNIPE: enchère %d prolongée de %ds (offre de dernière seconde).", auctionId, extension));
            }
        }
        if (config().getBoolean("misc.log_transactions", true)) {
            plugin.getLogger().info(String.format("ENCHÈRE: %s a enchéri %s sur l'enchère %d.", bidder.getName(), economy().formatCurrency(amount), auctionId));
        }
        plugin.getGuiManager().refreshOpenBidMenus();
        changed("bid", auctionId, row.getSellerUuid(), null);
        return true;
    }

    private boolean refused(Player bidder, double amount, String key, Map<String, String> placeholders) {
        economy().deposit(bidder, amount);
        messages().sendMessage(bidder, key, placeholders);
        return false;
    }

    // ------------------------------------------------------------------ annulation

    boolean cancel(Player canceller, long auctionId, boolean isAdminCancel, String reason) {
        AuctionItem listed = dao().getAuctionById(auctionId);
        if (listed == null) {
            messages().sendMessage(canceller, "item_not_found");
            return false;
        }
        boolean canCancel = false;
        if (listed.getSellerUuid().equals(canceller.getUniqueId()) && canceller.hasPermission("shauctions.cancel.self")
            && config().getBoolean("misc.allow_self_cancel", true)) {
            canCancel = true;
        } else if (isAdminCancel && canceller.hasPermission("shauctions.cancel.others") && config().getBoolean("misc.allow_admin_cancel", true)) {
            canCancel = true;
        }
        if (!canCancel) {
            messages().sendMessage(canceller, "no_permission_cancel");
            return false;
        }
        if (listed.getItemStack() == null) {
            plugin.getLogger().severe("Critical error: Failed to deserialize item for auction " + auctionId + " during cancellation.");
            messages().sendMessage(canceller, "generic_error_occurred", Collections.singletonMap("error_details", "Item deserialization failed during cancel."));
            return false;
        }
        boolean isStaffCancel = isAdminCancel && !listed.getSellerUuid().equals(canceller.getUniqueId());
        String storedReason = isStaffCancel ? reason : null;
        // Une annulation staff passe toujours par les invendus pour que le vendeur voie la raison
        Player sellerHere = local(listed.getSellerUuid());
        boolean returnDirect = !isStaffCancel && sellerHere != null && sellerHere.getInventory().firstEmpty() != -1;

        AuctionItem removed;
        try {
            removed = auctions.cancel(auctionId, returnDirect ? null : new ExpiredEntry(now(), storedReason));
        } catch (UncertainCommit e) {
            Boolean gone = auctions.isGone(auctionId);
            if (Boolean.TRUE.equals(gone)) {
                removed = (AuctionItem) e.result();
            } else {
                if (gone == null) {
                    plugin.getLogger().severe("Critical error: cancellation of auction " + auctionId + " by " + canceller.getName()
                        + " not confirmed by the database. Check by hand whether it is still listed.");
                }
                messages().sendMessage(canceller, "generic_error_occurred", Collections.singletonMap("error_details", "Database error."));
                return false;
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Cancellation of auction " + auctionId + " failed", e);
            messages().sendMessage(canceller, "generic_error_occurred", Collections.singletonMap("error_details", "Database error."));
            return false;
        }
        if (removed == null) {
            messages().sendMessage(canceller, "item_not_found");
            return false;
        }
        am.invalidateDialogs(auctionId);
        ItemStack itemToReturn = removed.getItemStack();
        String itemName = am.getItemNameForMessage(itemToReturn);

        // Remboursement de l'offre en séquestre, lue sur la ligne verrouillée
        if (removed.isBid() && removed.hasBid() && removed.getHighestBidderUuid() != null) {
            if (!economy().deposit(Bukkit.getOfflinePlayer(removed.getHighestBidderUuid()), removed.getHighestBid())) {
                plugin.getLogger().severe("Critical error: bidder " + removed.getHighestBidderName() + " could not be refunded "
                    + logPrice(removed.getHighestBid()) + " after cancellation of auction " + auctionId + ". Refund by hand.");
            }
            Map<String, String> ph = new HashMap<>();
            ph.put("item_name", itemName);
            ph.put("amount", economy().formatCurrency(removed.getHighestBid()));
            notify(removed.getHighestBidderUuid(), "bid_refunded_cancel", ph, null);
        }

        if (returnDirect) {
            am.giveOrDrop(sellerHere, itemToReturn.clone());
            messages().sendMessage(sellerHere, "auction_cancelled_self", Collections.singletonMap("item_name", itemName));
            am.playSound(sellerHere, config().getString("misc.sound_on_cancel"));
        }

        if (isStaffCancel) {
            am.writeLog("staff", "staff", "%staff% -> annule l'annonce de %seller% (%amount% x %item%, raison : %reason%)",
                "%staff%", canceller.getName(),
                "%seller%", removed.getSellerName(),
                "%amount%", String.valueOf(itemToReturn.getAmount()),
                "%item%", itemName,
                "%reason%", (reason != null && !reason.isEmpty()) ? reason : "N/A");
            Map<String, String> adminPh = new HashMap<>();
            adminPh.put("item_name", itemName);
            adminPh.put("seller", removed.getSellerName());
            adminPh.put("reason", (reason != null && !reason.isEmpty()) ? reason : "N/A");
            messages().sendMessage(canceller, "admin_item_cancelled", adminPh);
            Map<String, String> sellerPh = new HashMap<>();
            sellerPh.put("item_name", itemName);
            sellerPh.put("reason", (reason != null && !reason.isEmpty()) ? reason : "N/A");
            notify(removed.getSellerUuid(), "auction_cancelled_admin", sellerPh, null);
        } else {
            am.writeLog("retraits", "retraits", "%player% -> retire son annonce (%amount% x %item%)",
                "%player%", canceller.getName(),
                "%amount%", String.valueOf(itemToReturn.getAmount()),
                "%item%", itemName);
            if (removed.getSellerUuid().equals(canceller.getUniqueId()) && !returnDirect) {
                messages().sendMessage(canceller, "item_sent_to_expired_after_cancel", Collections.singletonMap("item_name", itemName));
            }
        }
        plugin.getLogger().info(String.format("ANNULATION: Enchère %d (%d x %s par %s) annulée par %s.%s", auctionId, itemToReturn.getAmount(),
            itemName, removed.getSellerName(), canceller.getName(), (storedReason != null ? " Raison: " + storedReason : "")));
        AuctionItem snapshot = new AuctionItem(auctionId, removed.getSellerUuid(), removed.getSellerName(), itemToReturn.clone(), removed.getPrice(),
            removed.getListTimestamp(), removed.getExpireTimestamp());
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(new AuctionItemCancelledEvent(snapshot, canceller)));
        changed("removed", auctionId, removed.getSellerUuid(), null);
        return true;
    }

    // ------------------------------------------------------------------ échéances (meneur seulement)

    void processExpired() {
        recoverStaleClaims();
        List<AuctionItem> expired = dao().getExpiredAuctions();
        if (expired.isEmpty()) {
            return;
        }
        int processed = 0;
        for (AuctionItem candidate : expired) {
            ItemStack item = candidate.getItemStack();
            if (item == null) {
                if (am.unreadableWarned.add(candidate.getId())) {
                    plugin.getLogger().warning("Could not deserialize item for expired auction ID: " + candidate.getId() + ". Skipping (left in database).");
                }
                continue;
            }
            if (candidate.isBid() && candidate.hasBid() && !economy().isEconomyReady()) {
                continue; // le vendeur doit être payé : on attend l'économie
            }
            Settlement settlement;
            try {
                settlement = auctions.settle(candidate.getId(), this::canReceiveNow, this::onlineAnywhere,
                    row -> am.getItemNameForMessage(row.getItemStack()));
            } catch (UncertainCommit e) {
                Boolean gone = auctions.isGone(candidate.getId());
                if (Boolean.TRUE.equals(gone)) {
                    settlement = (Settlement) e.result();
                } else {
                    if (gone == null) {
                        plugin.getLogger().severe("Critical error: settlement of auction " + candidate.getId() + " not confirmed by the database. "
                            + "If it is gone from " + "shauctions_auctions, pay the seller and give the item by hand.");
                    }
                    continue;
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Expired auction " + candidate.getId() + " could not be settled; retried next cycle", e);
                continue;
            }
            if (settlement == null) {
                continue; // prolongée par l'anti-snipe, ou déjà réglée
            }
            if (settlement.kind() == SettleKind.AWARDED) {
                award(settlement, item);
            } else {
                expire(settlement, item);
            }
            processed++;
        }
        if (processed > 0) {
            plugin.getLogger().info("Successfully processed " + processed + " expired auctions.");
        }
    }

    /** Le gagnant peut-il recevoir l'objet tout de suite, sur ce serveur ? */
    private boolean canReceiveNow(UUID winner) {
        Player player = local(winner);
        return player != null && player.getInventory().firstEmpty() != -1;
    }

    private void award(Settlement settlement, ItemStack item) {
        AuctionItem row = settlement.auction();
        long id = row.getId();
        double winningBid = row.getHighestBid();
        UUID winnerUuid = row.getHighestBidderUuid();
        String winnerName = row.getHighestBidderName() != null ? row.getHighestBidderName() : "?";
        String itemName = am.getItemNameForMessage(item);
        if (!economy().deposit(Bukkit.getOfflinePlayer(row.getSellerUuid()), winningBid)) {
            plugin.getLogger().severe("Critical error: seller " + row.getSellerName() + " (" + row.getSellerUuid() + ") could not be paid "
                + logPrice(winningBid) + " for won auction " + id + ". Pay by hand.");
        }
        am.invalidateDialogs(id);
        Map<String, String> ph = new HashMap<>();
        ph.put("item_name", itemName);
        if (!settlement.winnerToExpired()) {
            Player winner = local(winnerUuid);
            if (winner != null) {
                am.giveOrDrop(winner, item.clone());
                ph.put("price", economy().formatCurrency(winningBid));
                messages().sendMessage(winner, "bid_won", ph);
                am.playSound(winner, config().getString("misc.sound_on_purchase"));
            } else if (!plugin.getDatabaseManager().getExpiredItemDAO().addExpiredItem(id, winnerUuid, winnerName, item.clone(), now())) {
                am.rescueItem(winnerUuid, item, id);
            }
        } else {
            notify(winnerUuid, "bid_won_sent_to_expired", ph, null);
        }
        am.writeLog("encheres", "encheres", "%winner% -> remporte %amount% x %item% de %seller% (%price% SHCoins)",
            "%winner%", winnerName,
            "%seller%", row.getSellerName(),
            "%amount%", String.valueOf(item.getAmount()),
            "%item%", itemName,
            "%price%", logPrice(winningBid));
        Map<String, String> sellerPh = new HashMap<>();
        sellerPh.put("item_name", itemName);
        sellerPh.put("price", economy().formatCurrency(winningBid));
        sellerPh.put("buyer", winnerName);
        notify(row.getSellerUuid(), "bid_sold_to_winner", sellerPh, "misc.sound_on_sell");
        AuctionItem snapshot = new AuctionItem(id, row.getSellerUuid(), row.getSellerName(), item.clone(), winningBid, row.getListTimestamp(),
            row.getExpireTimestamp(), AuctionItem.TYPE_BID, winningBid, winnerUuid, row.getHighestBidderName());
        Bukkit.getPluginManager().callEvent(new AuctionItemPurchasedEvent(snapshot, local(winnerUuid)));
        plugin.getLogger().info(String.format("ENCHÈRE REMPORTÉE: %s remporte %d x %s pour %s (vendeur %s, ID %d).",
            winnerName, item.getAmount(), itemName, economy().formatCurrency(winningBid), row.getSellerName(), id));
        changed("removed", id, row.getSellerUuid(), winnerUuid);
    }

    private void expire(Settlement settlement, ItemStack item) {
        AuctionItem row = settlement.auction();
        long id = row.getId();
        String itemName = am.getItemNameForMessage(item);
        am.invalidateDialogs(id);
        plugin.getLogger().info(String.format("EXPIRATION: Enchère %d (%d x %s par %s) a expiré.", id, item.getAmount(), itemName, row.getSellerName()));
        notify(row.getSellerUuid(), "item_sent_to_expired", Collections.singletonMap("item_name", itemName), null);
        AuctionItem snapshot = new AuctionItem(id, row.getSellerUuid(), row.getSellerName(), item.clone(), row.getPrice(), row.getListTimestamp(),
            row.getExpireTimestamp());
        String serialized = SerializationUtil.serializeItemStack(item);
        ExpiredItem entry = new ExpiredItem(-1, id, row.getSellerUuid(), row.getSellerName(), serialized != null ? serialized : "ERROR_SERIALIZING",
            row.getExpireTimestamp(), false);
        Bukkit.getPluginManager().callEvent(new AuctionItemExpiredEvent(snapshot, entry));
        changed("removed", id, row.getSellerUuid(), null);
    }

    /**
     * Réservations orphelines : une mise en vente interrompue est supprimée (l'objet est resté chez le vendeur), un
     * achat interrompu est signalé une fois pour une vérification à la main (on ne sait pas si l'argent a bougé).
     */
    private void recoverStaleClaims() {
        long olderThan = now() - STALE_SECONDS;
        List<StaleClaim> stale;
        try {
            stale = auctions.staleClaims(olderThan);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Stale reservations could not be read", e);
            return;
        }
        for (StaleClaim claim : stale) {
            if ("LIST".equals(claim.kind())) {
                try {
                    if (auctions.deleteStaleListing(claim.id(), olderThan)) {
                        plugin.getLogger().warning("Interrupted listing " + claim.id() + " by " + claim.sellerName() + " (server " + claim.server()
                            + ") deleted: it was never published.");
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING, "Interrupted listing " + claim.id() + " could not be deleted", e);
                }
            } else if (staleWarned.add(claim.id())) {
                plugin.getLogger().severe("Interrupted purchase of auction " + claim.id() + " (" + claim.sellerName() + ", " + logPrice(claim.price())
                    + ") reserved by " + claim.server() + " since " + claim.claimedAt() + ": check the buyer's and seller's balances, "
                    + "then delete the row or clear its claim_token by hand.");
            }
        }
    }

    private static String logPrice(double price) {
        return AuctionManager.logPrice(price);
    }
}
