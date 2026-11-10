package org.Cry_Legende.sHAuctions.wanted;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.database.DatabaseManager;
import org.Cry_Legende.sHAuctions.database.SharedDatabase;

import java.sql.Connection;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Table {@code wanted_requests} de la base SQLite de SHAuctions (même connexion que les autres DAO, jamais fermée),
 * ou {@code shauctions_wanted} de la base partagée en mode réseau. Appels sur le thread principal.
 */
public final class WantedStore {

    private final SHAuctions plugin;
    private final DatabaseManager db;
    // wanted_requests en SQLite, shauctions_wanted dans la base partagée du mode réseau
    private final String table;

    public WantedStore(SHAuctions plugin) {
        this.plugin = plugin;
        this.db = plugin.getDatabaseManager();
        this.table = db.isShared() ? SharedDatabase.WANTED : "wanted_requests";
        createTable();
    }

    private void createTable() {
        if (db.isShared()) {
            return; // table créée avec les autres tables partagées
        }
        try (Statement st = db.getConnection().createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS wanted_requests ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "owner_uuid TEXT NOT NULL,"
                + "owner_name TEXT NOT NULL,"
                + "text TEXT NOT NULL,"
                + "created_at INTEGER NOT NULL,"
                + "expires_at INTEGER NOT NULL)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_wanted_expires ON wanted_requests(expires_at)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_wanted_owner ON wanted_requests(owner_uuid)");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Impossible de créer la table wanted_requests", e);
        }
    }

    /** Requête sur une connexion empruntée puis rendue (jamais fermée en SQLite, rendue au pool en MariaDB). */
    @FunctionalInterface
    private interface Query<T> {
        T run(Connection connection) throws SQLException;
    }

    private <T> T with(Query<T> query) throws SQLException {
        Connection connection = db.borrow();
        try {
            return query.run(connection);
        } finally {
            db.giveBack(connection);
        }
    }

    /** Ajoute une recherche ; renvoie son id, ou -1 en cas d'erreur. */
    public long insert(UUID owner, String ownerName, String text, long createdAt, long expiresAt) {
        String sql = "INSERT INTO " + table + "(owner_uuid, owner_name, text, created_at, expires_at) VALUES(?,?,?,?,?)";
        try {
            return with(c -> {
                try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, owner.toString());
                    ps.setString(2, ownerName);
                    ps.setString(3, text);
                    ps.setLong(4, createdAt);
                    ps.setLong(5, expiresAt);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        return keys.next() ? keys.getLong(1) : -1L;
                    }
                }
            });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Impossible d'enregistrer la recherche de " + ownerName, e);
            return -1;
        }
    }

    public boolean delete(long id) {
        try {
            return with(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE id = ?")) {
                    ps.setLong(1, id);
                    return ps.executeUpdate() > 0;
                }
            });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Impossible de supprimer la recherche #" + id, e);
            return false;
        }
    }

    public WantedRequest get(long id) {
        try {
            return with(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT * FROM " + table + " WHERE id = ?")) {
                    ps.setLong(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? read(rs) : null;
                    }
                }
            });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Impossible de lire la recherche #" + id, e);
            return null;
        }
    }

    /** Recherches encore valides, la plus récente d'abord. */
    public List<WantedRequest> listActive(long now) {
        try {
            return with(c -> {
                List<WantedRequest> out = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM " + table + " WHERE expires_at > ? ORDER BY created_at DESC, id DESC")) {
                    ps.setLong(1, now);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.add(read(rs));
                        }
                    }
                }
                return out;
            });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Impossible de lire les recherches", e);
            return new ArrayList<>();
        }
    }

    /** Recherches encore valides d'un joueur, la plus récente d'abord. */
    public List<WantedRequest> listActiveByOwner(UUID owner, long now) {
        try {
            return with(c -> {
                List<WantedRequest> out = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM " + table + " WHERE owner_uuid = ? AND expires_at > ? ORDER BY created_at DESC, id DESC")) {
                    ps.setString(1, owner.toString());
                    ps.setLong(2, now);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.add(read(rs));
                        }
                    }
                }
                return out;
            });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Impossible de lire les recherches du joueur", e);
            return new ArrayList<>();
        }
    }

    public int countActive(UUID owner, long now) {
        try {
            return with(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM " + table + " WHERE owner_uuid = ? AND expires_at > ?")) {
                    ps.setString(1, owner.toString());
                    ps.setLong(2, now);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getInt(1) : 0;
                    }
                }
            });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Impossible de compter les recherches", e);
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Heure de publication de la recherche la plus récente du joueur encore en base (0 sans recherche) : en mode
     * réseau, le délai entre deux publications vaut sur tous les serveurs.
     */
    public long lastCreated(UUID owner) {
        try {
            return with(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT MAX(created_at) FROM " + table + " WHERE owner_uuid = ?")) {
                    ps.setString(1, owner.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getLong(1) : 0L;
                    }
                }
            });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Impossible de lire la dernière recherche du joueur", e);
            return 0L;
        }
    }

    /** Supprime les recherches expirées ; renvoie le nombre supprimé. */
    public int purgeExpired(long now) {
        try {
            return with(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE expires_at <= ?")) {
                    ps.setLong(1, now);
                    return ps.executeUpdate();
                }
            });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Impossible de purger les recherches expirées", e);
            return 0;
        }
    }

    private static WantedRequest read(ResultSet rs) throws SQLException {
        return new WantedRequest(rs.getLong("id"), UUID.fromString(rs.getString("owner_uuid")), rs.getString("owner_name"),
            rs.getString("text"), rs.getLong("created_at"), rs.getLong("expires_at"));
    }
}
