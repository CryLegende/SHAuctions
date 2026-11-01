package org.Cry_Legende.sHAuctions.wanted;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.database.DatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Table {@code wanted_requests} de la base SQLite de SHAuctions. Même connexion partagée que les autres DAO
 * (seuls les statements sont fermés), appels sur le thread principal.
 */
public final class WantedStore {

    private final SHAuctions plugin;
    private final DatabaseManager db;

    public WantedStore(SHAuctions plugin) {
        this.plugin = plugin;
        this.db = plugin.getDatabaseManager();
        createTable();
    }

    private void createTable() {
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

    /** Ajoute une recherche ; renvoie son id, ou -1 en cas d'erreur. */
    public long insert(UUID owner, String ownerName, String text, long createdAt, long expiresAt) {
        String sql = "INSERT INTO wanted_requests(owner_uuid, owner_name, text, created_at, expires_at) VALUES(?,?,?,?,?)";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, owner.toString());
            ps.setString(2, ownerName);
            ps.setString(3, text);
            ps.setLong(4, createdAt);
            ps.setLong(5, expiresAt);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Impossible d'enregistrer la recherche de " + ownerName, e);
            return -1;
        }
    }

    public boolean delete(long id) {
        try (PreparedStatement ps = db.getConnection().prepareStatement("DELETE FROM wanted_requests WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Impossible de supprimer la recherche #" + id, e);
            return false;
        }
    }

    public WantedRequest get(long id) {
        try (PreparedStatement ps = db.getConnection().prepareStatement("SELECT * FROM wanted_requests WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Impossible de lire la recherche #" + id, e);
            return null;
        }
    }

    /** Recherches encore valides, la plus récente d'abord. */
    public List<WantedRequest> listActive(long now) {
        List<WantedRequest> out = new ArrayList<>();
        try (PreparedStatement ps = db.getConnection().prepareStatement(
            "SELECT * FROM wanted_requests WHERE expires_at > ? ORDER BY created_at DESC, id DESC")) {
            ps.setLong(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Impossible de lire les recherches", e);
        }
        return out;
    }

    /** Recherches encore valides d'un joueur, la plus récente d'abord. */
    public List<WantedRequest> listActiveByOwner(UUID owner, long now) {
        List<WantedRequest> out = new ArrayList<>();
        try (PreparedStatement ps = db.getConnection().prepareStatement(
            "SELECT * FROM wanted_requests WHERE owner_uuid = ? AND expires_at > ? ORDER BY created_at DESC, id DESC")) {
            ps.setString(1, owner.toString());
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Impossible de lire les recherches du joueur", e);
        }
        return out;
    }

    public int countActive(UUID owner, long now) {
        try (PreparedStatement ps = db.getConnection().prepareStatement(
            "SELECT COUNT(*) FROM wanted_requests WHERE owner_uuid = ? AND expires_at > ?")) {
            ps.setString(1, owner.toString());
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Impossible de compter les recherches", e);
            return Integer.MAX_VALUE;
        }
    }

    /** Supprime les recherches expirées ; renvoie le nombre supprimé. */
    public int purgeExpired(long now) {
        try (PreparedStatement ps = db.getConnection().prepareStatement("DELETE FROM wanted_requests WHERE expires_at <= ?")) {
            ps.setLong(1, now);
            return ps.executeUpdate();
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
