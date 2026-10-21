package org.Cry_Legende.sHAuctions.listener;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.config.MessageManager;
import org.Cry_Legende.sHAuctions.manager.ChatInputManager;

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
            double amount;
            try {
                amount = Double.parseDouble(raw.replace(",", ".").replace(" ", ""));
            } catch (NumberFormatException e) {
                // Saisie invalide : on garde l'état en attente pour permettre une nouvelle tentative.
                messageManager.sendMessage(player, "bid_invalid_amount");
                return;
            }
            if (amount <= 0) {
                messageManager.sendMessage(player, "bid_invalid_amount");
                return;
            }
            // Saisie valide : retirer l'état et traiter sur le thread principal.
            chatInputManager.consume(player.getUniqueId());
            final double finalAmount = amount;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    plugin.getAuctionManager().placeBid(player, auctionId, finalAmount);
                }
            });
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

    private boolean isCancelKeyword(String message) {
        for (String kw : CANCEL_KEYWORDS) {
            if (kw.equalsIgnoreCase(message)) return true;
        }
        return false;
    }
}
