package org.Cry_Legende.sHAuctions.database;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.database.dao.AuctionDAO;
import org.Cry_Legende.sHAuctions.database.dao.ExpiredItemDAO;
import org.Cry_Legende.sHAuctions.database.dao.SoldItemDAO;
import org.Cry_Legende.sHAuctions.database.impl.SQLiteAuctionDAO;
import org.Cry_Legende.sHAuctions.database.impl.SQLiteExpiredItemDAO;
import org.Cry_Legende.sHAuctions.database.impl.SQLiteSoldItemDAO;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;

public class DatabaseManager {

    private final SHAuctions plugin;
    private SQLiteConnector connector;

    private AuctionDAO auctionDAO;
    private SoldItemDAO soldItemDAO;
    private ExpiredItemDAO expiredItemDAO;

    public DatabaseManager(SHAuctions plugin) {
        this.plugin = plugin;
    }

    public boolean initialize() {
        String dbFileName = plugin.getConfigManager().getString("database.filename", "shauctions.db");
        File dbFile = new File(plugin.getDataFolder(), dbFileName);

        // SQLiteConnector établit la connexion dans son constructeur maintenant
        this.connector = new SQLiteConnector(plugin, dbFile.getAbsolutePath());

        try {
            // Vérifier que la connexion initiale a bien fonctionné
            Connection initialConnTest = connector.getConnection(); // Force la vérification/établissement
            if (initialConnTest == null || initialConnTest.isClosed()) {
                plugin.getLogger().severe("Failed to establish a valid initial database connection through connector.");
                return false;
            }
            plugin.getLogger().info("Database connector initialized and initial connection seems valid for: " + dbFileName);

            createTables();

            this.auctionDAO = new SQLiteAuctionDAO(this);
            this.soldItemDAO = new SQLiteSoldItemDAO(this);
            this.expiredItemDAO = new SQLiteExpiredItemDAO(this);

            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not initialize the database (tables or DAOs)!", e);
            return false;
        }
    }

