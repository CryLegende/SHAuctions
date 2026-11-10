package org.Cry_Legende.sHAuctions.database.impl;

import org.Cry_Legende.sHAuctions.database.SharedDatabase;
import org.Cry_Legende.sHAuctions.database.dao.AuctionDAO;
import org.Cry_Legende.sHAuctions.model.AuctionItem;
import org.Cry_Legende.sHAuctions.util.SerializationUtil;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Annonces en MariaDB (mode réseau). Les lectures ignorent les annonces réservées ({@code claim_token} posé) :
 * une annonce en cours d'achat ou de mise en vente n'est visible nulle part.
 *
 * <p>Les opérations qui déplacent de l'argent ou un objet sont des transactions qui verrouillent la ligne
 * ({@code SELECT ... FOR UPDATE}) et rendent l'état exact de l'annonce au moment où elle a été modifiée : le
 * remboursement ou le paiement se calcule sur cet état, jamais sur une lecture faite plus tôt.</p>
 */
public class MariaAuctionDAO implements AuctionDAO {

    private static final String T = SharedDatabase.AUCTIONS;
    private static final String COLUMNS = "id, seller_uuid, seller_name, item_serialized, price, list_timestamp, expire_timestamp, type, "
        + "highest_bid, highest_bidder_uuid, highest_bidder_name";
    private static final String VISIBLE = "claim_token IS NULL";

    private final SharedDatabase db;
    private final Logger logger;
    private final BinaryOperator<String> option;

    /**
     * La base n'a pas confirmé la validation d'une transaction : elle a pu être enregistrée ou non. {@link #result()}
     * est ce qu'aurait rendu l'opération si elle l'a été ; l'appelant vérifie en base avant d'agir.
     */
    public static final class UncertainCommit extends SQLException {
        private final transient Object result;

        UncertainCommit(SQLException cause, Object result) {
            super("Validation non confirmée par la base", cause);
            this.result = result;
        }

        public Object result() {
            return result;
        }
    }

    private static void commit(Connection c, Object result) throws UncertainCommit {
        try {
            c.commit();
        } catch (SQLException e) {
            throw new UncertainCommit(e, result);
        }
    }

    /**
     * @param logger journal du plugin
     * @param option valeur d'une option de config.yml (chemin, valeur par défaut)
     */
    public MariaAuctionDAO(SharedDatabase db, Logger logger, BinaryOperator<String> option) {
        this.db = db;
        this.logger = logger;
        this.option = option;
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    private String order(String key) {
        String sort = String.valueOf(option.apply("display_options." + key, "DESC")).toUpperCase();
        return "ORDER BY list_timestamp " + ("ASC".equals(sort) ? "ASC" : "DESC");
    }

    // ------------------------------------------------------------------ interface AuctionDAO

    @Override
    public long addAuction(UUID sellerUuid, String sellerName, ItemStack item, double price, long durationSeconds) {
        return insert(sellerUuid, sellerName, item, price, durationSeconds, AuctionItem.TYPE_SALE, null, null);
    }

    @Override
    public long addBidAuction(UUID sellerUuid, String sellerName, ItemStack item, double startingPrice, long durationSeconds) {
        return insert(sellerUuid, sellerName, item, startingPrice, durationSeconds, AuctionItem.TYPE_BID, null, null);
    }

    @Override
    public List<AuctionItem> getActiveBidAuctions(int page, int itemsPerPage) {
        return page("type = 'BID'", order("auction_house_sort_order"), page, itemsPerPage);
    }

    @Override
    public int countActiveBidAuctions() {
        return count("SELECT COUNT(*) FROM " + T + " WHERE type = 'BID' AND " + VISIBLE + " AND expire_timestamp > ?", null);
    }

    @Override
    public boolean updateHighestBid(long id, double bid, UUID bidderUuid, String bidderName) {
        // En mode réseau, une mise passe par placeBid (transaction) ; cette écriture garde la même garde que SQLite.
        String sql = "UPDATE " + T + " SET highest_bid = ?, highest_bidder_uuid = ?, highest_bidder_name = ? "
            + "WHERE id = ? AND type = 'BID' AND " + VISIBLE + " AND expire_timestamp > ? AND (highest_bid IS NULL OR highest_bid < ?)";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDouble(1, bid);
            ps.setString(2, bidderUuid.toString());
            ps.setString(3, bidderName);
            ps.setLong(4, id);
            ps.setLong(5, now());
            ps.setDouble(6, bid);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error updating highest bid for auction ID: " + id, e);
            return false;
        }
    }

