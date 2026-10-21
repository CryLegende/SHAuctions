package org.Cry_Legende.sHAuctions.database.dao;

import org.Cry_Legende.sHAuctions.model.SoldItem;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

public interface SoldItemDAO {

    /**
     * Ajoute un item à l'historique des ventes.
     *
     * @param originalAuctionId ID de l'enchère originale (peut être null).
     * @param sellerUuid        UUID du vendeur.
     * @param sellerName        Nom du vendeur.
     * @param buyerUuid         UUID de l'acheteur.
     * @param buyerName         Nom de l'acheteur.
     * @param itemName          Nom simple de l'item vendu.
     * @param itemStack         L'ItemStack vendu (pour la sérialisation).
     * @param price             Le prix de vente.
     * @param type              "SALE" (vente directe) ou "BID" (enchère remportée).
     * @return true si l'ajout a réussi, false sinon.
     */
    boolean addSoldItem(Long originalAuctionId, UUID sellerUuid, String sellerName,
                        UUID buyerUuid, String buyerName, String itemName, ItemStack itemStack, double price, String type);

    /**
     * Récupère l'historique des ventes d'un joueur (en tant que vendeur).
     *
     * @param sellerUuid   L'UUID du vendeur.
     * @param page         Le numéro de la page.
     * @param itemsPerPage Le nombre d'items par page.
     * @return Une liste de SoldItems.
     */
    List<SoldItem> getSalesBySeller(UUID sellerUuid, int page, int itemsPerPage);

    /**
     * Compte le nombre total de ventes pour un vendeur.
     *
     * @param sellerUuid L'UUID du vendeur.
     * @return Le nombre de ventes.
     */
    int countSalesBySeller(UUID sellerUuid);

    /**
     * Récupère l'historique des achats d'un joueur.
     *
     * @param buyerUuid    L'UUID de l'acheteur.
     * @param page         Le numéro de la page.
     * @param itemsPerPage Le nombre d'items par page.
     * @return Une liste de SoldItems.
     */
    List<SoldItem> getPurchasesByBuyer(UUID buyerUuid, int page, int itemsPerPage);

    /**
     * Compte le nombre total d'achats pour un acheteur.
     *
     * @param buyerUuid L'UUID de l'acheteur.
     * @return Le nombre d'achats.
     */
    int countPurchasesByBuyer(UUID buyerUuid);

    /**
     * Récupère l'historique global des ventes (pour les admins).
     *
     * @param page         Le numéro de la page.
     * @param itemsPerPage Le nombre d'items par page.
     * @return Une liste de SoldItems.
     */
    List<SoldItem> getGlobalSalesHistory(int page, int itemsPerPage);

    /**
     * Compte le nombre total d'items dans l'historique global des ventes.
     *
     * @return Le nombre total de ventes enregistrées.
     */
    int countGlobalSalesHistory();

    /**
     * Supprime les entrées d'historique plus anciennes qu'un certain timestamp.
     * (Pour le nettoyage automatique de la base de données)
     *
     * @param olderThanTimestamp Timestamp Unix. Les entrées plus anciennes seront supprimées.
     * @return Le nombre d'entrées supprimées.
     */
    int clearOldSoldItems(long olderThanTimestamp);
}
