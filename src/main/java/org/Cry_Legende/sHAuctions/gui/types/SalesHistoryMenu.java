package org.Cry_Legende.sHAuctions.gui.types;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.config.ConfigManager;
import org.Cry_Legende.sHAuctions.config.MessageManager;
import org.Cry_Legende.sHAuctions.database.dao.SoldItemDAO;
import org.Cry_Legende.sHAuctions.gui.AbstractMenu;
import org.Cry_Legende.sHAuctions.gui.GUIManager;
import org.Cry_Legende.sHAuctions.model.GuiItemDefinition;
import org.Cry_Legende.sHAuctions.model.SoldItem;
import org.Cry_Legende.sHAuctions.util.ItemUtil;
import org.Cry_Legende.sHAuctions.util.PlaceholderUtil;
import org.Cry_Legende.sHAuctions.util.PriceFormatterUtil;
import org.Cry_Legende.sHAuctions.util.TimeUtil;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SalesHistoryMenu extends AbstractMenu {

    private final SoldItemDAO soldItemDAO;
    private final ConfigManager configManager;
    private List<SoldItem> currentHistoryPageItems; // Garder private, accessible via getter
    private final boolean isGlobalHistoryView;
    private final UUID targetPlayerUuidView;

    public SalesHistoryMenu(SHAuctions plugin, GUIManager guiManager, Player viewer, boolean isGlobal, UUID targetUuid) {
        super(plugin, guiManager, "sales_history", viewer);
        this.soldItemDAO = plugin.getDatabaseManager().getSoldItemDAO();
        this.configManager = plugin.getConfigManager();
        this.currentHistoryPageItems = new ArrayList<>();
        this.isGlobalHistoryView = isGlobal;
        this.targetPlayerUuidView = targetUuid;
    }

    public boolean isGlobalHistory() {
        return isGlobalHistoryView;
    }

    public UUID getTargetPlayerUuid() {
        return targetPlayerUuidView;
    }

    // Getter pour MenuRefreshListener (si jamais il doit vérifier les items spécifiques)
    public List<SoldItem> getCurrentHistoryPageItems() {
        return currentHistoryPageItems;
    }

    @Override
    protected void preparePageData() {
        if (menuDefinition == null) {
            this.totalPages = 1;
            this.currentPage = 1;
            return;
        }
        List<Integer> displaySlots = menuDefinition.getAuctionItemSlots();
        int itemsPerPage = (displaySlots != null && !displaySlots.isEmpty()) ? displaySlots.size() : 1;
        if (itemsPerPage == 0) itemsPerPage = 1;
        int totalHistoryItems;

        if (isGlobalHistoryView) {
            totalHistoryItems = soldItemDAO.countGlobalSalesHistory();
        } else {
            UUID actualTarget = (this.targetPlayerUuidView != null) ? this.targetPlayerUuidView : viewer.getUniqueId();
            // Pour un historique complet, il faudrait sommer les ventes ET les achats
            // Pour l'instant, on se base sur les ventes du joueur comme exemple.
            totalHistoryItems = soldItemDAO.countSalesBySeller(actualTarget);
        }
        setTotalPages((int) Math.ceil((double) totalHistoryItems / itemsPerPage));
        this.currentPage = Math.max(1, Math.min(this.currentPage, this.totalPages));
    }

    @Override
    public void initializeItems() {
        if (menuDefinition == null || inventory == null) {
            return;
        }
        List<Integer> displaySlots = menuDefinition.getAuctionItemSlots();

        if (displaySlots == null || displaySlots.isEmpty()) {
            plugin.getLogger().warning("No 'auction_item_slots' defined in menu: " + menuName);
        } else {
            int itemsPerPage = displaySlots.size();
            if (isGlobalHistoryView) {
                this.currentHistoryPageItems = soldItemDAO.getGlobalSalesHistory(this.currentPage, itemsPerPage);
            } else {
                UUID actualTarget = (this.targetPlayerUuidView != null) ? this.targetPlayerUuidView : viewer.getUniqueId();
                // Afficher les ventes du joueur. Pour un historique complet, il faudrait une logique pour combiner/sélectionner ventes/achats.
                this.currentHistoryPageItems = soldItemDAO.getSalesBySeller(actualTarget, this.currentPage, itemsPerPage);
            }

            GuiItemDefinition historyItemFormat = menuDefinition.getAuctionItemFormat();
            if (historyItemFormat == null) {
                plugin.getLogger().warning("No 'auction_item_format' defined in menu: " + menuName + ". Cannot display history items.");
            } else {
                for (int i = 0; i < currentHistoryPageItems.size(); i++) {
                    if (i >= displaySlots.size()) break;
                    SoldItem soldItem = currentHistoryPageItems.get(i);
                    int slot = displaySlots.get(i);
                    ItemStack originalItem = soldItem.getItemStack();
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
                        Map<String, String> placeholders = getItemSpecificPlaceholders(soldItem);
                        String nameFormatFromConfig = historyItemFormat.getDisplayName();
                        String originalItemCustomName = ItemUtil.getItemName(originalItem); // Nom custom si l'item en avait un
                        String storedItemName = soldItem.getItemNameDisplay(); // Nom stocké en DB (fallback)

                        if (originalItemCustomName != null && !originalItemCustomName.isEmpty()) {
                            // L'item désérialisé a un nom custom
                            if (nameFormatFromConfig != null && !nameFormatFromConfig.isEmpty()) {
                                String finalName = nameFormatFromConfig.replace("{ItemName}", originalItemCustomName);
                                meta.setDisplayName(PlaceholderUtil.applyPlaceholders(finalName, placeholders));
                            } else {
                                meta.setDisplayName(originalItemCustomName);
                            }
                        } else {
                            // Pas de nom custom sur l'item désérialisé.
                            // On peut utiliser le nom stocké en DB (itemNameDisplay) ou laisser Minecraft gérer.
                            // Pour l'historique, utiliser le nom stocké est souvent préférable.
                            if (nameFormatFromConfig != null && !nameFormatFromConfig.isEmpty() && !nameFormatFromConfig.equals("{ItemName}")) {
                                String finalName = nameFormatFromConfig.replace("{ItemName}", storedItemName); // Utiliser le nom stocké
                                meta.setDisplayName(PlaceholderUtil.applyPlaceholders(finalName, placeholders));
                            } else if (nameFormatFromConfig != null && nameFormatFromConfig.equals("{ItemName}")) {
                                // Si le format est juste {ItemName}, utiliser le nom stocké
                                meta.setDisplayName(PlaceholderUtil.applyPlaceholders(storedItemName, placeholders));
                            } else { // Pas de format config, pas de nom custom sur item actuel
                                // Utiliser le nom stocké si disponible, sinon laisser MC gérer
                                if (storedItemName != null && !storedItemName.isEmpty()) {
                                    meta.setDisplayName(PlaceholderUtil.applyPlaceholders(storedItemName, placeholders));
                                } else {
                                    if (meta.hasDisplayName()) meta.setDisplayName(null); // Laisser MC gérer
                                }
                            }
                        }

                        List<String> configLore = historyItemFormat.getLore();
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
            if (currentHistoryPageItems.isEmpty() && menuDefinition.getItems().containsKey(-3)) {
                GuiItemDefinition noItemsDef = menuDefinition.getItem(-3);
                if (noItemsDef != null && displaySlots.size() > 0) {
                    ItemStack noItemsStack = ItemUtil.createItemStack(noItemsDef.getMaterial(),
                        PlaceholderUtil.applyPlaceholders(noItemsDef.getDisplayName(), getGlobalPlaceholders()),
                        PlaceholderUtil.applyPlaceholdersInList(noItemsDef.getLore(), getGlobalPlaceholders()));
                    inventory.setItem(displaySlots.get(Math.min(displaySlots.size() - 1, displaySlots.size() / 2)), noItemsStack);
                }
            }
        }
        populateStaticItems();
        updateSwitchButton(); // Appel à la nouvelle méthode
    }

    private void updateSwitchButton() {
        int switchSlot = -1;
        GuiItemDefinition switchItemDef = null;

        for (Map.Entry<Integer, GuiItemDefinition> entry : menuDefinition.getItems().entrySet()) {
            if ("SWITCH_HISTORY_SCOPE".equalsIgnoreCase(entry.getValue().getActionType())) {
                switchSlot = entry.getKey();
                switchItemDef = entry.getValue();
                break;
            }
        }

        if (switchSlot == -1 || switchItemDef == null) {
            return;
        }

        if (!viewer.hasPermission("shauctions.history.global")) {
            inventory.setItem(switchSlot, null);
            return;
        }

        ItemStack switchButton = inventory.getItem(switchSlot);
        if (switchButton == null) {
            switchButton = new ItemStack(switchItemDef.getMaterial());
        }

        ItemMeta meta = switchButton.getItemMeta();
        if (meta == null) return;

        if (this.isGlobalHistoryView) {
            meta.setDisplayName(MessageManager.translateColors("&bVoir mon Historique Personnel"));
            meta.setLore(Arrays.asList(
                MessageManager.translateColors("&7Cliquez pour afficher uniquement"),
                MessageManager.translateColors("&7les items que vous avez vendus.")
            ));
        } else {
            meta.setDisplayName(MessageManager.translateColors("&eVoir l'Historique Global"));
            meta.setLore(Arrays.asList(
                MessageManager.translateColors("&7Cliquez pour afficher toutes"),
                MessageManager.translateColors("&7les transactions du serveur.")
            ));
        }

        switchButton.setItemMeta(meta);
        inventory.setItem(switchSlot, switchButton);
    }

    @Override
    public Map<String, String> getGlobalPlaceholders() {
        Map<String, String> placeholders = super.getGlobalPlaceholders();
        if (isGlobalHistoryView) {
            placeholders.put("{HistoryScope}", "Global");
        } else {
            OfflinePlayer op = (targetPlayerUuidView != null) ? Bukkit.getOfflinePlayer(targetPlayerUuidView) : viewer;
            placeholders.put("{HistoryScope}", op.getName() != null ? op.getName() : "Personnel");
        }
        return placeholders;
    }

    private Map<String, String> getItemSpecificPlaceholders(SoldItem soldItem) {
        Map<String, String> placeholders = getGlobalPlaceholders();
        ItemStack itemStack = soldItem.getItemStack();
        String itemNameForPlaceholder = ItemUtil.getItemName(itemStack);
        if (itemNameForPlaceholder == null || itemNameForPlaceholder.isEmpty()) {
            itemNameForPlaceholder = soldItem.getItemNameDisplay(); // Fallback sur le nom stocké
        }
        placeholders.put("{ItemName}", itemNameForPlaceholder != null ? itemNameForPlaceholder : "Item Vendu"); // Dernier fallback
        placeholders.put("{Price}", PriceFormatterUtil.formatPrice(soldItem.getPrice()));
        placeholders.put("{CurrencySymbol}", configManager.getString("economy.currency_symbol", "$"));
        placeholders.put("{SellerName}", soldItem.getSellerName());
        placeholders.put("{BuyerName}", soldItem.getBuyerName());
        placeholders.put("{SoldTimestamp}", TimeUtil.formatDate(soldItem.getSoldTimestamp()));
        placeholders.put("{SoldID}", String.valueOf(soldItem.getId()));
        placeholders.put("{SaleType}", soldItem.isBid() ? "Enchère" : "Vente directe");
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
        // Les items de l'historique ne sont pas interactifs par défaut.
    }
}
