package org.Cry_Legende.sHAuctions.model;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class MenuDefinition {

    private final String menuName; // Nom identifiant le menu (ex: "main_auction_house")
    private final String title;
    private final int size;
    private final Map<Integer, GuiItemDefinition> items; // Slot -> Item Definition
    private final Map<String, Object> options; // Options supplémentaires (ex: type de pagination, son à l'ouverture)

    // Optionnel : Configuration pour les items d'enchère (si le menu est paginé avec des items d'enchère)
    private List<Integer> auctionItemSlots; // Liste des slots où les items d'enchère doivent apparaître
    private GuiItemDefinition auctionItemFormat; // Format de l'item d'enchère (nom, lore avec placeholders)

    public MenuDefinition(String menuName, String title, int size, Map<Integer, GuiItemDefinition> items,
                          Map<String, Object> options, List<Integer> auctionItemSlots, GuiItemDefinition auctionItemFormat) {
        this.menuName = menuName;
        this.title = title;
        this.size = size;
        // Utiliser TreeMap pour s'assurer que les items sont triés par slot, bien que non crucial pour la logique
        this.items = new TreeMap<>(items);
        this.options = options;
        this.auctionItemSlots = auctionItemSlots;
        this.auctionItemFormat = auctionItemFormat;
    }

    public String getMenuName() {
        return menuName;
    }

    public String getTitle() {
        return title;
    }

    public int getSize() {
        return size;
    }

    public Map<Integer, GuiItemDefinition> getItems() {
        return items;
    }

    public GuiItemDefinition getItem(int slot) {
        return items.get(slot);
    }

    public Map<String, Object> getOptions() {
        return options;
    }

    public <T> T getOption(String key, T defaultValue) {
        if (options == null || !options.containsKey(key)) {
            return defaultValue;
        }
        try {
            return (T) options.get(key);
        } catch (ClassCastException e) {
            // Log l'erreur ou retourner la valeur par défaut
            System.err.println("Error casting option '" + key + "' for menu '" + menuName + "'. Expected type: " + defaultValue.getClass().getName());
            return defaultValue;
        }
    }

    public List<Integer> getAuctionItemSlots() {
        return auctionItemSlots;
    }

    public GuiItemDefinition getAuctionItemFormat() {
        return auctionItemFormat;
    }
}