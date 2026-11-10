package org.Cry_Legende.sHAuctions.database.impl;

import org.Cry_Legende.sHAuctions.database.SharedDatabase;
import org.Cry_Legende.sHAuctions.database.dao.ExpiredItemDAO;
import org.Cry_Legende.sHAuctions.model.ExpiredItem;
import org.Cry_Legende.sHAuctions.util.SerializationUtil;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BinaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Invendus en MariaDB (mode réseau). Récupérer un objet reste un UPDATE conditionnel ({@code claimed = 0}) : un seul
 * serveur peut le donner, même si le joueur clique sur deux serveurs à la suite.
 */
public class MariaExpiredItemDAO implements ExpiredItemDAO {

    private static final String T = SharedDatabase.EXPIRED;
    private static final String COLUMNS = "id, original_auction_id, owner_uuid, owner_name, item_serialized, expiration_timestamp, claimed, cancel_reason";

    private final SharedDatabase db;
    private final Logger logger;
    private final BinaryOperator<String> option;

    /**
     * @param logger journal du plugin
     * @param option valeur d'une option de config.yml (chemin, valeur par défaut)
     */
    public MariaExpiredItemDAO(SharedDatabase db, Logger logger, BinaryOperator<String> option) {
        this.db = db;
        this.logger = logger;
        this.option = option;
    }

    @Override
    public boolean addExpiredItem(long originalAuctionId, UUID ownerUuid, String ownerName, ItemStack itemStack, long expirationTimestamp) {
        return addExpiredItem(originalAuctionId, ownerUuid, ownerName, itemStack, expirationTimestamp, null);
    }

    @Override
    public boolean addExpiredItem(long originalAuctionId, UUID ownerUuid, String ownerName, ItemStack itemStack, long expirationTimestamp,
                                  String cancelReason) {
        String serialized = SerializationUtil.serializeItemStack(itemStack);
        if (serialized == null) {
            logger.severe("Failed to serialize item for expired_items: " + itemStack.getType());
            return false;
        }
        String sql = "INSERT INTO " + T + " (original_auction_id, owner_uuid, owner_name, item_serialized, expiration_timestamp, claimed, "
            + "cancel_reason) VALUES (?,?,?,?,?,0,?)";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, originalAuctionId);
            ps.setString(2, ownerUuid.toString());
            ps.setString(3, ownerName);
            ps.setString(4, serialized);
            ps.setLong(5, expirationTimestamp);
            if (cancelReason == null) {
                ps.setNull(6, Types.VARCHAR);
            } else {
                ps.setString(6, cancelReason);
            }
            return ps.executeUpdate() > 0;
        } catch (SQLIntegrityConstraintViolationException e) {
            logger.warning("Attempted to add duplicate expired item for original_auction_id: " + originalAuctionId + ". Item not added again.");
            return false;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Could not add expired item to database for original_auction_id: " + originalAuctionId, e);
            return false;
        }
    }

    @Override
    public List<ExpiredItem> getUnclaimedExpiredItemsByOwner(UUID ownerUuid) {
        String sort = String.valueOf(option.apply("display_options.expired_items_sort_order", "ASC")).toUpperCase();
        String sql = "SELECT " + COLUMNS + " FROM " + T + " WHERE owner_uuid = ? AND claimed = 0 ORDER BY expiration_timestamp "
            + ("DESC".equals(sort) ? "DESC" : "ASC");
        List<ExpiredItem> out = new ArrayList<>();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, ownerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error fetching unclaimed expired items for owner: " + ownerUuid, e);
        }
        return out;
    }

    @Override
    public ExpiredItem getUnclaimedExpiredItemById(long expiredItemId) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM " + T + " WHERE id = ? AND claimed = 0")) {
            ps.setLong(1, expiredItemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error fetching unclaimed expired item by ID: " + expiredItemId, e);
            return null;
        }
    }

    @Override
    public boolean markAsClaimed(long id) {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement("UPDATE " + T + " SET claimed = 1 WHERE id = ? AND claimed = 0")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error marking expired item as claimed: " + id, e);
            return false;
        }
    }

    @Override
    public int clearExpiredItems(long olderThanTimestamp, boolean removeOnlyClaimed) {
        String sql;
        if (removeOnlyClaimed) {
            sql = "DELETE FROM " + T + " WHERE claimed = 1";
        } else {
            // Mêmes règles que SQLite : un objet d'enchère gagnée ou retiré par le staff n'est jamais détruit avant d'être récupéré
            sql = "DELETE FROM " + T + " WHERE claimed = 1 OR (expiration_timestamp < ? AND cancel_reason IS NULL AND NOT EXISTS ("
                + "SELECT 1 FROM " + SharedDatabase.SOLD + " s WHERE s.original_auction_id = " + T + ".original_auction_id))";
        }
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (!removeOnlyClaimed) {
                ps.setLong(1, olderThanTimestamp);
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error clearing expired items", e);
            return 0;
        }
    }

    @Override
    public int countUnclaimedExpiredItemsByOwner(UUID ownerUuid) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + T + " WHERE owner_uuid = ? AND claimed = 0")) {
            ps.setString(1, ownerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error counting unclaimed expired items for owner: " + ownerUuid, e);
            return 0;
        }
    }

    private static ExpiredItem map(ResultSet rs) throws SQLException {
        return new ExpiredItem(rs.getLong("id"), rs.getLong("original_auction_id"), UUID.fromString(rs.getString("owner_uuid")),
            rs.getString("owner_name"), rs.getString("item_serialized"), rs.getLong("expiration_timestamp"), rs.getInt("claimed") != 0,
            rs.getString("cancel_reason"));
    }
}
