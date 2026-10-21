package org.Cry_Legende.sHAuctions.command.subcommands;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.config.MessageManager;
import org.Cry_Legende.sHAuctions.gui.GUIManager;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public class HistoryCommand implements SubCommand {

    private final SHAuctions plugin;
    private final GUIManager guiManager;
    private final MessageManager messageManager;

    public HistoryCommand(SHAuctions plugin) {
        this.plugin = plugin;
        this.guiManager = plugin.getGuiManager();
        this.messageManager = plugin.getMessageManager();
    }

    @Override
    public String getName() {
        return "history";
    }

    @Override
    public List<String> getAliases() {
        return List.of("hist");
    }

    @Override
    public String getDescription() {
        return "Affiche votre historique de ventes ou l'historique global.";
    }

    @Override
    public String getUsage() {
        return "[global]"; // Optionnel
    }

    @Override
    public String getPermission() {
        // La permission de base pour voir son propre historique.
        // La permission pour l'historique global sera vérifiée séparément.
        return "shauctions.history.self";
    }

    @Override
    public boolean isPlayerOnly() {
        return true; // L'historique est un GUI
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;

        boolean viewGlobal = false;
        if (args.length > 0 && args[0].equalsIgnoreCase("global")) {
            if (!player.hasPermission("shauctions.history.global")) {
                messageManager.sendMessage(player, "no_permission");
                return;
            }
            viewGlobal = true;
        }

        // Si juste /hdv history (sans "global"), et qu'il a la perm shauctions.history.self
        // (déjà vérifiée par AuctionCommand), on ouvre son historique.
        // S'il a la perm shauctions.history.global ET tape "global", on ouvre le global.

        if (viewGlobal) {
            guiManager.openSalesHistoryMenu(player, 1, true, null); // true pour global, null pour targetPlayer (non applicable ici)
        } else {
            // Vérifier à nouveau la permission pour son propre historique, même si c'est la perm de la sous-commande.
            // C'est une bonne pratique au cas où la logique de permission de AuctionCommand changerait.
            if (!player.hasPermission(getPermission())) {
                messageManager.sendMessage(player, "no_permission");
                return;
            }
            guiManager.openSalesHistoryMenu(player, 1, false, player.getUniqueId()); // false pour non-global, cibler le joueur lui-même
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            if (sender.hasPermission("shauctions.history.global") && "global".startsWith(args[0].toLowerCase())) {
                return Collections.singletonList("global");
            }
        }
        return Collections.emptyList();
    }
}