package org.Cry_Legende.sHAuctions.database;

import org.Cry_Legende.sHAuctions.SHAuctions;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;

public class SQLiteConnector {

    private final SHAuctions plugin;
    private Connection connection; // L'unique instance de connexion
    private final String dbPath;
    private final Object lock = new Object(); // Pour la synchronisation de l'accès à la connexion

    public SQLiteConnector(SHAuctions plugin, String dbPath) {
        this.plugin = plugin;
        this.dbPath = dbPath;
        // Tenter d'établir la connexion dès la création du connector
        try {
            establishConnection();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Initial SQLite connection failed during connector construction!", e);
            // L'état de la connexion sera null, getConnection tentera de recréer si nécessaire.
        }
    }

    /**
     * Méthode interne pour établir ou réétablir la connexion.
     */
    private void establishConnection() throws SQLException {
        plugin.getLogger().info("[DB Connector] Attempting to establish SQLite connection to: " + dbPath);
        try {
            Class.forName("org.sqlite.JDBC");
            String url = "jdbc:sqlite:" + dbPath;
            connection = DriverManager.getConnection(url);
            // Pour SQLite, setAutoCommit(true) est souvent le défaut et suffisant pour des opérations simples.
            // Si vous avez besoin de transactions explicites, vous gérerez conn.setAutoCommit(false) et conn.commit()/rollback()
            // dans les méthodes DAO spécifiques qui en ont besoin.
            // connection.setAutoCommit(true);
            applyPragmas();
            plugin.getLogger().info("[DB Connector] SQLite connection established successfully. AutoCommit: " + connection.getAutoCommit());
        } catch (ClassNotFoundException e) {
            plugin.getLogger().log(Level.SEVERE, "SQLite JDBC driver not found!", e);
            throw new SQLException("SQLite JDBC driver not found", e);
        } catch (SQLException e) {
            // Garder la connexion précédente si elle existe et que la nouvelle tentative échoue,
            // sauf si la connexion précédente était déjà invalide.
            // Mais si on est ici, c'est qu'on voulait une nouvelle connexion.
            connection = null; // S'assurer qu'elle est nulle en cas d'échec d'établissement
            plugin.getLogger().log(Level.SEVERE, "Could not connect to SQLite database at " + dbPath, e);
            throw e;
        }
    }

    /**
     * Applique les PRAGMAs SQLite sur la connexion courante.
     * <ul>
     *     <li>{@code busy_timeout} : attendre au lieu d'échouer si la base est verrouillée
     *         (connexion unique partagée entre le thread principal et la tâche d'expiration async).</li>
     *     <li>{@code journal_mode=WAL} : lectures/écritures concurrentes sans blocage mutuel.</li>
     *     <li>{@code synchronous=NORMAL} : bon compromis durabilité/performance avec WAL.</li>
     *     <li>{@code foreign_keys=ON} : intégrité référentielle.</li>
     * </ul>
     */
    private void applyPragmas() {
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA busy_timeout = 5000;");
            st.execute("PRAGMA journal_mode = WAL;");
            st.execute("PRAGMA synchronous = NORMAL;");
            st.execute("PRAGMA foreign_keys = ON;");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "[DB Connector] Failed to apply SQLite PRAGMAs", e);
        }
    }

    /**
     * Récupère la connexion existante.
     * Si la connexion est jugée invalide, tente de la rétablir.
     *
     * @return L'instance de connexion.
     * @throws SQLException si la connexion ne peut pas être obtenue ou rétablie.
     */
    public Connection getConnection() throws SQLException {
        synchronized (lock) {
            if (connection == null || connection.isClosed()) {
                plugin.getLogger().warning("[DB Connector] Connection is null or closed. Attempting to re-establish...");
                // Fermer l'ancienne proprement si elle n'est pas null mais juste fermée (sécurité)
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (SQLException ignored) {
                    }
                }
                try {
                    establishConnection();
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to re-establish SQLite connection!", e);
                    throw e;
                }
            }
            // Vérification finale après tentative de (ré)établissement
            if (connection == null || connection.isClosed()) {
                plugin.getLogger().severe("[DB Connector] FATAL: Failed to obtain a valid SQLite connection.");
                throw new SQLException("SQLite connection is not available.");
            }
            return connection;
        }
    }

    /**
     * Ferme la connexion à la base de données.
     * À appeler lors de la désactivation du plugin.
     */
    public void closeConnection() {
        synchronized (lock) {
            try {
                if (connection != null && !connection.isClosed()) {
                    plugin.getLogger().info("[DB Connector] Closing SQLite connection.");
                    connection.close();
                    if (connection.isClosed()) {
                        plugin.getLogger().info("[DB Connector] SQLite connection successfully closed.");
                    } else {
                        plugin.getLogger().warning("[DB Connector] SQLite connection.close() called, but isClosed() is still false!");
                    }
                } else {
                    plugin.getLogger().info("[DB Connector] SQLite connection was already null or closed upon calling closeConnection().");
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Error closing SQLite connection", e);
            } finally {
                connection = null; // S'assurer que la référence est nulle pour forcer une recréation si besoin.
            }
        }
    }
}
