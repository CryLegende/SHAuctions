package org.Cry_Legende.sHAuctions.gui.types;

import org.Cry_Legende.sHAuctions.SHAuctions;
// import org.Cry_Legende.sHAuctions.config.ConfigManager; // Non utilisé directement
import org.Cry_Legende.sHAuctions.database.dao.ExpiredItemDAO;
import org.Cry_Legende.sHAuctions.gui.AbstractMenu;
import org.Cry_Legende.sHAuctions.gui.GUIManager;
import org.Cry_Legende.sHAuctions.manager.AuctionManager;
import org.Cry_Legende.sHAuctions.model.ExpiredItem;
import org.Cry_Legende.sHAuctions.model.GuiItemDefinition;
import org.Cry_Legende.sHAuctions.util.ItemUtil;
import org.Cry_Legende.sHAuctions.util.PlaceholderUtil;
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

public class ExpiredItemsMenu extends AbstractMenu {

    private final ExpiredItemDAO expiredItemDAO;
    private final AuctionManager auctionManager;
    private List<ExpiredItem> currentExpiredPageItems; // Garder private, accessible via getter

    public ExpiredItemsMenu(SHAuctions plugin, GUIManager guiManager, Player viewer) {
        super(plugin, guiManager, "expired_items", viewer);
        this.expiredItemDAO = plugin.getDatabaseManager().getExpiredItemDAO();
        this.auctionManager = plugin.getAuctionManager();
        this.currentExpiredPageItems = new ArrayList<>();
    }

    // Getter pour MenuRefreshListener (si jamais il doit vérifier les items spécifiques)
    public List<ExpiredItem> getCurrentExpiredPageItems() {
        return currentExpiredPageItems;
    }

    @Override
    protected void preparePageData() {
        if (menuDefinition == null) {
            this.totalPages = 1;
            this.currentPage = 1;
            return;
        }
        List<ExpiredItem> allUnclaimedItems = expiredItemDAO.getUnclaimedExpiredItemsByOwner(viewer.getUniqueId());
        List<Integer> displaySlots = menuDefinition.getAuctionItemSlots();
        int itemsPerPage = (displaySlots != null && !displaySlots.isEmpty()) ? displaySlots.size() : 1;
        if (itemsPerPage == 0) itemsPerPage = 1;

        setTotalPages((int) Math.ceil((double) allUnclaimedItems.size() / itemsPerPage));
        this.currentPage = Math.max(1, Math.min(this.currentPage, this.totalPages));
    }

    @Override
    public void initializeItems() {
        if (menuDefinition == null || inventory == null) {
            return;
        }

        List<ExpiredItem> allUnclaimedItems = expiredItemDAO.getUnclaimedExpiredItemsByOwner(viewer.getUniqueId());
        List<Integer> displaySlots = menuDefinition.getAuctionItemSlots();

        if (displaySlots == null || displaySlots.isEmpty()) {
            plugin.getLogger().warning("No 'auction_item_slots' defined in menu: " + menuName);
        } else {
            int itemsPerPage = displaySlots.size();
            int startIndex = (this.currentPage - 1) * itemsPerPage;
            int endIndex = Math.min(startIndex + itemsPerPage, allUnclaimedItems.size());

            this.currentExpiredPageItems.clear();
            if (startIndex < allUnclaimedItems.size() && startIndex <= endIndex) {
                this.currentExpiredPageItems = allUnclaimedItems.subList(startIndex, endIndex);
            }

            GuiItemDefinition expiredItemFormat = menuDefinition.getAuctionItemFormat();
            if (expiredItemFormat == null) {
                plugin.getLogger().warning("No 'auction_item_format' defined in menu: " + menuName + ". Cannot display expired items.");
            } else {
                for (int i = 0; i < currentExpiredPageItems.size(); i++) {
                    if (i >= displaySlots.size()) break;
                    ExpiredItem expiredItem = currentExpiredPageItems.get(i);
                    int slot = displaySlots.get(i);
                    ItemStack originalItem = expiredItem.getItemStack();
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
                        Map<String, String> placeholders = getItemSpecificPlaceholders(expiredItem);
                        String nameFormatFromConfig = expiredItemFormat.getDisplayName();
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
                                if (meta.hasDisplayName()) meta.setDisplayName(null);
                            }
                        }

                        List<String> configLore = expiredItemFormat.getLore();
                        List<String> finalLore = new ArrayList<>();
                        if (configLore != null && !configLore.isEmpty()) {
                            for (String line : configLore) {
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
            if (allUnclaimedItems.isEmpty() && menuDefinition.getItems().containsKey(-1)) {
                GuiItemDefinition noItemsDef = menuDefinition.getItem(-1);
                if (noItemsDef != null && displaySlots.size() > 0) {
                    ItemStack noItemsStack = ItemUtil.createItemStack(noItemsDef.getMaterial(),
                        PlaceholderUtil.applyPlaceholders(noItemsDef.getDisplayName(), getGlobalPlaceholders()),
                        PlaceholderUtil.applyPlaceholdersInList(noItemsDef.getLore(), getGlobalPlaceholders()));
                    inventory.setItem(displaySlots.get(Math.min(displaySlots.size() - 1, displaySlots.size() / 2)), noItemsStack);
                }
            }
        }
        populateStaticItems();
    }

    private Map<String, String> getItemSpecificPlaceholders(ExpiredItem expiredItem) {
        Map<String, String> placeholders = getGlobalPlaceholders();
        ItemStack itemStack = expiredItem.getItemStack();
        String itemNameForPlaceholder = ItemUtil.getItemName(itemStack);
        placeholders.put("{ItemName}", itemNameForPlaceholder != null ? itemNameForPlaceholder : "");
        placeholders.put("{ExpiredTimestamp}", TimeUtil.formatDate(expiredItem.getExpirationTimestamp()));
        placeholders.put("{ExpiredID}", String.valueOf(expiredItem.getId())); // ID de l'entrée dans la table expired_items
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
        List<Integer> displaySlots = menuDefinition.getAuctionItemSlots(); // Utiliser le nom configuré pour les slots
        if (displaySlots != null && displaySlots.contains(slot)) {
            int itemIndex = displaySlots.indexOf(slot);
            if (itemIndex >= 0 && itemIndex < currentExpiredPageItems.size()) {
                ExpiredItem expiredItem = currentExpiredPageItems.get(itemIndex);
                handleExpiredItemClick(expiredItem, clickType);
            }
        }
    }

    private void handleExpiredItemClick(ExpiredItem expiredItem, ClickType clickType) {
        if (clickType.isLeftClick()) { // Action de réclamation par défaut sur clic gauche
            boolean claimed = auctionManager.claimExpiredItem(viewer, expiredItem.getId());
            if (claimed) {
                this.refresh(); // Utiliser refresh() pour ne pas jouer de son et reconstruire
            }
            // Si !claimed, AuctionManager a envoyé un message (inventaire plein, etc.)
        }
    }
}
