package org.Cry_Legende.sHAuctions.listener;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.gui.AbstractMenu;
import org.Cry_Legende.sHAuctions.gui.GUIManager;
import org.Cry_Legende.sHAuctions.model.GuiItemDefinition; // Peut être utile ici

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

public class InventoryClickListener implements Listener {

    private final SHAuctions plugin;
    private final GUIManager guiManager;

    public InventoryClickListener(SHAuctions plugin) {
        this.plugin = plugin;
        this.guiManager = plugin.getGuiManager();
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return; // Ignorer si ce n'est pas un joueur
        }

        Player player = (Player) event.getWhoClicked();
        Inventory clickedInventory = event.getClickedInventory(); // L'inventaire où le clic a eu lieu

        // Vérifier si le clic a eu lieu dans un de nos menus
        InventoryHolder holder = event.getInventory().getHolder(); // L'inventaire du haut (notre GUI)

        if (holder instanceof AbstractMenu) {
            AbstractMenu menu = (AbstractMenu) holder;

            // Empêcher le joueur de prendre/déplacer des items dans nos GUIs
            event.setCancelled(true);

            // Vérifier si le clic est bien dans l'inventaire du haut (le GUI) et non l'inventaire du joueur
            if (clickedInventory == null || !clickedInventory.equals(event.getView().getTopInventory())) {
                // Si le clic est dans l'inventaire du joueur (ex: SHIFT+CLIC pour vendre),
                // nous ne le gérons pas ici pour l'instant. Cette logique serait dans la commande /hdv sell
                // ou une future fonctionnalité de "clic pour vendre".
                // Pour les menus normaux, on ignore ces clics si on veut juste les actions des boutons.
                return;
            }

            ItemStack clickedItem = event.getCurrentItem();
            if (clickedItem == null || clickedItem.getType().isAir()) {
                return; // Clic sur un slot vide
            }

            int slot = event.getSlot();

            // Déléguer la gestion du clic au menu spécifique
            // Le menu lui-même ou le GUIManager via le menu déterminera l'action
            menu.handleClick(slot, clickedItem, event.getClick());

        }
        // Si ce n'est pas un AbstractMenu, l'event n'est pas annulé et se comporte normalement.
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        // Empêcher le drag d'items dans nos menus
        if (event.getInventory().getHolder() instanceof AbstractMenu) {
            // Vérifier si au moins un des slots affectés par le drag est dans notre GUI
            // Le plus simple est d'annuler si le GUI est un AbstractMenu
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getPlayer();
        InventoryHolder holder = event.getInventory().getHolder();

        if (holder instanceof AbstractMenu) {
            // Retirer le menu de la map des menus ouverts quand le joueur le ferme
            guiManager.removeOpenMenu(player);
            // Aucune autre action spécifique à la fermeture n'est nécessaire ici pour l'instant.
            // Les menus eux-mêmes pourraient avoir une méthode onClose() si besoin.
        }
    }
}