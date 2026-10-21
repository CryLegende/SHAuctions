package org.Cry_Legende.sHAuctions.database.impl;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.database.DatabaseManager;
import org.Cry_Legende.sHAuctions.database.dao.SoldItemDAO;
import org.Cry_Legende.sHAuctions.model.SoldItem;
import org.Cry_Legende.sHAuctions.util.SerializationUtil;

import org.bukkit.inventory.ItemStack;

import java.sql.*; // Statement, PreparedStatement, ResultSet, Connection, SQLException, Types
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public class SQLiteSoldItemDAO implements SoldItemDAO {

    private final DatabaseManager dbManager;
    private final SHAuctions plugin;

    public SQLiteSoldItemDAO(DatabaseManager dbManager) {
        this.dbManager = dbManager;
        this.plugin = SHAuctions.getInstance();
    }

    @Override
    public boolean addSoldItem(Long originalAuctionId, UUID sellerUuid, String sellerName,
                               UUID buyerUuid, String buyerName, String itemNameDisplay,
                               ItemStack itemStack, double price, String type) {
        String sql = "INSERT INTO sold_items(original_auction_id, seller_uuid, seller_name, buyer_uuid, buyer_name, item_name, item_serialized, price, sold_timestamp, type) VALUES(?,?,?,?,?,?,?,?,?,?)";
        String serializedItem = SerializationUtil.serializeItemStack(itemStack);
        if (serializedItem == null) {
            plugin.getLogger().severe("Failed to serialize item for sold_items: " + (itemNameDisplay != null ? itemNameDisplay : itemStack.getType()));
            return false;
        }
        long soldTimestamp = System.currentTimeMillis() / 1000L;

        Connection conn = null;
        PreparedStatement pstmt = null;
        try {
            conn = dbManager.getConnection();
            pstmt = conn.prepareStatement(sql);

            if (originalAuctionId != null) {
                pstmt.setLong(1, originalAuctionId);
            } else {
                pstmt.setNull(1, Types.INTEGER);
            }
            pstmt.setString(2, sellerUuid.toString());
            pstmt.setString(3, sellerName);
            pstmt.setString(4, buyerUuid.toString());
            pstmt.setString(5, buyerName);
            pstmt.setString(6, itemNameDisplay);
            pstmt.setString(7, serializedItem);
            pstmt.setDouble(8, price);
            pstmt.setLong(9, soldTimestamp);
            pstmt.setString(10, (type != null) ? type : "SALE");

            return pstmt.executeUpdate() > 0;

        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not add sold item to database", e);
        } finally {
            closeQuietly(pstmt);
            // Ne pas fermer conn
        }
        return false;
    }

    private List<SoldItem> getHistoryListWithManualClose(String baseSqlWithOrderBy, int page, int itemsPerPage, String... params) {
        if (page < 1) page = 1;
        int offset = (page - 1) * itemsPerPage;
        List<SoldItem> history = new ArrayList<>();
        String paginatedSql = baseSqlWithOrderBy + " LIMIT ? OFFSET ?";

        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            conn = dbManager.getConnection();
            pstmt = conn.prepareStatement(paginatedSql);
            int paramIndex = 1;
            for (String param : params) {
                pstmt.setString(paramIndex++, param);
            }
            pstmt.setInt(paramIndex++, itemsPerPage);
            pstmt.setInt(paramIndex, offset);
            rs = pstmt.executeQuery();
            while (rs.next()) {
                history.add(mapResultSetToSoldItem(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error fetching sales history (paginated): " + baseSqlWithOrderBy, e);
        } finally {
            closeQuietly(rs);
            closeQuietly(pstmt);
        }
        return history;
    }

    private int countHistoryWithManualClose(String sqlCount, String... params) {
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            conn = dbManager.getConnection();
            pstmt = conn.prepareStatement(sqlCount);
            int paramIndex = 1;
            for (String param : params) {
                pstmt.setString(paramIndex++, param);
            }
            rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error counting sales history: " + sqlCount, e);
        } finally {
            closeQuietly(rs);
            closeQuietly(pstmt);
        }
        return 0;
    }

    @Override
    public List<SoldItem> getSalesBySeller(UUID sellerUuid, int page, int itemsPerPage) {
        String sortOrderConfig = plugin.getConfigManager().getString("display_options.sales_history_sort_order", "DESC").toUpperCase();
        String orderByClause = "ORDER BY sold_timestamp " + ("ASC".equals(sortOrderConfig) ? "ASC" : "DESC");
        String baseSql = "SELECT id, original_auction_id, seller_uuid, seller_name, buyer_uuid, buyer_name, item_name, item_serialized, price, sold_timestamp, type FROM sold_items WHERE seller_uuid = ? " + orderByClause;
        return getHistoryListWithManualClose(baseSql, page, itemsPerPage, sellerUuid.toString());
    }

    @Override
    public int countSalesBySeller(UUID sellerUuid) {
        String sql = "SELECT COUNT(*) FROM sold_items WHERE seller_uuid = ?";
        return countHistoryWithManualClose(sql, sellerUuid.toString());
    }

    @Override
    public List<SoldItem> getPurchasesByBuyer(UUID buyerUuid, int page, int itemsPerPage) {
        String sortOrderConfig = plugin.getConfigManager().getString("display_options.sales_history_sort_order", "DESC").toUpperCase();
        String orderByClause = "ORDER BY sold_timestamp " + ("ASC".equals(sortOrderConfig) ? "ASC" : "DESC");
        String baseSql = "SELECT id, original_auction_id, seller_uuid, seller_name, buyer_uuid, buyer_name, item_name, item_serialized, price, sold_timestamp, type FROM sold_items WHERE buyer_uuid = ? " + orderByClause;
        return getHistoryListWithManualClose(baseSql, page, itemsPerPage, buyerUuid.toString());
    }

    @Override
    public int countPurchasesByBuyer(UUID buyerUuid) {
        String sql = "SELECT COUNT(*) FROM sold_items WHERE buyer_uuid = ?";
        return countHistoryWithManualClose(sql, buyerUuid.toString());
    }

    @Override
    public List<SoldItem> getGlobalSalesHistory(int page, int itemsPerPage) {
        String sortOrderConfig = plugin.getConfigManager().getString("display_options.sales_history_sort_order", "DESC").toUpperCase();
        String orderByClause = "ORDER BY sold_timestamp " + ("ASC".equals(sortOrderConfig) ? "ASC" : "DESC");
        String baseSql = "SELECT id, original_auction_id, seller_uuid, seller_name, buyer_uuid, buyer_name, item_name, item_serialized, price, sold_timestamp, type FROM sold_items " + orderByClause;
        return getHistoryListWithManualClose(baseSql, page, itemsPerPage);
    }

    @Override
    public int countGlobalSalesHistory() {
        String sql = "SELECT COUNT(*) FROM sold_items";
        return countHistoryWithManualClose(sql);
    }

    @Override
    public int clearOldSoldItems(long olderThanTimestamp) {
        String sql = "DELETE FROM sold_items WHERE sold_timestamp < ?";
        Connection conn = null;
        PreparedStatement pstmt = null;
        try {
            conn = dbManager.getConnection();
            pstmt = conn.prepareStatement(sql);
            pstmt.setLong(1, olderThanTimestamp);
            return pstmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error clearing old sold items", e);
        } finally {
            closeQuietly(pstmt);
        }
        return 0;
    }

    private SoldItem mapResultSetToSoldItem(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        long originalAuctionIdLong = rs.getLong("original_auction_id");
        Long originalAuctionId = rs.wasNull() ? null : originalAuctionIdLong;
        UUID sellerUuid = UUID.fromString(rs.getString("seller_uuid"));
        String sellerName = rs.getString("seller_name");
        UUID buyerUuid = UUID.fromString(rs.getString("buyer_uuid"));
        String buyerName = rs.getString("buyer_name");
        String itemName = rs.getString("item_name");
        String itemSerialized = rs.getString("item_serialized");
        double price = rs.getDouble("price");
        long soldTimestamp = rs.getLong("sold_timestamp");
        String type = rs.getString("type");
        return new SoldItem(id, originalAuctionId, sellerUuid, sellerName, buyerUuid, buyerName, itemName, itemSerialized, price, soldTimestamp, type);
    }

    // Méthodes utilitaires pour fermer les ressources JDBC silencieusement
    private void closeQuietly(Statement stmt) {
        if (stmt != null) {
            try {
                stmt.close();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Error closing Statement in SoldItemDAO", e);
            }
        }
    }

    private void closeQuietly(ResultSet rs) {
        if (rs != null) {
            try {
                rs.close();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Error closing ResultSet in SoldItemDAO", e);
            }
        }
    }
}
