package org.Cry_Legende.sHAuctions.listener;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.event.*;
import org.Cry_Legende.sHAuctions.gui.AbstractMenu;
import org.Cry_Legende.sHAuctions.gui.GUIManager;
import org.Cry_Legende.sHAuctions.gui.types.AuctionHouseMenu;
import org.Cry_Legende.sHAuctions.gui.types.BidHouseMenu;
import org.Cry_Legende.sHAuctions.gui.types.OwnSalesMenu;
import org.Cry_Legende.sHAuctions.gui.types.ExpiredItemsMenu;
import org.Cry_Legende.sHAuctions.gui.types.SalesHistoryMenu;
import org.Cry_Legende.sHAuctions.model.AuctionItem;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MenuRefreshListener implements Listener {
    private final SHAuctions plugin;
    private final GUIManager guiManager;

    public MenuRefreshListener(SHAuctions plugin) {
        this.plugin = plugin;
        this.guiManager = plugin.getGuiManager(); // Assurez-vous que GUIManager est initialisé avant ce listener
    }

    /**
     * Planifie le rafraîchissement d'un menu sur le thread principal de Bukkit.
     *
     * @param menuToRefresh Le menu à rafraîchir.
     */
    private void scheduleMenuRefresh(AbstractMenu menuToRefresh) {
        Player viewer = menuToRefresh.getViewer();
        // Vérifier si le joueur est toujours en ligne et si le menu qu'il a ouvert
        // est bien celui que l'on s'attend à rafraîchir.
        if (viewer != null && viewer.isOnline() && guiManager.getOpenMenu(viewer) == menuToRefresh) {
            plugin.getLogger().fine("Scheduling refresh for menu " + menuToRefresh.getMenuName() +
                " (page " + menuToRefresh.getCurrentPage() + ") for player " + viewer.getName());

            Bukkit.getScheduler().runTask(plugin, () -> {
                // Re-vérifier les conditions au moment de l'exécution de la tâche
                if (viewer.isOnline() && guiManager.getOpenMenu(viewer) == menuToRefresh) {
                    plugin.getLogger().fine("Executing refresh for menu " + menuToRefresh.getMenuName() +
                        " for player " + viewer.getName());
                    menuToRefresh.refresh(); // Utilise la nouvelle méthode refresh() qui ne joue pas de son
                } else {
                    plugin.getLogger().fine("Refresh cancelled for " + viewer.getName() +
                        ": player no longer online or menu changed.");
                }
            });
        } else if (viewer != null) {
            plugin.getLogger().fine("Refresh not scheduled for " + viewer.getName() +
                ": player not online or current open menu is not the target for refresh.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAuctionItemListed(AuctionItemListedEvent event) {
        plugin.getLogger().fine("AuctionItemListedEvent detected. Checking AuctionHouseMenu instances for refresh.");
        for (AbstractMenu openMenu : new ArrayList<>(guiManager.getOpenMenus().values())) {
            if (openMenu instanceof AuctionHouseMenu) {
                AuctionHouseMenu ahMenu = (AuctionHouseMenu) openMenu;
                boolean sortIsDesc = plugin.getConfigManager().getString("display_options.auction_house_sort_order", "DESC").equalsIgnoreCase("DESC");
                int oldTotalPages = ahMenu.getTotalPages();
                int oldCurrentPage = ahMenu.getCurrentPage();

                // Forcer le recalcul des données de page pour obtenir les nouvelles totalPages
                // La méthode refresh() appellera preparePageData() de toute façon.
                // Mais pour la condition, il faut le faire avant.
                ahMenu.preparePageData();
                int newTotalPages = ahMenu.getTotalPages(); // totalPages est mis à jour dans preparePageData

                // Si la page 1 est affectée par un nouvel item (tri DESC) ou si les pages changent
                if ((oldCurrentPage == 1 && sortIsDesc) || oldTotalPages != newTotalPages || oldCurrentPage > newTotalPages) {
                    scheduleMenuRefresh(ahMenu);
                }
                // Remettre la page à son état d'avant preparePageData si on ne rafraichit pas,
                // car preparePageData peut l'avoir ajustée.
                else if (ahMenu.getCurrentPage() != oldCurrentPage) {
                    ahMenu.setCurrentPage(oldCurrentPage);
                }
            } else if (openMenu instanceof BidHouseMenu) {
                BidHouseMenu bidMenu = (BidHouseMenu) openMenu;
                boolean sortIsDesc = plugin.getConfigManager().getString("display_options.auction_house_sort_order", "DESC").equalsIgnoreCase("DESC");
                int oldTotalPages = bidMenu.getTotalPages();
                int oldCurrentPage = bidMenu.getCurrentPage();

                bidMenu.preparePageData();
                int newTotalPages = bidMenu.getTotalPages();

                if ((oldCurrentPage == 1 && sortIsDesc) || oldTotalPages != newTotalPages || oldCurrentPage > newTotalPages) {
                    scheduleMenuRefresh(bidMenu);
                } else if (bidMenu.getCurrentPage() != oldCurrentPage) {
                    bidMenu.setCurrentPage(oldCurrentPage);
                }
            }
        }
    }

    private void handleItemRemovalOrUpdateEvent(AuctionItem affectedItem, Player buyerForPurchaseEvent) {
        if (affectedItem == null) return;
        final long affectedItemId = affectedItem.getId();
        final UUID sellerUuid = affectedItem.getSellerUuid();
        final UUID buyerUuid = (buyerForPurchaseEvent != null) ? buyerForPurchaseEvent.getUniqueId() : null;

        for (AbstractMenu openMenu : new ArrayList<>(guiManager.getOpenMenus().values())) {
            // Cas 1: AuctionHouseMenu
            if (openMenu instanceof AuctionHouseMenu) {
                AuctionHouseMenu ahMenu = (AuctionHouseMenu) openMenu;
                boolean wasOnPage = false;
                if (ahMenu.getCurrentAuctionPageItems() != null) { // Vérifier nullité
                    wasOnPage = ahMenu.getCurrentAuctionPageItems().stream()
                        .anyMatch(item -> item.getId() == affectedItemId);
                }

                int oldTotalPages = ahMenu.getTotalPages();
                int oldCurrentPage = ahMenu.getCurrentPage();
                ahMenu.preparePageData(); // Recalcule totalPages et ajuste currentPage
                int newTotalPages = ahMenu.getTotalPages();

                if (wasOnPage || oldTotalPages != newTotalPages || oldCurrentPage > newTotalPages) {
                    scheduleMenuRefresh(ahMenu);
                } else if (ahMenu.getCurrentPage() != oldCurrentPage) {
                    ahMenu.setCurrentPage(oldCurrentPage);
                }
            }
            // Cas 1bis: BidHouseMenu (enchère remportée/annulée/expirée retirée de la liste)
            else if (openMenu instanceof BidHouseMenu) {
                BidHouseMenu bidMenu = (BidHouseMenu) openMenu;
                boolean wasOnPage = false;
                if (bidMenu.getCurrentBidPageItems() != null) {
                    wasOnPage = bidMenu.getCurrentBidPageItems().stream()
                        .anyMatch(item -> item.getId() == affectedItemId);
                }

                int oldTotalPages = bidMenu.getTotalPages();
                int oldCurrentPage = bidMenu.getCurrentPage();
                bidMenu.preparePageData();
                int newTotalPages = bidMenu.getTotalPages();

                if (wasOnPage || oldTotalPages != newTotalPages || oldCurrentPage > newTotalPages) {
                    scheduleMenuRefresh(bidMenu);
                } else if (bidMenu.getCurrentPage() != oldCurrentPage) {
                    bidMenu.setCurrentPage(oldCurrentPage);
                }
            }
            // Cas 2: OwnSalesMenu du vendeur
            else if (openMenu instanceof OwnSalesMenu && openMenu.getViewer().getUniqueId().equals(sellerUuid)) {
                scheduleMenuRefresh(openMenu);
            }
            // Cas 3: ExpiredItemsMenu du vendeur (pertinent pour Cancelled et Expired)
            else if (openMenu instanceof ExpiredItemsMenu && openMenu.getViewer().getUniqueId().equals(sellerUuid) && buyerForPurchaseEvent == null) { // buyerForPurchaseEvent null indique Cancelled ou Expired
                scheduleMenuRefresh(openMenu);
            }
            // Cas 4: SalesHistoryMenu
            else if (openMenu instanceof SalesHistoryMenu) {
                SalesHistoryMenu shMenu = (SalesHistoryMenu) openMenu;
                boolean refreshHistory = false;
                if (shMenu.isGlobalHistory()) {
                    refreshHistory = true;
                } else if (shMenu.getTargetPlayerUuid() != null) {
                    if (shMenu.getTargetPlayerUuid().equals(sellerUuid)) refreshHistory = true;
                    if (buyerUuid != null && shMenu.getTargetPlayerUuid().equals(buyerUuid)) refreshHistory = true;
                }
                if (refreshHistory) {
                    scheduleMenuRefresh(shMenu);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAuctionItemPurchased(AuctionItemPurchasedEvent event) {
        plugin.getLogger().fine("AuctionItemPurchasedEvent detected. Refreshing relevant menus.");
        handleItemRemovalOrUpdateEvent(event.getPurchasedItem(), event.getBuyer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAuctionItemCancelled(AuctionItemCancelledEvent event) {
        plugin.getLogger().fine("AuctionItemCancelledEvent detected. Refreshing relevant menus.");
        handleItemRemovalOrUpdateEvent(event.getCancelledItem(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAuctionItemExpired(AuctionItemExpiredEvent event) {
        plugin.getLogger().fine("AuctionItemExpiredEvent detected. Refreshing relevant menus.");
        handleItemRemovalOrUpdateEvent(event.getOriginalAuctionItem(), null);
    }
}
