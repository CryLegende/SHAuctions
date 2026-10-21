package org.Cry_Legende.sHAuctions.gui.types;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.config.ConfigManager;
import org.Cry_Legende.sHAuctions.database.dao.AuctionDAO;
import org.Cry_Legende.sHAuctions.gui.AbstractMenu;
import org.Cry_Legende.sHAuctions.gui.GUIManager;
import org.Cry_Legende.sHAuctions.model.AuctionItem;
import org.Cry_Legende.sHAuctions.model.GuiItemDefinition;
import org.Cry_Legende.sHAuctions.util.ItemUtil;
import org.Cry_Legende.sHAuctions.util.PlaceholderUtil;
import org.Cry_Legende.sHAuctions.util.PriceFormatterUtil;
import org.Cry_Legende.sHAuctions.util.TimeUtil;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Menu des enchères (type BID). Structure identique à l'HDV direct mais alimenté par les
 * annonces de type BID. Cliquer un item ne l'achète pas : cela demande au joueur de saisir
 * un montant d'enchère dans le chat (géré par ChatInputManager/ChatInputListener).
 */
public class BidHouseMenu extends AbstractMenu {

    private final AuctionDAO auctionDAO;
    private final ConfigManager configManager;
    private List<AuctionItem> currentBidPageItems;

    public BidHouseMenu(SHAuctions plugin, GUIManager guiManager, Player viewer) {
        super(plugin, guiManager, "auction_bids", viewer);
        this.auctionDAO = plugin.getDatabaseManager().getAuctionDAO();
        this.configManager = plugin.getConfigManager();
        this.currentBidPageItems = new ArrayList<>();
    }

    public List<AuctionItem> getCurrentBidPageItems() {
        return currentBidPageItems;
    }

    @Override
    public void preparePageData() {
        if (menuDefinition == null) {
            this.totalPages = 1;
            this.currentPage = 1;
            plugin.getLogger().warning("BidHouseMenu: menuDefinition is null during preparePageData for " + viewer.getName());
            return;
        }
        List<Integer> bidSlots = menuDefinition.getAuctionItemSlots();
        int itemsPerPage = (bidSlots != null && !bidSlots.isEmpty()) ? bidSlots.size() : 1;
        if (itemsPerPage == 0) itemsPerPage = 1;

        int totalActiveBids = auctionDAO.countActiveBidAuctions();
        setTotalPages((int) Math.ceil((double) totalActiveBids / itemsPerPage));
        this.currentPage = Math.max(1, Math.min(this.currentPage, this.totalPages));
    }

