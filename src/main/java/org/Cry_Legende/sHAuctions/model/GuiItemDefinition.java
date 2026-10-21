package org.Cry_Legende.sHAuctions.model;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

public class GuiItemDefinition {

    private final int slot;
    private Material material;
    private String displayName;
    private List<String> lore;
    private String actionType; // e.g., "OPEN_MENU", "COMMAND", "PREVIOUS_PAGE", "NEXT_PAGE", "AUCTION_ITEM", "FILLER"
    private String actionValue; // e.g., menu name, command string, (aucun pour page_suiv/prec)
    private Map<String, Object> extraData; // Pour des configurations spécifiques (ex: texture de tête de joueur, NBT)
    private boolean requiresPermission; // Si l'item nécessite une permission pour être affiché/cliquable
    private String permissionNode;      // La permission requise

    // Constructeur, getters (et potentiellement setters si la modification dynamique est nécessaire, mais généralement on charge et on lit)

    public GuiItemDefinition(int slot, Material material, String displayName, List<String> lore,
                             String actionType, String actionValue, Map<String, Object> extraData,
                             boolean requiresPermission, String permissionNode) {
        this.slot = slot;
        this.material = material;
        this.displayName = displayName;
        this.lore = lore;
        this.actionType = actionType;
        this.actionValue = actionValue;
        this.extraData = extraData;
        this.requiresPermission = requiresPermission;
        this.permissionNode = permissionNode;
    }

    public int getSlot() {
        return slot;
    }

    public Material getMaterial() {
        return material;
    }

    public void setMaterial(Material material) { // Utile si le matériau peut changer dynamiquement (ex: remplacement)
        this.material = material;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) { // Utile pour les placeholders
        this.displayName = displayName;
    }

    public List<String> getLore() {
        return lore;
    }

    public void setLore(List<String> lore) { // Utile pour les placeholders
        this.lore = lore;
    }

    public String getActionType() {
        return actionType;
    }

    public String getActionValue() {
        return actionValue;
    }

    public Map<String, Object> getExtraData() {
        return extraData;
    }

    public boolean isRequiresPermission() {
        return requiresPermission;
    }

    public String getPermissionNode() {
        return permissionNode;
    }

    // Potentiellement une méthode pour créer un ItemStack à partir de cette définition
    // (sera gérée par GUIManager ou une classe utilitaire pour items)
    // public ItemStack toItemStack() { ... }
}