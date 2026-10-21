package org.Cry_Legende.sHAuctions.model;

import org.Cry_Legende.sHAuctions.util.SerializationUtil;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public class ExpiredItem {

    private final long id; // ID unique de l'entrée d'item expiré
    private final long originalAuctionId; // ID de l'enchère originale
    private final UUID ownerUuid;
    private final String ownerName;
    private ItemStack itemStack; // L'item physique, désérialisé à la demande
    private final String itemSerializedInitial; // L'item sérialisé depuis la DB
    private final long expirationTimestamp; // Moment où l'item a expiré
    private boolean claimed; // Si l'item a été réclamé
    // Raison d'annulation par le staff. null = expiration normale (pas d'annulation staff).
    private final String cancelReason;

    public ExpiredItem(long id, long originalAuctionId, UUID ownerUuid, String ownerName,
                       String itemSerializedFromDb, long expirationTimestamp, boolean claimed) {
        this(id, originalAuctionId, ownerUuid, ownerName, itemSerializedFromDb, expirationTimestamp, claimed, null);
    }

    public ExpiredItem(long id, long originalAuctionId, UUID ownerUuid, String ownerName,
                       String itemSerializedFromDb, long expirationTimestamp, boolean claimed, String cancelReason) {
        this.id = id;
        this.originalAuctionId = originalAuctionId;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.itemSerializedInitial = itemSerializedFromDb;
        this.itemStack = null; // Désérialiser à la demande
        this.expirationTimestamp = expirationTimestamp;
        this.claimed = claimed;
        this.cancelReason = cancelReason;
    }

    // Pas de constructeur avec ItemStack ici, car un ExpiredItem est toujours créé APRÈS l'expiration
    // d'un AuctionItem, donc on aura l'ItemStack original à sérialiser directement dans le DAO.

    public long getId() {
        return id;
    }

    public long getOriginalAuctionId() {
        return originalAuctionId;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public String getOwnerName() {
        return ownerName;
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
        // Un ExpiredItem est typiquement créé avec une chaîne sérialisée.
        return itemSerializedInitial;
    }

    public long getExpirationTimestamp() {
        return expirationTimestamp;
    }

    public boolean isClaimed() {
        return claimed;
    }

    /**
     * Raison d'annulation staff, ou null si l'item a simplement expiré.
     */
    public String getCancelReason() {
        return cancelReason;
    }

    /**
     * True si cet item a été annulé par le staff (et non simplement expiré).
     */
    public boolean isStaffCancelled() {
        return cancelReason != null && !cancelReason.isEmpty();
    }

    public void setClaimed(boolean claimed) {
        this.claimed = claimed;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ExpiredItem that = (ExpiredItem) o;
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    @Override
    public String toString() {
        return "ExpiredItem{" +
            "id=" + id +
            ", ownerName='" + ownerName + '\'' +
            ", expirationTimestamp=" + expirationTimestamp +
            ", claimed=" + claimed +
            ", itemPresent=" + (itemStack != null || itemSerializedInitial != null) +
            '}';
    }
}
