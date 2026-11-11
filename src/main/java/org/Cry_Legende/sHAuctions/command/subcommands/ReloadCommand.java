package org.Cry_Legende.sHAuctions.command.subcommands;

import org.Cry_Legende.sHAuctions.SHAuctions;

import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.List;

/**
 * Ancienne forme de {@code /shauctions reload}, acceptée sur {@code /hdv} sans être proposée (ni dans l'usage ni dans
 * la complétion).
 */
public class ReloadCommand implements SubCommand {

    private final SHAuctions plugin;

    public ReloadCommand(SHAuctions plugin) {
        this.plugin = plugin;
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
        return "shauctions.admin.reload";
    }

    @Override
    public boolean isPlayerOnly() {
        return false; // La console peut aussi recharger
    }

    @Override
    public boolean isHidden() {
        return true;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        plugin.reloadAll(sender);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        return Collections.emptyList();
    }
}
