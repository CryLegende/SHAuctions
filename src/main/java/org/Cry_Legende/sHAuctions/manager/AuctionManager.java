package org.Cry_Legende.sHAuctions.manager;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.config.ConfigManager;
import org.Cry_Legende.sHAuctions.config.MessageManager;
import org.Cry_Legende.sHAuctions.database.dao.AuctionDAO;
import org.Cry_Legende.sHAuctions.database.dao.ExpiredItemDAO;
import org.Cry_Legende.sHAuctions.database.dao.SoldItemDAO;
import org.Cry_Legende.sHAuctions.dialog.DialogManager;
import org.Cry_Legende.sHAuctions.economy.EconomyProvider;
import org.Cry_Legende.sHAuctions.event.AuctionItemCancelledEvent;
import org.Cry_Legende.sHAuctions.event.AuctionItemExpiredEvent;
import org.Cry_Legende.sHAuctions.event.AuctionItemListedEvent;
import org.Cry_Legende.sHAuctions.event.AuctionItemPurchasedEvent;
import org.Cry_Legende.sHAuctions.model.AuctionItem;
import org.Cry_Legende.sHAuctions.model.ExpiredItem;
import org.Cry_Legende.sHAuctions.util.ItemUtil;
import org.Cry_Legende.sHAuctions.util.SerializationUtil;
import org.Cry_Legende.sHAuctions.util.TimeUtil;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class AuctionManager {

    private final SHAuctions plugin;
    private final ConfigManager configManager;
    private final MessageManager messageManager;
    private final AuctionDAO auctionDAO;
    private final SoldItemDAO soldItemDAO;
    private final ExpiredItemDAO expiredItemDAO;
    private final EconomyProvider economyProvider;
    private final PermissionManager permissionManager;

    // Verrou dédié aux enchères pour sérialiser withdraw/refund/update et éviter les races
    // lorsque plusieurs joueurs enchérissent quasi simultanément.
    private final Object bidLock = new Object();

    public AuctionManager(SHAuctions plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.messageManager = plugin.getMessageManager();
        this.auctionDAO = plugin.getDatabaseManager().getAuctionDAO();
        this.soldItemDAO = plugin.getDatabaseManager().getSoldItemDAO();
        this.expiredItemDAO = plugin.getDatabaseManager().getExpiredItemDAO();
        this.economyProvider = plugin.getEconomyProvider();
        this.permissionManager = plugin.getPermissionManager();
    }

    /**
     * L'enchère n'existe plus : les saisies par dialogue qui la visaient sont annulées (sans effet sur la saisie par chat).
     */
    private void invalidateDialogs(long auctionId) {
        DialogManager dialogs = plugin.getDialogManager();
        if (dialogs != null) {
            dialogs.invalidateAuction(auctionId);
        }
    }

    private String getItemNameForMessage(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "Objet Inconnu";
        }
        String name = ItemUtil.getItemName(item);
        return (name != null && !name.isEmpty()) ? name : ItemUtil.getMaterialNameFallback(item.getType());
    }

    /**
     * Propose au vendeur d'annoncer publiquement l'enchère qu'il vient de lister, via deux boutons
     * cliquables dans le chat (Annoncer / Ignorer). Aucun ID n'est exposé : la commande exécutée ne
     * porte pas d'identifiant, l'enchère à annoncer est mémorisée côté serveur (map UUID -> id).
     */
    private void promptAnnounce(Player seller, long auctionId) {
        plugin.getChatInputManager().awaitAnnounce(seller.getUniqueId(), auctionId);

        // Ligne d'invite (avec préfixe DA).
        messageManager.sendMessage(seller, "announce_prompt");

        // Ligne des boutons cliquables (sans préfixe pour rester compacte).
        String yesLabel = messageManager.getMessage("announce_yes_button", false);
        String yesHover = messageManager.getMessage("announce_yes_hover", false);
        String noLabel = messageManager.getMessage("announce_no_button", false);
        String noHover = messageManager.getMessage("announce_no_hover", false);

        TextComponent yes = new TextComponent(TextComponent.fromLegacyText(yesLabel));
        yes.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/hdv announce"));
        yes.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
            new Text(TextComponent.fromLegacyText(yesHover))));

        TextComponent gap = new TextComponent(TextComponent.fromLegacyText("  "));

        TextComponent no = new TextComponent(TextComponent.fromLegacyText(noLabel));
        no.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/hdv announce no"));
        no.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
            new Text(TextComponent.fromLegacyText(noHover))));

        TextComponent line = new TextComponent();
        line.addExtra(yes);
        line.addExtra(gap);
        line.addExtra(no);
        seller.spigot().sendMessage(line);
    }

    /**
     * Diffuse l'annonce d'une enchère à tous les joueurs connectés. Le nom de l'item est cliquable/survolable :
     * au survol s'affiche le nom ET la description (lore) de l'objet.
     */
    public void broadcastBidAnnouncement(Player seller, AuctionItem auctionItem) {
        if (auctionItem == null) return;
        ItemStack item = auctionItem.getItemStack();
        String itemName = getItemNameForMessage(item);

        // Construction du survol : nom coloré + lore.
        StringBuilder hoverText = new StringBuilder(itemName);
        if (item != null) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.hasLore()) {
                for (String loreLine : meta.getLore()) {
                    hoverText.append("\n").append(loreLine);
                }
            }
        }

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", seller.getName());
        placeholders.put("price", economyProvider.formatCurrencyDetailed(auctionItem.getPrice()));
        // On garde %item_name% littéral pour le remplacer par le composant survolable.
        String template = messageManager.getMessage("bid_announcement", false, placeholders);

        // Nom affiché en jaune (&e) pour rester cohérent avec le reste de l'annonce, à moins que
        // l'item n'ait déjà ses propres codes couleur (qui prendront alors le dessus).
        String coloredName = "\u00A7e" + itemName;

        TextComponent message = new TextComponent();
        String marker = "%item_name%";
        int idx = template.indexOf(marker);
        if (idx < 0) {
            // Pas de marqueur : on ajoute le nom survolable à la fin.
            message.addExtra(new TextComponent(TextComponent.fromLegacyText(template + " ")));
            idx = -1;
        }
        if (idx >= 0) {
            String before = template.substring(0, idx);
            String after = template.substring(idx + marker.length());
            if (!before.isEmpty()) {
                message.addExtra(new TextComponent(TextComponent.fromLegacyText(before)));
            }
            TextComponent nameComp = new TextComponent(TextComponent.fromLegacyText(coloredName));
            nameComp.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new Text(TextComponent.fromLegacyText(hoverText.toString()))));
            message.addExtra(nameComp);
            if (!after.isEmpty()) {
                message.addExtra(new TextComponent(TextComponent.fromLegacyText(after)));
            }
        } else {
            TextComponent nameComp = new TextComponent(TextComponent.fromLegacyText(coloredName));
            nameComp.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new Text(TextComponent.fromLegacyText(hoverText.toString()))));
            message.addExtra(nameComp);
        }

        for (Player online : Bukkit.getOnlinePlayers()) {
            online.spigot().sendMessage(message);
        }
    }

    public boolean createAuction(Player seller, ItemStack itemInHand, double price, long durationInSeconds) {
        if (itemInHand == null || itemInHand.getType().isAir()) {
            messageManager.sendMessage(seller, "no_item_in_hand");
            return false;
        }
        if (price < configManager.getDouble("auction_limits.min_price", 0.01)) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("min_price", economyProvider.formatCurrency(configManager.getDouble("auction_limits.min_price")));
            placeholders.put("max_price", economyProvider.formatCurrency(configManager.getDouble("auction_limits.max_price")));
            messageManager.sendMessage(seller, "invalid_price", placeholders);
            return false;
        }
        double maxPriceConfig = configManager.getDouble("auction_limits.max_price", 0);
        if (maxPriceConfig > 0 && price > maxPriceConfig) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("min_price", economyProvider.formatCurrency(configManager.getDouble("auction_limits.min_price")));
            placeholders.put("max_price", economyProvider.formatCurrency(maxPriceConfig));
            messageManager.sendMessage(seller, "invalid_price", placeholders);
            return false;
        }
        if (isItemBlacklisted(itemInHand)) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("item_name", getItemNameForMessage(itemInHand));
            messageManager.sendMessage(seller, "item_blacklisted", placeholders);
            return false;
        }
        // Limite des ventes directes uniquement (les enchères ont leur propre limite séparée).
        int maxListings = permissionManager.getPlayerMaxListings(seller);
        if (maxListings != Integer.MAX_VALUE && auctionDAO.countSaleAuctionsBySeller(seller.getUniqueId()) >= maxListings) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("limit", String.valueOf(maxListings));
            messageManager.sendMessage(seller, "auction_limit_reached", placeholders);
            return false;
        }
        int maxGlobalListings = configManager.getInt("auction_limits.max_global_listings", 0);
        if (maxGlobalListings > 0 && auctionDAO.countActiveAuctions() >= maxGlobalListings) {
            messageManager.sendMessage(seller, "global_auction_limit_reached");
            return false;
        }
        long minDuration = configManager.getLong("auction_limits.min_duration_seconds", 3600);
        long maxDurationConf = configManager.getLong("auction_limits.max_duration_seconds", 604800);
        if (durationInSeconds < minDuration || durationInSeconds > maxDurationConf) {
            messageManager.sendMessage(seller, "invalid_duration", Map.of(
                "min_duration", TimeUtil.formatDuration(minDuration),
                "max_duration", TimeUtil.formatDuration(maxDurationConf)
            ));
            return false;
        }
        ItemStack itemToSell = itemInHand.clone();
        long auctionDbId = auctionDAO.addAuction(seller.getUniqueId(), seller.getName(), itemToSell, price, durationInSeconds);
        if (auctionDbId != -1) {
            ItemStack originalItemToRemove = itemInHand.clone();
            originalItemToRemove.setAmount(itemToSell.getAmount());
            seller.getInventory().removeItem(originalItemToRemove);
            String itemNameForLogAndMsg = getItemNameForMessage(itemToSell);
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("item_name", itemNameForLogAndMsg);
            placeholders.put("amount", String.valueOf(itemToSell.getAmount()));
            placeholders.put("price", economyProvider.formatCurrency(price));
            placeholders.put("duration", TimeUtil.formatDuration(durationInSeconds));
            messageManager.sendMessage(seller, "item_listed_successfully", placeholders);
            messageManager.sendMessage(seller, "listing_promo_warning");
            playSound(seller, configManager.getString("misc.sound_on_sell"));
            plugin.getLogger().info(String.format("MISE EN VENTE: %s a listé %d x %s pour %s pendant %s. (ID: %d)",
                seller.getName(), itemToSell.getAmount(), itemNameForLogAndMsg,
                economyProvider.formatCurrency(price), TimeUtil.formatDuration(durationInSeconds), auctionDbId));
            AuctionItem listedAuctionItem = auctionDAO.getAuctionById(auctionDbId);
            if (listedAuctionItem != null) {
                Bukkit.getScheduler().runTask(plugin, () -> // Déclencher l'event sur le thread principal
                    Bukkit.getServer().getPluginManager().callEvent(new AuctionItemListedEvent(listedAuctionItem))
                );
            }
            return true;
        } else {
            messageManager.sendMessage(seller, "generic_error_occurred", Collections.singletonMap("error_details", "Failed to add auction to database."));
            return false;
        }
    }

    /**
     * Met un item aux enchères (type BID). Le plus offrant remportera l'item à l'expiration.
     */
    public boolean createBidAuction(Player seller, ItemStack itemInHand, double startingPrice, long durationInSeconds) {
        if (itemInHand == null || itemInHand.getType().isAir()) {
            messageManager.sendMessage(seller, "no_item_in_hand");
            return false;
        }
        double minStart = configManager.getDouble("bids.min_starting_price", configManager.getDouble("auction_limits.min_price", 0.01));
        double maxStart = configManager.getDouble("bids.max_starting_price", configManager.getDouble("auction_limits.max_price", 0));
        if (startingPrice < minStart) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("min_price", economyProvider.formatCurrency(minStart));
            placeholders.put("max_price", economyProvider.formatCurrency(maxStart));
            messageManager.sendMessage(seller, "invalid_price", placeholders);
            return false;
        }
        if (maxStart > 0 && startingPrice > maxStart) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("min_price", economyProvider.formatCurrency(minStart));
            placeholders.put("max_price", economyProvider.formatCurrency(maxStart));
            messageManager.sendMessage(seller, "invalid_price", placeholders);
            return false;
        }
        if (isItemBlacklisted(itemInHand)) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("item_name", getItemNameForMessage(itemInHand));
            messageManager.sendMessage(seller, "item_blacklisted", placeholders);
            return false;
        }
        // Limite d'enchères séparée des ventes directes (perm shauctions.bidlimit.X / config bids.default_max_bid_listings_per_player).
        int maxBidListings = permissionManager.getPlayerMaxBidListings(seller);
        if (maxBidListings != Integer.MAX_VALUE && auctionDAO.countBidAuctionsBySeller(seller.getUniqueId()) >= maxBidListings) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("limit", String.valueOf(maxBidListings));
            messageManager.sendMessage(seller, "bid_limit_reached", placeholders);
            return false;
        }
        long minDuration = configManager.getLong("auction_limits.min_duration_seconds", 3600);
        long maxDurationConf = configManager.getLong("auction_limits.max_duration_seconds", 604800);
        if (durationInSeconds < minDuration || durationInSeconds > maxDurationConf) {
            messageManager.sendMessage(seller, "invalid_duration", Map.of(
                "min_duration", TimeUtil.formatDuration(minDuration),
                "max_duration", TimeUtil.formatDuration(maxDurationConf)
            ));
            return false;
        }
        ItemStack itemToSell = itemInHand.clone();
        long auctionDbId = auctionDAO.addBidAuction(seller.getUniqueId(), seller.getName(), itemToSell, startingPrice, durationInSeconds);
        if (auctionDbId != -1) {
            ItemStack originalItemToRemove = itemInHand.clone();
            originalItemToRemove.setAmount(itemToSell.getAmount());
            seller.getInventory().removeItem(originalItemToRemove);
            String itemNameForLogAndMsg = getItemNameForMessage(itemToSell);
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("item_name", itemNameForLogAndMsg);
            placeholders.put("amount", String.valueOf(itemToSell.getAmount()));
            placeholders.put("price", economyProvider.formatCurrency(startingPrice));
            placeholders.put("duration", TimeUtil.formatDuration(durationInSeconds));
            messageManager.sendMessage(seller, "bid_listed_successfully", placeholders);
            messageManager.sendMessage(seller, "listing_promo_warning");
            promptAnnounce(seller, auctionDbId);
            playSound(seller, configManager.getString("misc.sound_on_sell"));
            plugin.getLogger().info(String.format("MISE EN ENCHÈRE: %s a listé %d x %s (départ %s) pendant %s. (ID: %d)",
                seller.getName(), itemToSell.getAmount(), itemNameForLogAndMsg,
                economyProvider.formatCurrency(startingPrice), TimeUtil.formatDuration(durationInSeconds), auctionDbId));
            AuctionItem listedAuctionItem = auctionDAO.getAuctionById(auctionDbId);
            if (listedAuctionItem != null) {
                Bukkit.getScheduler().runTask(plugin, () ->
                    Bukkit.getServer().getPluginManager().callEvent(new AuctionItemListedEvent(listedAuctionItem))
                );
            }
            return true;
        } else {
            messageManager.sendMessage(seller, "generic_error_occurred", Collections.singletonMap("error_details", "Failed to add bid auction to database."));
            return false;
        }
    }

    /**
     * Place une offre sur une enchère. Modèle d'entiercement (escrow) :
     * l'argent est retiré immédiatement de l'enchérisseur et l'ancien meilleur enchérisseur est remboursé.
     * Doit être appelée depuis le thread principal (via le listener de chat qui planifie la tâche).
     *
     * @return true si l'offre est acceptée et le joueur devient meilleur enchérisseur.
     */
    public boolean placeBid(Player bidder, long auctionId, double amount) {
        synchronized (bidLock) {
            AuctionItem auction = auctionDAO.getAuctionById(auctionId);
            if (auction == null || !auction.isBid()) {
                messageManager.sendMessage(bidder, "item_not_found_or_expired");
                return false;
            }
            if (auction.isExpired()) {
                messageManager.sendMessage(bidder, "bid_auction_ended");
                return false;
            }
            if (auction.getSellerUuid().equals(bidder.getUniqueId())) {
                messageManager.sendMessage(bidder, "cannot_bid_own_item");
                return false;
            }
            if (auction.hasBid() && bidder.getUniqueId().equals(auction.getHighestBidderUuid())) {
                messageManager.sendMessage(bidder, "already_highest_bidder");
                return false;
            }
            double minIncrement = configManager.getDouble("bids.min_bid_increment", 1.0);
            double minRequired = auction.hasBid() ? auction.getHighestBid() + minIncrement : auction.getStartingPrice();
            if (amount < minRequired) {
                messageManager.sendMessage(bidder, "bid_too_low", Collections.singletonMap("min_bid", economyProvider.formatCurrency(minRequired)));
                return false;
            }
            if (!economyProvider.isEconomyReady()) {
                messageManager.sendMessage(bidder, "economy_disabled_error");
                return false;
            }
            if (!economyProvider.hasEnough(bidder, amount)) {
                messageManager.sendMessage(bidder, "not_enough_money", Collections.singletonMap("price", economyProvider.formatCurrency(amount)));
                return false;
            }

            // Sauvegarde de l'ancien enchérisseur avant remplacement (pour remboursement).
            boolean hadBid = auction.hasBid();
            UUID prevBidderUuid = auction.getHighestBidderUuid();
            double prevBid = auction.getHighestBid();

            if (!economyProvider.withdraw(bidder, amount)) {
                messageManager.sendMessage(bidder, "generic_error_occurred", Collections.singletonMap("error_details", "Economy withdrawal failed."));
                return false;
            }

            // Mise à jour DB avec garde anti-concurrence (n'accepte que si strictement supérieure et non expirée).
            if (!auctionDAO.updateHighestBid(auctionId, amount, bidder.getUniqueId(), bidder.getName())) {
                economyProvider.deposit(bidder, amount); // Remboursement si la mise a échoué (course perdue / expirée).
                messageManager.sendMessage(bidder, "bid_failed_retry");
                return false;
            }

            // Rembourser l'ancien meilleur enchérisseur.
            if (hadBid && prevBidderUuid != null) {
                OfflinePlayer prev = Bukkit.getOfflinePlayer(prevBidderUuid);
                economyProvider.deposit(prev, prevBid);
                if (prev.isOnline() && prev.getPlayer() != null) {
                    Map<String, String> ph = new HashMap<>();
                    ph.put("item_name", getItemNameForMessage(auction.getItemStack()));
                    ph.put("amount", economyProvider.formatCurrency(prevBid));
                    messageManager.sendMessage(prev.getPlayer(), "bid_outbid", ph);
                }
            }

            Map<String, String> ph = new HashMap<>();
            ph.put("item_name", getItemNameForMessage(auction.getItemStack()));
            ph.put("amount", economyProvider.formatCurrency(amount));
            messageManager.sendMessage(bidder, "bid_placed_successfully", ph);
            playSound(bidder, configManager.getString("misc.sound_on_sell"));

            // Protection anti-snipe : une offre placée dans les dernières secondes repousse l'échéance
            // pour éviter de "voler" l'enchère au dernier moment.
            long antiSnipeWindow = configManager.getLong("bids.anti_snipe_seconds", 30);
            long antiSnipeExtension = configManager.getLong("bids.anti_snipe_extension_seconds", 30);
            if (antiSnipeWindow > 0 && antiSnipeExtension > 0) {
                long now = System.currentTimeMillis() / 1000L;
                long remaining = auction.getExpireTimestamp() - now;
                if (remaining < antiSnipeWindow) {
                    long newExpire = now + antiSnipeExtension;
                    if (newExpire > auction.getExpireTimestamp() && auctionDAO.setExpireTimestamp(auctionId, newExpire)) {
                        messageManager.sendMessage(bidder, "bid_time_extended",
                            Collections.singletonMap("seconds", String.valueOf(antiSnipeExtension)));
                        if (configManager.getBoolean("misc.log_transactions", true)) {
                            plugin.getLogger().info(String.format("ANTI-SNIPE: enchère %d prolongée de %ds (offre de dernière seconde).",
                                auctionId, antiSnipeExtension));
                        }
                    }
                }
            }

            if (configManager.getBoolean("misc.log_transactions", true)) {
                plugin.getLogger().info(String.format("ENCHÈRE: %s a enchéri %s sur l'enchère %d.",
                    bidder.getName(), economyProvider.formatCurrency(amount), auctionId));
            }

            // Rafraîchir les menus d'enchères ouverts (thread principal).
            plugin.getGuiManager().refreshOpenBidMenus();
            return true;
        }
    }

    public boolean purchaseAuction(Player buyer, long auctionId) {
        AuctionItem auctionItem = auctionDAO.getAuctionById(auctionId);
        if (auctionItem == null) {
            messageManager.sendMessage(buyer, "item_not_found");
            return false;
        }
        if (auctionItem.getSellerUuid().equals(buyer.getUniqueId())) {
            messageManager.sendMessage(buyer, "cannot_buy_own_item");
            return false;
        }
        if (auctionItem.isExpired()) {
            messageManager.sendMessage(buyer, "item_not_found_or_expired");
            return false;
        }
        if (!economyProvider.isEconomyReady()) {
            messageManager.sendMessage(buyer, "economy_disabled_error");
            return false;
        }
        if (!economyProvider.hasEnough(buyer, auctionItem.getPrice())) {
            messageManager.sendMessage(buyer, "not_enough_money", Collections.singletonMap("price", economyProvider.formatCurrency(auctionItem.getPrice())));
            return false;
        }
        if (buyer.getInventory().firstEmpty() == -1) {
            messageManager.sendMessage(buyer, "inventory_full");
            return false;
        }

        ItemStack purchasedItemStack = auctionItem.getItemStack();
        if (purchasedItemStack == null) {
            plugin.getLogger().severe("Critical error: Failed to deserialize item for auction " + auctionId + " during purchase (pre-transaction).");
            messageManager.sendMessage(buyer, "generic_error_occurred", Collections.singletonMap("error_details", "Item data error before purchase."));
            return false;
        }
        AuctionItem auctionItemSnapshot = new AuctionItem(auctionItem.getId(), auctionItem.getSellerUuid(), auctionItem.getSellerName(), purchasedItemStack.clone(), auctionItem.getPrice(), auctionItem.getListTimestamp(), auctionItem.getExpireTimestamp());

        if (!economyProvider.withdraw(buyer, auctionItem.getPrice())) {
            messageManager.sendMessage(buyer, "generic_error_occurred", Collections.singletonMap("error_details", "Economy withdrawal failed."));
            return false;
        }
        OfflinePlayer seller = Bukkit.getOfflinePlayer(auctionItem.getSellerUuid());
        if (!economyProvider.deposit(seller, auctionItem.getPrice())) {
            economyProvider.deposit(buyer, auctionItem.getPrice());
            plugin.getLogger().severe("Critical error: Failed to deposit money to seller " + seller.getName() + " (UUID: " + seller.getUniqueId() + ") after successful withdrawal from buyer " + buyer.getName() + " for auction " + auctionId + ". Buyer was refunded.");
            messageManager.sendMessage(buyer, "generic_error_occurred", Collections.singletonMap("error_details", "Economy deposit to seller failed."));
            return false;
        }

        buyer.getInventory().addItem(purchasedItemStack.clone());
        auctionDAO.removeAuction(auctionId);
        invalidateDialogs(auctionId);
        String itemNameForMsgsAndLogs = getItemNameForMessage(purchasedItemStack);
        soldItemDAO.addSoldItem(auctionId, auctionItem.getSellerUuid(), auctionItem.getSellerName(), buyer.getUniqueId(), buyer.getName(), itemNameForMsgsAndLogs, purchasedItemStack, auctionItem.getPrice(), "SALE");

        Map<String, String> buyerPlaceholders = new HashMap<>();
        buyerPlaceholders.put("item_name", itemNameForMsgsAndLogs);
        buyerPlaceholders.put("price", economyProvider.formatCurrency(auctionItem.getPrice()));
        buyerPlaceholders.put("seller", auctionItem.getSellerName());
        messageManager.sendMessage(buyer, "item_purchased_successfully", buyerPlaceholders);
        playSound(buyer, configManager.getString("misc.sound_on_purchase"));
        if (seller.isOnline() && seller.getPlayer() != null) {
            Map<String, String> sellerPlaceholders = new HashMap<>();
            sellerPlaceholders.put("item_name", itemNameForMsgsAndLogs);
            sellerPlaceholders.put("price", economyProvider.formatCurrency(auctionItem.getPrice()));
            messageManager.sendMessage(seller.getPlayer(), "item_sold_successfully", sellerPlaceholders);
            playSound(seller.getPlayer(), configManager.getString("misc.sound_on_sell"));
        }
        if (configManager.getBoolean("misc.log_transactions", true)) {
            plugin.getLogger().info(String.format("ACHAT: %s a acheté %d x %s de %s pour %s. (ID Enchère: %d)", buyer.getName(), purchasedItemStack.getAmount(), itemNameForMsgsAndLogs, auctionItem.getSellerName(), economyProvider.formatCurrency(auctionItem.getPrice()), auctionId));
        }

        final AuctionItem eventSnapshot = auctionItemSnapshot; // Pour utilisation dans lambda
        Bukkit.getScheduler().runTask(plugin, () -> // Déclencher l'event sur le thread principal
            Bukkit.getServer().getPluginManager().callEvent(new AuctionItemPurchasedEvent(eventSnapshot, buyer))
        );
        return true;
    }

    public boolean cancelAuction(Player canceller, long auctionId, boolean isAdminCancel) {
        return cancelAuction(canceller, auctionId, isAdminCancel, null);
    }

    /**
     * Annule une vente ou une enchère.
     *
     * @param reason Raison d'annulation staff (obligatoire pour une annulation admin d'autrui).
     *               null pour une auto-annulation. La raison est stockée avec l'item invendu
     *               afin que le vendeur sache que l'item a été annulé par le staff (et non expiré).
     */
    public boolean cancelAuction(Player canceller, long auctionId, boolean isAdminCancel, String reason) {
        AuctionItem auctionItem = auctionDAO.getAuctionById(auctionId);
        if (auctionItem == null) {
            messageManager.sendMessage(canceller, "item_not_found");
            return false;
        }
        boolean canCancel = false;
        if (auctionItem.getSellerUuid().equals(canceller.getUniqueId()) && configManager.getBoolean("misc.allow_self_cancel", true)) {
            canCancel = true;
        } else if (isAdminCancel && canceller.hasPermission("shauctions.cancel.others") && configManager.getBoolean("misc.allow_admin_cancel", true)) {
            canCancel = true;
        }
        if (!canCancel) {
            messageManager.sendMessage(canceller, "no_permission_cancel");
            return false;
        }

        ItemStack itemToReturn = auctionItem.getItemStack();
        if (itemToReturn == null) {
            plugin.getLogger().severe("Critical error: Failed to deserialize item for auction " + auctionId + " during cancellation.");
            messageManager.sendMessage(canceller, "generic_error_occurred", Collections.singletonMap("error_details", "Item deserialization failed during cancel."));
            return false;
        }
        AuctionItem auctionItemSnapshot = new AuctionItem(auctionItem.getId(), auctionItem.getSellerUuid(), auctionItem.getSellerName(), itemToReturn.clone(), auctionItem.getPrice(), auctionItem.getListTimestamp(), auctionItem.getExpireTimestamp());
        String itemNameForMsgsAndLogs = getItemNameForMessage(itemToReturn);

        boolean isStaffCancel = isAdminCancel && !auctionItem.getSellerUuid().equals(canceller.getUniqueId());
        String storedReason = isStaffCancel ? reason : null;

        // Rembourser le meilleur enchérisseur si c'est une enchère avec offre en cours (escrow).
        if (auctionItem.isBid() && auctionItem.hasBid() && auctionItem.getHighestBidderUuid() != null) {
            OfflinePlayer prevBidder = Bukkit.getOfflinePlayer(auctionItem.getHighestBidderUuid());
            economyProvider.deposit(prevBidder, auctionItem.getHighestBid());
            if (prevBidder.isOnline() && prevBidder.getPlayer() != null) {
                Map<String, String> ph = new HashMap<>();
                ph.put("item_name", itemNameForMsgsAndLogs);
                ph.put("amount", economyProvider.formatCurrency(auctionItem.getHighestBid()));
                messageManager.sendMessage(prevBidder.getPlayer(), "bid_refunded_cancel", ph);
            }
        }

        OfflinePlayer sellerOffline = Bukkit.getOfflinePlayer(auctionItem.getSellerUuid());
        boolean itemReturnedDirectly = false;
        // Une annulation staff force le passage par les invendus pour que le vendeur voie la raison.
        if (!isStaffCancel && sellerOffline.isOnline() && sellerOffline.getPlayer() != null) {
            Player sellerOnline = sellerOffline.getPlayer();
            if (sellerOnline.getInventory().firstEmpty() != -1) {
                sellerOnline.getInventory().addItem(itemToReturn.clone());
                itemReturnedDirectly = true;
                messageManager.sendMessage(sellerOnline, "auction_cancelled_self", Collections.singletonMap("item_name", itemNameForMsgsAndLogs));
                playSound(sellerOnline, configManager.getString("misc.sound_on_cancel"));
            }
        }
        if (!itemReturnedDirectly) {
            expiredItemDAO.addExpiredItem(auctionItem.getId(), auctionItem.getSellerUuid(), auctionItem.getSellerName(), itemToReturn, System.currentTimeMillis() / 1000L, storedReason);
        }
        auctionDAO.removeAuction(auctionId);
        invalidateDialogs(auctionId);

        if (isStaffCancel) {
            Map<String, String> adminPlaceholders = new HashMap<>();
            adminPlaceholders.put("item_name", itemNameForMsgsAndLogs);
            adminPlaceholders.put("seller", auctionItem.getSellerName());
            adminPlaceholders.put("reason", (reason != null && !reason.isEmpty()) ? reason : "N/A");
            messageManager.sendMessage(canceller, "admin_item_cancelled", adminPlaceholders);
            if (sellerOffline.isOnline() && sellerOffline.getPlayer() != null) {
                Map<String, String> sellerPlaceholders = new HashMap<>();
                sellerPlaceholders.put("item_name", itemNameForMsgsAndLogs);
                sellerPlaceholders.put("reason", (reason != null && !reason.isEmpty()) ? reason : "N/A");
                messageManager.sendMessage(sellerOffline.getPlayer(), "auction_cancelled_admin", sellerPlaceholders);
            }
        } else if (auctionItem.getSellerUuid().equals(canceller.getUniqueId()) && !itemReturnedDirectly) {
            messageManager.sendMessage(canceller, "item_sent_to_expired_after_cancel", Collections.singletonMap("item_name", itemNameForMsgsAndLogs));
        }

        plugin.getLogger().info(String.format("ANNULATION: Enchère %d (%d x %s par %s) annulée par %s.%s", auctionId, itemToReturn.getAmount(), itemNameForMsgsAndLogs, auctionItem.getSellerName(), canceller.getName(),
            (storedReason != null ? " Raison: " + storedReason : "")));

        final AuctionItem eventSnapshot = auctionItemSnapshot; // Pour utilisation dans lambda
        final Player finalCanceller = canceller; // Pour utilisation dans lambda
        Bukkit.getScheduler().runTask(plugin, () -> // Déclencher l'event sur le thread principal
            Bukkit.getServer().getPluginManager().callEvent(new AuctionItemCancelledEvent(eventSnapshot, finalCanceller))
        );
        return true;
    }

    public void processExpiredAuctions() { // Appelée par ExpirationTask, sur le thread principal (cf. SHAuctions#onEnable)
        List<AuctionItem> expiredAuctionsFromDB = auctionDAO.getExpiredAuctions();
        if (expiredAuctionsFromDB.isEmpty()) return;
        plugin.getLogger().fine("Processing " + expiredAuctionsFromDB.size() + " expired auction(s)...");
        int processedCount = 0;
        for (AuctionItem auctionItem : expiredAuctionsFromDB) {
            ItemStack itemStack = auctionItem.getItemStack();
            if (itemStack == null) {
                plugin.getLogger().warning("Could not deserialize item for expired auction ID: " + auctionItem.getId() + ". Skipping.");
                // On pourrait envisager de supprimer l'enchère corrompue ici pour éviter des boucles
                // auctionDAO.removeAuction(auctionItem.getId());
                continue;
            }

            // Cas d'une enchère remportée : attribuer l'item au gagnant et payer le vendeur.
            // L'argent du gagnant a déjà été prélevé au moment de l'offre (escrow).
            if (auctionItem.isBid() && auctionItem.hasBid() && auctionItem.getHighestBidderUuid() != null) {
                if (awardBidAuction(auctionItem, itemStack)) {
                    auctionDAO.removeAuction(auctionItem.getId());
                    invalidateDialogs(auctionItem.getId());
                    processedCount++;
                }
                continue;
            }

            // Copier les données pour l'événement avant toute modification
            final AuctionItem auctionItemSnapshot = new AuctionItem(auctionItem.getId(), auctionItem.getSellerUuid(), auctionItem.getSellerName(), itemStack.clone(), auctionItem.getPrice(), auctionItem.getListTimestamp(), auctionItem.getExpireTimestamp());
            final String itemNameForMsgsAndLogs = getItemNameForMessage(itemStack);
            final ItemStack finalItemStack = itemStack; // Pour la lambda

            boolean addedToExpired = expiredItemDAO.addExpiredItem(auctionItem.getId(), auctionItem.getSellerUuid(), auctionItem.getSellerName(), finalItemStack, auctionItem.getExpireTimestamp());

            if (addedToExpired) {
                auctionDAO.removeAuction(auctionItem.getId()); // Ceci est une opération DB, OK en async
                invalidateDialogs(auctionItem.getId());
                processedCount++;

                plugin.getLogger().info(String.format("EXPIRATION: Enchère %d (%d x %s par %s) a expiré.",
                    auctionItem.getId(),
                    finalItemStack.getAmount(),
                    itemNameForMsgsAndLogs,
                    auctionItem.getSellerName()));

                // Les notifications aux joueurs et le déclenchement d'événement doivent être synchrones
                Bukkit.getScheduler().runTask(plugin, () -> {
                    OfflinePlayer owner = Bukkit.getOfflinePlayer(auctionItemSnapshot.getSellerUuid());
                    if (owner.isOnline() && owner.getPlayer() != null) {
                        messageManager.sendMessage(owner.getPlayer(), "item_sent_to_expired", Collections.singletonMap("item_name", itemNameForMsgsAndLogs));
                    }

                    // Créer l'objet ExpiredItem pour l'événement.
                    // Idéalement, addExpiredItem retournerait l'objet créé ou son ID.
                    // Pour l'instant, on le reconstruit.
                    String serializedEventItem = SerializationUtil.serializeItemStack(finalItemStack); // Sérialiser pour l'event si besoin
                    ExpiredItem expiredEntryForEvent = new ExpiredItem(
                        -1, // On ne connaît pas l'ID de la table expired_items sans query
                        auctionItemSnapshot.getId(),
                        auctionItemSnapshot.getSellerUuid(),
                        auctionItemSnapshot.getSellerName(),
                        serializedEventItem != null ? serializedEventItem : "ERROR_SERIALIZING",
                        auctionItemSnapshot.getExpireTimestamp(),
                        false
                    );
                    Bukkit.getServer().getPluginManager().callEvent(new AuctionItemExpiredEvent(auctionItemSnapshot, expiredEntryForEvent));
                });
            } else {
                plugin.getLogger().warning("Failed to move expired auction ID " + auctionItem.getId() + " to expired items table. It might be processed again.");
            }
        }
        if (processedCount > 0) {
            plugin.getLogger().info("Successfully processed " + processedCount + " expired auctions.");
        }
    }

    /**
     * Attribue une enchère remportée : paie le vendeur (l'argent du gagnant est déjà en escrow),
     * enregistre la vente et remet l'item au gagnant (directement s'il est en ligne avec de la place,
     * sinon dans ses invendus). Exécuté depuis le thread d'expiration (async) — les opérations DB/économie
     * y sont sûres ; les notifications/inventaire sont planifiées sur le thread principal.
     *
     * @return true si l'attribution a réussi (l'enchère peut être retirée).
     */
    private boolean awardBidAuction(AuctionItem auction, ItemStack itemStack) {
        final double winningBid = auction.getHighestBid();
        final UUID winnerUuid = auction.getHighestBidderUuid();
        final String winnerName = auction.getHighestBidderName();
        final String itemName = getItemNameForMessage(itemStack);

        // Payer le vendeur (offline-safe).
        OfflinePlayer seller = Bukkit.getOfflinePlayer(auction.getSellerUuid());
        if (!economyProvider.deposit(seller, winningBid)) {
            plugin.getLogger().severe("Failed to deposit winning bid to seller for bid auction " + auction.getId() + ". Will retry next cycle.");
            return false;
        }

        // Enregistrer dans l'historique des ventes.
        soldItemDAO.addSoldItem(auction.getId(), auction.getSellerUuid(), auction.getSellerName(), winnerUuid, winnerName, itemName, itemStack, winningBid, "BID");

        // Remettre l'item au gagnant (thread principal) ; sinon dans ses invendus.
        final ItemStack winnerItem = itemStack.clone();
        final AuctionItem snapshot = new AuctionItem(auction.getId(), auction.getSellerUuid(), auction.getSellerName(), itemStack.clone(), winningBid, auction.getListTimestamp(), auction.getExpireTimestamp(), AuctionItem.TYPE_BID, winningBid, winnerUuid, winnerName);
        final long originalId = auction.getId();

        Bukkit.getScheduler().runTask(plugin, () -> {
            OfflinePlayer winner = Bukkit.getOfflinePlayer(winnerUuid);
            boolean givenDirectly = false;
            if (winner.isOnline() && winner.getPlayer() != null && winner.getPlayer().getInventory().firstEmpty() != -1) {
                winner.getPlayer().getInventory().addItem(winnerItem.clone());
                givenDirectly = true;
                Map<String, String> ph = new HashMap<>();
                ph.put("item_name", itemName);
                ph.put("price", economyProvider.formatCurrency(winningBid));
                messageManager.sendMessage(winner.getPlayer(), "bid_won", ph);
                playSound(winner.getPlayer(), configManager.getString("misc.sound_on_purchase"));
            }
            if (!givenDirectly) {
                // Déposer dans les invendus du GAGNANT (owner = gagnant) pour récupération ultérieure.
                expiredItemDAO.addExpiredItem(originalId, winnerUuid, winnerName, winnerItem, System.currentTimeMillis() / 1000L);
                if (winner.isOnline() && winner.getPlayer() != null) {
                    messageManager.sendMessage(winner.getPlayer(), "bid_won_sent_to_expired", Collections.singletonMap("item_name", itemName));
                }
            }
            // Notifier le vendeur.
            if (seller.isOnline() && seller.getPlayer() != null) {
                Map<String, String> ph = new HashMap<>();
                ph.put("item_name", itemName);
                ph.put("price", economyProvider.formatCurrency(winningBid));
                ph.put("buyer", winnerName != null ? winnerName : "?");
                messageManager.sendMessage(seller.getPlayer(), "bid_sold_to_winner", ph);
                playSound(seller.getPlayer(), configManager.getString("misc.sound_on_sell"));
            }
            Bukkit.getServer().getPluginManager().callEvent(new AuctionItemPurchasedEvent(snapshot, winner.getPlayer()));
        });

        plugin.getLogger().info(String.format("ENCHÈRE REMPORTÉE: %s remporte %d x %s pour %s (vendeur %s, ID %d).",
            winnerName, itemStack.getAmount(), itemName, economyProvider.formatCurrency(winningBid), auction.getSellerName(), auction.getId()));
        return true;
    }

    public boolean claimExpiredItem(Player player, long expiredItemId) {
        ExpiredItem expiredItem = expiredItemDAO.getUnclaimedExpiredItemById(expiredItemId);
        if (expiredItem == null) {
            messageManager.sendMessage(player, "expired_item_not_found_or_claimed");
            return false;
        }
        if (!expiredItem.getOwnerUuid().equals(player.getUniqueId())) {
            messageManager.sendMessage(player, "not_your_expired_item");
            return false;
        }
        if (player.getInventory().firstEmpty() == -1) {
            messageManager.sendMessage(player, "inventory_full");
            return false;
        }

        ItemStack itemStack = expiredItem.getItemStack();
        if (itemStack == null) {
            plugin.getLogger().severe("Could not deserialize expired item ID: " + expiredItemId + " for player " + player.getName());
            messageManager.sendMessage(player, "generic_error_occurred", Collections.singletonMap("error_details", "Expired item deserialization failed."));
            return false;
        }

        // Marquer d'abord (UPDATE conditionnel) : si la base échoue, l'item n'est pas donné
        if (expiredItemDAO.markAsClaimed(expiredItemId)) {
            player.getInventory().addItem(itemStack.clone());
            String itemNameForMsgsAndLogs = getItemNameForMessage(itemStack);
            messageManager.sendMessage(player, "expired_item_claimed", Collections.singletonMap("item_name", itemNameForMsgsAndLogs));
            playSound(player, configManager.getString("misc.sound_on_expire_claim"));

            plugin.getLogger().info(String.format("RÉCLAMATION: %s a réclamé l'item expiré %d x %s (ID Expiré: %d, ID Enchère Orig.: %d).",
                player.getName(), itemStack.getAmount(), itemNameForMsgsAndLogs, expiredItemId, expiredItem.getOriginalAuctionId()));
            // TODO: Déclencher un événement AuctionItemClaimedFromExpiredEvent si nécessaire, de manière synchrone
            // final ExpiredItem finalExpiredItem = expiredItem; // Copie pour lambda
            // final ItemStack finalItemStack = itemStack.clone();
            // Bukkit.getScheduler().runTask(plugin, () ->
            //     Bukkit.getServer().getPluginManager().callEvent(new AuctionItemClaimedEvent(finalExpiredItem, player, finalItemStack))
            // );
            return true;
        } else {
            // Déjà réclamé (UPDATE conditionnel sans effet) ou erreur base : rien n'a été donné.
            plugin.getLogger().warning("Expired item ID " + expiredItemId + " could not be marked as claimed for " + player.getName() + " (already claimed or DB error). Nothing was given.");
            messageManager.sendMessage(player, "expired_item_not_found_or_claimed");
            return false;
        }
    }

    private boolean isItemBlacklisted(ItemStack item) {
        List<String> blacklist = configManager.getStringList("item_blacklist");
        if (item == null) return false;
        return blacklist.contains(item.getType().name());
        // TODO: Enchantment blacklist
    }

    private void playSound(Player player, String soundName) {
        if (soundName == null || soundName.trim().isEmpty() || "NONE".equalsIgnoreCase(soundName)) return;
        try {
            Sound sound = Sound.valueOf(soundName.toUpperCase());
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid sound name in config: " + soundName);
        }
    }
}
