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

public class ConfirmPurchaseMenu extends AbstractMenu {

    private final AuctionDAO auctionDAO;
    private final AuctionManager auctionManager;
    private final ConfigManager configManager;
    private final long auctionIdToConfirm;
    private AuctionItem auctionItem; // Garder private, accessible via getter

    public ConfirmPurchaseMenu(SHAuctions plugin, GUIManager guiManager, Player viewer, long auctionIdToConfirm) {
        super(plugin, guiManager, "confirm_purchase", viewer);
        this.auctionDAO = plugin.getDatabaseManager().getAuctionDAO();
        this.auctionManager = plugin.getAuctionManager();
        this.configManager = plugin.getConfigManager();
        this.auctionIdToConfirm = auctionIdToConfirm;
    }

    // Getters pour RealTimeLoreUpdateTask et potentiellement d'autres systèmes
    public AuctionItem getAuctionItemToConfirm() {
        return auctionItem;
    }

    public int getDisplayItemSlot() {
        if (menuDefinition == null) return -1;
        for (Map.Entry<Integer, GuiItemDefinition> entry : menuDefinition.getItems().entrySet()) {
            if ("DISPLAY_ITEM_TO_PURCHASE".equalsIgnoreCase(entry.getValue().getActionType())) {
                return entry.getKey();
            }
        }
        return -1; // Slot non trouvé
    }


    @Override
    protected void preparePageData() {
        this.totalPages = 1; // Ce menu n'est pas paginé
        this.currentPage = 1;
        // Charger l'item ici pour que les placeholders globaux (utilisés par le titre et les items statiques)
        // puissent accéder aux informations de l'item.
        this.auctionItem = auctionDAO.getAuctionById(auctionIdToConfirm);
        if (this.auctionItem == null || this.auctionItem.isExpired()) {
            // Si l'item n'est plus valide, marquer auctionItem comme null.
            // La méthode open() ou initializeItems() devra gérer ce cas.
            this.auctionItem = null;
        }
    }

