package org.Cry_Legende.sHAuctions.gui;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.config.ConfigManager;
import org.Cry_Legende.sHAuctions.config.MenuConfigManager;
import org.Cry_Legende.sHAuctions.config.MessageManager;
import org.Cry_Legende.sHAuctions.model.GuiItemDefinition;
import org.Cry_Legende.sHAuctions.model.MenuDefinition;
import org.Cry_Legende.sHAuctions.util.ItemUtil;
import org.Cry_Legende.sHAuctions.util.PlaceholderUtil;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class AbstractMenu implements InventoryHolder {

    protected final SHAuctions plugin;
    protected final GUIManager guiManager;
    protected final MenuConfigManager menuConfigManager;
    protected final Player viewer;
    protected Inventory inventory;
    protected MenuDefinition menuDefinition;
    protected String menuName;

    protected int currentPage = 1;
    protected int totalPages = 1;

    public AbstractMenu(SHAuctions plugin, GUIManager guiManager, String menuName, Player viewer) {
        this.plugin = plugin;
        this.guiManager = guiManager;
        this.menuConfigManager = plugin.getMenuConfigManager();
        this.viewer = viewer;
        this.menuName = menuName;
        this.menuDefinition = menuConfigManager.getMenuDefinition(menuName);

        if (this.menuDefinition == null) {
            plugin.getLogger().severe("Cannot create menu: Menu definition for '" + menuName + "' not found for player " + viewer.getName());
        }
    }

    public abstract void initializeItems();

    protected abstract void preparePageData();

    public abstract void handleClick(int slot, ItemStack clickedItem, org.bukkit.event.inventory.ClickType clickType);

    /**
     * Ouvre le menu pour la première fois, jouant le son d'ouverture.
     */
    public void open() {
        open(true);
    }

    /**
     * Ouvre ou rafraîchit le menu.
     *
     * @param playOpenSound Si true et que c'est une nouvelle ouverture (pas un refresh d'un inventaire déjà de ce type), joue le son.
     */
    public void open(boolean playOpenSound) {
        if (menuDefinition == null) {
            viewer.sendMessage(plugin.getMessageManager().translateColors("&cErreur: La configuration du menu '" + menuName + "' est introuvable."));
            if (viewer.getOpenInventory().getTopInventory().getHolder() == this) {
                viewer.closeInventory();
            }
            return;
        }

        preparePageData(); // Calcule totalPages, ajuste currentPage
        String title = PlaceholderUtil.applyPlaceholders(menuDefinition.getTitle(), getGlobalPlaceholders());

        // Toujours recréer l'inventaire pour s'assurer que le titre est à jour et que c'est "propre"
        this.inventory = Bukkit.createInventory(this, menuDefinition.getSize(), title);

        initializeItems(); // Remplit this.inventory (appelle populateStaticItems à la fin)

        if (viewer == null || !viewer.isOnline()) {
            plugin.getLogger().warning("Viewer for menu " + menuName + " became invalid before opening inventory.");
            guiManager.removeOpenMenu(viewer);
            return;
        }

        viewer.openInventory(this.inventory);
        guiManager.getOpenMenus().put(viewer.getUniqueId(), this);

        if (playOpenSound) {
            String openSoundName = menuDefinition.getOption("open_sound", "");
            if (!openSoundName.isEmpty() && !openSoundName.equalsIgnoreCase("NONE")) {
                Sound openSound = ConfigManager.sound(openSoundName);
                if (openSound != null) {
                    viewer.playSound(viewer.getLocation(), openSound, 1.0f, 1.0f);
                } else {
                    plugin.getLogger().warning("Invalid open_sound '" + openSoundName + "' for menu '" + menuName + "'.");
                }
            }
        }
    }

    /**
     * Rafraîchit le contenu du menu actuel sans jouer le son d'ouverture.
     * S'assure que le joueur a toujours ce menu ouvert.
     */
    public void refresh() {
        if (viewer == null || !viewer.isOnline() || guiManager.getOpenMenu(viewer) != this) {
            // Le joueur a fermé le menu ou a ouvert un autre menu entre-temps, ou est hors ligne.
            // Ne pas tenter de rafraîchir.
            if (viewer != null) guiManager.removeOpenMenu(viewer); // Nettoyer s'il est encore dans la map
            return;
        }
        // Appeler open() avec playOpenSound = false pour reconstruire et rouvrir.
        open(false);
    }

    @SuppressWarnings("unchecked")
    protected void populateStaticItems() {
        if (menuDefinition == null || inventory == null) return;
        Map<String, String> placeholders = getGlobalPlaceholders();
        for (Map.Entry<Integer, GuiItemDefinition> entry : menuDefinition.getItems().entrySet()) {
            int slot = entry.getKey();
            if (slot < 0) continue; // item spécial, placé par le menu lui-même
            GuiItemDefinition itemDef = entry.getValue();
            if (itemDef.isRequiresPermission() && (itemDef.getPermissionNode() == null || itemDef.getPermissionNode().isEmpty() || !viewer.hasPermission(itemDef.getPermissionNode()))) {
                String replacementType = itemDef.getExtraData() != null ? (String) itemDef.getExtraData().get("replacement_if_no_perm_type") : null;
                if ("SHOW_DISABLED".equalsIgnoreCase(replacementType)) {
                    Material replacementMat = Material.matchMaterial(itemDef.getExtraData().getOrDefault("replacement_material", "BARRIER").toString().toUpperCase());
                    if (replacementMat == null) replacementMat = Material.BARRIER;
                    String replacementName = itemDef.getExtraData().containsKey("replacement_name") ?
                        MessageManager.translateColors(itemDef.getExtraData().get("replacement_name").toString()) :
                        PlaceholderUtil.applyPlaceholders(itemDef.getDisplayName(), placeholders);
                    List<String> replacementLore = itemDef.getExtraData().containsKey("replacement_lore") ?
                        PlaceholderUtil.applyPlaceholdersInList((List<String>) itemDef.getExtraData().get("replacement_lore"), placeholders) :
                        List.of(MessageManager.translateColors("&cPermission Requise"));
                    ItemStack disabledItem = ItemUtil.createItemStack(replacementMat, replacementName, replacementLore);
                    inventory.setItem(slot, disabledItem);
                }
                continue;
            }
            ItemStack itemStack = null;
            boolean hideItem = false;
            Map<String, Object> extraData = itemDef.getExtraData() != null ? itemDef.getExtraData() : new HashMap<>();
            String onUnavailableAction = (String) extraData.getOrDefault("on_unavailable", "SHOW");
            if ("PREVIOUS_PAGE".equals(itemDef.getActionType()) && currentPage <= 1) {
                if ("HIDE_IF_UNAVAILABLE".equalsIgnoreCase(onUnavailableAction)) hideItem = true;
                else if ("DISABLE_IF_UNAVAILABLE".equalsIgnoreCase(onUnavailableAction)) {
                    Material disabledMat = Material.matchMaterial(extraData.getOrDefault("disabled_material", "GRAY_DYE").toString().toUpperCase());
                    if (disabledMat == null) disabledMat = Material.GRAY_DYE;
                    itemStack = ItemUtil.createItemStack(disabledMat, PlaceholderUtil.applyPlaceholders(itemDef.getDisplayName(), placeholders), List.of(MessageManager.translateColors("&7Vous êtes à la première page.")));
                }
            }
            if ("NEXT_PAGE".equals(itemDef.getActionType()) && currentPage >= totalPages) {
                if ("HIDE_IF_UNAVAILABLE".equalsIgnoreCase(onUnavailableAction)) hideItem = true;
                else if ("DISABLE_IF_UNAVAILABLE".equalsIgnoreCase(onUnavailableAction)) {
                    Material disabledMat = Material.matchMaterial(extraData.getOrDefault("disabled_material", "GRAY_DYE").toString().toUpperCase());
                    if (disabledMat == null) disabledMat = Material.GRAY_DYE;
                    itemStack = ItemUtil.createItemStack(disabledMat, PlaceholderUtil.applyPlaceholders(itemDef.getDisplayName(), placeholders), List.of(MessageManager.translateColors("&7Vous êtes à la dernière page.")));
                }
            }
            if (hideItem) {
                inventory.setItem(slot, null);
                continue;
            }
            if (itemStack == null) {
                itemStack = ItemUtil.createItemStack(itemDef.getMaterial(), 1, PlaceholderUtil.applyPlaceholders(itemDef.getDisplayName(), placeholders), PlaceholderUtil.applyPlaceholdersInList(itemDef.getLore(), placeholders));
                if (itemDef.getMaterial() == Material.PLAYER_HEAD && extraData.containsKey("texture")) {
                    itemStack = guiManager.applyHeadTexture(itemStack, (String) extraData.get("texture"));
                }
            }
            inventory.setItem(slot, itemStack);
        }
    }

    public Map<String, String> getGlobalPlaceholders() {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("{currentPage}", String.valueOf(currentPage));
        placeholders.put("{totalPages}", String.valueOf(totalPages));
        placeholders.put("{playerName}", viewer.getName());
        return placeholders;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public Player getViewer() {
        return viewer;
    }

    public MenuDefinition getMenuDefinition() {
        return menuDefinition;
    }

    public String getMenuName() {
        return menuName;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public void setCurrentPage(int currentPage) {
        this.currentPage = Math.max(1, currentPage);
    }

    public int getTotalPages() {
        return totalPages;
    }

    public void setTotalPages(int totalPages) {
        this.totalPages = Math.max(1, totalPages);
    }

    public SHAuctions getPluginReference() {
        return plugin;
    }
}
