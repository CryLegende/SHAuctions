package org.Cry_Legende.sHAuctions.command.subcommands;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.config.MessageManager;
import org.Cry_Legende.sHAuctions.gui.GUIManager;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public class ExpiredCommand implements SubCommand {

    private final SHAuctions plugin;
    private final GUIManager guiManager;
    private final MessageManager messageManager;


    public ExpiredCommand(SHAuctions plugin) {
        this.plugin = plugin;
        this.guiManager = plugin.getGuiManager();
        this.messageManager = plugin.getMessageManager();
    }

    @Override
    public String getName() {
        return "expired";
    }

    @Override
    public List<String> getAliases() {
        return List.of("invendus", "claim");
    }

    @Override
    public String getDescription() {
        return "Affiche vos items expirés à récupérer.";
    }

    @Override
    public String getUsage() {
        return ""; // Pas d'arguments
    }

    @Override
    public String getPermission() {
        return "shauctions.expired.claim";
    }

    @Override
    public boolean isPlayerOnly() {
        return true; // C'est un GUI
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        guiManager.openExpiredItemsMenu(player, 1);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        return Collections.emptyList();
    }
}