    private void createTables() throws SQLException {
        Connection conn = null;
        Statement stmt = null;
        try {
            conn = getConnection(); // Obtient LA connexion gérée par SQLiteConnector
            stmt = conn.createStatement();

            String auctionsTableSQL = "CREATE TABLE IF NOT EXISTS auctions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "seller_uuid TEXT NOT NULL," +
                "seller_name TEXT NOT NULL," +
                "item_serialized TEXT NOT NULL," +
                "price REAL NOT NULL," +
                "list_timestamp INTEGER NOT NULL," +
                "expire_timestamp INTEGER NOT NULL," +
                "type TEXT NOT NULL DEFAULT 'SALE'," +
                "highest_bid REAL," +
                "highest_bidder_uuid TEXT," +
                "highest_bidder_name TEXT," +
                "UNIQUE (seller_uuid, item_serialized, list_timestamp)" +
                ");";
            stmt.execute(auctionsTableSQL);

            String soldItemsTableSQL = "CREATE TABLE IF NOT EXISTS sold_items (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "original_auction_id INTEGER, " +
                "seller_uuid TEXT NOT NULL," +
                "seller_name TEXT NOT NULL," +
                "buyer_uuid TEXT NOT NULL," +
                "buyer_name TEXT NOT NULL," +
                "item_name TEXT NOT NULL," +
                "item_serialized TEXT NOT NULL," +
                "price REAL NOT NULL," +
                "sold_timestamp INTEGER NOT NULL," +
                "type TEXT NOT NULL DEFAULT 'SALE'" +
                ");";
            stmt.execute(soldItemsTableSQL);

            String expiredItemsTableSQL = "CREATE TABLE IF NOT EXISTS expired_items (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "original_auction_id INTEGER NOT NULL UNIQUE," +
                "owner_uuid TEXT NOT NULL," +
                "owner_name TEXT NOT NULL," +
                "item_serialized TEXT NOT NULL," +
                "expiration_timestamp INTEGER NOT NULL," +
                "claimed BOOLEAN DEFAULT 0," +
                "cancel_reason TEXT" +
                ");";
            stmt.execute(expiredItemsTableSQL);

            // Migration pour les bases de données existantes (prod) : ajoute les colonnes manquantes.
            migrateSchema(conn, stmt);

            // Index pour accélérer les requêtes chaudes (idempotent : IF NOT EXISTS).
            createIndexes(stmt);

            plugin.getLogger().info("Database tables checked/created successfully.");

        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error creating database tables!", e);
            throw e;
        } finally {
            if (stmt != null) {
                try {
                    stmt.close();
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING, "Error closing Statement in createTables", e);
                }
            }
            // NE PAS FERMER 'conn' ici, car elle est gérée par SQLiteConnector
        }
    }

    /**
     * Ajoute les colonnes manquantes aux tables existantes (bases de production déjà créées).
     * {@code CREATE TABLE IF NOT EXISTS} n'ajoute PAS de colonnes à une table existante,
     * donc on inspecte le schéma via PRAGMA table_info et on exécute des ALTER TABLE au besoin.
     */
    private void migrateSchema(Connection conn, Statement stmt) throws SQLException {
        addColumnIfMissing(conn, stmt, "auctions", "type", "TEXT NOT NULL DEFAULT 'SALE'");
        addColumnIfMissing(conn, stmt, "auctions", "highest_bid", "REAL");
        addColumnIfMissing(conn, stmt, "auctions", "highest_bidder_uuid", "TEXT");
        addColumnIfMissing(conn, stmt, "auctions", "highest_bidder_name", "TEXT");
        addColumnIfMissing(conn, stmt, "expired_items", "cancel_reason", "TEXT");
        addColumnIfMissing(conn, stmt, "sold_items", "type", "TEXT NOT NULL DEFAULT 'SALE'");
    }

    /**
     * Crée les index utiles aux requêtes fréquentes. {@code IF NOT EXISTS} => idempotent
     * et sûr au hot-reload.
     */
    private void createIndexes(Statement stmt) throws SQLException {
        // Listing actif / expiration : filtres par type + expire_timestamp (HDV, enchères, expiration).
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_auctions_type_expire ON auctions(type, expire_timestamp);");
        // own_sales et comptage des listings par vendeur.
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_auctions_seller ON auctions(seller_uuid);");
        // Historique par vendeur.
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_sold_seller ON sold_items(seller_uuid);");
        // Invendus par propriétaire (avec état réclamé).
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_expired_owner ON expired_items(owner_uuid, claimed);");
    }

    private void addColumnIfMissing(Connection conn, Statement stmt, String table, String column, String definition) throws SQLException {
        boolean exists = false;
        // Le Statement est fermé lui aussi (seul le ResultSet l'était)
        try (Statement pragma = conn.createStatement();
             java.sql.ResultSet rs = pragma.executeQuery("PRAGMA table_info(" + table + ");")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition + ";");
            plugin.getLogger().info("Schema migration: added column '" + column + "' to table '" + table + "'.");
        }
    }

    public Connection getConnection() throws SQLException {
        if (connector == null) {
            plugin.getLogger().severe("Database connector is not initialized! Attempting to re-initialize DatabaseManager (this is a recovery attempt).");
            // Tentative de récupération très agressive - pourrait être dangereuse si appelée en boucle.
            // En théorie, on ne devrait jamais arriver ici si onEnable a bien fonctionné.
            if (!initialize()) { // Tenter de réinitialiser tout le DatabaseManager
                throw new SQLException("Database connector is null and DatabaseManager re-initialization failed.");
            }
        }
        return connector.getConnection();
    }

    public void close() {
        if (connector != null) {
            connector.closeConnection();
        }
    }

    public AuctionDAO getAuctionDAO() {
        return auctionDAO;
    }

    public SoldItemDAO getSoldItemDAO() {
        return soldItemDAO;
    }

    public ExpiredItemDAO getExpiredItemDAO() {
        return expiredItemDAO;
    }
}
