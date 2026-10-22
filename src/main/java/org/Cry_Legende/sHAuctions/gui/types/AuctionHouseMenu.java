package org.Cry_Legende.sHAuctions.gui.types;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.config.ConfigManager;
import org.Cry_Legende.sHAuctions.database.dao.AuctionDAO;
import org.Cry_Legende.sHAuctions.dialog.DialogManager;
import org.Cry_Legende.sHAuctions.gui.AbstractMenu;
import org.Cry_Legende.sHAuctions.gui.GUIManager;
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

public class AuctionHouseMenu extends AbstractMenu {

    private final AuctionDAO auctionDAO;
    private final ConfigManager configManager;
    private List<AuctionItem> currentAuctionPageItems; // Garder private, accessible via getter

    public AuctionHouseMenu(SHAuctions plugin, GUIManager guiManager, Player viewer) {
        super(plugin, guiManager, "main_auction_house", viewer);
        this.auctionDAO = plugin.getDatabaseManager().getAuctionDAO();
        this.configManager = plugin.getConfigManager();
        this.currentAuctionPageItems = new ArrayList<>();
    }

    // Getter pour RealTimeLoreUpdateTask et MenuRefreshListener
    public List<AuctionItem> getCurrentAuctionPageItems() {
        return currentAuctionPageItems;
    }

    @Override
    public void preparePageData() {
        if (menuDefinition == null) { // S'assurer que menuDefinition est chargé
            this.totalPages = 1;
            this.currentPage = 1;
            plugin.getLogger().warning("AuctionHouseMenu: menuDefinition is null during preparePageData for " + viewer.getName());
            return;
        }
        List<Integer> auctionSlots = menuDefinition.getAuctionItemSlots();
        int itemsPerPage = (auctionSlots != null && !auctionSlots.isEmpty()) ? auctionSlots.size() : 1;
        if (itemsPerPage == 0) itemsPerPage = 1;

        int totalActiveAuctions = auctionDAO.countActiveAuctions();

        setTotalPages((int) Math.ceil((double) totalActiveAuctions / itemsPerPage));
        this.currentPage = Math.max(1, Math.min(this.currentPage, this.totalPages));
    }

