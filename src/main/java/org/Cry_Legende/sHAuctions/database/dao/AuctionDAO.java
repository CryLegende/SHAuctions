package org.Cry_Legende.sHAuctions.database.dao;

import org.Cry_Legende.sHAuctions.model.AuctionItem;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

public interface AuctionDAO {

    /**
     * Ajoute un nouvel item à l'hôtel des ventes.
     *
     * @param sellerUuid      UUID du vendeur.
     * @param sellerName      Nom du vendeur.
     * @param item            L'ItemStack à vendre.
     * @param price           Le prix de l'item.
     * @param durationSeconds La durée de la mise en vente en secondes.
     * @return L'ID de l'enchère créée, ou -1 en cas d'échec.
     */
    long addAuction(UUID sellerUuid, String sellerName, ItemStack item, double price, long durationSeconds);

    /**
     * Ajoute une nouvelle enchère (type BID) à l'hôtel des ventes.
     *
     * @param sellerUuid      UUID du vendeur.
     * @param sellerName      Nom du vendeur.
     * @param item            L'ItemStack mis aux enchères.
     * @param startingPrice   Le prix de départ de l'enchère.
     * @param durationSeconds La durée de l'enchère en secondes.
     * @return L'ID de l'enchère créée, ou -1 en cas d'échec.
     */
    long addBidAuction(UUID sellerUuid, String sellerName, ItemStack item, double startingPrice, long durationSeconds);

    /**
     * Récupère les enchères (BID) actives pour une page donnée (pagination).
     */
    List<AuctionItem> getActiveBidAuctions(int page, int itemsPerPage);

    /**
     * Compte le nombre total d'enchères (BID) actives.
     */
    int countActiveBidAuctions();

    /**
     * Met à jour la meilleure offre d'une enchère.
     *
     * @param id         ID de l'enchère.
     * @param bid        Montant de la nouvelle meilleure offre.
     * @param bidderUuid UUID du nouvel enchérisseur.
     * @param bidderName Nom du nouvel enchérisseur.
     * @return true si la mise à jour a réussi.
     */
    boolean updateHighestBid(long id, double bid, UUID bidderUuid, String bidderName);

    /**
     * Prolonge l'échéance d'une enchère (protection anti-snipe : une offre de dernière seconde
     * repousse la fin). Ne raccourcit jamais l'échéance et ne s'applique qu'aux enchères (BID).
     *
     * @param id                 ID de l'enchère.
     * @param newExpireTimestamp Nouvelle échéance (timestamp UNIX en secondes).
     * @return true si l'échéance a été prolongée.
     */
    boolean setExpireTimestamp(long id, long newExpireTimestamp);

    /**
     * Récupère une enchère par son ID.
     *
     * @param id L'ID de l'enchère.
     * @return L'AuctionItem correspondant, ou null s'il n'est pas trouvé.
     */
    AuctionItem getAuctionById(long id);

    /**
     * Récupère toutes les enchères actives.
     *
     * @return Une liste de tous les AuctionItems actifs.
     */
    List<AuctionItem> getAllActiveAuctions();

    /**
     * Récupère les enchères actives pour une page donnée (pagination).
     *
     * @param page         Le numéro de la page (commence à 0 ou 1 selon votre logique).
     * @param itemsPerPage Le nombre d'items par page.
     * @return Une liste d'AuctionItems pour la page spécifiée.
     */
    List<AuctionItem> getActiveAuctions(int page, int itemsPerPage);

    /**
     * Compte le nombre total d'enchères actives.
     *
     * @return Le nombre total d'enchères actives.
     */
    int countActiveAuctions();

    /**
     * Récupère toutes les enchères actives d'un joueur spécifique.
     *
     * @param sellerUuid L'UUID du vendeur.
     * @return Une liste des AuctionItems du vendeur.
     */
    List<AuctionItem> getAuctionsBySeller(UUID sellerUuid);

    /**
     * Compte le nombre d'enchères actives pour un vendeur spécifique.
     *
     * @param sellerUuid L'UUID du vendeur.
     * @return Le nombre d'items que le joueur a en vente.
     */
    int countAuctionsBySeller(UUID sellerUuid);

    /**
     * Compte les ventes directes actives (type SALE) d'un vendeur.
     *
     * @param sellerUuid L'UUID du vendeur.
     * @return Le nombre de ventes directes actives.
     */
    int countSaleAuctionsBySeller(UUID sellerUuid);

    /**
     * Compte les enchères actives (type BID) d'un vendeur.
     *
     * @param sellerUuid L'UUID du vendeur.
     * @return Le nombre d'enchères actives.
     */
    int countBidAuctionsBySeller(UUID sellerUuid);


    /**
     * Supprime une enchère de la base de données (ex: après un achat ou une annulation).
     *
     * @param id L'ID de l'enchère à supprimer.
     * @return true si la suppression a réussi, false sinon.
     */
    boolean removeAuction(long id);

    /**
     * Récupère toutes les enchères qui ont expiré (expire_timestamp <= now).
     *
     * @return Une liste des AuctionItems expirés.
     */
    List<AuctionItem> getExpiredAuctions();

    // Pas besoin de méthode updateAuction pour l'instant, car les enchères sont généralement
    // soit créées, soit supprimées (achetées, annulées, expirées).
    // Si on voulait permettre de modifier le prix ou la durée, on ajouterait une méthode update.
}
