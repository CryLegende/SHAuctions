package org.Cry_Legende.sHAuctions.gui.types;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.config.MessageManager;
import org.Cry_Legende.sHAuctions.gui.AbstractMenu;
import org.Cry_Legende.sHAuctions.gui.GUIManager;
import org.Cry_Legende.sHAuctions.model.GuiItemDefinition;
import org.Cry_Legende.sHAuctions.util.ItemUtil;
import org.Cry_Legende.sHAuctions.util.PlaceholderUtil;
import org.Cry_Legende.sHAuctions.util.TimeUtil;
import org.Cry_Legende.sHAuctions.wanted.WantedManager;
import org.Cry_Legende.sHAuctions.wanted.WantedRequest;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Onglet « Recherches » ({@code menus/wanted_requests.yml}) : ce que les joueurs cherchent.
 * Clic gauche sur une recherche = contacter son auteur ; clic droit = supprimer la sienne (ou n'importe laquelle
 * pour le staff). Vue « Vos recherches » ({@code menus/own_wanted_requests.yml}) : seulement celles du joueur,
 * un clic = annuler. Les cases vides sont remplies avec {@code options.filler_material} ; l'item « aucune
 * recherche » (slot -1) se place en {@code options.empty_slot}.
 */
public class WantedMenu extends AbstractMenu {

    /** Largeur d'une ligne du texte d'une recherche dans le lore. */
    private static final int WRAP = 32;

    private final WantedManager wanted;
    private final boolean ownOnly;
    private List<WantedRequest> all = new ArrayList<>();
    private List<WantedRequest> page = new ArrayList<>();

    public WantedMenu(SHAuctions plugin, GUIManager guiManager, Player viewer, boolean ownOnly) {
        super(plugin, guiManager, ownOnly ? "own_wanted_requests" : "wanted_requests", viewer);
        this.wanted = plugin.getWantedManager();
        this.ownOnly = ownOnly;
    }

    @Override
    protected void preparePageData() {
        if (menuDefinition == null) {
            totalPages = 1;
            currentPage = 1;
            return;
        }
        all = ownOnly ? wanted.listActive(viewer.getUniqueId()) : wanted.listActive();
        List<Integer> slots = menuDefinition.getAuctionItemSlots();
        int perPage = slots == null || slots.isEmpty() ? 1 : slots.size();
        setTotalPages((int) Math.ceil((double) all.size() / perPage));
        currentPage = Math.max(1, Math.min(currentPage, totalPages));
    }

    @Override
    public void initializeItems() {
        if (menuDefinition == null || inventory == null) {
            return;
        }
        List<Integer> slots = menuDefinition.getAuctionItemSlots();
        GuiItemDefinition format = menuDefinition.getAuctionItemFormat();
        page = new ArrayList<>();
        if (slots != null && !slots.isEmpty() && format != null) {
            int start = (currentPage - 1) * slots.size();
            for (int i = start; i < Math.min(start + slots.size(), all.size()); i++) {
                page.add(all.get(i));
            }
            for (int i = 0; i < page.size(); i++) {
                inventory.setItem(slots.get(i), display(format, page.get(i)));
            }
            if (all.isEmpty() && menuDefinition.getItems().containsKey(-1)) {
                GuiItemDefinition empty = menuDefinition.getItem(-1);
                int emptySlot = parseSlot(menuDefinition.getOptionString("empty_slot", ""),slots.get(Math.min(slots.size() - 1, slots.size() / 2)));
                inventory.setItem(emptySlot, ItemUtil.createItemStack(empty.getMaterial(),
                    PlaceholderUtil.applyPlaceholders(empty.getDisplayName(), getGlobalPlaceholders()),
                    PlaceholderUtil.applyPlaceholdersInList(empty.getLore(), getGlobalPlaceholders())));
            }
        }
        populateStaticItems();
        fillEmptySlots();
    }

    private ItemStack display(GuiItemDefinition format, WantedRequest request) {
        Map<String, String> ph = getGlobalPlaceholders();
        ph.put("{Owner}", request.ownerName());
        ph.put("{Created}", TimeUtil.formatDate(request.createdAt()));
        ph.put("{ExpiresIn}", TimeUtil.formatDuration(Math.max(0, request.expiresAt() - System.currentTimeMillis() / 1000L)));
        ph.put("{Id}", String.valueOf(request.id()));
        String actionKey = ownOnly ? "recherche_lore_action_annuler"
            : request.isOwnedBy(viewer.getUniqueId()) ? "recherche_lore_action_vous"
            : wanted.isStaff(viewer) ? "recherche_lore_action_staff" : "recherche_lore_action";
        ph.put("{Action}", plugin.getMessageManager().getMessage(actionKey, false));

        List<String> lore = new ArrayList<>();
        for (String line : format.getLore()) {
            if (line.contains("{Text}")) {
                // Texte du joueur, sans couleur interprétée, coupé en lignes de WRAP caractères
                List<String> wrapped = wrap(request.text());
                for (int i = 0; i < wrapped.size(); i++) {
                    lore.add(i == 0 ? PlaceholderUtil.applyPlaceholders(line, ph).replace("{Text}", wrapped.get(0))
                        : MessageManager.translateColors("&f") + wrapped.get(i));
                }
            } else {
                lore.add(PlaceholderUtil.applyPlaceholders(line, ph));
            }
        }
        return ItemUtil.createItemStack(format.getMaterial(), 1, PlaceholderUtil.applyPlaceholders(format.getDisplayName(), ph), lore);
    }

    private int parseSlot(String raw, int def) {
        try {
            int slot = Integer.parseInt(raw.trim());
            return slot >= 0 && slot < inventory.getSize() ? slot : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static List<String> wrap(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > WRAP) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0 || out.isEmpty()) {
            out.add(line.toString());
        }
        return out;
    }

    private void fillEmptySlots() {
        Material filler = Material.matchMaterial(menuDefinition.getOptionString("filler_material","GRAY_STAINED_GLASS_PANE"));
        if (filler == null || filler == Material.AIR) {
            return;
        }
        List<Integer> slots = menuDefinition.getAuctionItemSlots();
        ItemStack pane = ItemUtil.createItemStack(filler, " ");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null && (slots == null || !slots.contains(slot))) {
                inventory.setItem(slot, pane.clone());
            }
        }
    }

    @Override
    public void handleClick(int slot, ItemStack clickedItem, ClickType clickType) {
        if (menuDefinition == null) {
            return;
        }
        GuiItemDefinition staticItem = menuDefinition.getItem(slot);
        if (staticItem != null) {
            guiManager.executeItemAction(viewer, staticItem, this, clickType);
            return;
        }
        List<Integer> slots = menuDefinition.getAuctionItemSlots();
        int index = slots == null ? -1 : slots.indexOf(slot);
        if (index < 0 || index >= page.size()) {
            return;
        }
        // Relue en base : supprimée ou expirée entre l'affichage et le clic
        WantedRequest request = wanted.get(page.get(index).id());
        if (request == null || request.expiresAt() <= System.currentTimeMillis() / 1000L) {
            plugin.getMessageManager().sendMessage(viewer, "recherche_introuvable");
            refresh();
            return;
        }
        if (ownOnly || clickType.isRightClick()) {
            if (wanted.delete(viewer, request)) {
                refresh();
            }
            return;
        }
        if (request.isOwnedBy(viewer.getUniqueId())) {
            plugin.getMessageManager().sendMessage(viewer, "recherche_la_votre");
            return;
        }
        viewer.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> wanted.contact(viewer, request));
    }
}
