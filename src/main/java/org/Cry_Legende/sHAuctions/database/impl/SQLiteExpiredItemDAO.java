package org.Cry_Legende.sHAuctions.database.impl;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.database.DatabaseManager;
import org.Cry_Legende.sHAuctions.database.dao.ExpiredItemDAO;
import org.Cry_Legende.sHAuctions.model.ExpiredItem;
import org.Cry_Legende.sHAuctions.util.SerializationUtil;

import org.bukkit.inventory.ItemStack;

import java.sql.*; // Statement, PreparedStatement, ResultSet, Connection, SQLException
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public class SQLiteExpiredItemDAO implements ExpiredItemDAO {

    private final DatabaseManager dbManager;
    private final SHAuctions plugin;

    public SQLiteExpiredItemDAO(DatabaseManager dbManager) {
        this.dbManager = dbManager;
        this.plugin = SHAuctions.getInstance();
    }

    @Override
    public boolean addExpiredItem(long originalAuctionId, UUID ownerUuid, String ownerName, ItemStack itemStack, long expirationTimestamp) {
        return addExpiredItem(originalAuctionId, ownerUuid, ownerName, itemStack, expirationTimestamp, null);
    }

    @Override
    public boolean addExpiredItem(long originalAuctionId, UUID ownerUuid, String ownerName, ItemStack itemStack, long expirationTimestamp, String cancelReason) {
        String sql = "INSERT INTO expired_items(original_auction_id, owner_uuid, owner_name, item_serialized, expiration_timestamp, claimed, cancel_reason) VALUES(?,?,?,?,?,0,?)";
        String serializedItem = SerializationUtil.serializeItemStack(itemStack);
        if (serializedItem == null) {
            plugin.getLogger().severe("Failed to serialize item for expired_items: " + itemStack.getType());
            return false;
        }

        Connection conn = null;
        PreparedStatement pstmt = null;
        try {
            conn = dbManager.getConnection();
            pstmt = conn.prepareStatement(sql);
            pstmt.setLong(1, originalAuctionId);
            pstmt.setString(2, ownerUuid.toString());
            pstmt.setString(3, ownerName);
            pstmt.setString(4, serializedItem);
            pstmt.setLong(5, expirationTimestamp);
            if (cancelReason == null) {
                pstmt.setNull(6, Types.VARCHAR);
            } else {
                pstmt.setString(6, cancelReason);
            }
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            if (e.getErrorCode() == 19 && e.getMessage().toLowerCase().contains("unique constraint failed: expired_items.original_auction_id")) {
                plugin.getLogger().warning("Attempted to add duplicate expired item for original_auction_id: " + originalAuctionId + ". Item not added again.");
            } else {
                plugin.getLogger().log(Level.SEVERE, "Could not add expired item to database for original_auction_id: " + originalAuctionId, e);
            }
        } finally {
            closeQuietly(pstmt);
        }
        return false;
    }

    @Override
    public List<ExpiredItem> getUnclaimedExpiredItemsByOwner(UUID ownerUuid) {
        String sortOrderConfig = plugin.getConfigManager().getString("display_options.expired_items_sort_order", "ASC").toUpperCase();
        String orderByClause = "ORDER BY expiration_timestamp " + ("DESC".equals(sortOrderConfig) ? "DESC" : "ASC");
        String sql = "SELECT id, original_auction_id, owner_uuid, owner_name, item_serialized, expiration_timestamp, claimed, cancel_reason FROM expired_items WHERE owner_uuid = ? AND claimed = 0 " + orderByClause;
        List<ExpiredItem> items = new ArrayList<>();
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            conn = dbManager.getConnection();
            pstmt = conn.prepareStatement(sql);
            pstmt.setString(1, ownerUuid.toString());
            rs = pstmt.executeQuery();
            while (rs.next()) {
                items.add(mapResultSetToExpiredItem(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error fetching unclaimed expired items for owner: " + ownerUuid, e);
        } finally {
            closeQuietly(rs);
            closeQuietly(pstmt);
        }
        return items;
    }

    @Override
    public ExpiredItem getUnclaimedExpiredItemById(long expiredItemId) {
        String sql = "SELECT id, original_auction_id, owner_uuid, owner_name, item_serialized, expiration_timestamp, claimed, cancel_reason FROM expired_items WHERE id = ? AND claimed = 0";
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            conn = dbManager.getConnection();
            pstmt = conn.prepareStatement(sql);
            pstmt.setLong(1, expiredItemId);
            rs = pstmt.executeQuery();
            if (rs.next()) {
                return mapResultSetToExpiredItem(rs);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error fetching unclaimed expired item by ID: " + expiredItemId, e);
        } finally {
            closeQuietly(rs);
            closeQuietly(pstmt);
        }
        return null;
    }

    @Override
    public boolean markAsClaimed(long id) {
        // Conditionnel : un item déjà réclamé ne peut pas l'être une seconde fois
        String sql = "UPDATE expired_items SET claimed = 1 WHERE id = ? AND claimed = 0";
        Connection conn = null;
        PreparedStatement pstmt = null;
        try {
            conn = dbManager.getConnection();
            pstmt = conn.prepareStatement(sql);
            pstmt.setLong(1, id);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error marking expired item as claimed: " + id, e);
        } finally {
            closeQuietly(pstmt);
        }
        return false;
    }

    @Override
    public int clearExpiredItems(long olderThanTimestamp, boolean removeOnlyClaimed) {
        StringBuilder sqlBuilder = new StringBuilder("DELETE FROM expired_items WHERE ");
        List<Object> params = new ArrayList<>();
        if (removeOnlyClaimed) {
            sqlBuilder.append("claimed = 1");
        } else {
            // Seuls les invendus ordinaires sont purgés : un objet d'enchère gagnée (déjà payé, présent dans sold_items)
            // ou retiré par le staff (cancel_reason) n'est jamais détruit tant qu'il n'est pas réclamé
            sqlBuilder.append("(claimed = 1 OR (expiration_timestamp < ? AND cancel_reason IS NULL AND NOT EXISTS ("
                + "SELECT 1 FROM sold_items s WHERE s.original_auction_id = expired_items.original_auction_id)))");
            if (olderThanTimestamp <= 0 && !removeOnlyClaimed) {
                plugin.getLogger().warning("clearExpiredItems called with removeOnlyClaimed=false and olderThanTimestamp<=0. This might be unintended. Check cleanup logic.");
            }
            params.add(olderThanTimestamp);
        }
        Connection conn = null;
        PreparedStatement pstmt = null;
        try {
            conn = dbManager.getConnection();
            pstmt = conn.prepareStatement(sqlBuilder.toString());
            for (int i = 0; i < params.size(); i++) {
                pstmt.setObject(i + 1, params.get(i));
            }
            return pstmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error clearing expired items", e);
        } finally {
            closeQuietly(pstmt);
        }
        return 0;
    }

    @Override
    public int countUnclaimedExpiredItemsByOwner(UUID ownerUuid) {
        String sql = "SELECT COUNT(*) FROM expired_items WHERE owner_uuid = ? AND claimed = 0";
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            conn = dbManager.getConnection();
            pstmt = conn.prepareStatement(sql);
            pstmt.setString(1, ownerUuid.toString());
            rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error counting unclaimed expired items for owner: " + ownerUuid, e);
        } finally {
            closeQuietly(rs);
            closeQuietly(pstmt);
        }
        return 0;
    }

    private ExpiredItem mapResultSetToExpiredItem(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        long originalAuctionId = rs.getLong("original_auction_id");
        UUID ownerUuid = UUID.fromString(rs.getString("owner_uuid"));
        String ownerName = rs.getString("owner_name");
        String itemSerialized = rs.getString("item_serialized");
        long expirationTimestamp = rs.getLong("expiration_timestamp");
        boolean claimed = rs.getBoolean("claimed");
        String cancelReason = rs.getString("cancel_reason");
        return new ExpiredItem(id, originalAuctionId, ownerUuid, ownerName, itemSerialized, expirationTimestamp, claimed, cancelReason);
    }

    // Méthodes utilitaires pour fermer les ressources JDBC silencieusement
    private void closeQuietly(Statement stmt) {
        if (stmt != null) {
            try {
                stmt.close();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Error closing Statement in ExpiredItemDAO", e);
            }
        }
    }

    private void closeQuietly(ResultSet rs) {
        if (rs != null) {
            try {
                rs.close();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Error closing ResultSet in ExpiredItemDAO", e);
            }
        }
    }
}