    @Override
    public void initializeItems() {
        // this.inventory est créé par AbstractMenu.open() juste avant cet appel.
        // this.auctionItem est chargé (ou mis à null si invalide) par preparePageData().

        if (menuDefinition == null || inventory == null) {
            plugin.getLogger().warning("Menu definition or inventory is null for ConfirmPurchaseMenu. Viewer: " + viewer.getName());
            // AbstractMenu.open() devrait déjà avoir géré la fermeture si menuDefinition est null
            return;
        }

        if (this.auctionItem == null) { // Item non trouvé ou expiré, déjà géré par preparePageData.
            viewer.sendMessage(plugin.getMessageManager().getMessage("item_not_found_or_expired")); // Message spécifique
            // Forcer la fermeture si ce n'est pas déjà fait.
            // Le scheduler est utilisé pour éviter des problèmes de modification d'inventaire pendant un événement d'inventaire.
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (viewer.getOpenInventory().getTopInventory().getHolder() == this) {
                    viewer.closeInventory();
                }
            });
            return;
        }

        // populateStaticItems() est appelé en premier pour mettre les boutons "Confirmer", "Annuler".
        // Ces boutons peuvent utiliser des placeholders globaux qui incluent maintenant les infos de l'item.
        populateStaticItems();

        // Ensuite, on place l'item à acheter lui-même.
        for (Map.Entry<Integer, GuiItemDefinition> entry : menuDefinition.getItems().entrySet()) {
            int slot = entry.getKey();
            GuiItemDefinition itemDefFromYml = entry.getValue(); // Définition de l'item de display depuis YML

            if ("DISPLAY_ITEM_TO_PURCHASE".equalsIgnoreCase(itemDefFromYml.getActionType())) {
                ItemStack originalItemFromAuction = auctionItem.getItemStack(); // L'item réel à acheter
                ItemStack displayItem; // L'item qui sera affiché dans le GUI

                if (originalItemFromAuction == null) {
                    // Ce cas ne devrait pas arriver si auctionItem est valide, car getItemStack désérialiserait.
                    // Mais par sécurité :
                    displayItem = ItemUtil.createItemStack(Material.BARRIER, "&cErreur Item", List.of("&7Impossible de charger l'item."));
                } else {
                    displayItem = originalItemFromAuction.clone(); // Toujours travailler sur un clone
                }

                ItemMeta meta = displayItem.getItemMeta();
                if (meta == null && displayItem.getType() != Material.AIR) { // S'assurer qu'on a une meta
                    meta = Bukkit.getItemFactory().getItemMeta(displayItem.getType());
                }

                if (meta != null) {
                    // Les placeholders spécifiques à l'item pour son nom/lore dans CE menu
                    Map<String, String> placeholders = getItemSpecificPlaceholders(auctionItem);
                    String nameFormatFromConfig = itemDefFromYml.getDisplayName(); // Format du nom depuis YML (ex: "&6Acheter: {ItemName}")
                    String originalItemCustomName = ItemUtil.getItemName(originalItemFromAuction); // Nom custom de l'item, ou null

                    if (originalItemCustomName != null && !originalItemCustomName.isEmpty()) {
                        // L'item original A un nom custom.
                        if (nameFormatFromConfig != null && !nameFormatFromConfig.isEmpty()) {
                            // La config YML fournit un format pour le nom.
                            String finalName = nameFormatFromConfig.replace("{ItemName}", originalItemCustomName);
                            meta.setDisplayName(PlaceholderUtil.applyPlaceholders(finalName, placeholders)); // Appliquer les autres placeholders
                        } else {
                            // Pas de format dans YML, on utilise le nom custom de l'item original tel quel.
                            meta.setDisplayName(originalItemCustomName);
                        }
                    } else {
                        // L'item original N'A PAS de nom custom.
                        if (nameFormatFromConfig != null && !nameFormatFromConfig.isEmpty() && !nameFormatFromConfig.equals("{ItemName}")) {
                            // Le YML a un format qui ajoute du texte (ex: "Info: {ItemName}").
                            String finalName = nameFormatFromConfig.replace("{ItemName}", ""); // {ItemName} devient vide
                            meta.setDisplayName(PlaceholderUtil.applyPlaceholders(finalName, placeholders));
                        } else {
                            // Pas de format YML spécifique OU format YML est juste "{ItemName}".
                            // On ne définit PAS de nom pour laisser Minecraft afficher le nom par défaut.
                            if (meta.hasDisplayName()) { // Sécurité : si le clone avait un nom par défaut
                                meta.setDisplayName(null);
                            }
                        }
                    }

                    // Gestion du Lore
                    List<String> configLore = itemDefFromYml.getLore(); // Lore format from YML
                    List<String> finalLore = new ArrayList<>();
                    if (configLore != null && !configLore.isEmpty()) {
                        for (String line : configLore) {
                            if (line.contains("{ItemLore}")) {
                                List<String> originalLore = ItemUtil.getItemLore(originalItemFromAuction); // Lore de l'item original
                                if (originalLore != null && !originalLore.isEmpty()) {
                                    finalLore.addAll(PlaceholderUtil.applyPlaceholdersInList(originalLore, placeholders));
                                }
                            } else {
                                finalLore.add(PlaceholderUtil.applyPlaceholders(line, placeholders));
                            }
                        }
                    } else { // Si pas de lore défini dans le YML pour cet item de display, on prend celui de l'item original
                        List<String> originalLore = ItemUtil.getItemLore(originalItemFromAuction);
                        if (originalLore != null && !originalLore.isEmpty()) {
                            finalLore.addAll(PlaceholderUtil.applyPlaceholdersInList(originalLore, placeholders)); // Appliquer placeholders au lore original
                        }
                    }
                    meta.setLore(finalLore);
                    displayItem.setItemMeta(meta);
                }
                inventory.setItem(slot, displayItem);
                break; // On suppose qu'il n'y a qu'un seul slot pour DISPLAY_ITEM_TO_PURCHASE
            }
        }
    }

    @Override
    public Map<String, String> getGlobalPlaceholders() {
        // Surcharger pour que les placeholders globaux (utilisés par le titre et populateStaticItems)
        // aient accès aux infos de l'item en cours de confirmation.
        Map<String, String> placeholders = super.getGlobalPlaceholders(); // Obtient {currentPage}, {totalPages}, {playerName}
        if (this.auctionItem != null) { // auctionItem est chargé (ou null) dans preparePageData
            placeholders.putAll(getItemSpecificPlaceholders(this.auctionItem));
        }
        return placeholders;
    }

    // Placeholders spécifiques à l'item pour ce menu (utilisés par le titre, les items statiques et l'item de display)
    private Map<String, String> getItemSpecificPlaceholders(AuctionItem itemToDisplay) {
        Map<String, String> placeholders = new HashMap<>(); // Ne pas hériter ici pour éviter une boucle infinie avec getGlobalPlaceholders

        ItemStack itemStack = itemToDisplay.getItemStack(); // L'item réel
        String itemNameForPlaceholder = ItemUtil.getItemName(itemStack); // Peut être null ou vide

        placeholders.put("{ItemName}", itemNameForPlaceholder != null ? itemNameForPlaceholder : "");
        placeholders.put("{Price}", PriceFormatterUtil.formatPrice(itemToDisplay.getPrice()));
        placeholders.put("{CurrencySymbol}", configManager.getString("economy.currency_symbol", "$"));
        placeholders.put("{OwnerName}", itemToDisplay.getSellerName());
        placeholders.put("{RemainingTime}", TimeUtil.formatDuration(itemToDisplay.getExpireTimestamp() - (System.currentTimeMillis() / 1000L)));
        placeholders.put("{AuctionID}", String.valueOf(itemToDisplay.getId()));
        // Ajoutez d'autres placeholders spécifiques si le YML de confirm_purchase les utilise

        return placeholders;
    }

    @Override
    public void handleClick(int slot, ItemStack clickedItem, ClickType clickType) {
        if (menuDefinition == null || auctionItem == null) { // Vérifier auctionItem aussi car il est crucial
            if (viewer.getOpenInventory().getTopInventory().getHolder() == this) {
                viewer.closeInventory(); // Sécurité
            }
            return;
        }

        GuiItemDefinition itemDef = menuDefinition.getItem(slot);
        if (itemDef == null) return; // Clic sur un slot vide

        String actionType = itemDef.getActionType() != null ? itemDef.getActionType().toUpperCase() : "NONE";

        switch (actionType) {
            case "CONFIRM_PURCHASE_ACTION": // Action spécifique définie dans le YML
                viewer.closeInventory(); // Fermer le menu de confirmation avant de traiter
                auctionManager.purchaseAuction(viewer, auctionItem.getId());
                // L'AuctionManager enverra les messages de succès/échec.
                // Le MenuRefreshListener s'occupera de rafraîchir l'HDV principal.
                break;

            case "CANCEL_PURCHASE_ACTION": // Action spécifique définie dans le YML
                viewer.closeInventory();
                guiManager.openAuctionHouseMenu(viewer, 1);
                break;

            case "DISPLAY_ITEM_TO_PURCHASE":
                // Généralement, on ne fait rien si on clique sur l'item de display lui-même.
                break;

            default:
                // Laisser GUIManager gérer les actions communes (OPEN_MENU, COMMAND, etc.)
                // si d'autres types d'items étaient présents dans ce menu.
                guiManager.executeItemAction(viewer, itemDef, this, clickType);
                break;
        }
    }
}