package org.Cry_Legende.sHAuctions.config;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageManager {

    private final SHAuctions plugin;
    private FileConfiguration messagesConfig;
    private File messagesFile;
    private String prefix;

    // Pour les placeholders spécifiques aux messages (non ceux des items de GUI)
    private final Pattern placeholderPattern = Pattern.compile("%([^%]+)%");


    public MessageManager(SHAuctions plugin) {
        this.plugin = plugin;
        this.messagesFile = new File(plugin.getDataFolder(), "messages.yml");
    }

    public void loadMessages() {
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
            plugin.getLogger().info("messages.yml not found, created a default one.");
        }
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);

        InputStream defaultMessagesStream = plugin.getResource("messages.yml");
        if (defaultMessagesStream != null) {
            YamlConfiguration defaultMessages = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultMessagesStream, StandardCharsets.UTF_8));
            messagesConfig.setDefaults(defaultMessages);
            messagesConfig.options().copyDefaults(true);
            try {
                messagesConfig.save(messagesFile);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not save messages.yml after adding defaults", e);
            }
        }
        this.prefix = translateColors(messagesConfig.getString("plugin_prefix", "&8[&6SHAuctions&8] &r"));
        plugin.getLogger().info("messages.yml loaded successfully.");
    }

    public void reloadMessages() {
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
        InputStream defaultMessagesStream = plugin.getResource("messages.yml");
        if (defaultMessagesStream != null) {
            YamlConfiguration defaultMessages = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultMessagesStream, StandardCharsets.UTF_8));
            messagesConfig.setDefaults(defaultMessages);
        }
        this.prefix = translateColors(messagesConfig.getString("plugin_prefix", "&8[&6SHAuctions&8] &r"));
        plugin.getLogger().info("messages.yml reloaded.");
    }

    public String getMessage(String key) {
        return getMessage(key, true, new HashMap<>());
    }

    public String getMessage(String key, boolean withPrefix) {
        return getMessage(key, withPrefix, new HashMap<>());
    }

    public String getMessage(String key, Map<String, String> placeholders) {
        return getMessage(key, true, placeholders);
    }

    public String getMessage(String key, boolean withPrefix, Map<String, String> placeholders) {
        String message = messagesConfig.getString(key);
        if (message == null || message.isEmpty()) {
            plugin.getLogger().warning("Missing message for key: " + key + " in messages.yml");
            return translateColors("&cMissing message: " + key);
        }

        // Remplacer les placeholders %placeholder%
        Matcher matcher = placeholderPattern.matcher(message);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String placeholderKey = matcher.group(1);
            String replacement = placeholders.getOrDefault(placeholderKey, "%" + placeholderKey + "%"); // Garde le placeholder s'il n'est pas trouvé
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        message = sb.toString();

        if (withPrefix) {
            return translateColors(prefix + message);
        }
        return translateColors(message);
    }

    public void sendMessage(CommandSender sender, String key) {
        sender.sendMessage(getMessage(key));
    }

    public void sendMessage(CommandSender sender, String key, Map<String, String> placeholders) {
        sender.sendMessage(getMessage(key, true, placeholders));
    }

    public void sendMessageNoPrefix(CommandSender sender, String key) {
        sender.sendMessage(getMessage(key, false, new HashMap<>()));
    }

    public void sendMessageNoPrefix(CommandSender sender, String key, Map<String, String> placeholders) {
        sender.sendMessage(getMessage(key, false, placeholders));
    }

    public void sendPlayerTitle(Player player, String titleKey, String subtitleKey, int fadeIn, int stay, int fadeOut) {
        sendPlayerTitle(player, titleKey, subtitleKey, fadeIn, stay, fadeOut, new HashMap<>(), new HashMap<>());
    }

    public void sendPlayerTitle(Player player, String titleKey, String subtitleKey, int fadeIn, int stay, int fadeOut, Map<String, String> titlePlaceholders, Map<String, String> subtitlePlaceholders) {
        String title = messagesConfig.getString("titles." + titleKey, "");
        String subtitle = messagesConfig.getString("titles." + subtitleKey, "");

        // Remplacer les placeholders pour le titre
        Matcher titleMatcher = placeholderPattern.matcher(title);
        StringBuffer titleSb = new StringBuffer();
        while (titleMatcher.find()) {
            String placeholderKey = titleMatcher.group(1);
            String replacement = titlePlaceholders.getOrDefault(placeholderKey, "%" + placeholderKey + "%");
            titleMatcher.appendReplacement(titleSb, Matcher.quoteReplacement(replacement));
        }
        titleMatcher.appendTail(titleSb);
        title = translateColors(titleSb.toString());

        // Remplacer les placeholders pour le sous-titre
        Matcher subtitleMatcher = placeholderPattern.matcher(subtitle);
        StringBuffer subtitleSb = new StringBuffer();
        while (subtitleMatcher.find()) {
            String placeholderKey = subtitleMatcher.group(1);
            String replacement = subtitlePlaceholders.getOrDefault(placeholderKey, "%" + placeholderKey + "%");
            subtitleMatcher.appendReplacement(subtitleSb, Matcher.quoteReplacement(replacement));
        }
        subtitleMatcher.appendTail(subtitleSb);
        subtitle = translateColors(subtitleSb.toString());

        player.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
    }


    public static String translateColors(String message) {
        if (message == null) return "";
        // Support des codes couleurs standards &
        message = ChatColor.translateAlternateColorCodes('&', message);

        // Support des codes couleurs hexadécimaux #RRGGBB (depuis 1.16+)
        Pattern hexPattern = Pattern.compile("&#([A-Fa-f0-9]{6})");
        Matcher matcher = hexPattern.matcher(message);
        while (matcher.find()) {
            try {
                message = message.replace(matcher.group(), ChatColor.valueOf(matcher.group(1)).toString());
            } catch (Exception e) {
                // Ignorer si le code hex n'est pas valide, ou si sur une version < 1.16
            }
        }
        // Support des codes couleurs hexadécimaux &x&R&R&G&G&B&B (format commun)
        Pattern legacyHexPattern = Pattern.compile("&x(&[A-Fa-f0-9]){6}");
        matcher = legacyHexPattern.matcher(message);
        while (matcher.find()) {
            try {
                String hexString = matcher.group().replace("&x", "").replace("&", "");
                if (hexString.length() == 6) {
                    message = message.replace(matcher.group(), net.md_5.bungee.api.ChatColor.of("#" + hexString).toString());
                }
            } catch (NoClassDefFoundError | Exception e) {
                // Ignorer si md_5.bungee.api.ChatColor n'est pas disponible (versions très anciennes ou environnement de test sans)
                // ou si le code hex n'est pas valide.
            }
        }
        return message;
    }

    public String getPrefix() {
        return prefix;
    }
}