    @Override
    public void initializeItems() {
        if (menuDefinition == null || inventory == null) {
            plugin.getLogger().warning("Menu definition or inventory is null for AuctionHouseMenu during initializeItems. Viewer: " + viewer.getName());
            if (viewer.getOpenInventory().getTopInventory().getHolder() == this) {
                viewer.closeInventory();
                viewer.sendMessage(plugin.getMessageManager().translateColors("&cErreur interne du menu HDV. Veuillez réessayer."));
            }
            return;
        }

        List<Integer> auctionSlots = menuDefinition.getAuctionItemSlots();
        if (auctionSlots == null || auctionSlots.isEmpty()) {
            plugin.getLogger().warning("No 'auction_item_slots' defined in menu: " + menuName + " for AuctionHouseMenu.");
        } else {
            int itemsPerPage = auctionSlots.size();
            // currentAuctionPageItems est mis à jour avec les données de la page actuelle
            this.currentAuctionPageItems = auctionDAO.getActiveAuctions(this.currentPage, itemsPerPage);

            GuiItemDefinition auctionItemFormat = menuDefinition.getAuctionItemFormat();
            if (auctionItemFormat == null) {
                plugin.getLogger().warning("No 'auction_item_format' defined in menu: " + menuName + ". Cannot display auction items.");
            } else {
                for (int i = 0; i < currentAuctionPageItems.size(); i++) {
                    if (i >= auctionSlots.size()) break;
                    AuctionItem auctionItem = currentAuctionPageItems.get(i);
                    int slot = auctionSlots.get(i);

                    ItemStack originalItemFromAuction = auctionItem.getItemStack();
                    ItemStack displayItem;

                    if (originalItemFromAuction == null) {
                        displayItem = ItemUtil.createItemStack(Material.BARRIER, "&cErreur de chargement", List.of("&7Cet item n'a pas pu être chargé."));
                    } else {
                        displayItem = originalItemFromAuction.clone();
                    }

                    ItemMeta meta = displayItem.getItemMeta();
                    if (meta == null && displayItem.getType() != Material.AIR) {
                        meta = Bukkit.getItemFactory().getItemMeta(displayItem.getType());
                    }

                    if (meta != null) {
                        Map<String, String> placeholders = getItemSpecificPlaceholders(auctionItem);
                        String nameFormatFromConfig = auctionItemFormat.getDisplayName();
                        String originalItemCustomName = ItemUtil.getItemName(originalItemFromAuction);

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
                                if (meta.hasDisplayName()) meta.setDisplayName(null);
                            }
                        }

                        List<String> configLore = auctionItemFormat.getLore();
                        List<String> finalLore = new ArrayList<>();
                        if (configLore != null && !configLore.isEmpty()) {
                            boolean isStaff = viewer.hasPermission("shauctions.cancel.others");
                            for (String line : configLore) {
                                // Ligne réservée au staff : visible seulement pour les joueurs ayant la permission d'annulation.
                                if (line.contains("{staff}")) {
                                    if (!isStaff) continue; // Masquer aux non-staff.
                                    line = line.replace("{staff}", "");
                                }
                                if (line.contains("{ItemLore}")) {
                                    List<String> originalLore = ItemUtil.getItemLore(originalItemFromAuction);
                                    if (originalLore != null && !originalLore.isEmpty()) {
                                        finalLore.addAll(PlaceholderUtil.applyPlaceholdersInList(originalLore, placeholders));
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
            if (currentAuctionPageItems.isEmpty() && menuDefinition.getItems().containsKey(-99)) {
                GuiItemDefinition noItemsDef = menuDefinition.getItem(-99);
                if (noItemsDef != null && auctionSlots.size() > 0) {
                    ItemStack noItemsStack = ItemUtil.createItemStack(noItemsDef.getMaterial(),
                        PlaceholderUtil.applyPlaceholders(noItemsDef.getDisplayName(), getGlobalPlaceholders()),
                        PlaceholderUtil.applyPlaceholdersInList(noItemsDef.getLore(), getGlobalPlaceholders()));
                    inventory.setItem(auctionSlots.get(Math.min(auctionSlots.size() - 1, auctionSlots.size() / 2)), noItemsStack);
                }
            }
        }
        populateStaticItems();
    }

    private Map<String, String> getItemSpecificPlaceholders(AuctionItem auctionItem) {
        Map<String, String> placeholders = getGlobalPlaceholders();
        ItemStack actualItem = auctionItem.getItemStack();
        String itemNameForPlaceholder = ItemUtil.getItemName(actualItem);
        placeholders.put("{ItemName}", itemNameForPlaceholder != null ? itemNameForPlaceholder : "");
        placeholders.put("{Price}", PriceFormatterUtil.formatPrice(auctionItem.getPrice()));
        placeholders.put("{CurrencySymbol}", configManager.getString("economy.currency_symbol", "$"));
        placeholders.put("{OwnerName}", auctionItem.getSellerName());
        placeholders.put("{SellerUUID}", auctionItem.getSellerUuid().toString());
        placeholders.put("{ListTime}", TimeUtil.formatDate(auctionItem.getListTimestamp()));
        placeholders.put("{ExpireTime}", TimeUtil.formatDate(auctionItem.getExpireTimestamp()));
        placeholders.put("{RemainingTime}", TimeUtil.formatDuration(auctionItem.getExpireTimestamp() - (System.currentTimeMillis() / 1000L)));
        placeholders.put("{AuctionID}", String.valueOf(auctionItem.getId()));
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
        List<Integer> auctionSlots = menuDefinition.getAuctionItemSlots();
        if (auctionSlots != null && auctionSlots.contains(slot)) {
            int itemIndex = auctionSlots.indexOf(slot);
            if (itemIndex >= 0 && itemIndex < currentAuctionPageItems.size()) {
                AuctionItem auctionItem = currentAuctionPageItems.get(itemIndex);
                handleAuctionItemClick(auctionItem, clickType);
            }
        }
    }

    private void handleAuctionItemClick(AuctionItem auctionItem, ClickType clickType) {
        if (clickType.isLeftClick()) {
            guiManager.openConfirmPurchaseMenu(viewer, auctionItem.getId());
        } else if (clickType.isRightClick()) {
            if (viewer.hasPermission("shauctions.cancel.others") && !auctionItem.getSellerUuid().equals(viewer.getUniqueId())) {
                // Annulation staff : raison saisie dans un dialogue (clients 1.21.6+) ou, sinon, dans le chat (aucun ID exposé).
                DialogManager dialogs = plugin.getDialogManager();
                if (dialogs != null && dialogs.openCancelReason(viewer, auctionItem)) {
                    return;
                }
                plugin.getChatInputManager().awaitCancelReason(viewer.getUniqueId(), auctionItem.getId());
                viewer.closeInventory();
                plugin.getMessageManager().sendMessage(viewer, "cancel_reason_prompt");
            } else if (auctionItem.getSellerUuid().equals(viewer.getUniqueId()) && plugin.getConfigManager().getBoolean("misc.allow_self_cancel", true)) {
                boolean cancelled = plugin.getAuctionManager().cancelAuction(viewer, auctionItem.getId(), false);
                if (cancelled) {
                    this.open();
                }
            }
        }
    }
}
