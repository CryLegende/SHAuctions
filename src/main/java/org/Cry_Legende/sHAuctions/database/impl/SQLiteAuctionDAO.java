package org.Cry_Legende.sHAuctions.database.impl;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.database.DatabaseManager;
import org.Cry_Legende.sHAuctions.database.dao.AuctionDAO;
import org.Cry_Legende.sHAuctions.model.AuctionItem;
import org.Cry_Legende.sHAuctions.util.SerializationUtil;

import org.bukkit.inventory.ItemStack;

import java.sql.*; // Statement, PreparedStatement, ResultSet, Connection, SQLException
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public class SQLiteAuctionDAO implements AuctionDAO {

    private final DatabaseManager dbManager;
    private final SHAuctions plugin;

    // Liste des colonnes sélectionnées, partagée par toutes les requêtes SELECT.
    private static final String COLUMNS = "id, seller_uuid, seller_name, item_serialized, price, list_timestamp, expire_timestamp, type, highest_bid, highest_bidder_uuid, highest_bidder_name";

    public SQLiteAuctionDAO(DatabaseManager dbManager) {
        this.dbManager = dbManager;
        this.plugin = SHAuctions.getInstance();
    }

    @Override
    public long addAuction(UUID sellerUuid, String sellerName, ItemStack item, double price, long durationSeconds) {
        return insertAuction(sellerUuid, sellerName, item, price, durationSeconds, AuctionItem.TYPE_SALE);
    }

    @Override
    public long addBidAuction(UUID sellerUuid, String sellerName, ItemStack item, double startingPrice, long durationSeconds) {
        return insertAuction(sellerUuid, sellerName, item, startingPrice, durationSeconds, AuctionItem.TYPE_BID);
    }

    private long insertAuction(UUID sellerUuid, String sellerName, ItemStack item, double price, long durationSeconds, String type) {
        // Une enchère (BID) démarre sans offre : highest_bid = NULL, aucun enchérisseur.
        String sql = "INSERT INTO auctions(seller_uuid, seller_name, item_serialized, price, list_timestamp, expire_timestamp, type, highest_bid, highest_bidder_uuid, highest_bidder_name) VALUES(?,?,?,?,?,?,?,NULL,NULL,NULL)";
        String serializedItem = SerializationUtil.serializeItemStack(item);
        if (serializedItem == null) {
            plugin.getLogger().severe("Failed to serialize item for auction: " + item.getType());
            return -1;
        }
        long listTimestamp = System.currentTimeMillis() / 1000L;
        long expireTimestamp = listTimestamp + durationSeconds;

        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet generatedKeys = null;
        try {
            conn = dbManager.getConnection();
            pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            pstmt.setString(1, sellerUuid.toString());
            pstmt.setString(2, sellerName);
            pstmt.setString(3, serializedItem);
            pstmt.setDouble(4, price);
            pstmt.setLong(5, listTimestamp);
            pstmt.setLong(6, expireTimestamp);
            pstmt.setString(7, type);
            int affectedRows = pstmt.executeUpdate();
            if (affectedRows > 0) {
                generatedKeys = pstmt.getGeneratedKeys();
                if (generatedKeys.next()) {
                    return generatedKeys.getLong(1);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not add auction to database", e);
        } finally {
            closeQuietly(generatedKeys);
            closeQuietly(pstmt);
            // Ne pas fermer 'conn'
        }
        return -1;
    }

    @Override
    public AuctionItem getAuctionById(long id) {
        String sql = "SELECT " + COLUMNS + " FROM auctions WHERE id = ? AND expire_timestamp > ?";
        long currentTime = System.currentTimeMillis() / 1000L;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            conn = dbManager.getConnection();
            pstmt = conn.prepareStatement(sql);
            pstmt.setLong(1, id);
            pstmt.setLong(2, currentTime);
            rs = pstmt.executeQuery();
            if (rs.next()) {
                return mapResultSetToAuctionItem(rs);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error fetching auction by ID: " + id, e);
        } finally {
            closeQuietly(rs);
            closeQuietly(pstmt);
        }
        return null;
    }

    @Override
    public List<AuctionItem> getAllActiveAuctions() {
        String sortOrderConfig = plugin.getConfigManager().getString("display_options.auction_house_sort_order", "DESC").toUpperCase();
        String orderByClause = "ORDER BY list_timestamp " + ("ASC".equals(sortOrderConfig) ? "ASC" : "DESC");
        String sql = "SELECT " + COLUMNS + " FROM auctions WHERE type = 'SALE' AND expire_timestamp > ? " + orderByClause;
        List<AuctionItem> auctions = new ArrayList<>();
        long currentTime = System.currentTimeMillis() / 1000L;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            conn = dbManager.getConnection();
            pstmt = conn.prepareStatement(sql);
            pstmt.setLong(1, currentTime);
            rs = pstmt.executeQuery();
            while (rs.next()) {
                auctions.add(mapResultSetToAuctionItem(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error fetching all active auctions", e);
        } finally {
            closeQuietly(rs);
            closeQuietly(pstmt);
        }
        return auctions;
    }

    @Override
    public List<AuctionItem> getActiveAuctions(int page, int itemsPerPage) {
        if (page < 1) page = 1;
        int offset = (page - 1) * itemsPerPage;
        String sortOrderConfig = plugin.getConfigManager().getString("display_options.auction_house_sort_order", "DESC").toUpperCase();
        String orderByClause = "ORDER BY list_timestamp " + ("ASC".equals(sortOrderConfig) ? "ASC" : "DESC");
        String sql = "SELECT " + COLUMNS + " FROM auctions WHERE type = 'SALE' AND expire_timestamp > ? " + orderByClause + " LIMIT ? OFFSET ?";
        List<AuctionItem> auctions = new ArrayList<>();
        long currentTime = System.currentTimeMillis() / 1000L;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            conn = dbManager.getConnection();
            pstmt = conn.prepareStatement(sql);
            pstmt.setLong(1, currentTime);
            pstmt.setInt(2, itemsPerPage);
            pstmt.setInt(3, offset);
            rs = pstmt.executeQuery();
            while (rs.next()) {
                auctions.add(mapResultSetToAuctionItem(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error fetching paginated active auctions", e);
        } finally {
            closeQuietly(rs);
            closeQuietly(pstmt);
        }
        return auctions;
    }

    @Override
    public int countActiveAuctions() {
        String sql = "SELECT COUNT(*) FROM auctions WHERE type = 'SALE' AND expire_timestamp > ?";
        long currentTime = System.currentTimeMillis() / 1000L;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            conn = dbManager.getConnection();
            pstmt = conn.prepareStatement(sql);
            pstmt.setLong(1, currentTime);
            rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error counting active auctions", e);
        } finally {
            closeQuietly(rs);
            closeQuietly(pstmt);
        }
        return 0;
    }

    @Override
    public List<AuctionItem> getAuctionsBySeller(UUID sellerUuid) {
        String sortOrderConfig = plugin.getConfigManager().getString("display_options.own_sales_sort_order", "DESC").toUpperCase();
        String orderByClause = "ORDER BY list_timestamp " + ("ASC".equals(sortOrderConfig) ? "ASC" : "DESC");
        String sql = "SELECT " + COLUMNS + " FROM auctions WHERE seller_uuid = ? AND type = 'SALE' AND expire_timestamp > ? " + orderByClause;
        List<AuctionItem> auctions = new ArrayList<>();
        long currentTime = System.currentTimeMillis() / 1000L;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            conn = dbManager.getConnection();
            pstmt = conn.prepareStatement(sql);
            pstmt.setString(1, sellerUuid.toString());
            pstmt.setLong(2, currentTime);
            rs = pstmt.executeQuery();
            while (rs.next()) {
                auctions.add(mapResultSetToAuctionItem(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error fetching auctions by seller: " + sellerUuid, e);
        } finally {
            closeQuietly(rs);
            closeQuietly(pstmt);
        }
        return auctions;
    }

    @Override
    public int countAuctionsBySeller(UUID sellerUuid) {
        String sql = "SELECT COUNT(*) FROM auctions WHERE seller_uuid = ? AND expire_timestamp > ?";
        long currentTime = System.currentTimeMillis() / 1000L;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            conn = dbManager.getConnection();
            pstmt = conn.prepareStatement(sql);
            pstmt.setString(1, sellerUuid.toString());
            pstmt.setLong(2, currentTime);
            rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error counting auctions for seller: " + sellerUuid, e);
        } finally {
            closeQuietly(rs);
            closeQuietly(pstmt);
        }
        return 0;
    }

    @Override
    public int countSaleAuctionsBySeller(UUID sellerUuid) {
        return countAuctionsBySellerByType(sellerUuid, "SALE");
    }

    @Override
    public int countBidAuctionsBySeller(UUID sellerUuid) {
        return countAuctionsBySellerByType(sellerUuid, "BID");
    }

    private int countAuctionsBySellerByType(UUID sellerUuid, String type) {
        String sql = "SELECT COUNT(*) FROM auctions WHERE seller_uuid = ? AND type = ? AND expire_timestamp > ?";
        long currentTime = System.currentTimeMillis() / 1000L;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            conn = dbManager.getConnection();
            pstmt = conn.prepareStatement(sql);
            pstmt.setString(1, sellerUuid.toString());
            pstmt.setString(2, type);
            pstmt.setLong(3, currentTime);
            rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error counting " + type + " auctions for seller: " + sellerUuid, e);
        } finally {
            closeQuietly(rs);
            closeQuietly(pstmt);
        }
        return 0;
    }

    @Override
    public boolean removeAuction(long id) {
        String sql = "DELETE FROM auctions WHERE id = ?";
        Connection conn = null;
        PreparedStatement pstmt = null;
        try {
            conn = dbManager.getConnection();
            pstmt = conn.prepareStatement(sql);
            pstmt.setLong(1, id);
            int affectedRows = pstmt.executeUpdate();
            return affectedRows > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error removing auction by ID: " + id, e);
        } finally {
            closeQuietly(pstmt);
        }
        return false;
    }

    @Override
    public List<AuctionItem> getExpiredAuctions() {
        String sql = "SELECT " + COLUMNS + " FROM auctions WHERE expire_timestamp <= ? ORDER BY expire_timestamp ASC";
        List<AuctionItem> auctions = new ArrayList<>();
        long currentTime = System.currentTimeMillis() / 1000L;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            conn = dbManager.getConnection();
            pstmt = conn.prepareStatement(sql);
            pstmt.setLong(1, currentTime);
            rs = pstmt.executeQuery();
            while (rs.next()) {
                auctions.add(mapResultSetToAuctionItem(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error fetching expired auctions", e);
        } finally {
            closeQuietly(rs);
            closeQuietly(pstmt);
        }
        return auctions;
    }

    @Override
    public List<AuctionItem> getActiveBidAuctions(int page, int itemsPerPage) {
        if (page < 1) page = 1;
        int offset = (page - 1) * itemsPerPage;
        String sortOrderConfig = plugin.getConfigManager().getString("display_options.auction_house_sort_order", "DESC").toUpperCase();
        String orderByClause = "ORDER BY list_timestamp " + ("ASC".equals(sortOrderConfig) ? "ASC" : "DESC");
        String sql = "SELECT " + COLUMNS + " FROM auctions WHERE type = 'BID' AND expire_timestamp > ? " + orderByClause + " LIMIT ? OFFSET ?";
        List<AuctionItem> auctions = new ArrayList<>();
        long currentTime = System.currentTimeMillis() / 1000L;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            conn = dbManager.getConnection();
            pstmt = conn.prepareStatement(sql);
            pstmt.setLong(1, currentTime);
            pstmt.setInt(2, itemsPerPage);
            pstmt.setInt(3, offset);
            rs = pstmt.executeQuery();
            while (rs.next()) {
                auctions.add(mapResultSetToAuctionItem(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error fetching paginated active bid auctions", e);
        } finally {
            closeQuietly(rs);
            closeQuietly(pstmt);
        }
        return auctions;
    }

    @Override
    public int countActiveBidAuctions() {
        String sql = "SELECT COUNT(*) FROM auctions WHERE type = 'BID' AND expire_timestamp > ?";
        long currentTime = System.currentTimeMillis() / 1000L;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            conn = dbManager.getConnection();
            pstmt = conn.prepareStatement(sql);
            pstmt.setLong(1, currentTime);
            rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error counting active bid auctions", e);
        } finally {
            closeQuietly(rs);
            closeQuietly(pstmt);
        }
        return 0;
    }

    @Override
    public boolean updateHighestBid(long id, double bid, UUID bidderUuid, String bidderName) {
        // On ne met à jour que si l'enchère existe encore, n'a pas expiré,
        // et que la nouvelle offre est strictement supérieure à l'offre courante
        // (ou qu'aucune offre n'existe). Ceci ajoute une barrière anti-concurrence côté SQL.
        String sql = "UPDATE auctions SET highest_bid = ?, highest_bidder_uuid = ?, highest_bidder_name = ? " +
            "WHERE id = ? AND type = 'BID' AND expire_timestamp > ? AND (highest_bid IS NULL OR highest_bid < ?)";
        long currentTime = System.currentTimeMillis() / 1000L;
        Connection conn = null;
        PreparedStatement pstmt = null;
        try {
            conn = dbManager.getConnection();
            pstmt = conn.prepareStatement(sql);
            pstmt.setDouble(1, bid);
            pstmt.setString(2, bidderUuid.toString());
            pstmt.setString(3, bidderName);
            pstmt.setLong(4, id);
            pstmt.setLong(5, currentTime);
            pstmt.setDouble(6, bid);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error updating highest bid for auction ID: " + id, e);
        } finally {
            closeQuietly(pstmt);
        }
        return false;
    }

    @Override
    public boolean setExpireTimestamp(long id, long newExpireTimestamp) {
        // Prolonge l'échéance d'une enchère (anti-snipe). On ne prolonge que si l'enchère existe encore,
        // est de type BID, et que la nouvelle échéance est postérieure à l'actuelle (jamais raccourcir).
        String sql = "UPDATE auctions SET expire_timestamp = ? WHERE id = ? AND type = 'BID' AND expire_timestamp < ?";
        Connection conn = null;
        PreparedStatement pstmt = null;
        try {
            conn = dbManager.getConnection();
            pstmt = conn.prepareStatement(sql);
            pstmt.setLong(1, newExpireTimestamp);
            pstmt.setLong(2, id);
            pstmt.setLong(3, newExpireTimestamp);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error extending expire timestamp for auction ID: " + id, e);
        } finally {
            closeQuietly(pstmt);
        }
        return false;
    }

    private AuctionItem mapResultSetToAuctionItem(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        UUID sellerUuid = UUID.fromString(rs.getString("seller_uuid"));
        String sellerName = rs.getString("seller_name");
        String itemSerialized = rs.getString("item_serialized");
        double price = rs.getDouble("price");
        long listTimestamp = rs.getLong("list_timestamp");
        long expireTimestamp = rs.getLong("expire_timestamp");

        String type = rs.getString("type");
        if (type == null) type = AuctionItem.TYPE_SALE;

        double highestBid = rs.getDouble("highest_bid");
        if (rs.wasNull()) {
            highestBid = -1;
        }
        String bidderUuidStr = rs.getString("highest_bidder_uuid");
        UUID highestBidderUuid = (bidderUuidStr != null && !bidderUuidStr.isEmpty()) ? UUID.fromString(bidderUuidStr) : null;
        String highestBidderName = rs.getString("highest_bidder_name");

        return new AuctionItem(id, sellerUuid, sellerName, itemSerialized, price, listTimestamp, expireTimestamp,
            type, highestBid, highestBidderUuid, highestBidderName);
    }

    // Méthodes utilitaires pour fermer les ressources JDBC silencieusement
    private void closeQuietly(Statement stmt) {
        if (stmt != null) {
            try {
                stmt.close();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Error closing Statement", e);
            }
        }
    }

    private void closeQuietly(ResultSet rs) {
        if (rs != null) {
            try {
                rs.close();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Error closing ResultSet", e);
            }
        }
    }
}
