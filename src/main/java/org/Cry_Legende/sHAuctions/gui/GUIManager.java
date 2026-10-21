package org.Cry_Legende.sHAuctions.gui;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.gui.types.*;
import org.Cry_Legende.sHAuctions.model.GuiItemDefinition;
import org.Cry_Legende.sHAuctions.util.PlaceholderUtil;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class GUIManager {

    private final SHAuctions plugin;
    private final Map<UUID, AbstractMenu> openMenus = new HashMap<>();

    public GUIManager(SHAuctions plugin) {
        this.plugin = plugin;
    }

    public void openAuctionHouseMenu(Player player, int page) {
        AuctionHouseMenu menu = new AuctionHouseMenu(plugin, this, player);
        menu.setCurrentPage(page);
        menu.open(); // Ouvre avec son par défaut
    }

    public void openBidHouseMenu(Player player, int page) {
        BidHouseMenu menu = new BidHouseMenu(plugin, this, player);
        menu.setCurrentPage(page);
        menu.open(); // Ouvre avec son par défaut
    }

    /**
     * Rafraîchit tous les menus d'enchères actuellement ouverts (après un placement d'enchère).
     */
    public void refreshOpenBidMenus() {
        // Copie défensive : refresh() peut muter openMenus (ConcurrentModificationException sinon).
        for (AbstractMenu menu : new java.util.ArrayList<>(openMenus.values())) {
            if (menu instanceof BidHouseMenu) {
                menu.refresh();
            }
        }
    }

    public void openConfirmPurchaseMenu(Player player, long auctionId) {
        ConfirmPurchaseMenu menu = new ConfirmPurchaseMenu(plugin, this, player, auctionId);
        menu.open(); // Ouvre avec son par défaut
    }

    public void openExpiredItemsMenu(Player player, int page) {
        ExpiredItemsMenu menu = new ExpiredItemsMenu(plugin, this, player);
        menu.setCurrentPage(page);
        menu.open(); // Ouvre avec son par défaut
    }

    public void openOwnSalesMenu(Player player, int page) {
        OwnSalesMenu menu = new OwnSalesMenu(plugin, this, player);
        menu.setCurrentPage(page);
        menu.open(); // Ouvre avec son par défaut
    }

    public void openSalesHistoryMenu(Player player, int page, boolean global, UUID targetPlayerUuid) {
        SalesHistoryMenu menu = new SalesHistoryMenu(plugin, this, player, global, targetPlayerUuid);
        menu.setCurrentPage(page);
        menu.open(); // Ouvre avec son par défaut
    }

    public void openMenuByName(Player player, String menuName) {
        plugin.getLogger().fine("Attempting to open menu by name: " + menuName + " for player " + player.getName());
        int defaultPage = 1;

        // Quand on ouvre un menu par son nom (via un clic sur un item d'un autre menu),
        // on utilise la méthode open() par défaut qui jouera le son.
        switch (menuName.toLowerCase()) {
            case "main_auction_house":
                openAuctionHouseMenu(player, defaultPage);
                break;
            case "auction_bids":
                openBidHouseMenu(player, defaultPage);
                break;
            case "expired_items":
                openExpiredItemsMenu(player, defaultPage);
                break;
            case "own_sales":
                openOwnSalesMenu(player, defaultPage);
                break;
            case "sales_history":
                openSalesHistoryMenu(player, defaultPage, false, player.getUniqueId());
                break;
            default:
                player.sendMessage(plugin.getMessageManager().translateColors("&cLe menu '" + menuName + "' n'est pas géré par ouverture directe."));
                plugin.getLogger().warning("No direct open method in GUIManager for menu name: " + menuName);
                break;
        }
    }

    public Map<UUID, AbstractMenu> getOpenMenus() {
        return openMenus;
    }

    public AbstractMenu getOpenMenu(Player player) {
        return openMenus.get(player.getUniqueId());
    }

    public void removeOpenMenu(Player player) {
        openMenus.remove(player.getUniqueId());
        plugin.getLogger().fine("Removed open menu for player: " + player.getName());
    }

    public ItemStack applyHeadTexture(ItemStack head, String base64Texture) {
        // ... (identique)
        if (head == null || head.getType() != Material.PLAYER_HEAD || base64Texture == null || base64Texture.isEmpty()) {
            return head;
        }
        SkullMeta skullMeta = (SkullMeta) head.getItemMeta();
        if (skullMeta == null) return head;
        GameProfile profile = new GameProfile(UUID.randomUUID(), null);
        profile.getProperties().put("textures", new Property("textures", base64Texture));
        try {
            Field profileField = skullMeta.getClass().getDeclaredField("profile");
            profileField.setAccessible(true);
            profileField.set(skullMeta, profile);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            plugin.getLogger().log(Level.WARNING, "Could not set player head texture via reflection", e);
        }
        head.setItemMeta(skullMeta);
        return head;
    }

    public void executeItemAction(Player player, GuiItemDefinition itemDef, AbstractMenu currentMenuContext, ClickType clickType) {
        if (itemDef == null || itemDef.getActionType() == null) {
            return;
        }
        String actionType = itemDef.getActionType().toUpperCase();
        String actionValue = itemDef.getActionValue();
        plugin.getLogger().fine("Executing action: " + actionType + " (value: " + actionValue + ", slot: " + itemDef.getSlot() + ") for player " + player.getName() + " in menu " + currentMenuContext.getMenuName());
        Map<String, String> globalPlaceholders = currentMenuContext.getGlobalPlaceholders();
        if (actionValue != null) {
            actionValue = PlaceholderUtil.applyPlaceholders(actionValue, globalPlaceholders);
        }

        switch (actionType) {
            case "NONE":
            case "FILLER":
            case "INFO":
            case "DISPLAY_ITEM_TO_PURCHASE":
                break;
            case "OPEN_MENU": // Changement ici
                if (actionValue != null && !actionValue.isEmpty()) {
                    // NE PAS player.closeInventory(); Bukkit gère la transition.
                    openMenuByName(player, actionValue);
                } else {
                    plugin.getLogger().warning("OPEN_MENU action in menu '" + currentMenuContext.getMenuName() + "' slot " + itemDef.getSlot() + " has no action_value.");
                }
                break;
            case "COMMAND":
                if (actionValue != null && !actionValue.isEmpty()) {
                    player.closeInventory();
                    String finalActionValue = actionValue;
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (finalActionValue.toLowerCase().startsWith("player:")) {
                            player.performCommand(finalActionValue.substring("player:".length()).trim());
                        } else if (finalActionValue.toLowerCase().startsWith("console:")) {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalActionValue.substring("console:".length()).trim());
                        } else {
                            player.performCommand(finalActionValue);
                        }
                    }, 1L);
                }
                break;
            case "CLOSE_MENU":
                player.closeInventory();
                break;
            case "PREVIOUS_PAGE":
                if (currentMenuContext.getCurrentPage() > 1) {
                    currentMenuContext.setCurrentPage(currentMenuContext.getCurrentPage() - 1);
                    currentMenuContext.refresh(); // Utiliser refresh pour ne pas jouer de son
                }
                break;
            case "NEXT_PAGE":
                if (currentMenuContext.getCurrentPage() < currentMenuContext.getTotalPages()) {
                    currentMenuContext.setCurrentPage(currentMenuContext.getCurrentPage() + 1);
                    currentMenuContext.refresh(); // Utiliser refresh pour ne pas jouer de son
                }
                break;
            case "SWITCH_HISTORY_SCOPE":
                if (currentMenuContext instanceof SalesHistoryMenu) {
                    SalesHistoryMenu historyMenu = (SalesHistoryMenu) currentMenuContext;

                    if (historyMenu.isGlobalHistory()) {
                        // Passe à la vue personnelle
                        this.openSalesHistoryMenu(player, 1, false, player.getUniqueId());
                    } else {
                        // Passe à la vue globale, si permission
                        if (player.hasPermission("shauctions.history.global")) {
                            this.openSalesHistoryMenu(player, 1, true, null);
                        } else {
                            plugin.getMessageManager().sendMessage(player, "no_permission");
                        }
                    }
                }
                break;
            default:
                plugin.getLogger().warning("Unknown action type '" + actionType + "' for item in menu '" + currentMenuContext.getMenuName() + "' slot " + itemDef.getSlot() + ".");
                break;
        }
    }
}
