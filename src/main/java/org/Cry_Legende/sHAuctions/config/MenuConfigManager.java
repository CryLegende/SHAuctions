package org.Cry_Legende.sHAuctions.config; // Le package est déjà config, donc MessageManager est au même niveau

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.model.GuiItemDefinition;
import org.Cry_Legende.sHAuctions.model.MenuDefinition;
// import org.Cry_Legende.sHAuctions.util.ChatUtil; // Commenté pour l'instant

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class MenuConfigManager {

    private final SHAuctions plugin;
    private final File menusFolder;
    private final Map<String, MenuDefinition> menuDefinitions = new HashMap<>();

    public MenuConfigManager(SHAuctions plugin) {
        this.plugin = plugin;
        this.menusFolder = new File(plugin.getDataFolder(), "menus");
    }

    public void loadMenus() {
        menuDefinitions.clear();
        if (!menusFolder.exists()) {
            if (menusFolder.mkdirs()) {
                plugin.getLogger().info("Menus directory created at: " + menusFolder.getPath());
                saveDefaultMenuIfNotExists("main_auction_house.yml");
                saveDefaultMenuIfNotExists("auction_bids.yml");
                saveDefaultMenuIfNotExists("confirm_purchase.yml");
                saveDefaultMenuIfNotExists("expired_items.yml");
                saveDefaultMenuIfNotExists("own_sales.yml");
                saveDefaultMenuIfNotExists("sales_history.yml");
            } else {
                plugin.getLogger().severe("Could not create menus directory! GUIs may not work.");
                return;
            }
        }

        File[] menuFiles = menusFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".yml"));
        if (menuFiles == null || menuFiles.length == 0) {
            plugin.getLogger().warning("No menu configuration files found in " + menusFolder.getPath() + ". Creating default examples if they don't exist.");
            saveDefaultMenuIfNotExists("main_auction_house.yml");
            saveDefaultMenuIfNotExists("auction_bids.yml");
            saveDefaultMenuIfNotExists("confirm_purchase.yml");
            // ... etc
            menuFiles = menusFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".yml"));
            if (menuFiles == null || menuFiles.length == 0) {
                plugin.getLogger().severe("Still no menu files after attempting to create defaults. GUIs will be unavailable.");
                return;
            }
        }

        for (File menuFile : menuFiles) {
            String menuName = menuFile.getName().replace(".yml", "");
            FileConfiguration menuConfig = YamlConfiguration.loadConfiguration(menuFile);

            try {
                MenuDefinition definition = parseMenuDefinition(menuName, menuConfig, menuFile);
                if (definition != null) {
                    menuDefinitions.put(menuName, definition);
                    plugin.getLogger().info("Loaded menu: " + menuName);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error parsing menu file: " + menuFile.getName(), e);
            }
        }
        plugin.getLogger().info(menuDefinitions.size() + " menu definitions loaded.");
    }

    private void saveDefaultMenuIfNotExists(String fileName) {
        File menuFile = new File(menusFolder, fileName);
        if (!menuFile.exists()) {
            try (InputStream in = plugin.getResource("menus/" + fileName)) {
                if (in == null) {
                    plugin.getLogger().warning("Default menu resource not found: menus/" + fileName);
                    return;
                }
                plugin.saveResource("menus/" + fileName, false);
                plugin.getLogger().info("Created default menu file: " + fileName);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Could not save default menu: " + fileName, e);
            }
        }
    }


    private MenuDefinition parseMenuDefinition(String menuName, FileConfiguration config, File fileOrigin) {
        // Utiliser MessageManager.translateColors au lieu de ChatUtil.translateColors
        String title = MessageManager.translateColors(config.getString("title", "&cMenu Title Missing"));
        int size = config.getInt("size", 54);
        if (size % 9 != 0 || size < 9 || size > 54) {
            plugin.getLogger().warning("Invalid size (" + size + ") for menu '" + menuName + "' in " + fileOrigin.getName() + ". Defaulting to 54. Size must be a multiple of 9 between 9 and 54.");
            size = 54;
        }

        Map<Integer, GuiItemDefinition> items = new TreeMap<>();
        ConfigurationSection itemsSection = config.getConfigurationSection("items");
        if (itemsSection != null) {
            for (String slotKey : itemsSection.getKeys(false)) {
                try {
                    int slot = Integer.parseInt(slotKey);
                    // Slots négatifs = items spéciaux (-1, -2, -3, -99 : "aucun item"), placés par chaque menu
                    if (slot >= size) {
                        plugin.getLogger().warning("Invalid slot '" + slotKey + "' for menu '" + menuName + "' in " + fileOrigin.getName() + ". Slot is out of bounds for size " + size + ". Skipping item.");
                        continue;
                    }

                    ConfigurationSection itemConfig = itemsSection.getConfigurationSection(slotKey);
                    if (itemConfig == null) continue;

                    String materialName = itemConfig.getString("material", "STONE");
                    Material material = Material.matchMaterial(materialName.toUpperCase());
                    if (material == null) {
                        plugin.getLogger().warning("Invalid material '" + materialName + "' for item in slot " + slot + " of menu '" + menuName + "'. Defaulting to STONE.");
                        material = Material.STONE;
                    }

                    String displayName = MessageManager.translateColors(itemConfig.getString("name"));
                    List<String> lore = itemConfig.getStringList("lore").stream()
                        .map(MessageManager::translateColors) // Utiliser MessageManager.translateColors ici aussi
                        .collect(Collectors.toList());

                    String actionType = itemConfig.getString("action.type", "NONE").toUpperCase();
                    String actionValue = itemConfig.getString("action.value");
                    Map<String, Object> extraData = itemConfig.getConfigurationSection("action.extra_data") != null ?
                        itemConfig.getConfigurationSection("action.extra_data").getValues(true) : new HashMap<>();

                    boolean requiresPermission = itemConfig.getBoolean("requires_permission", false);
                    String permissionNode = itemConfig.getString("permission_node", "");


                    GuiItemDefinition itemDef = new GuiItemDefinition(slot, material, displayName, lore,
                        actionType, actionValue, extraData, requiresPermission, permissionNode);
                    items.put(slot, itemDef);

                } catch (NumberFormatException e) {
                    plugin.getLogger().warning("Invalid slot key '" + slotKey + "' in menu '" + menuName + "' in " + fileOrigin.getName() + ". Must be an integer. Skipping item.");
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Error parsing item in slot " + slotKey + " for menu '" + menuName + "' in " + fileOrigin.getName() + ". Skipping item.", e);
                }
            }
        }

        Map<String, Object> options = config.getConfigurationSection("options") != null ?
            config.getConfigurationSection("options").getValues(true) : new HashMap<>();

        List<Integer> auctionItemSlots = config.getIntegerList("auction_item_slots");
        GuiItemDefinition auctionItemFormat = null;
        ConfigurationSection auctionItemFormatSection = config.getConfigurationSection("auction_item_format");
        if (auctionItemFormatSection != null) {
            String materialName = auctionItemFormatSection.getString("material", "PAPER");
            Material material = Material.matchMaterial(materialName.toUpperCase());
            if (material == null) material = Material.PAPER;

            String displayName = MessageManager.translateColors(auctionItemFormatSection.getString("name", "&f{ItemName}"));
            List<String> lore = auctionItemFormatSection.getStringList("lore").stream()
                .map(MessageManager::translateColors) // Utiliser MessageManager.translateColors
                .collect(Collectors.toList());
            auctionItemFormat = new GuiItemDefinition(-1, material, displayName, lore, "AUCTION_ITEM_DISPLAY", null, new HashMap<>(), false, null);
        }


        return new MenuDefinition(menuName, title, size, items, options, auctionItemSlots, auctionItemFormat);
    }

    public MenuDefinition getMenuDefinition(String menuName) {
        MenuDefinition def = menuDefinitions.get(menuName);
        if (def == null) {
            plugin.getLogger().warning("Attempted to get non-existent menu definition: " + menuName);
        }
        return def;
    }

    public void reloadMenus() {
        plugin.getLogger().info("Reloading menu configurations...");
        loadMenus();
        plugin.getLogger().info("Menu configurations reloaded.");
    }
}
