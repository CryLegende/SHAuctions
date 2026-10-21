package org.Cry_Legende.sHAuctions.model;

import org.Cry_Legende.sHAuctions.util.SerializationUtil;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public class SoldItem {

    private final long id; // ID unique de l'entrée d'historique
    private final Long originalAuctionId; // Peut être null
    private final UUID sellerUuid;
    private final String sellerName;
    private final UUID buyerUuid;
    private final String buyerName;
    private final String itemNameDisplay; // Nom simplifié de l'item pour affichage rapide
    private ItemStack itemStack; // L'item physique, désérialisé à la demande
    private final String itemSerializedInitial; // L'item sérialisé depuis la DB
    private final double price;
    private final long soldTimestamp; // Moment de la vente
    private final String type; // "SALE" (vente directe) ou "BID" (enchère remportée)

    public SoldItem(long id, Long originalAuctionId, UUID sellerUuid, String sellerName,
                    UUID buyerUuid, String buyerName, String itemNameDisplay, String itemSerializedFromDb,
                    double price, long soldTimestamp, String type) {
        this.id = id;
        this.originalAuctionId = originalAuctionId;
        this.sellerUuid = sellerUuid;
        this.sellerName = sellerName;
        this.buyerUuid = buyerUuid;
        this.buyerName = buyerName;
        this.itemNameDisplay = itemNameDisplay;
        this.itemSerializedInitial = itemSerializedFromDb;
        this.itemStack = null; // Désérialiser à la demande
        this.price = price;
        this.soldTimestamp = soldTimestamp;
        this.type = (type != null) ? type : "SALE";
    }

    // Pas de constructeur avec ItemStack ici, car un SoldItem est toujours créé APRÈS la vente,
    // donc on aura l'ItemStack original de la vente à sérialiser directement dans le DAO.

    public long getId() {
        return id;
    }

    public Long getOriginalAuctionId() {
        return originalAuctionId;
    }

    public UUID getSellerUuid() {
        return sellerUuid;
    }

    public String getSellerName() {
        return sellerName;
    }

    public UUID getBuyerUuid() {
        return buyerUuid;
    }

    public String getBuyerName() {
        return buyerName;
    }

    public String getItemNameDisplay() {
        return itemNameDisplay;
    }

    public ItemStack getItemStack() {
        if (this.itemStack == null && this.itemSerializedInitial != null) {
            this.itemStack = SerializationUtil.deserializeItemStack(this.itemSerializedInitial);
        }
        return this.itemStack;
    }

    public void setItemStack(ItemStack itemStack) {
        this.itemStack = itemStack;
    }

    public String getItemSerialized() {
        // Un SoldItem est typiquement créé avec une chaîne sérialisée, donc on la retourne.
        // Si jamais il était créé d'une autre manière (improbable pour ce modèle),
        // il faudrait une logique pour sérialiser itemStack.
        return itemSerializedInitial;
    }

    public double getPrice() {
        return price;
    }

    public long getSoldTimestamp() {
        return soldTimestamp;
    }

    public String getType() {
        return type;
    }

    public boolean isBid() {
        return "BID".equalsIgnoreCase(type);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SoldItem soldItem = (SoldItem) o;
        return id == soldItem.id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    @Override
    public String toString() {
        return "SoldItem{" +
            "id=" + id +
            ", sellerName='" + sellerName + '\'' +
            ", buyerName='" + buyerName + '\'' +
            ", itemNameDisplay='" + itemNameDisplay + '\'' +
            ", price=" + price +
            ", soldTimestamp=" + soldTimestamp +
            '}';
    }
}
