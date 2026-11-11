package org.Cry_Legende.sHAuctions.database.impl;

import org.Cry_Legende.sHAuctions.database.SharedDatabase;
import org.Cry_Legende.sHAuctions.database.dao.SoldItemDAO;
import org.Cry_Legende.sHAuctions.model.SoldItem;
import org.Cry_Legende.sHAuctions.util.SerializationUtil;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BinaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Historique des ventes en MariaDB (mode réseau). Mêmes requêtes que la version SQLite. */
public class MariaSoldItemDAO implements SoldItemDAO {

    private static final String T = SharedDatabase.SOLD;
    private static final String COLUMNS = "id, original_auction_id, seller_uuid, seller_name, buyer_uuid, buyer_name, item_name, "
        + "item_serialized, price, sold_timestamp, type";

    private final SharedDatabase db;
    private final Logger logger;
    private final BinaryOperator<String> option;

    /**
     * @param logger journal du plugin
     * @param option valeur d'une option de config.yml (chemin, valeur par défaut)
     */
    public MariaSoldItemDAO(SharedDatabase db, Logger logger, BinaryOperator<String> option) {
        this.db = db;
        this.logger = logger;
        this.option = option;
    }

    private String order() {
        String sort = String.valueOf(option.apply("display_options.sales_history_sort_order", "DESC")).toUpperCase();
        return " ORDER BY sold_timestamp " + ("ASC".equals(sort) ? "ASC" : "DESC");
    }

    @Override
    public boolean addSoldItem(Long originalAuctionId, UUID sellerUuid, String sellerName, UUID buyerUuid, String buyerName, String itemName,
                               ItemStack itemStack, double price, String type, boolean sellerNotified) {
        String serialized = SerializationUtil.serializeItemStack(itemStack);
        if (serialized == null) {
            logger.severe("Failed to serialize item for sold_items: " + itemName);
            return false;
        }
        String sql = "INSERT INTO " + T + " (original_auction_id, seller_uuid, seller_name, buyer_uuid, buyer_name, item_name, item_serialized, "
            + "price, sold_timestamp, type, seller_notified) VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (originalAuctionId != null) {
                ps.setLong(1, originalAuctionId);
            } else {
                ps.setNull(1, Types.BIGINT);
            }
            ps.setString(2, sellerUuid.toString());
            ps.setString(3, sellerName);
            ps.setString(4, buyerUuid.toString());
            ps.setString(5, buyerName);
            ps.setString(6, itemName);
            ps.setString(7, serialized);
            ps.setDouble(8, price);
            ps.setLong(9, System.currentTimeMillis() / 1000L);
            ps.setString(10, type != null ? type : "SALE");
            ps.setInt(11, sellerNotified ? 1 : 0);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Could not add sold item to database", e);
            return false;
        }
    }

    @Override
    public OfflineSales getOfflineSales(UUID sellerUuid) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id, item_name, price FROM " + T + " WHERE seller_uuid = ? AND seller_notified = 0 ORDER BY id")) {
            ps.setString(1, sellerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                int count = 0;
                double total = 0;
                String lastItem = null;
                long lastId = 0;
                while (rs.next()) {
                    count++;
                    total += rs.getDouble("price");
                    lastItem = rs.getString("item_name");
                    lastId = rs.getLong("id");
                }
                return count == 0 ? null : new OfflineSales(count, total, lastItem, lastId);
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Could not read offline sales of " + sellerUuid, e);
            return null;
        }
    }

    @Override
    public boolean markOfflineSalesNotified(UUID sellerUuid, long lastId) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE " + T + " SET seller_notified = 1 WHERE seller_uuid = ? AND seller_notified = 0 AND id <= ?")) {
            ps.setString(1, sellerUuid.toString());
            ps.setLong(2, lastId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Could not mark offline sales of " + sellerUuid, e);
            return false;
        }
    }

    @Override
    public List<SoldItem> getSalesBySeller(UUID sellerUuid, int page, int itemsPerPage) {
        return page("SELECT " + COLUMNS + " FROM " + T + " WHERE seller_uuid = ?" + order(), page, itemsPerPage, sellerUuid.toString());
    }

    @Override
    public int countSalesBySeller(UUID sellerUuid) {
        return count("SELECT COUNT(*) FROM " + T + " WHERE seller_uuid = ?", sellerUuid.toString());
    }

    @Override
    public List<SoldItem> getPurchasesByBuyer(UUID buyerUuid, int page, int itemsPerPage) {
        return page("SELECT " + COLUMNS + " FROM " + T + " WHERE buyer_uuid = ?" + order(), page, itemsPerPage, buyerUuid.toString());
    }

    @Override
    public int countPurchasesByBuyer(UUID buyerUuid) {
        return count("SELECT COUNT(*) FROM " + T + " WHERE buyer_uuid = ?", buyerUuid.toString());
    }

    @Override
    public int countPurchasesFrom(UUID sellerUuid, UUID buyerUuid, long since) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + T + " WHERE seller_uuid = ? AND buyer_uuid = ? AND sold_timestamp >= ?")) {
            ps.setString(1, sellerUuid.toString());
            ps.setString(2, buyerUuid.toString());
            ps.setLong(3, since);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error counting purchases of " + buyerUuid + " from " + sellerUuid, e);
            return 0;
        }
    }

    @Override
    public List<SoldItem> getGlobalSalesHistory(int page, int itemsPerPage) {
        return page("SELECT " + COLUMNS + " FROM " + T + order(), page, itemsPerPage, null);
    }

    @Override
    public int countGlobalSalesHistory() {
        return count("SELECT COUNT(*) FROM " + T, null);
    }

    @Override
    public int clearOldSoldItems(long olderThanTimestamp) {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement("DELETE FROM " + T + " WHERE sold_timestamp < ?")) {
            ps.setLong(1, olderThanTimestamp);
            return ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error clearing old sold items", e);
            return 0;
        }
    }

    private List<SoldItem> page(String sql, int page, int itemsPerPage, String param) {
        if (page < 1) page = 1;
        List<SoldItem> out = new ArrayList<>();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql + " LIMIT ? OFFSET ?")) {
            int i = 1;
            if (param != null) {
                ps.setString(i++, param);
            }
            ps.setInt(i++, itemsPerPage);
            ps.setInt(i, (page - 1) * itemsPerPage);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long original = rs.getLong("original_auction_id");
                    Long originalId = rs.wasNull() ? null : original;
                    out.add(new SoldItem(rs.getLong("id"), originalId, UUID.fromString(rs.getString("seller_uuid")), rs.getString("seller_name"),
                        UUID.fromString(rs.getString("buyer_uuid")), rs.getString("buyer_name"), rs.getString("item_name"),
                        rs.getString("item_serialized"), rs.getDouble("price"), rs.getLong("sold_timestamp"), rs.getString("type")));
                }
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error fetching sales history", e);
        }
        return out;
    }

    private int count(String sql, String param) {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (param != null) {
                ps.setString(1, param);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error counting sales history", e);
            return 0;
        }
    }
}