    @Override
    public void initializeItems() {
        if (menuDefinition == null || inventory == null) {
            plugin.getLogger().warning("Menu definition or inventory is null for BidHouseMenu during initializeItems. Viewer: " + viewer.getName());
            if (viewer.getOpenInventory().getTopInventory().getHolder() == this) {
                viewer.closeInventory();
                viewer.sendMessage(plugin.getMessageManager().translateColors("&cErreur interne du menu des enchères. Veuillez réessayer."));
            }
            return;
        }

        List<Integer> bidSlots = menuDefinition.getAuctionItemSlots();
        if (bidSlots == null || bidSlots.isEmpty()) {
            plugin.getLogger().warning("No 'auction_item_slots' defined in menu: " + menuName + " for BidHouseMenu.");
        } else {
            int itemsPerPage = bidSlots.size();
            this.currentBidPageItems = auctionDAO.getActiveBidAuctions(this.currentPage, itemsPerPage);

            GuiItemDefinition bidItemFormat = menuDefinition.getAuctionItemFormat();
            if (bidItemFormat == null) {
                plugin.getLogger().warning("No 'auction_item_format' defined in menu: " + menuName + ". Cannot display bid items.");
            } else {
                boolean isStaff = viewer.hasPermission("shauctions.cancel.others");
                for (int i = 0; i < currentBidPageItems.size(); i++) {
                    if (i >= bidSlots.size()) break;
                    AuctionItem auctionItem = currentBidPageItems.get(i);
                    int slot = bidSlots.get(i);

                    ItemStack originalItemFromAuction = auctionItem.getItemStack();
                    ItemStack displayItem;
                    if (originalItemFromAuction == null) {
                        displayItem = ItemUtil.createItemStack(Material.BARRIER, "&cErreur de chargement", List.of("&7Cet item n'a pas pu être chargé."));
                    } else {
                        displayItem = originalItemFromAuction.clone();
                    }

                    ItemMeta meta = displayItem.getItemMeta();
                    if (meta == null && displayItem.getType() != Material.AIR) {
                        meta = Bukkit.getItemFactory().getItemMeta(displayItem.getType());
                    }

                    if (meta != null) {
                        Map<String, String> placeholders = getItemSpecificPlaceholders(auctionItem);
                        String nameFormatFromConfig = bidItemFormat.getDisplayName();
                        String originalItemCustomName = ItemUtil.getItemName(originalItemFromAuction);

                        if (originalItemCustomName != null && !originalItemCustomName.isEmpty()) {
                            if (nameFormatFromConfig != null && !nameFormatFromConfig.isEmpty()) {
                                String finalName = nameFormatFromConfig.replace("{ItemName}", originalItemCustomName);
                                meta.setDisplayName(PlaceholderUtil.applyPlaceholders(finalName, placeholders));
                            } else {
                                meta.setDisplayName(originalItemCustomName);
                            }
                        } else {
                            if (nameFormatFromConfig != null && !nameFormatFromConfig.isEmpty() && !nameFormatFromConfig.equals("{ItemName}")) {
                                String finalName = nameFormatFromConfig.replace("{ItemName}", "");
                                meta.setDisplayName(PlaceholderUtil.applyPlaceholders(finalName, placeholders));
                            } else {
                                if (meta.hasDisplayName()) meta.setDisplayName(null);
                            }
                        }

                        List<String> configLore = bidItemFormat.getLore();
                        List<String> finalLore = new ArrayList<>();
                        if (configLore != null && !configLore.isEmpty()) {
                            for (String line : configLore) {
                                // Ligne staff : visible seulement avec la permission d'annulation.
                                if (line.contains("{staff}")) {
                                    if (!isStaff) continue;
                                    line = line.replace("{staff}", "");
                                }
                                if (line.contains("{ItemLore}")) {
                                    List<String> originalLore = ItemUtil.getItemLore(originalItemFromAuction);
                                    if (originalLore != null && !originalLore.isEmpty()) {
                                        finalLore.addAll(PlaceholderUtil.applyPlaceholdersInList(originalLore, placeholders));
                                    }
                                } else {
                                    finalLore.add(PlaceholderUtil.applyPlaceholders(line, placeholders));
                                }
                            }
                        }
                        meta.setLore(finalLore);
                        displayItem.setItemMeta(meta);
                    }
                    inventory.setItem(slot, displayItem);
                }
            }
        }
        populateStaticItems();
    }

    private Map<String, String> getItemSpecificPlaceholders(AuctionItem auctionItem) {
        Map<String, String> placeholders = getGlobalPlaceholders();
        ItemStack actualItem = auctionItem.getItemStack();
        String itemNameForPlaceholder = ItemUtil.getItemName(actualItem);
        placeholders.put("{ItemName}", itemNameForPlaceholder != null ? itemNameForPlaceholder : "");
        placeholders.put("{CurrencySymbol}", configManager.getString("economy.currency_symbol", "$"));
        placeholders.put("{OwnerName}", auctionItem.getSellerName());
        placeholders.put("{SellerUUID}", auctionItem.getSellerUuid().toString());
        placeholders.put("{ListTime}", TimeUtil.formatDate(auctionItem.getListTimestamp()));
        placeholders.put("{ExpireTime}", TimeUtil.formatDate(auctionItem.getExpireTimestamp()));
        placeholders.put("{RemainingTime}", TimeUtil.formatDuration(auctionItem.getExpireTimestamp() - (System.currentTimeMillis() / 1000L)));
        // Placeholders spécifiques aux enchères.
        placeholders.put("{StartingPrice}", PriceFormatterUtil.formatPrice(auctionItem.getStartingPrice()));
        placeholders.put("{Price}", PriceFormatterUtil.formatPrice(auctionItem.getCurrentBidOrStart()));
        placeholders.put("{CurrentBid}", auctionItem.hasBid()
            ? PriceFormatterUtil.formatPrice(auctionItem.getHighestBid())
            : configManager.getString("bids.no_bid_placeholder", "Aucune offre"));
        placeholders.put("{HighestBidder}", auctionItem.hasBid() && auctionItem.getHighestBidderName() != null
            ? auctionItem.getHighestBidderName()
            : configManager.getString("bids.no_bidder_placeholder", "—"));
        double minIncrement = configManager.getDouble("bids.min_bid_increment", 1.0);
        double nextMin = auctionItem.hasBid() ? auctionItem.getHighestBid() + minIncrement : auctionItem.getStartingPrice();
        placeholders.put("{NextMinBid}", PriceFormatterUtil.formatPrice(nextMin));
        return placeholders;
    }

