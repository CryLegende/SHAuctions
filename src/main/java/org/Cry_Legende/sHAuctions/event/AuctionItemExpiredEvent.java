package org.Cry_Legende.sHAuctions.event;

import org.Cry_Legende.sHAuctions.model.AuctionItem; // L'item tel qu'il était dans la table 'auctions'
import org.Cry_Legende.sHAuctions.model.ExpiredItem; // L'item tel qu'il est dans la table 'expired_items'
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class AuctionItemExpiredEvent extends Event {
    private static final HandlerList handlers = new HandlerList();
    private final AuctionItem originalAuctionItem; // L'état de l'item avant qu'il n'expire de la table 'auctions'
    private final ExpiredItem expiredEntry; // L'entrée dans la table 'expired_items'

    public AuctionItemExpiredEvent(AuctionItem originalAuctionItem, ExpiredItem expiredEntry) {
        // Cet événement est déclenché par une tâche asynchrone (ExpirationTask)
        // Il est préférable que les événements Bukkit soient synchrones par défaut.
        // Si le listener fait des opérations Bukkit API, il devra les scheduler sur le thread principal.
        // Pour l'instant, on le met synchrone, le listener s'en chargera.
        super(false);
        this.originalAuctionItem = originalAuctionItem;
        this.expiredEntry = expiredEntry;
    }

    public AuctionItem getOriginalAuctionItem() {
        return originalAuctionItem;
    }

    public ExpiredItem getExpiredEntry() {
        return expiredEntry;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}