package org.Cry_Legende.sHAuctions.task;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.config.ConfigManager; // Pour le symbole monétaire
import org.Cry_Legende.sHAuctions.gui.AbstractMenu;
import org.Cry_Legende.sHAuctions.gui.GUIManager;
import org.Cry_Legende.sHAuctions.gui.types.AuctionHouseMenu;
import org.Cry_Legende.sHAuctions.gui.types.BidHouseMenu;
import org.Cry_Legende.sHAuctions.gui.types.ConfirmPurchaseMenu;
import org.Cry_Legende.sHAuctions.gui.types.OwnSalesMenu;
import org.Cry_Legende.sHAuctions.model.AuctionItem;
import org.Cry_Legende.sHAuctions.model.GuiItemDefinition;
import org.Cry_Legende.sHAuctions.util.ItemUtil;
import org.Cry_Legende.sHAuctions.util.PlaceholderUtil;
import org.Cry_Legende.sHAuctions.util.PriceFormatterUtil;
import org.Cry_Legende.sHAuctions.util.TimeUtil;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RealTimeLoreUpdateTask extends BukkitRunnable {

    private final SHAuctions plugin;
    private final GUIManager guiManager;
    private final ConfigManager configManager; // Pour le symbole monétaire

    public RealTimeLoreUpdateTask(SHAuctions plugin) {
        this.plugin = plugin;
        this.guiManager = plugin.getGuiManager();
        this.configManager = plugin.getConfigManager(); // Initialiser
    }

    @Override
    public void run() {
        if (guiManager == null) return;

        for (AbstractMenu openMenu : new ArrayList<>(guiManager.getOpenMenus().values())) {
            Player viewer = openMenu.getViewer();
            if (viewer == null || !viewer.isOnline() || openMenu.getInventory() == null || openMenu.getMenuDefinition() == null) {
                continue;
            }

            Inventory inventory = openMenu.getInventory();
            GuiItemDefinition itemFormatFromYml = openMenu.getMenuDefinition().getAuctionItemFormat();

            // Cas spécial pour ConfirmPurchaseMenu qui peut avoir un format d'item différent
            if (openMenu instanceof ConfirmPurchaseMenu) {
                ConfirmPurchaseMenu cpMenu = (ConfirmPurchaseMenu) openMenu;
                AuctionItem auctionItem = cpMenu.getAuctionItemToConfirm();
                int displaySlot = cpMenu.getDisplayItemSlot();
                if (auctionItem != null && displaySlot != -1) {
                    GuiItemDefinition displayItemDef = cpMenu.getMenuDefinition().getItem(displaySlot);
                    if (displayItemDef != null && displayItemDef.getLore() != null && displayItemDef.getLore().stream().anyMatch(line -> line.contains("{RemainingTime}"))) {
                        updateLoreForAuctionItem(inventory, displaySlot, auctionItem, displayItemDef.getLore(), cpMenu);
                    }
                }
                continue; // Passer au menu suivant après avoir traité ConfirmPurchaseMenu
            }

            // Pour les autres menus (AuctionHouseMenu, OwnSalesMenu)
            if (itemFormatFromYml == null || itemFormatFromYml.getLore() == null || itemFormatFromYml.getLore().stream().noneMatch(line -> line.contains("{RemainingTime}"))) {
                continue;
            }
            List<String> loreFormatYML = itemFormatFromYml.getLore();

            if (openMenu instanceof AuctionHouseMenu) {
                AuctionHouseMenu ahMenu = (AuctionHouseMenu) openMenu;
                List<AuctionItem> itemsOnPage = ahMenu.getCurrentAuctionPageItems();
                List<Integer> displaySlots = ahMenu.getMenuDefinition().getAuctionItemSlots();
                if (itemsOnPage == null || displaySlots == null) continue;

                for (int i = 0; i < itemsOnPage.size(); i++) {
                    if (i >= displaySlots.size()) break;
                    updateLoreForAuctionItem(inventory, displaySlots.get(i), itemsOnPage.get(i), loreFormatYML, ahMenu);
                }
            } else if (openMenu instanceof OwnSalesMenu) {
                OwnSalesMenu osMenu = (OwnSalesMenu) openMenu;
                List<AuctionItem> itemsOnPage = osMenu.getCurrentOwnSalesPageItems();
                List<Integer> displaySlots = osMenu.getMenuDefinition().getAuctionItemSlots();
                if (itemsOnPage == null || displaySlots == null) continue;

                for (int i = 0; i < itemsOnPage.size(); i++) {
                    if (i >= displaySlots.size()) break;
                    AuctionItem osItem = itemsOnPage.get(i);
                    // own_sales mélange ventes directes et enchères : router selon le type.
                    if (osItem.isBid()) {
                        updateLoreForBidItem(inventory, displaySlots.get(i), osItem, loreFormatYML, osMenu);
                    } else {
                        updateLoreForAuctionItem(inventory, displaySlots.get(i), osItem, loreFormatYML, osMenu);
                    }
                }
            } else if (openMenu instanceof BidHouseMenu) {
                BidHouseMenu bidMenu = (BidHouseMenu) openMenu;
                List<AuctionItem> itemsOnPage = bidMenu.getCurrentBidPageItems();
                List<Integer> displaySlots = bidMenu.getMenuDefinition().getAuctionItemSlots();
                if (itemsOnPage == null || displaySlots == null) continue;

                for (int i = 0; i < itemsOnPage.size(); i++) {
                    if (i >= displaySlots.size()) break;
                    updateLoreForBidItem(inventory, displaySlots.get(i), itemsOnPage.get(i), loreFormatYML, bidMenu);
                }
            }
        }
    }

    private void updateLoreForAuctionItem(Inventory inventory, int slot, AuctionItem auctionItem, List<String> loreFormatFromYml, AbstractMenu menuContext) {
        if (auctionItem == null || auctionItem.isExpired()) {
            return;
        }

        ItemStack currentStackInSlot = inventory.getItem(slot);
        if (currentStackInSlot == null || currentStackInSlot.getType().isAir()) {
            return;
        }

        ItemMeta meta = currentStackInSlot.getItemMeta();
        if (meta == null) {
            return;
        }

        Map<String, String> placeholders = new HashMap<>(menuContext.getGlobalPlaceholders());
        ItemStack actualItem = auctionItem.getItemStack(); // L'item réel pour ses propriétés
        String itemNameForPlaceholder = ItemUtil.getItemName(actualItem);

        placeholders.put("{ItemName}", itemNameForPlaceholder != null ? itemNameForPlaceholder : "");
        placeholders.put("{Price}", PriceFormatterUtil.formatPrice(auctionItem.getPrice()));
        placeholders.put("{CurrencySymbol}", configManager.getString("economy.currency_symbol", "$"));
        placeholders.put("{OwnerName}", auctionItem.getSellerName());
        placeholders.put("{ListTime}", TimeUtil.formatDate(auctionItem.getListTimestamp()));
        placeholders.put("{ExpireTime}", TimeUtil.formatDate(auctionItem.getExpireTimestamp()));
        placeholders.put("{RemainingTime}", TimeUtil.formatDuration(Math.max(0, auctionItem.getExpireTimestamp() - (System.currentTimeMillis() / 1000L)))); // Assurer >= 0
        placeholders.put("{AuctionID}", String.valueOf(auctionItem.getId()));

        boolean isStaff = menuContext.getViewer().hasPermission("shauctions.cancel.others");
        List<String> newLore = new ArrayList<>();
        for (String lineFormat : loreFormatFromYml) {
            // Marqueurs {sale}/{bid} : masquer la ligne si le type ne correspond pas (own_sales mixte).
            lineFormat = PlaceholderUtil.stripTypeMarker(lineFormat, auctionItem.isBid());
            if (lineFormat == null) continue;
            // Ligne staff : masquée aux non-staff, marqueur retiré pour le staff.
            if (lineFormat.contains("{staff}")) {
                if (!isStaff) continue;
                lineFormat = lineFormat.replace("{staff}", "");
            }
            String newLine = lineFormat;
            if (lineFormat.contains("{ItemLore}")) {
                List<String> originalItemLore = ItemUtil.getItemLore(actualItem);
                if (originalItemLore != null && !originalItemLore.isEmpty()) {
                    if (lineFormat.trim().equals("{ItemLore}")) {
                        for (String originalLoreLine : originalItemLore) {
                            newLore.add(PlaceholderUtil.applyPlaceholders(originalLoreLine, placeholders));
                        }
                        continue;
                    } else {
                        newLine = newLine.replace("{ItemLore}", "");
                    }
                } else {
                    newLine = newLine.replace("{ItemLore}", "");
                }
            }
            newLore.add(PlaceholderUtil.applyPlaceholders(newLine, placeholders));
        }

        List<String> currentLore = meta.getLore();
        if (currentLore == null) currentLore = new ArrayList<>();

        if (!currentLore.equals(newLore)) {
            meta.setLore(newLore);
            currentStackInSlot.setItemMeta(meta);
            // La ligne inventory.setItem(slot, currentStackInSlot); est omise pour tenter d'éviter le clignotement.
            // Si le lore ne se met pas à jour, cette ligne sera nécessaire, au risque d'un clignotement.
            // Pour un changement de lore, modifier l'ItemMeta et le réappliquer à l'ItemStack *peut* suffire.
        }
    }

    /**
     * Met à jour en temps réel le lore d'un item d'enchère (type BID) : temps restant + placeholders de mise.
     * Gère aussi le marqueur {staff} comme {@code BidHouseMenu#initializeItems}.
     */
    private void updateLoreForBidItem(Inventory inventory, int slot, AuctionItem auctionItem, List<String> loreFormatFromYml, AbstractMenu menuContext) {
        if (auctionItem == null || auctionItem.isExpired()) {
            return;
        }

        ItemStack currentStackInSlot = inventory.getItem(slot);
        if (currentStackInSlot == null || currentStackInSlot.getType().isAir()) {
            return;
        }

        ItemMeta meta = currentStackInSlot.getItemMeta();
        if (meta == null) {
            return;
        }

        Map<String, String> placeholders = new HashMap<>(menuContext.getGlobalPlaceholders());
        ItemStack actualItem = auctionItem.getItemStack();
        String itemNameForPlaceholder = ItemUtil.getItemName(actualItem);

        placeholders.put("{ItemName}", itemNameForPlaceholder != null ? itemNameForPlaceholder : "");
        placeholders.put("{CurrencySymbol}", configManager.getString("economy.currency_symbol", "$"));
        placeholders.put("{OwnerName}", auctionItem.getSellerName());
        placeholders.put("{ListTime}", TimeUtil.formatDate(auctionItem.getListTimestamp()));
        placeholders.put("{ExpireTime}", TimeUtil.formatDate(auctionItem.getExpireTimestamp()));
        placeholders.put("{RemainingTime}", TimeUtil.formatDuration(Math.max(0, auctionItem.getExpireTimestamp() - (System.currentTimeMillis() / 1000L))));
        // Placeholders spécifiques aux enchères.
        placeholders.put("{StartingPrice}", PriceFormatterUtil.formatPrice(auctionItem.getStartingPrice()));
        placeholders.put("{Price}", PriceFormatterUtil.formatPrice(auctionItem.getCurrentBidOrStart()));
        placeholders.put("{CurrentBid}", auctionItem.hasBid()
            ? PriceFormatterUtil.formatPrice(auctionItem.getHighestBid())
            : configManager.getString("bids.no_bid_placeholder", "Aucune offre"));
        placeholders.put("{HighestBidder}", auctionItem.hasBid() && auctionItem.getHighestBidderName() != null
            ? auctionItem.getHighestBidderName()
            : configManager.getString("bids.no_bidder_placeholder", "—"));
        double minIncrement = configManager.getDouble("bids.min_bid_increment", 1.0);
        double nextMin = auctionItem.hasBid() ? auctionItem.getHighestBid() + minIncrement : auctionItem.getStartingPrice();
        placeholders.put("{NextMinBid}", PriceFormatterUtil.formatPrice(nextMin));

        boolean isStaff = menuContext.getViewer().hasPermission("shauctions.cancel.others");
        List<String> newLore = new ArrayList<>();
        for (String lineFormat : loreFormatFromYml) {
            // Marqueurs {sale}/{bid} : masquer la ligne si le type ne correspond pas (own_sales mixte).
            lineFormat = PlaceholderUtil.stripTypeMarker(lineFormat, auctionItem.isBid());
            if (lineFormat == null) continue;
            if (lineFormat.contains("{staff}")) {
                if (!isStaff) continue;
                lineFormat = lineFormat.replace("{staff}", "");
            }
            String newLine = lineFormat;
            if (lineFormat.contains("{ItemLore}")) {
                List<String> originalItemLore = ItemUtil.getItemLore(actualItem);
                if (originalItemLore != null && !originalItemLore.isEmpty()) {
                    if (lineFormat.trim().equals("{ItemLore}")) {
                        for (String originalLoreLine : originalItemLore) {
                            newLore.add(PlaceholderUtil.applyPlaceholders(originalLoreLine, placeholders));
                        }
                        continue;
                    } else {
                        newLine = newLine.replace("{ItemLore}", "");
                    }
                } else {
                    newLine = newLine.replace("{ItemLore}", "");
                }
            }
            newLore.add(PlaceholderUtil.applyPlaceholders(newLine, placeholders));
        }

        List<String> currentLore = meta.getLore();
        if (currentLore == null) currentLore = new ArrayList<>();

        if (!currentLore.equals(newLore)) {
            meta.setLore(newLore);
            currentStackInSlot.setItemMeta(meta);
        }
    }
}
