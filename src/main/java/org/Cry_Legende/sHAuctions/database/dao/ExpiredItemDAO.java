package org.Cry_Legende.sHAuctions.database.dao;

import org.Cry_Legende.sHAuctions.model.ExpiredItem;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

public interface ExpiredItemDAO {

    /**
     * Ajoute un item expiré à la base de données.
     *
     * @param originalAuctionId   L'ID de l'enchère originale qui a expiré.
     * @param ownerUuid           L'UUID du propriétaire de l'item.
     * @param ownerName           Le nom du propriétaire.
     * @param itemStack           L'ItemStack expiré.
     * @param expirationTimestamp Le moment où l'item a expiré.
     * @return true si l'ajout a réussi, false sinon.
     */
    boolean addExpiredItem(long originalAuctionId, UUID ownerUuid, String ownerName, ItemStack itemStack, long expirationTimestamp);

    /**
     * Ajoute un item expiré/annulé à la base de données, avec une raison optionnelle d'annulation staff.
     *
     * @param cancelReason Raison d'annulation par le staff, ou null pour une expiration normale.
     */
    boolean addExpiredItem(long originalAuctionId, UUID ownerUuid, String ownerName, ItemStack itemStack, long expirationTimestamp, String cancelReason);

    /**
     * Récupère tous les items expirés non réclamés d'un joueur.
     *
     * @param ownerUuid L'UUID du propriétaire.
     * @return Une liste d'ExpiredItems.
     */
    List<ExpiredItem> getUnclaimedExpiredItemsByOwner(UUID ownerUuid);

    /**
     * Récupère un item expiré spécifique par son ID.
     *
     * @param expiredItemId L'ID de l'item expiré.
     * @return L'ExpiredItem ou null s'il n'est pas trouvé ou déjà réclamé.
     */
    ExpiredItem getUnclaimedExpiredItemById(long expiredItemId);

    /**
     * Marque un item expiré comme réclamé.
     *
     * @param id L'ID de l'item expiré (dans la table expired_items).
     * @return true si la mise à jour a réussi, false sinon.
     */
    boolean markAsClaimed(long id);

    /**
     * Supprime les items expirés qui ont été réclamés ou qui sont plus anciens qu'un certain timestamp.
     * (Pour le nettoyage automatique de la base de données)
     *
     * @param olderThanTimestamp Timestamp Unix. Les items (réclamés ou non) plus anciens seront supprimés.
     *                           Si 0, supprime uniquement les items réclamés.
     * @param removeOnlyClaimed  Si true, supprime uniquement les items marqués comme "claimed", ignorant le timestamp.
     *                           Si false, utilise le `olderThanTimestamp` pour supprimer les items (réclamés ou non).
     * @return Le nombre d'entrées supprimées.
     */
    int clearExpiredItems(long olderThanTimestamp, boolean removeOnlyClaimed);

    /**
     * Compte le nombre d'items expirés non réclamés pour un joueur.
     *
     * @param ownerUuid L'UUID du propriétaire.
     * @return Le nombre d'items expirés non réclamés.
     */
    int countUnclaimedExpiredItemsByOwner(UUID ownerUuid);
}
