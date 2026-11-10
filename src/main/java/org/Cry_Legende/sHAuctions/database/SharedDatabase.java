package org.Cry_Legende.sHAuctions.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.logging.Logger;

/**
 * Base MariaDB partagée par tous les serveurs en mode réseau (tables {@code shauctions_*}). Chargée seulement en mode
 * réseau : HikariCP et le pilote MariaDB viennent de {@code libraries:} dans plugin.yml.
 *
 * <p>Les annonces portent un jeton de réservation ({@code claim_token}) : une annonce réservée est invisible et ne
 * peut être ni achetée, ni annulée, ni expirée par un autre serveur. C'est la base qui décide qui agit.</p>
 */
public final class SharedDatabase {

    public static final String AUCTIONS = "shauctions_auctions";
    public static final String SOLD = "shauctions_sold_items";
    public static final String EXPIRED = "shauctions_expired_items";
    public static final String WANTED = "shauctions_wanted";
    public static final String BIDS = "shauctions_bids";
    private static final String STATE = "shauctions_state";

    private final Logger logger;
    private final HikariDataSource pool;

    public SharedDatabase(Logger logger, String url, String user, String password, int timeoutMs) {
        this.logger = logger;
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("SHAuctions");
        hikari.setJdbcUrl(url);
        hikari.setUsername(user);
        hikari.setPassword(password);
        hikari.setDriverClassName("org.mariadb.jdbc.Driver");
        hikari.addDataSourceProperty("connectTimeout", String.valueOf(timeoutMs));
        hikari.addDataSourceProperty("socketTimeout", String.valueOf(timeoutMs));
        hikari.setMaximumPoolSize(4);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(timeoutMs);
        hikari.setKeepaliveTime(60_000);
        this.pool = new HikariDataSource(hikari);
    }

    public Connection getConnection() throws SQLException {
        return pool.getConnection();
    }

    public void close() {
        pool.close();
    }

