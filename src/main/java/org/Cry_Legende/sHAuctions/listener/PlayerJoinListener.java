package org.Cry_Legende.sHAuctions.listener;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.config.MessageManager;
import org.Cry_Legende.sHAuctions.database.dao.ExpiredItemDAO;
import org.bukkit.Bukkit; // Import pour Bukkit.getScheduler()
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Collections;
// import java.util.Map; // Pas utilisé ici
import java.util.logging.Level;

public class PlayerJoinListener implements Listener {

    private final SHAuctions plugin;

    public PlayerJoinListener(SHAuctions plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (!plugin.getConfigManager().getBoolean("expired_items.notify_on_join", true)) {
            return;
        }

        if (plugin.getDatabaseManager() == null || plugin.getMessageManager() == null) {
            plugin.getLogger().log(Level.WARNING, "PlayerJoinListener: DatabaseManager or MessageManager not initialized. Cannot notify for expired items.");
            return;
        }

        final ExpiredItemDAO finalExpiredItemDAO = plugin.getDatabaseManager().getExpiredItemDAO();
        final MessageManager finalMessageManager = plugin.getMessageManager();

        if (finalExpiredItemDAO == null) {
            plugin.getLogger().log(Level.WARNING, "PlayerJoinListener: ExpiredItemDAO is null. Cannot check for expired items.");
            return;
        }

        // Délai en ticks (ex: 3 secondes = 3 * 20 = 60 ticks)
        long delayTicks = plugin.getConfigManager().getLong("expired_items.notification_delay_ticks_on_join", 60L);
        if (delayTicks < 0) delayTicks = 0; // Pas de délai négatif

        plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> { // Toujours asynchrone pour la DB
            try {
                int unclaimedCount = finalExpiredItemDAO.countUnclaimedExpiredItemsByOwner(player.getUniqueId());

                if (unclaimedCount > 0) {
                    // Envoyer le message au joueur sur le thread principal
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) { // Re-vérifier si le joueur est toujours en ligne
                            finalMessageManager.sendMessage(player, "expired_notification_on_join",
                                Collections.singletonMap("count", String.valueOf(unclaimedCount)));
                        }
                    });
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error checking for expired items for player " + player.getName() + " on join:", e);
            }
        }, delayTicks); // Appliquer le délai ici
    }
}