    @Override
    public void handleClick(int slot, ItemStack clickedItem, ClickType clickType) {
        if (menuDefinition == null) return;
        GuiItemDefinition staticItemDef = menuDefinition.getItem(slot);
        if (staticItemDef != null) {
            guiManager.executeItemAction(viewer, staticItemDef, this, clickType);
            return;
        }
        List<Integer> bidSlots = menuDefinition.getAuctionItemSlots();
        if (bidSlots != null && bidSlots.contains(slot)) {
            int itemIndex = bidSlots.indexOf(slot);
            if (itemIndex >= 0 && itemIndex < currentBidPageItems.size()) {
                AuctionItem auctionItem = currentBidPageItems.get(itemIndex);
                handleBidItemClick(auctionItem, clickType);
            }
        }
    }

    private void handleBidItemClick(AuctionItem auctionItem, ClickType clickType) {
        if (clickType.isRightClick()) {
            if (viewer.hasPermission("shauctions.cancel.others") && !auctionItem.getSellerUuid().equals(viewer.getUniqueId())) {
                // Annulation staff : raison saisie dans le chat.
                plugin.getChatInputManager().awaitCancelReason(viewer.getUniqueId(), auctionItem.getId());
                viewer.closeInventory();
                plugin.getMessageManager().sendMessage(viewer, "cancel_reason_prompt");
                return;
            } else if (auctionItem.getSellerUuid().equals(viewer.getUniqueId()) && plugin.getConfigManager().getBoolean("misc.allow_self_cancel", true)) {
                boolean cancelled = plugin.getAuctionManager().cancelAuction(viewer, auctionItem.getId(), false);
                if (cancelled) {
                    this.open();
                }
                return;
            }
        }

        // Clic gauche (ou tout autre clic non-staff) : enchérir. On ne peut pas enchérir sur son propre item.
        if (auctionItem.getSellerUuid().equals(viewer.getUniqueId())) {
            plugin.getMessageManager().sendMessage(viewer, "cannot_bid_own_item");
            return;
        }
        // Placer le joueur en attente de saisie du montant.
        plugin.getChatInputManager().awaitBid(viewer.getUniqueId(), auctionItem.getId());
        viewer.closeInventory();
        double minIncrement = configManager.getDouble("bids.min_bid_increment", 1.0);
        double nextMin = auctionItem.hasBid() ? auctionItem.getHighestBid() + minIncrement : auctionItem.getStartingPrice();
        Map<String, String> ph = new java.util.HashMap<>();
        ph.put("item_name", ItemUtil.getItemName(auctionItem.getItemStack()) != null ? ItemUtil.getItemName(auctionItem.getItemStack()) : ItemUtil.getMaterialNameFallback(auctionItem.getItemStack().getType()));
        ph.put("min_bid", plugin.getEconomyProvider().formatCurrency(nextMin));
        plugin.getMessageManager().sendMessage(viewer, "bid_prompt", ph);
    }
}
