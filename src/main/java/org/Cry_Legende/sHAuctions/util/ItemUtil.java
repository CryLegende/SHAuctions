package org.Cry_Legende.sHAuctions.util;

import org.Cry_Legende.sHAuctions.config.MessageManager;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ItemUtil {

    public static String getItemName(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return "";
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return meta.getDisplayName();
        }
        return null;
    }

    public static String getMaterialNameFallback(Material material) {
        if (material == null || material == Material.AIR) {
            return "Objet Inconnu";
        }
        String keyName = material.getKey().getKey();
        StringBuilder formattedName = new StringBuilder();
        boolean nextIsUpper = true;
        for (char c : keyName.replace('_', ' ').toCharArray()) {
            if (nextIsUpper && Character.isLetter(c)) {
                formattedName.append(Character.toUpperCase(c));
                nextIsUpper = false;
            } else {
                formattedName.append(c);
            }
            if (c == ' ') {
                nextIsUpper = true;
            }
        }
        return formattedName.toString().trim();
    }

    public static ItemStack createItemStack(Material material, int amount, String displayName, List<String> lore) {
        ItemStack item = new ItemStack(material, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (displayName != null && !displayName.isEmpty()) {
                meta.setDisplayName(MessageManager.translateColors(displayName));
            }
            if (lore != null && !lore.isEmpty()) {
                List<String> coloredLore = new ArrayList<>();
                for (String line : lore) {
                    coloredLore.add(MessageManager.translateColors(line));
                }
                meta.setLore(coloredLore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack createItemStack(Material material, String displayName, List<String> lore) {
        return createItemStack(material, 1, displayName, lore);
    }

    public static ItemStack createItemStack(Material material, String displayName) {
        return createItemStack(material, 1, displayName, null);
    }

    public static List<String> getItemLore(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return new ArrayList<>();
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore() || meta.getLore() == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(meta.getLore());
    }

    public static ItemStack replacePlaceholders(ItemStack item, Map<String, String> placeholders) {
        if (item == null || placeholders == null || placeholders.isEmpty()) {
            return item != null ? item.clone() : null;
        }
        ItemStack newItem = item.clone();
        ItemMeta meta = newItem.getItemMeta();
        if (meta != null) {
            if (meta.hasDisplayName()) {
                String currentName = meta.getDisplayName();
                String newName = currentName;
                for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) continue;
                    if (newName != null) {
                        newName = newName.replace(entry.getKey(), entry.getValue());
                    }
                }
                meta.setDisplayName(newName);
            }
            if (meta.hasLore() && meta.getLore() != null) {
                List<String> currentLore = meta.getLore();
                List<String> newLore = currentLore.stream()
                    .map(line -> {
                        if (line == null) return null;
                        String tempLine = line;
                        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                            if (entry.getKey() == null || entry.getValue() == null) continue;
                            tempLine = tempLine.replace(entry.getKey(), entry.getValue());
                        }
                        return tempLine;
                    })
                    .filter(line -> line != null)
                    .collect(Collectors.toList());
                meta.setLore(newLore);
            }
            newItem.setItemMeta(meta);
        }
        return newItem;
    }
}
