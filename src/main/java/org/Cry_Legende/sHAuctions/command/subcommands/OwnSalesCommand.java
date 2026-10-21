package org.Cry_Legende.sHAuctions.command.subcommands;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.gui.GUIManager;
import org.Cry_Legende.sHAuctions.config.MessageManager; // Si besoin de messages spécifiques

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public class OwnSalesCommand implements SubCommand {

    private final SHAuctions plugin;
    private final GUIManager guiManager;
    // private final MessageManager messageManager; // Décommenter si utilisé

    public OwnSalesCommand(SHAuctions plugin) {
        this.plugin = plugin;
        this.guiManager = plugin.getGuiManager();
        // this.messageManager = plugin.getMessageManager(); // Décommenter si utilisé
    }

    @Override
    public String getName() {
        return "ownsales";
    }

    @Override
    public List<String> getAliases() {
        return List.of("mysales", "ventes");
    }

    @Override
    public String getDescription() {
        return "Affiche vos items actuellement en vente.";
    }

    @Override
    public String getUsage() {
        return ""; // Pas d'arguments
    }

    @Override
    public String getPermission() {
        // La permission de base pour voir ses propres ventes en cours.
        // Le menu lui-même peut avoir une permission de configuration `shauctions.menu.ownsales`
        // qui sera vérifiée par le `MenuConfigManager` ou `GUIManager` lors de l'ouverture.
        // Ici, c'est la permission pour la commande elle-même.
        return "shauctions.ownsales.view";
    }

    @Override
    public boolean isPlayerOnly() {
        return true; // C'est un GUI
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        // Le GUIManager se chargera d'ouvrir le menu correspondant, par exemple "own_sales.yml"
        guiManager.openOwnSalesMenu(player, 1);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        return Collections.emptyList();
    }
}