package org.Cry_Legende.sHAuctions.manager;

import net.milkbowl.vault.permission.Permission; // Import pour Vault Permissions
import org.Cry_Legende.sHAuctions.SHAuctions;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.RegisteredServiceProvider;

public class PermissionManager {

    private final SHAuctions plugin;
    private Permission vaultPermission = null;
    private boolean useVaultPermissions;

    public PermissionManager(SHAuctions plugin) {
        this.plugin = plugin;
        // Décider si on utilise Vault pour les permissions numériques
        // Pour l'instant, on le fait manuellement, mais on pourrait l'intégrer.
        // this.useVaultPermissions = setupVaultPermissions();
        this.useVaultPermissions = false; // Mettre à true si vous implémentez setupVaultPermissions() complètement
    }

    // Optionnel: Intégration avec Vault Permissions si vous voulez gérer les groupes etc. plus finement
    // Pour les limites numériques "shauctions.limit.X", une vérification manuelle est souvent suffisante et plus simple.
    /*
    private boolean setupVaultPermissions() {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Permission> rsp = plugin.getServer().getServicesManager().getRegistration(Permission.class);
        if (rsp == null) {
            return false;
        }
        vaultPermission = rsp.getProvider();
        return vaultPermission != null;
    }
    */

    /**
     * Obtient le nombre maximum d'items qu'un joueur peut lister, basé sur ses permissions.
     * Vérifie les permissions de la forme "shauctions.limit.X" et prend la plus élevée.
     *
     * @param player Le joueur.
     * @return Le nombre maximum de listings autorisés.
     */
    public int getPlayerMaxListings(Player player) {
        int maxListings = plugin.getConfigManager().getInt("auction_limits.default_max_listings_per_player", 5);
        return resolveNumericLimit(player, "shauctions.limit.", maxListings);
    }

    /**
     * Résout une limite numérique basée sur les permissions effectives du joueur.
     * Parcourt une seule fois {@link Player#getEffectivePermissions()} au lieu de tester
     * 200 nœuds individuellement (bien plus rapide et sans plafond arbitraire).
     * Prend la valeur la plus élevée parmi "prefix + N". "unlimited"/"*" => illimité.
     *
     * @param player       Le joueur.
     * @param prefix       Le préfixe de permission (ex: "shauctions.limit.").
     * @param defaultValue La valeur par défaut si aucune permission numérique n'est trouvée.
     * @return La limite résolue.
     */
    private int resolveNumericLimit(Player player, String prefix, int defaultValue) {
        int highestLimitFound = -1;
        boolean unlimited = false;

        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (!info.getValue()) continue; // Permission explicitement à false : ignorer.
            String perm = info.getPermission();
            if (perm == null || !perm.startsWith(prefix)) continue;
            String suffix = perm.substring(prefix.length());
            if (suffix.equals("unlimited") || suffix.equals("*")) {
                unlimited = true;
                continue;
            }
            try {
                int value = Integer.parseInt(suffix);
                if (value > highestLimitFound) {
                    highestLimitFound = value;
                }
            } catch (NumberFormatException ignored) {
                // Suffixe non numérique : ignorer.
            }
        }

        if (unlimited) {
            return Integer.MAX_VALUE;
        }
        if (highestLimitFound != -1) {
            return highestLimitFound;
        }
        return defaultValue;
    }

    /**
     * Obtient le nombre maximum d'enchères (type BID) qu'un joueur peut lister simultanément.
     * Vérifie les permissions "shauctions.bidlimit.X" (indépendantes des ventes directes).
     *
     * @param player Le joueur.
     * @return Le nombre maximum d'enchères autorisées.
     */
    public int getPlayerMaxBidListings(Player player) {
        int maxBidListings = plugin.getConfigManager().getInt("bids.default_max_bid_listings_per_player", 1);
        return resolveNumericLimit(player, "shauctions.bidlimit.", maxBidListings);
    }

    /**
     * Vérifie si un joueur a une permission spécifique.
     *
     * @param player         Le joueur.
     * @param permissionNode Le nœud de permission.
     * @return true si le joueur a la permission.
     */
    public boolean hasPermission(Player player, String permissionNode) {
        if (player == null || permissionNode == null || permissionNode.isEmpty()) {
            return false; // Ou true si on considère qu'un joueur null a toutes les permissions (ops)
        }
        return player.hasPermission(permissionNode);
    }
}