    @Override
    public boolean setExpireTimestamp(long id, long newExpireTimestamp) {
        String sql = "UPDATE " + T + " SET expire_timestamp = ? WHERE id = ? AND type = 'BID' AND expire_timestamp < ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, newExpireTimestamp);
            ps.setLong(2, id);
            ps.setLong(3, newExpireTimestamp);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error extending expire timestamp for auction ID: " + id, e);
            return false;
        }
    }

    @Override
    public AuctionItem getAuctionById(long id) {
        String sql = "SELECT " + COLUMNS + " FROM " + T + " WHERE id = ? AND " + VISIBLE + " AND expire_timestamp > ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setLong(2, now());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error fetching auction by ID: " + id, e);
            return null;
        }
    }

    @Override
    public List<AuctionItem> getAllActiveAuctions() {
        return list("SELECT " + COLUMNS + " FROM " + T + " WHERE type = 'SALE' AND " + VISIBLE + " AND expire_timestamp > ? "
            + order("auction_house_sort_order"), null);
    }

    @Override
    public List<AuctionItem> getActiveAuctions(int page, int itemsPerPage) {
        return page("type = 'SALE'", order("auction_house_sort_order"), page, itemsPerPage);
    }

    @Override
    public int countActiveAuctions() {
        return count("SELECT COUNT(*) FROM " + T + " WHERE type = 'SALE' AND " + VISIBLE + " AND expire_timestamp > ?", null);
    }

    @Override
    public List<AuctionItem> getAuctionsBySeller(UUID sellerUuid) {
        return list("SELECT " + COLUMNS + " FROM " + T + " WHERE seller_uuid = ? AND " + VISIBLE + " AND expire_timestamp > ? "
            + order("own_sales_sort_order"), sellerUuid.toString());
    }

    @Override
    public int countAuctionsBySeller(UUID sellerUuid) {
        // Les annonces réservées comptent : elles sont encore à ce vendeur
        return count("SELECT COUNT(*) FROM " + T + " WHERE seller_uuid = ? AND expire_timestamp > ?", sellerUuid.toString());
    }

    @Override
    public int countSaleAuctionsBySeller(UUID sellerUuid) {
        return count("SELECT COUNT(*) FROM " + T + " WHERE seller_uuid = ? AND type = 'SALE' AND expire_timestamp > ?", sellerUuid.toString());
    }

    @Override
    public int countBidAuctionsBySeller(UUID sellerUuid) {
        return count("SELECT COUNT(*) FROM " + T + " WHERE seller_uuid = ? AND type = 'BID' AND expire_timestamp > ?", sellerUuid.toString());
    }

    @Override
    public boolean removeAuction(long id) {
        // Une annonce réservée par un autre serveur n'est jamais retirée par ce chemin
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement("DELETE FROM " + T + " WHERE id = ? AND " + VISIBLE)) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error removing auction by ID: " + id, e);
            return false;
        }
    }

    @Override
    public List<AuctionItem> getExpiredAuctions() {
        return list("SELECT " + COLUMNS + " FROM " + T + " WHERE " + VISIBLE + " AND expire_timestamp <= ? ORDER BY expire_timestamp ASC", null);
    }

    // ------------------------------------------------------------------ mise en vente

    /**
     * Insère une annonce déjà réservée par ce serveur : invisible tant que l'objet n'a pas quitté l'inventaire du
     * vendeur ({@link #releaseClaim}), supprimée s'il n'y est plus ({@link #deleteClaimed}).
     *
     * @return l'id de l'annonce, ou -1 en cas d'erreur
     */
    public long insertListing(UUID sellerUuid, String sellerName, ItemStack item, double price, long durationSeconds, String type,
                              String token, String server) {
        return insert(sellerUuid, sellerName, item, price, durationSeconds, type, token, server);
    }

    private long insert(UUID sellerUuid, String sellerName, ItemStack item, double price, long durationSeconds, String type,
                        String token, String server) {
        String serialized = SerializationUtil.serializeItemStack(item);
        if (serialized == null) {
            logger.severe("Failed to serialize item for auction: " + item.getType());
            return -1;
        }
        long listed = now();
        String sql = "INSERT INTO " + T + " (seller_uuid, seller_name, item_serialized, price, list_timestamp, expire_timestamp, type, "
            + "claim_token, claim_kind, claimed_by, claimed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, sellerUuid.toString());
            ps.setString(2, sellerName);
            ps.setString(3, serialized);
            ps.setDouble(4, price);
            ps.setLong(5, listed);
            ps.setLong(6, listed + durationSeconds);
            ps.setString(7, type);
            if (token == null) {
                ps.setNull(8, Types.CHAR);
                ps.setNull(9, Types.VARCHAR);
                ps.setNull(10, Types.VARCHAR);
                ps.setNull(11, Types.BIGINT);
            } else {
                ps.setString(8, token);
                ps.setString(9, "LIST");
                ps.setString(10, server);
                ps.setLong(11, listed);
            }
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Could not add auction to database", e);
            return -1;
        }
    }

    /** Rend visible une annonce réservée par ce jeton. */
    public boolean releaseClaim(long id, String token) {
        String sql = "UPDATE " + T + " SET claim_token = NULL, claim_kind = NULL, claimed_by = NULL, claimed_at = NULL WHERE id = ? AND claim_token = ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setString(2, token);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Could not release auction " + id, e);
            return false;
        }
    }

    /** Supprime une annonce réservée par ce jeton. */
    public boolean deleteClaimed(long id, String token) {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement("DELETE FROM " + T + " WHERE id = ? AND claim_token = ?")) {
            ps.setLong(1, id);
            ps.setString(2, token);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Could not delete claimed auction " + id, e);
            return false;
        }
    }

    // ------------------------------------------------------------------ achat

    /**
     * Réserve une vente directe pour un achat : un seul serveur y parvient. L'annonce rendue est celle qui a été
     * réservée (relue par le jeton).
     *
     * @return l'annonce réservée, ou null si elle n'est plus en vente (achetée, retirée, expirée, déjà réservée)
     */
    public AuctionItem claimForPurchase(long id, String token, String server) throws SQLException {
        long now = now();
        try (Connection c = db.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + T + " SET claim_token = ?, claim_kind = 'BUY', claimed_by = ?, claimed_at = ? "
                + "WHERE id = ? AND type = 'SALE' AND " + VISIBLE + " AND expire_timestamp > ?")) {
                ps.setString(1, token);
                ps.setString(2, server);
                ps.setLong(3, now);
                ps.setLong(4, id);
                ps.setLong(5, now);
                if (ps.executeUpdate() == 0) {
                    return null;
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM " + T + " WHERE id = ? AND claim_token = ?")) {
                ps.setLong(1, id);
                ps.setString(2, token);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? map(rs) : null;
                }
            }
        }
    }

    /**
     * Termine un achat réservé : l'annonce est supprimée et la vente enregistrée dans la même transaction.
     *
     * @return faux si l'annonce n'est plus réservée par ce jeton (rien n'est écrit)
     */
    public boolean finalizePurchase(long id, String token, Sale sale) throws SQLException {
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + T + " WHERE id = ? AND claim_token = ?")) {
                    ps.setLong(1, id);
                    ps.setString(2, token);
                    if (ps.executeUpdate() == 0) {
                        c.rollback();
                        return false;
                    }
                }
                insertSale(c, sale);
                commit(c, Boolean.TRUE);
                return true;
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(c);
                throw e;
            }
        }
    }

    // ------------------------------------------------------------------ enchères

    /** Résultat d'une mise. */
    public enum BidStatus { OK, GONE, ENDED, OWN, ALREADY_HIGHEST, TOO_LOW }

    /**
     * @param previousBidder enchérisseur remplacé (à rembourser de {@code previousBid}), null s'il n'y avait aucune offre
     * @param extended       vrai si l'anti-snipe a repoussé la fin
     * @param minRequired    mise minimale au moment du refus (TOO_LOW)
     */
    public record BidOutcome(BidStatus status, AuctionItem auction, UUID previousBidder, String previousBidderName, double previousBid,
                             boolean extended, double minRequired) {
    }

    /**
     * Place une mise dans une transaction : la ligne est verrouillée, toutes les règles sont revérifiées sur son état
     * réel, puis la mise remplace l'ancienne. L'enchérisseur remplacé est celui de la ligne verrouillée : chaque mise
     * déjà payée est remboursée une fois, et une seule.
     */
    public BidOutcome placeBid(long id, UUID bidder, String bidderName, double amount, double minIncrement,
                               long antiSnipeWindow, long antiSnipeExtension, String token, String server) throws SQLException {
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                AuctionItem row = lock(c, id);
                long now = now();
                if (row == null || !row.isBid()) {
                    c.rollback();
                    return new BidOutcome(BidStatus.GONE, null, null, null, 0, false, 0);
                }
                if (row.getExpireTimestamp() <= now) {
                    c.rollback();
                    return new BidOutcome(BidStatus.ENDED, row, null, null, 0, false, 0);
                }
                if (row.getSellerUuid().equals(bidder)) {
                    c.rollback();
                    return new BidOutcome(BidStatus.OWN, row, null, null, 0, false, 0);
                }
                if (row.hasBid() && bidder.equals(row.getHighestBidderUuid())) {
                    c.rollback();
                    return new BidOutcome(BidStatus.ALREADY_HIGHEST, row, null, null, 0, false, 0);
                }
                double min = row.hasBid() ? row.getHighestBid() + minIncrement : row.getStartingPrice();
                if (amount < min || (row.hasBid() && amount <= row.getHighestBid())) {
                    c.rollback();
                    return new BidOutcome(BidStatus.TOO_LOW, row, null, null, 0, false, min);
                }
                long expire = row.getExpireTimestamp();
                boolean extended = false;
                if (antiSnipeWindow > 0 && antiSnipeExtension > 0 && expire - now < antiSnipeWindow && now + antiSnipeExtension > expire) {
                    expire = now + antiSnipeExtension;
                    extended = true;
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE " + T + " SET highest_bid = ?, highest_bidder_uuid = ?, "
                    + "highest_bidder_name = ?, expire_timestamp = ? WHERE id = ?")) {
                    ps.setDouble(1, amount);
                    ps.setString(2, bidder.toString());
                    ps.setString(3, bidderName);
                    ps.setLong(4, expire);
                    ps.setLong(5, id);
                    ps.executeUpdate();
                }
                boolean hadBid = row.hasBid() && row.getHighestBidderUuid() != null;
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + SharedDatabase.BIDS + " (auction_id, bidder_uuid, bidder_name, "
                    + "amount, replaced_uuid, replaced_amount, token, server, placed_at) VALUES (?,?,?,?,?,?,?,?,?)")) {
                    ps.setLong(1, id);
                    ps.setString(2, bidder.toString());
                    ps.setString(3, bidderName);
                    ps.setDouble(4, amount);
                    if (hadBid) {
                        ps.setString(5, row.getHighestBidderUuid().toString());
                        ps.setDouble(6, row.getHighestBid());
                    } else {
                        ps.setNull(5, Types.CHAR);
                        ps.setNull(6, Types.DOUBLE);
                    }
                    ps.setString(7, token);
                    ps.setString(8, server);
                    ps.setLong(9, now);
                    ps.executeUpdate();
                }
                BidOutcome outcome = new BidOutcome(BidStatus.OK, row, hadBid ? row.getHighestBidderUuid() : null,
                    hadBid ? row.getHighestBidderName() : null, hadBid ? row.getHighestBid() : 0, extended, min);
                commit(c, outcome);
                return outcome;
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(c);
                throw e;
            }
        }
    }

    /**
     * La mise de ce jeton a-t-elle été enregistrée ?
     *
     * @return vrai ou faux, ou null si la base ne répond pas
     */
    public Boolean bidRecorded(String token) {
        return exists("SELECT 1 FROM " + SharedDatabase.BIDS + " WHERE token = ?", null, token);
    }

    /**
     * L'annonce a-t-elle disparu de la base ?
     *
     * @return vrai ou faux, ou null si la base ne répond pas
     */
    public Boolean isGone(long id) {
        Boolean present = exists("SELECT 1 FROM " + T + " WHERE id = ?", id, null);
        return present == null ? null : !present;
    }

    /**
     * L'annonce est-elle toujours réservée par ce jeton ?
     *
     * @return vrai ou faux, ou null si la base ne répond pas
     */
    public Boolean isClaimedBy(long id, String token) {
        return exists("SELECT 1 FROM " + T + " WHERE id = ? AND claim_token = ?", id, token);
    }

    /** Paramètres dans l'ordre : l'id s'il est donné, puis le texte s'il est donné. */
    private Boolean exists(String sql, Long id, String text) {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            if (id != null) {
                ps.setLong(i++, id);
            }
            if (text != null) {
                ps.setString(i, text);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Base injoignable pendant une vérification", e);
            return null;
        }
    }

    // ------------------------------------------------------------------ annulation

    /**
     * Retire une annonce encore en cours (non réservée, non échue) dans une transaction. Si {@code toExpired} n'est
     * pas null, l'objet est rangé dans les invendus du vendeur dans la même transaction.
     *
     * @return l'annonce telle qu'elle était au moment du retrait (meilleur enchérisseur à rembourser compris),
     *         ou null si elle n'était plus disponible
     */
    public AuctionItem cancel(long id, ExpiredEntry toExpired) throws SQLException {
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                AuctionItem row = lock(c, id);
                if (row == null || row.getExpireTimestamp() <= now()) {
                    c.rollback();
                    return null;
                }
                delete(c, id);
                if (toExpired != null) {
                    insertExpired(c, id, row.getSellerUuid(), row.getSellerName(), row.getItemSerialized(), toExpired.timestamp(), toExpired.reason());
                }
                commit(c, row);
                return row;
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(c);
                throw e;
            }
        }
    }

    // ------------------------------------------------------------------ échéance

    /** Ce qu'il est advenu d'une annonce échue. */
    public enum SettleKind { AWARDED, EXPIRED }

    /**
     * @param auction        l'annonce telle qu'elle était au moment du règlement
     * @param winnerToExpired vrai si l'objet gagné a été rangé dans les invendus du gagnant
     */
    public record Settlement(SettleKind kind, AuctionItem auction, boolean winnerToExpired) {
    }

    /**
     * Règle une annonce échue dans une transaction : enchère gagnée (annonce supprimée, vente enregistrée, objet
     * rangé dans les invendus du gagnant s'il ne peut pas le recevoir tout de suite) ou invendu (annonce supprimée,
     * objet rangé dans les invendus du vendeur). Une annonce prolongée entre-temps par l'anti-snipe ou réservée
     * par un autre serveur n'est pas touchée.
     *
     * @param giveDirectly   le gagnant peut-il recevoir l'objet tout de suite sur ce serveur ?
     * @param sellerNotified le vendeur sera-t-il prévenu en direct (connecté quelque part) ?
     * @param itemName       nom de l'objet pour l'historique
     * @return le règlement, ou null si l'annonce n'est plus à régler
     */
    public Settlement settle(long id, Predicate<UUID> giveDirectly, Predicate<UUID> sellerNotified,
                             Function<AuctionItem, String> itemName) throws SQLException {
        long now = now();
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                AuctionItem row = lock(c, id);
                if (row == null || row.getExpireTimestamp() > now) {
                    c.rollback();
                    return null;
                }
                delete(c, id);
                Settlement result;
                if (row.isBid() && row.hasBid() && row.getHighestBidderUuid() != null) {
                    UUID winner = row.getHighestBidderUuid();
                    insertSale(c, new Sale(id, row.getSellerUuid(), row.getSellerName(), winner, row.getHighestBidderName(),
                        itemName.apply(row), row.getItemSerialized(), row.getHighestBid(), AuctionItem.TYPE_BID,
                        sellerNotified.test(row.getSellerUuid())));
                    boolean toExpired = !giveDirectly.test(winner);
                    if (toExpired) {
                        insertExpired(c, id, winner, row.getHighestBidderName() != null ? row.getHighestBidderName() : "?",
                            row.getItemSerialized(), now, null);
                    }
                    result = new Settlement(SettleKind.AWARDED, row, toExpired);
                } else {
                    insertExpired(c, id, row.getSellerUuid(), row.getSellerName(), row.getItemSerialized(), row.getExpireTimestamp(), null);
                    result = new Settlement(SettleKind.EXPIRED, row, true);
                }
                commit(c, result);
                return result;
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(c);
                throw e;
            }
        }
    }

    // ------------------------------------------------------------------ réservations orphelines

    /** Réservation restée en place (serveur arrêté brutalement, base indisponible au mauvais moment). */
    public record StaleClaim(long id, String kind, String server, long claimedAt, String sellerName, double price) {
    }

    public List<StaleClaim> staleClaims(long olderThan) throws SQLException {
        List<StaleClaim> out = new ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id, claim_kind, claimed_by, claimed_at, seller_name, price FROM " + T
                 + " WHERE claim_token IS NOT NULL AND claimed_at < ?")) {
            ps.setLong(1, olderThan);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new StaleClaim(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getDouble(6)));
                }
            }
        }
        return out;
    }

    /**
     * Supprime une mise en vente jamais terminée. Le serveur qui la faisait s'est arrêté avant de rendre l'annonce
     * visible : l'inventaire du vendeur est revenu à sa dernière sauvegarde, l'objet y est encore.
     */
    public boolean deleteStaleListing(long id, long olderThan) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM " + T + " WHERE id = ? AND claim_kind = 'LIST' AND claimed_at < ?")) {
            ps.setLong(1, id);
            ps.setLong(2, olderThan);
            return ps.executeUpdate() > 0;
        }
    }

    // ------------------------------------------------------------------ écritures internes

    /** Vente à enregistrer dans l'historique. */
    public record Sale(long auctionId, UUID sellerUuid, String sellerName, UUID buyerUuid, String buyerName, String itemName,
                       String itemSerialized, double price, String type, boolean sellerNotified) {
    }

    /** Objet à ranger dans les invendus du vendeur lors d'une annulation. */
    public record ExpiredEntry(long timestamp, String reason) {
    }

    private static void insertSale(Connection c, Sale sale) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + SharedDatabase.SOLD + " (original_auction_id, seller_uuid, seller_name, "
            + "buyer_uuid, buyer_name, item_name, item_serialized, price, sold_timestamp, type, seller_notified) VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, sale.auctionId());
            ps.setString(2, sale.sellerUuid().toString());
            ps.setString(3, sale.sellerName());
            ps.setString(4, sale.buyerUuid().toString());
            ps.setString(5, sale.buyerName() != null ? sale.buyerName() : "?");
            ps.setString(6, sale.itemName());
            ps.setString(7, sale.itemSerialized());
            ps.setDouble(8, sale.price());
            ps.setLong(9, now());
            ps.setString(10, sale.type());
            ps.setInt(11, sale.sellerNotified() ? 1 : 0);
            ps.executeUpdate();
        }
    }

    private static void insertExpired(Connection c, long auctionId, UUID owner, String ownerName, String itemSerialized, long timestamp,
                                      String reason) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + SharedDatabase.EXPIRED + " (original_auction_id, owner_uuid, owner_name, "
            + "item_serialized, expiration_timestamp, claimed, cancel_reason) VALUES (?,?,?,?,?,0,?)")) {
            ps.setLong(1, auctionId);
            ps.setString(2, owner.toString());
            ps.setString(3, ownerName);
            ps.setString(4, itemSerialized);
            ps.setLong(5, timestamp);
            if (reason == null) {
                ps.setNull(6, Types.VARCHAR);
            } else {
                ps.setString(6, reason);
            }
            ps.executeUpdate();
        }
    }

    /** Ligne non réservée, verrouillée jusqu'à la fin de la transaction. */
    private static AuctionItem lock(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM " + T + " WHERE id = ? AND " + VISIBLE + " FOR UPDATE")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    private static void delete(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + T + " WHERE id = ?")) {
            ps.setLong(1, id);
            if (ps.executeUpdate() != 1) {
                throw new SQLException("Annonce " + id + " introuvable alors qu'elle était verrouillée");
            }
        }
    }

    private static void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (SQLException ignored) {
            // la connexion est fermée juste après : la transaction est abandonnée de toute façon
        }
    }

    // ------------------------------------------------------------------ lectures

    private List<AuctionItem> page(String where, String orderBy, int page, int itemsPerPage) {
        if (page < 1) page = 1;
        String sql = "SELECT " + COLUMNS + " FROM " + T + " WHERE " + where + " AND " + VISIBLE + " AND expire_timestamp > ? " + orderBy + " LIMIT ? OFFSET ?";
        List<AuctionItem> out = new ArrayList<>();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, now());
            ps.setInt(2, itemsPerPage);
            ps.setInt(3, (page - 1) * itemsPerPage);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error fetching paginated auctions", e);
        }
        return out;
    }

    /** Requête dont le dernier paramètre est l'heure courante, précédé du paramètre texte s'il est donné. */
    private List<AuctionItem> list(String sql, String param) {
        List<AuctionItem> out = new ArrayList<>();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            if (param != null) {
                ps.setString(i++, param);
            }
            ps.setLong(i, now());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error fetching auctions", e);
        }
        return out;
    }

    private int count(String sql, String param) {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            if (param != null) {
                ps.setString(i++, param);
            }
            ps.setLong(i, now());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error counting auctions", e);
            return 0;
        }
    }

    private static AuctionItem map(ResultSet rs) throws SQLException {
        double highestBid = rs.getDouble("highest_bid");
        if (rs.wasNull()) {
            highestBid = -1;
        }
        String bidder = rs.getString("highest_bidder_uuid");
        String type = rs.getString("type");
        return new AuctionItem(rs.getLong("id"), UUID.fromString(rs.getString("seller_uuid")), rs.getString("seller_name"),
            rs.getString("item_serialized"), rs.getDouble("price"), rs.getLong("list_timestamp"), rs.getLong("expire_timestamp"),
            type == null ? AuctionItem.TYPE_SALE : type, highestBid, bidder == null || bidder.isEmpty() ? null : UUID.fromString(bidder),
            rs.getString("highest_bidder_name"));
    }
}
