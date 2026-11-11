package org.Cry_Legende.sHAuctions.config;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.InvalidConfigurationException;
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
    // Remplacé d'un bloc par le reload : les menus et les tâches gardent la référence du ConfigManager, pas de la config
    private volatile FileConfiguration config;
    private final File configFile;

    public ConfigManager(SHAuctions plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "config.yml");
        this.config = new YamlConfiguration();
    }

    /**
     * Lecture au démarrage. Un fichier illisible n'est jamais réécrit : le plugin tourne sur les valeurs du jar le
     * temps que le fichier soit corrigé.
     */
    public void load() {
        try {
            YamlConfiguration loaded = read();
            loaded.options().copyDefaults(true);
            try {
                loaded.save(configFile);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not save config.yml after adding defaults", e);
            }
            config = loaded;
            plugin.getLogger().info("config.yml loaded successfully.");
        } catch (ConfigLoadException e) {
            plugin.getLogger().severe("config.yml illisible (" + e.getMessage() + ") : valeurs du jar utilisées, fichier non modifié.");
            YamlConfiguration fallback = new YamlConfiguration();
            attachDefaults(fallback);
            config = fallback;
        }
    }

    /** Lit config.yml dans un objet à part, avec les valeurs du jar pour toute clé absente. Ne modifie rien. */
    public YamlConfiguration read() throws ConfigLoadException {
        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
            plugin.getLogger().info("config.yml not found, created a default one.");
        }
        YamlConfiguration loaded = new YamlConfiguration();
        try {
            loaded.load(configFile);
        } catch (IOException | InvalidConfigurationException e) {
            throw new ConfigLoadException("config.yml", e);
        }
        attachDefaults(loaded);
        return loaded;
    }

    /** Remplace la configuration en mémoire par celle que {@link #read()} a validée. */
    public void apply(YamlConfiguration loaded) {
        this.config = loaded;
        plugin.getLogger().info("config.yml reloaded.");
    }

    private void attachDefaults(YamlConfiguration target) {
        try (InputStream in = plugin.getResource("config.yml")) {
            if (in != null) {
                target.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "config.yml de référence illisible", e);
        }
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