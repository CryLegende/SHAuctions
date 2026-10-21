package org.Cry_Legende.sHAuctions.event;

import org.Cry_Legende.sHAuctions.model.AuctionItem;
import org.bukkit.command.CommandSender; // Peut être un joueur ou la console (si admin cancel)
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class AuctionItemCancelledEvent extends Event {
    private static final HandlerList handlers = new HandlerList();
    private final AuctionItem cancelledItem;
    private final CommandSender canceller; // Qui a annulé

    public AuctionItemCancelledEvent(AuctionItem cancelledItem, CommandSender canceller) {
        super(false);
        this.cancelledItem = cancelledItem;
        this.canceller = canceller;
    }

    public AuctionItem getCancelledItem() {
        return cancelledItem;
    }

    public CommandSender getCanceller() {
        return canceller;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}