package org.Cry_Legende.sHAuctions.task;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.manager.AuctionManager;

import org.bukkit.scheduler.BukkitRunnable;

public class ExpirationTask extends BukkitRunnable {

    private final SHAuctions plugin;
    private final AuctionManager auctionManager;

    public ExpirationTask(SHAuctions plugin) {
        this.plugin = plugin;
        // Il est préférable d'obtenir l'AuctionManager après son initialisation complète.
        // Si cette tâche est créée dans onEnable avant que auctionManager ne soit initialisé dans SHAuctions,
        // cela pourrait causer un NullPointerException.
        // Assurez-vous que l'ordre d'initialisation est correct.
        // Option 1: Passer directement l'instance
        // this.auctionManager = plugin.getAuctionManager();
        // Option 2 (plus sûre si la tâche est créée tôt): Obtenir à l'exécution
        this.auctionManager = null; // Sera initialisé au premier run() si nécessaire
    }

    private AuctionManager getAuctionManagerInstance() {
        // Cette méthode permet de s'assurer que l'auctionManager est bien disponible
        // au moment de l'exécution, même si la tâche a été initialisée très tôt.
        if (this.auctionManager != null) {
            return this.auctionManager;
        }
        // Tenter de récupérer l'instance maintenant si elle n'a pas été passée
        // ou si on veut être sûr d'avoir la dernière référence (peu probable ici)
        AuctionManager am = plugin.getAuctionManager();
        if (am == null) {
            plugin.getLogger().severe("ExpirationTask cannot run: AuctionManager is not available!");
            this.cancel(); // Annuler la tâche si le manager n'est pas là
            return null;
        }
        return am;
    }


    @Override
    public void run() {
        AuctionManager am = getAuctionManagerInstance();
        if (am == null) {
            // Le message d'erreur et l'annulation sont déjà gérés dans getAuctionManagerInstance()
            return;
        }

        plugin.getLogger().fine("ExpirationTask running: Checking for expired auctions..."); // "fine" pour moins de spam console

        try {
            am.processExpiredAuctions();
            // Maintenant, nous pouvons aussi appeler la logique de nettoyage pour les items expirés
            // qui sont dans la table expired_items depuis trop longtemps.
            processExpiredItemsCleanup(am);

        } catch (Exception e) {
            plugin.getLogger().severe("An error occurred during the ExpirationTask execution: " + e.getMessage());
            e.printStackTrace(); // Important pour le débogage
        }
        plugin.getLogger().fine("ExpirationTask finished.");
    }

    private void processExpiredItemsCleanup(AuctionManager am) {
        // Cette logique est séparée car elle concerne la table expired_items, pas active_auctions
        long cleanupDelaySeconds = plugin.getConfigManager().getLong("expired_items.cleanup_after_seconds", 2592000L); // 30 jours par défaut

        if (cleanupDelaySeconds == -1) {
            plugin.getLogger().fine("Expired items cleanup is disabled (cleanup_after_seconds: -1).");
            return; // Conservation illimitée
        }

        if (cleanupDelaySeconds == 0) {
            // Signifie suppression immédiate des items expirés DÈS QU'ILS SONT RÉCLAMÉS.
            // Donc, on ne supprime que les réclamés.
            int cleanedCount = plugin.getDatabaseManager().getExpiredItemDAO().clearExpiredItems(0, true);
            if (cleanedCount > 0) {
                plugin.getLogger().info("Cleaned up " + cleanedCount + " claimed expired item(s).");
                if (plugin.getConfigManager().getBoolean("misc.log_debug", false)) { // Option de debug
                    plugin.getLogger().info("[Debug] Expired items cleanup (claimed only) due to cleanup_after_seconds: 0");
                }
            }
            return;
        }

        // Si cleanupDelaySeconds > 0, c'est un délai avant suppression des items (réclamés ou non).
        // Le timestamp pour la suppression sera "maintenant - délai".
        long olderThanTimestamp = (System.currentTimeMillis() / 1000L) - cleanupDelaySeconds;

        // Supprimer les items réclamés SANS condition de date (ils peuvent être supprimés dès qu'ils sont réclamés si on le souhaite)
        // Ou, on peut les laisser jusqu'à ce que le `olderThanTimestamp` les attrape aussi.
        // Pour l'instant, la méthode clearExpiredItems avec removeOnlyClaimed=false
        // va supprimer les réclamés ET les non-réclamés plus vieux que olderThanTimestamp.
        // Si on veut supprimer les réclamés immédiatement, on ferait un appel séparé :
        // plugin.getDatabaseManager().getExpiredItemDAO().clearExpiredItems(0, true);

        int cleanedCount = plugin.getDatabaseManager().getExpiredItemDAO().clearExpiredItems(olderThanTimestamp, false);
        if (cleanedCount > 0) {
            plugin.getLogger().info("Cleaned up " + cleanedCount + " old/claimed expired item(s) from the database.");
            if (plugin.getConfigManager().getBoolean("misc.log_debug", false)) {
                plugin.getLogger().info("[Debug] Expired items cleanup (older than " + cleanupDelaySeconds + "s or claimed) ran.");
            }
        }
    }
}
