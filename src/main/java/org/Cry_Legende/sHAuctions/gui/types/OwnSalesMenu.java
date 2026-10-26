package org.Cry_Legende.sHAuctions.gui.types;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.config.ConfigManager;
import org.Cry_Legende.sHAuctions.database.dao.AuctionDAO;
import org.Cry_Legende.sHAuctions.gui.AbstractMenu;
import org.Cry_Legende.sHAuctions.gui.GUIManager;
import org.Cry_Legende.sHAuctions.manager.AuctionManager;
import org.Cry_Legende.sHAuctions.model.AuctionItem;
import org.Cry_Legende.sHAuctions.model.GuiItemDefinition;
import org.Cry_Legende.sHAuctions.util.ItemUtil;
import org.Cry_Legende.sHAuctions.util.PlaceholderUtil;
import org.Cry_Legende.sHAuctions.util.PriceFormatterUtil;
import org.Cry_Legende.sHAuctions.util.TimeUtil;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OwnSalesMenu extends AbstractMenu {

    private final AuctionDAO auctionDAO;
    private final AuctionManager auctionManager;
    private final ConfigManager configManager;
    private List<AuctionItem> currentOwnSalesPageItems; // Garder private, accessible via getter
    // Liste lue par preparePageData et réutilisée par initializeItems (une seule requête par affichage)
    private List<AuctionItem> allOwnSales = new ArrayList<>();

    public OwnSalesMenu(SHAuctions plugin, GUIManager guiManager, Player viewer) {
        super(plugin, guiManager, "own_sales", viewer);
        this.auctionDAO = plugin.getDatabaseManager().getAuctionDAO();
        this.auctionManager = plugin.getAuctionManager();
        this.configManager = plugin.getConfigManager();
        this.currentOwnSalesPageItems = new ArrayList<>();
    }

    // Getter pour RealTimeLoreUpdateTask et MenuRefreshListener
    public List<AuctionItem> getCurrentOwnSalesPageItems() {
        return currentOwnSalesPageItems;
    }

    @Override
    protected void preparePageData() {
        if (menuDefinition == null) {
            this.totalPages = 1;
            this.currentPage = 1;
            return;
        }
        // Le DAO getAuctionsBySeller est déjà trié par la config display_options.own_sales_sort_order
        allOwnSales = auctionDAO.getAuctionsBySeller(viewer.getUniqueId());
        List<Integer> displaySlots = menuDefinition.getAuctionItemSlots(); // Réutilise "auction_item_slots" du YML
        int itemsPerPage = (displaySlots != null && !displaySlots.isEmpty()) ? displaySlots.size() : 1;
        if (itemsPerPage == 0) itemsPerPage = 1;

        setTotalPages((int) Math.ceil((double) allOwnSales.size() / itemsPerPage));
        this.currentPage = Math.max(1, Math.min(this.currentPage, this.totalPages));
    }

    @Override
    public void initializeItems() {
        if (menuDefinition == null || inventory == null) {
            return;
        }

        // allOwnSales vient d'être lue par preparePageData (appelée juste avant par open())
        List<Integer> displaySlots = menuDefinition.getAuctionItemSlots();

        if (displaySlots == null || displaySlots.isEmpty()) {
            plugin.getLogger().warning("No 'auction_item_slots' defined in menu: " + menuName);
        } else {
            int itemsPerPage = displaySlots.size();
            int startIndex = (this.currentPage - 1) * itemsPerPage;
            int endIndex = Math.min(startIndex + itemsPerPage, allOwnSales.size());

            this.currentOwnSalesPageItems = new ArrayList<>(); // Vider avant de repeupler
            if (startIndex < allOwnSales.size() && startIndex <= endIndex) { // S'assurer que subList est valide
                this.currentOwnSalesPageItems = new ArrayList<>(allOwnSales.subList(startIndex, endIndex));
            }

            GuiItemDefinition ownSaleItemFormat = menuDefinition.getAuctionItemFormat(); // Réutilise "auction_item_format"
            if (ownSaleItemFormat == null) {
                plugin.getLogger().warning("No 'auction_item_format' (or equivalent) defined in menu: " + menuName + ". Cannot display own sales.");
            } else {
                for (int i = 0; i < currentOwnSalesPageItems.size(); i++) {
                    if (i >= displaySlots.size()) break;
                    AuctionItem auctionItem = currentOwnSalesPageItems.get(i);
                    int slot = displaySlots.get(i);
                    ItemStack originalItem = auctionItem.getItemStack(); // L'item réel de la vente
                    ItemStack displayItem;

                    if (originalItem == null) {
                        displayItem = ItemUtil.createItemStack(Material.BARRIER, "&cErreur Item", List.of("&7Impossible de charger cet item."));
                    } else {
                        displayItem = originalItem.clone();
                    }

                    ItemMeta meta = displayItem.getItemMeta();
                    if (meta == null && displayItem.getType() != Material.AIR) {
                        meta = Bukkit.getItemFactory().getItemMeta(displayItem.getType());
                    }

                    if (meta != null) {
                        Map<String, String> placeholders = getItemSpecificPlaceholders(auctionItem);
                        String nameFormatFromConfig = ownSaleItemFormat.getDisplayName();
                        String originalItemCustomName = ItemUtil.getItemName(originalItem);

                        if (originalItemCustomName != null && !originalItemCustomName.isEmpty()) {
                            if (nameFormatFromConfig != null && !nameFormatFromConfig.isEmpty()) {
                                String finalName = nameFormatFromConfig.replace("{ItemName}", originalItemCustomName);
                                meta.setDisplayName(PlaceholderUtil.applyPlaceholders(finalName, placeholders));
                            } else {
                                meta.setDisplayName(originalItemCustomName);
                            }
                        } else {
                            if (nameFormatFromConfig != null && !nameFormatFromConfig.isEmpty() && !nameFormatFromConfig.equals("{ItemName}")) {
                                String finalName = nameFormatFromConfig.replace("{ItemName}", "");
                                meta.setDisplayName(PlaceholderUtil.applyPlaceholders(finalName, placeholders));
                            } else {
                                if (meta.hasDisplayName()) meta.setDisplayName(null); // Laisser Minecraft gérer
                            }
                        }

                        List<String> configLore = ownSaleItemFormat.getLore();
                        List<String> finalLore = new ArrayList<>();
                        if (configLore != null && !configLore.isEmpty()) {
                            boolean isBid = auctionItem.isBid();
                            for (String line : configLore) {
                                // Marqueurs {sale}/{bid} : masquer la ligne si le type ne correspond pas.
                                line = PlaceholderUtil.stripTypeMarker(line, isBid);
                                if (line == null) continue;
                                if (line.contains("{ItemLore}")) {
                                    List<String> itemOriginalLore = ItemUtil.getItemLore(originalItem);
                                    if (itemOriginalLore != null && !itemOriginalLore.isEmpty()) {
                                        finalLore.addAll(PlaceholderUtil.applyPlaceholdersInList(itemOriginalLore, placeholders));
                                    }
                                } else {
                                    finalLore.add(PlaceholderUtil.applyPlaceholders(line, placeholders));
                                }
                            }
                        }
                        meta.setLore(finalLore);
                        displayItem.setItemMeta(meta);
                    }
                    inventory.setItem(slot, displayItem);
                }
            }
            // Afficher un item "aucune vente" si la liste est vide
            if (allOwnSales.isEmpty() && menuDefinition.getItems().containsKey(-2)) { // Convention pour slot "aucun item"
                GuiItemDefinition noItemsDef = menuDefinition.getItem(-2);
                if (noItemsDef != null && displaySlots.size() > 0) {
                    ItemStack noItemsStack = ItemUtil.createItemStack(noItemsDef.getMaterial(),
                        PlaceholderUtil.applyPlaceholders(noItemsDef.getDisplayName(), getGlobalPlaceholders()),
                        PlaceholderUtil.applyPlaceholdersInList(noItemsDef.getLore(), getGlobalPlaceholders()));
                    inventory.setItem(displaySlots.get(Math.min(displaySlots.size() - 1, displaySlots.size() / 2)), noItemsStack); // Au milieu
                }
            }
        }
        populateStaticItems();
    }

    private Map<String, String> getItemSpecificPlaceholders(AuctionItem auctionItem) {
        Map<String, String> placeholders = getGlobalPlaceholders(); // Hériter des placeholders globaux (page, etc.)
        ItemStack itemStack = auctionItem.getItemStack();
        String itemNameForPlaceholder = ItemUtil.getItemName(itemStack);
        placeholders.put("{ItemName}", itemNameForPlaceholder != null ? itemNameForPlaceholder : "");
        placeholders.put("{Price}", PriceFormatterUtil.formatPrice(auctionItem.getPrice()));
        placeholders.put("{CurrencySymbol}", configManager.getString("economy.currency_symbol", "$"));
        placeholders.put("{ListTime}", TimeUtil.formatDate(auctionItem.getListTimestamp()));
        placeholders.put("{ExpireTime}", TimeUtil.formatDate(auctionItem.getExpireTimestamp()));
        placeholders.put("{RemainingTime}", TimeUtil.formatDuration(auctionItem.getExpireTimestamp() - (System.currentTimeMillis() / 1000L)));
        placeholders.put("{AuctionID}", String.valueOf(auctionItem.getId()));
        placeholders.put("{OwnerName}", auctionItem.getSellerName());
        // Placeholders spécifiques aux enchères (utilisés par les lignes {bid} du lore).
        placeholders.put("{StartingPrice}", PriceFormatterUtil.formatPrice(auctionItem.getStartingPrice()));
        placeholders.put("{CurrentBid}", auctionItem.hasBid()
            ? PriceFormatterUtil.formatPrice(auctionItem.getHighestBid())
            : configManager.getString("bids.no_bid_placeholder", "Aucune offre"));
        placeholders.put("{HighestBidder}", auctionItem.hasBid() && auctionItem.getHighestBidderName() != null
            ? auctionItem.getHighestBidderName()
            : configManager.getString("bids.no_bidder_placeholder", "—"));
        double minIncrement = configManager.getDouble("bids.min_bid_increment", 1.0);
        double nextMin = auctionItem.hasBid() ? auctionItem.getHighestBid() + minIncrement : auctionItem.getStartingPrice();
        placeholders.put("{NextMinBid}", PriceFormatterUtil.formatPrice(nextMin));
        // Le lore dans le YML (auction_item_format) contiendra typiquement "&cCliquez pour annuler"
        return placeholders;
    }

    @Override
    public void handleClick(int slot, ItemStack clickedItem, ClickType clickType) {
        if (menuDefinition == null) return;
        GuiItemDefinition staticItemDef = menuDefinition.getItem(slot);
        if (staticItemDef != null) {
            guiManager.executeItemAction(viewer, staticItemDef, this, clickType);
            return;
        }
        List<Integer> displaySlots = menuDefinition.getAuctionItemSlots(); // Utiliser le nom configuré
        if (displaySlots != null && displaySlots.contains(slot)) {
            int itemIndex = displaySlots.indexOf(slot);
            if (itemIndex >= 0 && itemIndex < currentOwnSalesPageItems.size()) {
                AuctionItem auctionItem = currentOwnSalesPageItems.get(itemIndex);
                handleOwnSaleItemClick(auctionItem, clickType);
            }
        }
    }

    private void handleOwnSaleItemClick(AuctionItem auctionItem, ClickType clickType) {
        // Par défaut, un clic (gauche ou droit, configurable par le lore) annule la vente si permis
        if (plugin.getConfigManager().getBoolean("misc.allow_self_cancel", true)) {
            // Pourrait ajouter une confirmation GUI ici si souhaité, pour l'instant action directe
            boolean cancelled = auctionManager.cancelAuction(viewer, auctionItem.getId(), false); // false car annulation par le propriétaire
            if (cancelled) {
                this.refresh(); // Utiliser refresh() pour ne pas jouer de son et reconstruire correctement
            }
            // Si !cancelled, AuctionManager aura envoyé un message d'erreur au joueur.
        } else {
            // Optionnel: message si l'annulation par soi-même est désactivée
            // viewer.sendMessage(plugin.getMessageManager().translateColors("&cL'annulation de vos propres ventes est désactivée."));
        }
    }
}
