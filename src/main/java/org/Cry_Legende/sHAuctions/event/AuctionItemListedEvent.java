package org.Cry_Legende.sHAuctions.event;

import org.Cry_Legende.sHAuctions.model.AuctionItem;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class AuctionItemListedEvent extends Event {
    private static final HandlerList handlers = new HandlerList();
    private final AuctionItem listedItem;

    public AuctionItemListedEvent(AuctionItem listedItem) {
        super(false); // false pour synchrone par défaut, car déclenché par une action de joueur
        this.listedItem = listedItem;
    }

    public AuctionItem getListedItem() {
        return listedItem;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}