package org.Cry_Legende.sHAuctions.model;

import org.Cry_Legende.sHAuctions.util.SerializationUtil;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public class AuctionItem {

    private final long id; // ID unique de l'enchère dans la base de données
    private final UUID sellerUuid;
    private final String sellerName;
    private ItemStack itemStack; // L'item physique, peut être null si chargé depuis DB et non encore désérialisé
    private final String itemSerializedInitial; // La version sérialisée de la DB, ou null si créé avec ItemStack
    private final double price;
    private final long listTimestamp; // Moment de la mise en vente (Unix timestamp)
    private final long expireTimestamp; // Moment de l'expiration (Unix timestamp)

    // Type de l'annonce : "SALE" (vente directe) ou "BID" (enchère)
    private final String type;
    // Meilleure offre actuelle pour une enchère. -1 = aucune offre encore.
    private double highestBid;
    // Meilleur enchérisseur actuel (peut être null si aucune offre)
    private UUID highestBidderUuid;
    private String highestBidderName;

    public static final String TYPE_SALE = "SALE";
    public static final String TYPE_BID = "BID";

    /**
     * Constructeur pour charger un AuctionItem depuis la base de données.
     * L'ItemStack sera désérialisé à la demande.
     *
     * @param id                   L'ID de l'enchère.
     * @param sellerUuid           L'UUID du vendeur.
     * @param sellerName           Le nom du vendeur.
     * @param itemSerializedFromDb La chaîne sérialisée de l'item depuis la DB.
     * @param price                Le prix de l'item.
     * @param listTimestamp        Timestamp de mise en vente.
     * @param expireTimestamp      Timestamp d'expiration.
     */
    public AuctionItem(long id, UUID sellerUuid, String sellerName, String itemSerializedFromDb, double price, long listTimestamp, long expireTimestamp) {
        this(id, sellerUuid, sellerName, itemSerializedFromDb, price, listTimestamp, expireTimestamp, TYPE_SALE, -1, null, null);
    }

    /**
     * Constructeur complet pour charger un AuctionItem depuis la base de données,
     * incluant les champs d'enchère (type, meilleure offre, meilleur enchérisseur).
     */
    public AuctionItem(long id, UUID sellerUuid, String sellerName, String itemSerializedFromDb, double price, long listTimestamp, long expireTimestamp,
                       String type, double highestBid, UUID highestBidderUuid, String highestBidderName) {
        this.id = id;
        this.sellerUuid = sellerUuid;
        this.sellerName = sellerName;
        this.itemSerializedInitial = itemSerializedFromDb;
        this.itemStack = null; // Désérialisation à la demande via getItemStack()
        this.price = price;
        this.listTimestamp = listTimestamp;
        this.expireTimestamp = expireTimestamp;
        this.type = (type == null) ? TYPE_SALE : type;
        this.highestBid = highestBid;
        this.highestBidderUuid = highestBidderUuid;
        this.highestBidderName = highestBidderName;
    }

    /**
     * Constructeur pour créer un nouvel AuctionItem avant de l'insérer en DB.
     * L'ID est généralement fourni après l'insertion (ou mis à -1/0 avant).
     * La version sérialisée sera générée au besoin.
     *
     * @param id              L'ID de l'enchère (peut être temporaire si non encore en DB).
     * @param sellerUuid      L'UUID du vendeur.
     * @param sellerName      Le nom du vendeur.
     * @param itemStack       L'ItemStack à vendre.
     * @param price           Le prix de l'item.
     * @param listTimestamp   Timestamp de mise en vente.
     * @param expireTimestamp Timestamp d'expiration.
     */
    public AuctionItem(long id, UUID sellerUuid, String sellerName, ItemStack itemStack, double price, long listTimestamp, long expireTimestamp) {
        this(id, sellerUuid, sellerName, itemStack, price, listTimestamp, expireTimestamp, TYPE_SALE, -1, null, null);
    }

    /**
     * Constructeur complet pour créer un nouvel AuctionItem (ItemStack) incluant les champs d'enchère.
     */
    public AuctionItem(long id, UUID sellerUuid, String sellerName, ItemStack itemStack, double price, long listTimestamp, long expireTimestamp,
                       String type, double highestBid, UUID highestBidderUuid, String highestBidderName) {
        this.id = id;
        this.sellerUuid = sellerUuid;
        this.sellerName = sellerName;
        this.itemStack = itemStack;
        this.itemSerializedInitial = null; // Sera généré par getItemSerialized() si besoin
        this.price = price;
        this.listTimestamp = listTimestamp;
        this.expireTimestamp = expireTimestamp;
        this.type = (type == null) ? TYPE_SALE : type;
        this.highestBid = highestBid;
        this.highestBidderUuid = highestBidderUuid;
        this.highestBidderName = highestBidderName;
    }


    public long getId() {
        return id;
    }

    public UUID getSellerUuid() {
        return sellerUuid;
    }

    public String getSellerName() {
        return sellerName;
    }

    /**
     * Récupère l'ItemStack.
     * Si l'objet a été chargé depuis la base de données et que l'ItemStack n'a pas encore été
     * désérialisé, cette méthode s'en chargera.
     *
     * @return L'ItemStack, ou null si la désérialisation échoue ou si aucune donnée n'est disponible.
     */
    public ItemStack getItemStack() {
        if (this.itemStack == null && this.itemSerializedInitial != null) {
            this.itemStack = SerializationUtil.deserializeItemStack(this.itemSerializedInitial);
        }
        return this.itemStack;
    }

    /**
     * Permet de définir explicitement l'ItemStack.
     * Utile principalement si l'item est modifié après sa création (ce qui est rare pour une enchère).
     *
     * @param itemStack Le nouvel ItemStack.
     */
    public void setItemStack(ItemStack itemStack) {
        this.itemStack = itemStack;
        // Si l'itemStack est mis à jour, la représentation sérialisée initiale n'est plus pertinente
        // pour les objets créés à partir d'un item sérialisé.
        // Pour la simplicité, on ne gère pas la resérialisation automatique ici.
        // Le getter getItemSerialized() s'en chargera si itemSerializedInitial était null.
    }

    /**
     * Récupère la représentation sérialisée (Base64) de l'ItemStack.
     * Si l'objet a été créé avec un ItemStack et non chargé depuis la DB,
     * cette méthode sérialisera l'ItemStack à la volée.
     *
     * @return La chaîne sérialisée de l'item, ou null si l'itemStack est null et aucune donnée sérialisée n'existe.
     */
    public String getItemSerialized() {
        if (this.itemSerializedInitial != null) {
            return this.itemSerializedInitial; // Utiliser la version de la DB si disponible
        }
        if (this.itemStack != null) {
            return SerializationUtil.serializeItemStack(this.itemStack); // Sinon, sérialiser l'ItemStack actuel
        }
        return null; // Aucune représentation disponible
    }

    public double getPrice() {
        return price;
    }

    public long getListTimestamp() {
        return listTimestamp;
    }

    public long getExpireTimestamp() {
        return expireTimestamp;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() / 1000L >= expireTimestamp;
    }

    public String getType() {
        return type;
    }

    /**
     * True si cette annonce est une enchère (BID).
     */
    public boolean isBid() {
        return TYPE_BID.equalsIgnoreCase(type);
    }

    /**
     * Le prix de départ d'une enchère (identique au champ price).
     */
    public double getStartingPrice() {
        return price;
    }

    public double getHighestBid() {
        return highestBid;
    }

    public UUID getHighestBidderUuid() {
        return highestBidderUuid;
    }

    public String getHighestBidderName() {
        return highestBidderName;
    }

    /**
     * True si au moins une offre a été placée sur cette enchère.
     */
    public boolean hasBid() {
        return highestBid >= 0 && highestBidderUuid != null;
    }

    /**
     * Renvoie l'offre courante s'il en existe une, sinon le prix de départ.
     * Utile pour l'affichage et le calcul de la mise minimale suivante.
     */
    public double getCurrentBidOrStart() {
        return hasBid() ? highestBid : price;
    }

    /**
     * Met à jour la meilleure offre en mémoire (après une mise à jour DB réussie).
     */
    public void setHighestBid(double highestBid, UUID bidderUuid, String bidderName) {
        this.highestBid = highestBid;
        this.highestBidderUuid = bidderUuid;
        this.highestBidderName = bidderName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AuctionItem that = (AuctionItem) o;
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    @Override
    public String toString() {
        return "AuctionItem{" +
            "id=" + id +
            ", sellerUuid=" + sellerUuid +
            ", sellerName='" + sellerName + '\'' +
            ", price=" + price +
            ", listTimestamp=" + listTimestamp +
            ", expireTimestamp=" + expireTimestamp +
            ", itemPresent=" + (itemStack != null || itemSerializedInitial != null) +
            '}';
    }
}
