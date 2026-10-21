package org.Cry_Legende.sHAuctions.command.subcommands;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.config.ConfigManager;
import org.Cry_Legende.sHAuctions.config.MenuConfigManager;
import org.Cry_Legende.sHAuctions.config.MessageManager;

import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.List;

public class ReloadCommand implements SubCommand {

    private final SHAuctions plugin;
    private final ConfigManager configManager;
    private final MenuConfigManager menuConfigManager;
    private final MessageManager messageManager;

    public ReloadCommand(SHAuctions plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.menuConfigManager = plugin.getMenuConfigManager();
        this.messageManager = plugin.getMessageManager();
    }

    @Override
    public String getName() {
        return "reload";
    }

    @Override
    public List<String> getAliases() {
        return List.of("rl");
    }

    @Override
    public String getDescription() {
        return "Recharge la configuration du plugin.";
    }

    @Override
    public String getUsage() {
        return ""; // Pas d'arguments supplémentaires
    }

    @Override
    public String getPermission() {
        return "shauctions.reload";
    }

    @Override
    public boolean isPlayerOnly() {
        return false; // La console peut aussi recharger
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        try {
            long startTime = System.currentTimeMillis();

            configManager.reload();
            messageManager.reloadMessages(); // Doit être rechargé après configManager si le préfixe y est aussi.
            // Dans notre cas, le préfixe est dans messages.yml donc l'ordre est moins critique ici.
            menuConfigManager.reloadMenus();

            // Si d'autres managers ont besoin d'être informés d'un rechargement de config,
            // ajoutez leur logique de rechargement ici.
            // Par exemple, AuctionManager pourrait avoir une méthode reloadConfig()
            // pour mettre à jour les limites, etc.
            // plugin.getAuctionManager().reloadConfig(); // Exemple

            long endTime = System.currentTimeMillis();
            messageManager.sendMessage(sender, "reload_success",
                Collections.singletonMap("duration", String.valueOf(endTime - startTime)));
            plugin.getLogger().info("Configuration reloaded by " + sender.getName() + " in " + (endTime - startTime) + "ms.");

        } catch (Exception e) {
            messageManager.sendMessage(sender, "reload_failure");
            plugin.getLogger().severe("An error occurred while reloading the plugin configuration:");
            e.printStackTrace();
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        return Collections.emptyList(); // Pas de complétion d'arguments pour "reload"
    }
}