    /** Crée les tables qui manquent. Une table déjà là ne demande aucun droit CREATE. */
    public void createTables() throws SQLException {
        try (Connection c = getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + AUCTIONS + " ("
                + "id BIGINT NOT NULL AUTO_INCREMENT,"
                + "seller_uuid CHAR(36) NOT NULL,"
                + "seller_name VARCHAR(32) NOT NULL,"
                + "item_serialized MEDIUMTEXT NOT NULL,"
                + "price DOUBLE NOT NULL,"
                + "list_timestamp BIGINT NOT NULL,"
                + "expire_timestamp BIGINT NOT NULL,"
                + "type VARCHAR(8) NOT NULL DEFAULT 'SALE',"
                + "highest_bid DOUBLE NULL,"
                + "highest_bidder_uuid CHAR(36) NULL,"
                + "highest_bidder_name VARCHAR(32) NULL,"
                + "claim_token CHAR(36) NULL,"
                + "claim_kind VARCHAR(8) NULL,"
                + "claimed_by VARCHAR(32) NULL,"
                + "claimed_at BIGINT NULL,"
                + "PRIMARY KEY (id),"
                + "KEY idx_type_expire (type, expire_timestamp),"
                + "KEY idx_seller (seller_uuid),"
                + "KEY idx_claim (claim_token)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
            // Une annonce n'est vendue qu'une fois : la clé unique sur original_auction_id le garantit aussi en base.
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + SOLD + " ("
                + "id BIGINT NOT NULL AUTO_INCREMENT,"
                + "original_auction_id BIGINT NULL,"
                + "seller_uuid CHAR(36) NOT NULL,"
                + "seller_name VARCHAR(32) NOT NULL,"
                + "buyer_uuid CHAR(36) NOT NULL,"
                + "buyer_name VARCHAR(32) NOT NULL,"
                + "item_name TEXT NOT NULL,"
                + "item_serialized MEDIUMTEXT NOT NULL,"
                + "price DOUBLE NOT NULL,"
                + "sold_timestamp BIGINT NOT NULL,"
                + "type VARCHAR(8) NOT NULL DEFAULT 'SALE',"
                + "seller_notified TINYINT NOT NULL DEFAULT 1,"
                + "PRIMARY KEY (id),"
                + "UNIQUE KEY uq_original (original_auction_id),"
                + "KEY idx_seller (seller_uuid, sold_timestamp),"
                + "KEY idx_buyer (buyer_uuid),"
                + "KEY idx_notified (seller_uuid, seller_notified)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + EXPIRED + " ("
                + "id BIGINT NOT NULL AUTO_INCREMENT,"
                + "original_auction_id BIGINT NOT NULL,"
                + "owner_uuid CHAR(36) NOT NULL,"
                + "owner_name VARCHAR(32) NOT NULL,"
                + "item_serialized MEDIUMTEXT NOT NULL,"
                + "expiration_timestamp BIGINT NOT NULL,"
                + "claimed TINYINT NOT NULL DEFAULT 0,"
                + "cancel_reason TEXT NULL,"
                + "PRIMARY KEY (id),"
                + "UNIQUE KEY uq_original (original_auction_id),"
                + "KEY idx_owner (owner_uuid, claimed)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + WANTED + " ("
                + "id BIGINT NOT NULL AUTO_INCREMENT,"
                + "owner_uuid CHAR(36) NOT NULL,"
                + "owner_name VARCHAR(32) NOT NULL,"
                + "text VARCHAR(512) NOT NULL,"
                + "created_at BIGINT NOT NULL,"
                + "expires_at BIGINT NOT NULL,"
                + "PRIMARY KEY (id),"
                + "KEY idx_expires (expires_at),"
                + "KEY idx_owner (owner_uuid)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
            // Journal des mises acceptées : chaque mise payée y laisse une ligne écrite dans la même transaction que
            // l'enchère, ce qui permet de savoir à coup sûr si une mise a été prise quand la base n'a pas répondu.
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + BIDS + " ("
                + "id BIGINT NOT NULL AUTO_INCREMENT,"
                + "auction_id BIGINT NOT NULL,"
                + "bidder_uuid CHAR(36) NOT NULL,"
                + "bidder_name VARCHAR(32) NOT NULL,"
                + "amount DOUBLE NOT NULL,"
                + "replaced_uuid CHAR(36) NULL,"
                + "replaced_amount DOUBLE NULL,"
                + "token CHAR(36) NOT NULL,"
                + "server VARCHAR(32) NOT NULL,"
                + "placed_at BIGINT NOT NULL,"
                + "PRIMARY KEY (id),"
                + "UNIQUE KEY uq_token (token),"
                + "KEY idx_auction (auction_id)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + STATE + " ("
                + "k VARCHAR(64) NOT NULL,"
                + "v VARCHAR(255) NULL,"
                + "PRIMARY KEY (k)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
        }
    }

    /**
     * Reprend une seule fois, pour tout le réseau, la base SQLite de ce serveur (annonces, ventes, invendus,
     * recherches), en gardant les identifiants. Le premier serveur qui démarre en mode réseau pose le drapeau
     * {@code import_sqlite} dans la même transaction que la copie : les suivants n'importent rien.
     *
     * @return nombre de lignes copiées, ou -1 si l'importation avait déjà été faite
     */
    public int importSqliteOnce(File sqliteFile, String serverName) throws SQLException {
        try (Connection c = getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO " + STATE + " (k, v) VALUES ('import_sqlite', ?)")) {
                    ps.setString(1, serverName + " " + System.currentTimeMillis());
                    if (ps.executeUpdate() == 0) {
                        c.rollback();
                        return -1;
                    }
                }
                int copied = 0;
                if (sqliteFile.isFile()) {
                    try (Connection lite = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile.getAbsolutePath())) {
                        copied += copyAuctions(lite, c);
                        copied += copySold(lite, c);
                        copied += copyExpired(lite, c);
                        copied += copyWanted(lite, c);
                    }
                }
                c.commit();
                return copied;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    private static boolean hasTable(Connection lite, String table) throws SQLException {
        try (PreparedStatement ps = lite.prepareStatement("SELECT name FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Colonne lue telle quelle si elle existe dans l'ancienne base, sinon remplacée par une valeur par défaut
     * (bases créées avant l'ajout des enchères, des raisons d'annulation ou du récapitulatif des ventes).
     */
    private static String col(Connection lite, String table, String column, String fallback) throws SQLException {
        try (Statement st = lite.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return column;
                }
            }
        }
        return fallback + " AS " + column;
    }

    private int copyAuctions(Connection lite, Connection c) throws SQLException {
        if (!hasTable(lite, "auctions")) return 0;
        int n = 0;
        try (Statement st = lite.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, seller_uuid, seller_name, item_serialized, price, list_timestamp, expire_timestamp, "
                 + col(lite, "auctions", "type", "'SALE'") + ", " + col(lite, "auctions", "highest_bid", "NULL") + ", "
                 + col(lite, "auctions", "highest_bidder_uuid", "NULL") + ", " + col(lite, "auctions", "highest_bidder_name", "NULL")
                 + " FROM auctions");
             PreparedStatement ps = c.prepareStatement("INSERT INTO " + AUCTIONS + " (id, seller_uuid, seller_name, item_serialized, price, "
                 + "list_timestamp, expire_timestamp, type, highest_bid, highest_bidder_uuid, highest_bidder_name) VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            while (rs.next()) {
                ps.setLong(1, rs.getLong(1));
                ps.setString(2, rs.getString(2));
                ps.setString(3, rs.getString(3));
                ps.setString(4, rs.getString(4));
                ps.setDouble(5, rs.getDouble(5));
                ps.setLong(6, rs.getLong(6));
                ps.setLong(7, rs.getLong(7));
                String type = rs.getString(8);
                ps.setString(8, type == null ? "SALE" : type);
                double bid = rs.getDouble(9);
                if (rs.wasNull()) {
                    ps.setNull(9, Types.DOUBLE);
                } else {
                    ps.setDouble(9, bid);
                }
                ps.setString(10, rs.getString(10));
                ps.setString(11, rs.getString(11));
                ps.executeUpdate();
                n++;
            }
        }
        return n;
    }

    private int copySold(Connection lite, Connection c) throws SQLException {
        if (!hasTable(lite, "sold_items")) return 0;
        int n = 0;
        // INSERT IGNORE : une vente déjà en double dans l'ancienne base ne bloque pas l'importation (clé unique)
        try (Statement st = lite.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, original_auction_id, seller_uuid, seller_name, buyer_uuid, buyer_name, item_name, "
                 + "item_serialized, price, sold_timestamp, " + col(lite, "sold_items", "type", "'SALE'") + ", "
                 + col(lite, "sold_items", "seller_notified", "1") + " FROM sold_items");
             PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO " + SOLD + " (id, original_auction_id, seller_uuid, seller_name, "
                 + "buyer_uuid, buyer_name, item_name, item_serialized, price, sold_timestamp, type, seller_notified) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            while (rs.next()) {
                ps.setLong(1, rs.getLong(1));
                long original = rs.getLong(2);
                if (rs.wasNull()) {
                    ps.setNull(2, Types.BIGINT);
                } else {
                    ps.setLong(2, original);
                }
                for (int i = 3; i <= 8; i++) {
                    ps.setString(i, rs.getString(i));
                }
                ps.setDouble(9, rs.getDouble(9));
                ps.setLong(10, rs.getLong(10));
                String type = rs.getString(11);
                ps.setString(11, type == null ? "SALE" : type);
                ps.setInt(12, rs.getInt(12));
                if (ps.executeUpdate() > 0) {
                    n++;
                } else {
                    logger.warning("Importation : vente #" + rs.getLong(1) + " ignorée (annonce " + original + " déjà vendue).");
                }
            }
        }
        return n;
    }

    private int copyExpired(Connection lite, Connection c) throws SQLException {
        if (!hasTable(lite, "expired_items")) return 0;
        int n = 0;
        try (Statement st = lite.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, original_auction_id, owner_uuid, owner_name, item_serialized, expiration_timestamp, "
                 + "claimed, " + col(lite, "expired_items", "cancel_reason", "NULL") + " FROM expired_items");
             PreparedStatement ps = c.prepareStatement("INSERT INTO " + EXPIRED + " (id, original_auction_id, owner_uuid, owner_name, "
                 + "item_serialized, expiration_timestamp, claimed, cancel_reason) VALUES (?,?,?,?,?,?,?,?)")) {
            while (rs.next()) {
                ps.setLong(1, rs.getLong(1));
                ps.setLong(2, rs.getLong(2));
                ps.setString(3, rs.getString(3));
                ps.setString(4, rs.getString(4));
                ps.setString(5, rs.getString(5));
                ps.setLong(6, rs.getLong(6));
                ps.setInt(7, rs.getBoolean(7) ? 1 : 0);
                ps.setString(8, rs.getString(8));
                ps.executeUpdate();
                n++;
            }
        }
        return n;
    }

    private int copyWanted(Connection lite, Connection c) throws SQLException {
        if (!hasTable(lite, "wanted_requests")) return 0;
        int n = 0;
        try (Statement st = lite.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, owner_uuid, owner_name, text, created_at, expires_at FROM wanted_requests");
             PreparedStatement ps = c.prepareStatement("INSERT INTO " + WANTED + " (id, owner_uuid, owner_name, text, created_at, expires_at) "
                 + "VALUES (?,?,?,?,?,?)")) {
            while (rs.next()) {
                ps.setLong(1, rs.getLong(1));
                ps.setString(2, rs.getString(2));
                ps.setString(3, rs.getString(3));
                ps.setString(4, rs.getString(4));
                ps.setLong(5, rs.getLong(5));
                ps.setLong(6, rs.getLong(6));
                ps.executeUpdate();
                n++;
            }
        }
        return n;
    }
}
