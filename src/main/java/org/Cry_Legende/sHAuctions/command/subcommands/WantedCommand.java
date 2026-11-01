package org.Cry_Legende.sHAuctions.command.subcommands;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

/**
 * {@code /hdv recherche} : ouvre l'onglet des recherches ; {@code /hdv recherche mes} : vos recherches en cours ;
 * {@code /hdv recherche nouvelle} : dialogue de publication ;
 * {@code /hdv recherche <texte>} : publie directement (clients sans dialogue).
 */
public class WantedCommand implements SubCommand {

    private final SHAuctions plugin;

    public WantedCommand(SHAuctions plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "recherche";
    }

    @Override
    public List<String> getAliases() {
        return List.of("recherches", "wanted");
    }

    @Override
    public String getDescription() {
        return "Recherches des joueurs : voir, publier ce que vous cherchez.";
    }

    @Override
    public String getUsage() {
        return "[mes|nouvelle|<texte>]";
    }

    @Override
    public String getPermission() {
        return "shauctions.recherches.use";
    }

    @Override
    public boolean isPlayerOnly() {
        return true;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        if (args.length == 0) {
            plugin.getGuiManager().openWantedMenu(player, 1);
        } else if (args.length == 1 && args[0].equalsIgnoreCase("mes")) {
            plugin.getGuiManager().openOwnWantedMenu(player, 1);
        } else if (args.length == 1 && args[0].equalsIgnoreCase("nouvelle")) {
            plugin.getWantedManager().openCreateDialog(player);
        } else if (plugin.getWantedManager().create(player, String.join(" ", args))) {
            plugin.getGuiManager().openWantedMenu(player, 1);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length != 1) {
            return Collections.emptyList();
        }
        return List.of("mes", "nouvelle").stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
    }
}
