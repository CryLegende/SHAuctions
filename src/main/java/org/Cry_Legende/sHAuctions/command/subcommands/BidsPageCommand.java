package org.Cry_Legende.sHAuctions.command.subcommands;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * /hdv encheres : ouvre directement la page Enchères (clic sur l'annonce d'une enchère du serveur).
 */
public class BidsPageCommand implements SubCommand {

    private final SHAuctions plugin;

    public BidsPageCommand(SHAuctions plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "encheres";
    }

    @Override
    public List<String> getAliases() {
        return List.of("enchères", "bids");
    }

    @Override
    public String getDescription() {
        return "Ouvre la page des enchères.";
    }

    @Override
    public String getUsage() {
        return "";
    }

    @Override
    public String getPermission() {
        return "shauctions.menu.bids";
    }

    @Override
    public boolean isPlayerOnly() {
        return true;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        if (!player.hasPermission("shauctions.use")) {
            plugin.getMessageManager().sendMessage(player, "no_permission");
            return;
        }
        plugin.getGuiManager().openBidHouseMenu(player, 1);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
