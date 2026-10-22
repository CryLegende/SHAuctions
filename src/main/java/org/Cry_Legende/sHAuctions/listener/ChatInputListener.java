package org.Cry_Legende.sHAuctions.listener;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.config.MessageManager;
import org.Cry_Legende.sHAuctions.manager.ChatInputManager;
import org.Cry_Legende.sHAuctions.model.AuctionItem;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/**
 * Intercepte les messages de chat des joueurs en attente d'une saisie (enchère ou raison d'annulation).
 *
 * <p>Le parsing du message se fait sur le thread de chat asynchrone, mais toute mutation
 * (économie, base de données) est planifiée sur le thread principal via le scheduler Bukkit,
 * ce qui sérialise les opérations et garantit la sûreté en cas de saisies simultanées.</p>
 */
public class ChatInputListener implements Listener {

    private final SHAuctions plugin;
    private final ChatInputManager chatInputManager;
    private final MessageManager messageManager;

    // Mots-clés permettant d'annuler la saisie en cours.
    private static final String[] CANCEL_KEYWORDS = {"annuler", "cancel", "annulé", "annule"};

    public ChatInputListener(SHAuctions plugin) {
        this.plugin = plugin;
        this.chatInputManager = plugin.getChatInputManager();
        this.messageManager = plugin.getMessageManager();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        final Player player = event.getPlayer();
        ChatInputManager.PendingInput pending = chatInputManager.peek(player.getUniqueId());
        if (pending == null) {
            return; // Le joueur n'attend aucune saisie : chat normal.
        }

        // Empêcher la diffusion du message dans le chat public.
        event.setCancelled(true);

        final String raw = event.getMessage().trim();

        // Annulation par mot-clé.
        if (isCancelKeyword(raw)) {
            chatInputManager.cancel(player.getUniqueId());
            messageManager.sendMessage(player, "chat_input_cancelled");
            return;
        }

        final ChatInputManager.InputType type = pending.getType();
        final long auctionId = pending.getAuctionId();

        if (type == ChatInputManager.InputType.BID) {
            final Double amount = parseAmount(raw);
            if (amount == null) {
                // Saisie invalide (texte, NaN, infini, nul ou négatif) : on garde l'état en attente pour une nouvelle tentative.
                messageManager.sendMessage(player, "bid_invalid_amount");
                return;
            }
            // L'attente reste active pendant le traitement : elle n'est retirée qu'une fois la mise acceptée
            // (ou devenue impossible). Une mise refusée (trop basse, solde...) redemande, au lieu de laisser
            // le montant retapé partir dans le chat public.
            Bukkit.getScheduler().runTask(plugin, () -> processBid(player, auctionId, amount));
        } else if (type == ChatInputManager.InputType.CANCEL_REASON) {
            if (raw.isEmpty()) {
                messageManager.sendMessage(player, "cancel_reason_empty");
                return;
            }
            chatInputManager.consume(player.getUniqueId());
            final String reason = raw;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    plugin.getAuctionManager().cancelAuction(player, auctionId, true, reason);
                }
            });
        }
    }

    /**
     * Traite une mise saisie au chat (thread principal). Mise acceptée ou devenue impossible : l'attente est retirée.
     * Mise refusée alors que l'enchère reste ouverte au joueur : l'attente repart pour un délai complet et le joueur
     * est invité à retaper un montant.
     */
    private void processBid(Player player, long auctionId, double amount) {
        final ChatInputManager.InputType type = ChatInputManager.InputType.BID;
        boolean retry = false;
        try {
            if (player.isOnline()) {
                boolean accepted = plugin.getAuctionManager().placeBid(player, auctionId, amount);
                retry = !accepted && canStillBid(player, auctionId);
                if (retry) {
                    messageManager.sendMessage(player, "bid_retry_prompt");
                }
            }
        } finally {
            if (retry) {
                chatInputManager.refresh(player.getUniqueId(), type, auctionId);
            } else {
                chatInputManager.finish(player.getUniqueId(), type, auctionId);
            }
        }
    }

    /** L'enchère est-elle toujours ouverte à une mise de ce joueur ? (sinon inutile de continuer à attendre) */
    private boolean canStillBid(Player player, long auctionId) {
        AuctionItem auction = plugin.getDatabaseManager().getAuctionDAO().getAuctionById(auctionId);
        return auction != null && auction.isBid() && !auction.isExpired()
                && !auction.getSellerUuid().equals(player.getUniqueId())
                && !(auction.hasBid() && player.getUniqueId().equals(auction.getHighestBidderUuid()));
    }

    /** Montant saisi : virgule décimale et espaces tolérés, fini et strictement positif (sinon null). */
    static Double parseAmount(String raw) {
        try {
            double value = Double.parseDouble(raw.replace(",", ".").replace(" ", ""));
            if (Double.isNaN(value) || Double.isInfinite(value) || value <= 0) {
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean isCancelKeyword(String message) {
        for (String kw : CANCEL_KEYWORDS) {
            if (kw.equalsIgnoreCase(message)) return true;
        }
        return false;
    }
}
