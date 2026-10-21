package org.Cry_Legende.sHAuctions.command.subcommands;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.config.MessageManager;
import org.Cry_Legende.sHAuctions.database.dao.AuctionDAO;
import org.Cry_Legende.sHAuctions.manager.AuctionManager;
import org.Cry_Legende.sHAuctions.model.AuctionItem;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

/**
 * Commande interne déclenchée par les boutons cliquables proposés après la mise en enchère
 * d'un item. Aucun identifiant n'est passé : l'enchère à annoncer est mémorisée côté serveur
 * (map UUID -> id dans le ChatInputManager).
 *
 * <ul>
 *     <li>{@code /hdv announce} : confirme et diffuse l'annonce à tout le serveur.</li>
 *     <li>{@code /hdv announce no} : refuse (annule la proposition).</li>
 * </ul>
 * <p>
 * Masquée de l'aide et de la complétion ({@link #isHidden()}).
 */
public class AnnounceCommand implements SubCommand {

    private final SHAuctions plugin;
    private final MessageManager messageManager;
    private final AuctionManager auctionManager;
    private final AuctionDAO auctionDAO;

    public AnnounceCommand(SHAuctions plugin) {
        this.plugin = plugin;
        this.messageManager = plugin.getMessageManager();
        this.auctionManager = plugin.getAuctionManager();
        this.auctionDAO = plugin.getDatabaseManager().getAuctionDAO();
    }

    @Override
    public String getName() {
        return "announce";
    }

    @Override
    public List<String> getAliases() {
        return List.of("annoncer");
    }

    @Override
    public String getDescription() {
        return "Annonce l'enchère que vous venez de lister.";
    }

    @Override
    public String getUsage() {
        return "";
    }

    @Override
    public String getPermission() {
        return "shauctions.bid";
    }

    @Override
    public boolean isPlayerOnly() {
        return true;
    }

    @Override
    public boolean isHidden() {
        return true;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;

        long auctionId = plugin.getChatInputManager().consumeAnnounce(player.getUniqueId());

        // Refus explicite.
        if (args.length >= 1 && args[0].equalsIgnoreCase("no")) {
            messageManager.sendMessage(player, "announce_declined");
            return;
        }

        // Aucune proposition en attente (ou périmée).
        if (auctionId == -1) {
            messageManager.sendMessage(player, "announce_expired");
            return;
        }

        AuctionItem auctionItem = auctionDAO.getAuctionById(auctionId);
        if (auctionItem == null || !auctionItem.isBid() || auctionItem.isExpired()) {
            messageManager.sendMessage(player, "announce_expired");
            return;
        }

        auctionManager.broadcastBidAnnouncement(player, auctionItem);
        messageManager.sendMessage(player, "announce_done");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        return Collections.emptyList();
    }
}
