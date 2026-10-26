package org.Cry_Legende.sHAuctions.config;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

public class ConfigManager {

    private final SHAuctions plugin;
    private FileConfiguration config;
    private File configFile;

    public ConfigManager(SHAuctions plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "config.yml");
    }

    public void load() {
        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
            plugin.getLogger().info("config.yml not found, created a default one.");
        }
        config = YamlConfiguration.loadConfiguration(configFile);

        InputStream defaultConfigStream = plugin.getResource("config.yml");
        if (defaultConfigStream != null) {
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultConfigStream, StandardCharsets.UTF_8));
            config.setDefaults(defaultConfig);
            config.options().copyDefaults(true);
            try {
                config.save(configFile);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not save config.yml after adding defaults", e);
            }
        }
        plugin.getLogger().info("config.yml loaded successfully.");
    }

    public void reload() {
        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(configFile);
        InputStream defaultConfigStream = plugin.getResource("config.yml");
        if (defaultConfigStream != null) {
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultConfigStream, StandardCharsets.UTF_8));
            config.setDefaults(defaultConfig);
        }
        plugin.getLogger().info("config.yml reloaded.");
    }

    public String getString(String path) {
        return config.getString(path, "");
    }

    public String getString(String path, String def) {
        return config.getString(path, def);
    }

    public int getInt(String path) {
        return config.getInt(path, 0);
    }

    public int getInt(String path, int def) {
        return config.getInt(path, def);
    }

    public long getLong(String path) { // << NOUVELLE MÉTHODE
        return config.getLong(path, 0L);
    }

    public long getLong(String path, long def) { // << NOUVELLE MÉTHODE
        return config.getLong(path, def);
    }

    public double getDouble(String path) {
        return config.getDouble(path, 0.0);
    }

    public double getDouble(String path, double def) {
        return config.getDouble(path, def);
    }

    public boolean getBoolean(String path) {
        return config.getBoolean(path, false);
    }

    public boolean getBoolean(String path, boolean def) {
        return config.getBoolean(path, def);
    }

    public List<String> getStringList(String path) {
        return config.getStringList(path);
    }

    public List<String> getStringList(String path, List<String> def) {
        List<String> list = config.getStringList(path);
        return list.isEmpty() && !config.contains(path) ? def : list;
    }

    public Material getMaterial(String path, Material def) {
        String materialName = getString(path);
        if (materialName == null || materialName.isEmpty()) {
            return def;
        }
        try {
            return Material.valueOf(materialName.toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid material specified in config at path '" + path + "': " + materialName + ". Using default: " + def.name());
            return def;
        }
    }

    public FileConfiguration getRawConfig() {
        return config;
    }

    /**
     * Son lu en config : ancien nom (ENTITY_PLAYER_LEVELUP) ou clé (minecraft:entity.player.levelup).
     * Sound.valueOf est marqué pour suppression en 26.x.
     *
     * @return le son, ou null s'il est inconnu
     */
    public static Sound sound(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        if (s.indexOf(':') >= 0 || s.indexOf('.') >= 0) {
            NamespacedKey key = NamespacedKey.fromString(s.toLowerCase(Locale.ROOT));
            return key == null ? null : Registry.SOUND_EVENT.get(key);
        }
        try {
            return (Sound) Sound.class.getField(s.toUpperCase(Locale.ROOT)).get(null);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }
}