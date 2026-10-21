package org.Cry_Legende.sHAuctions.event;

import org.Cry_Legende.sHAuctions.model.AuctionItem;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class AuctionItemPurchasedEvent extends Event {
    private static final HandlerList handlers = new HandlerList();
    private final AuctionItem purchasedItem;
    private final Player buyer;

    public AuctionItemPurchasedEvent(AuctionItem purchasedItem, Player buyer) {
        super(false);
        this.purchasedItem = purchasedItem;
        this.buyer = buyer;
    }

    public AuctionItem getPurchasedItem() {
        return purchasedItem;
    }

    public Player getBuyer() {
        return buyer;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}