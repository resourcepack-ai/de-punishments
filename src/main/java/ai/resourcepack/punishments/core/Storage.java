package ai.resourcepack.punishments.core;

import ai.resourcepack.punishments.PunishmentsPlugin;
import ai.resourcepack.punishments.api.Punishment;

import ai.resourcepack.punishments.api.Punishment.Type;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The record, in SQLite (a file in the plugin folder, the default) or MySQL
 * (shared by several servers). One connection, used only from Punishments'
 * database thread, so nothing here is synchronised and every call is in the
 * order it was asked for.
 */
public final class Storage {

    public record PlayerRecord(UUID uuid, String name, String ip, long firstSeen, long lastSeen) {}

    private final boolean mysql;
    private final String url;
    private final String user;
    private final String password;
    private final String players;
    private final String punishments;
    private Connection connection;

    public Storage(ConfigurationSection cfg, File dataFolder) {
        String type = cfg.getString("type", "sqlite").toLowerCase(Locale.ROOT);
        this.mysql = type.equals("mysql") || type.equals("mariadb");
        String prefix = cfg.getString("table-prefix", "punishments_").replaceAll("[^A-Za-z0-9_]", "");
        this.players = prefix + "players";
        this.punishments = prefix + "punishments";
        if (mysql) {
            ConfigurationSection m = cfg.getConfigurationSection("mysql");
            String host = m == null ? "localhost" : m.getString("host", "localhost");
            int port = m == null ? 3306 : m.getInt("port", 3306);
            String db = m == null ? "minecraft" : m.getString("database", "minecraft");
            String params = m == null ? "" : m.getString("params", "autoReconnect=true");
            this.url = "jdbc:" + (type.equals("mariadb") ? "mariadb" : "mysql") + "://" + host + ":" + port + "/" + db
                    + (params.isEmpty() ? "" : "?" + params);
            this.user = m == null ? "root" : m.getString("username", "root");
            this.password = m == null ? "" : m.getString("password", "");
        } else {
            File file = new File(dataFolder, cfg.getString("sqlite-file", "punishments.db"));
            this.url = "jdbc:sqlite:" + file.getAbsolutePath();
            this.user = null;
            this.password = null;
        }
    }

    public void open() throws SQLException {
        try {
            Class.forName(mysql ? "com.mysql.cj.jdbc.Driver" : "org.sqlite.JDBC");
        } catch (ClassNotFoundException ignored) {
            // DriverManager may still find it through the service loader.
        }
        connection();
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + players + " ("
                    + "uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(16) NOT NULL, name_lower VARCHAR(16) NOT NULL, "
                    + "ip VARCHAR(64), first_seen BIGINT NOT NULL, last_seen BIGINT NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + punishments + " ("
                    + (mysql ? "id BIGINT AUTO_INCREMENT PRIMARY KEY, " : "id INTEGER PRIMARY KEY AUTOINCREMENT, ")
                    + "type VARCHAR(8) NOT NULL, target VARCHAR(36) NOT NULL, target_name VARCHAR(16) NOT NULL, ip VARCHAR(64), "
                    + "reason TEXT NOT NULL, actor VARCHAR(36), actor_name VARCHAR(32) NOT NULL, created BIGINT NOT NULL, "
                    + "expires BIGINT NOT NULL, active INT NOT NULL, revoked_by VARCHAR(32), revoked_at BIGINT NOT NULL DEFAULT 0, "
                    + "silent INT NOT NULL DEFAULT 0, seen INT NOT NULL DEFAULT 1)");
            index(s, "idx_" + punishments + "_target", punishments, "target");
            index(s, "idx_" + punishments + "_ip", punishments, "ip");
            index(s, "idx_" + players + "_name", players, "name_lower");
            // Whether the player held the exempt permission when last seen.
            // A permission cannot be asked of an offline player without a
            // permissions plugin's API, so it is remembered from their last
            // session instead. Added to tables made before it existed.
            try {
                s.executeUpdate("ALTER TABLE " + players + " ADD COLUMN exempt INT NOT NULL DEFAULT 0");
            } catch (SQLException exists) {
                // Already there: neither SQLite nor MySQL has ADD COLUMN IF NOT EXISTS.
            }
        }
    }

    private void index(Statement s, String name, String table, String column) throws SQLException {
        if (mysql) {
            try {
                s.executeUpdate("CREATE INDEX " + name + " ON " + table + " (" + column + ")");
            } catch (SQLException exists) {
                // MySQL has no IF NOT EXISTS for indexes; a duplicate is fine.
            }
        } else {
            s.executeUpdate("CREATE INDEX IF NOT EXISTS " + name + " ON " + table + " (" + column + ")");
        }
    }

    private Connection connection() throws SQLException {
        if (connection == null || connection.isClosed() || (mysql && !connection.isValid(2))) {
            connection = user == null ? DriverManager.getConnection(url) : DriverManager.getConnection(url, user, password);
        }
        return connection;
    }

    public void close() {
        try {
            if (connection != null) connection.close();
        } catch (SQLException ignored) {
            // shutting down anyway
        }
    }

    // ---------------------------------------------------------------- players

    public void seen(UUID uuid, String name, String ip, long now) throws SQLException {
        String sql = mysql
                ? "INSERT INTO " + players + " (uuid, name, name_lower, ip, first_seen, last_seen) VALUES (?,?,?,?,?,?) "
                  + "ON DUPLICATE KEY UPDATE name=VALUES(name), name_lower=VALUES(name_lower), ip=VALUES(ip), last_seen=VALUES(last_seen)"
                : "INSERT INTO " + players + " (uuid, name, name_lower, ip, first_seen, last_seen) VALUES (?,?,?,?,?,?) "
                  + "ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, name_lower=excluded.name_lower, ip=excluded.ip, last_seen=excluded.last_seen";
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setString(3, name.toLowerCase(Locale.ROOT));
            ps.setString(4, ip);
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.executeUpdate();
        }
    }

    public Optional<PlayerRecord> player(String name) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT * FROM " + players + " WHERE name_lower=? ORDER BY last_seen DESC LIMIT 1")) {
            ps.setString(1, name.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(playerRow(rs)) : Optional.empty();
            }
        }
    }

    public Optional<PlayerRecord> player(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement("SELECT * FROM " + players + " WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(playerRow(rs)) : Optional.empty();
            }
        }
    }

    /** Records whether this player holds the exempt permission, as of now. A player never seen is not touched. */
    public void exempt(UUID uuid, boolean exempt) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement("UPDATE " + players + " SET exempt=? WHERE uuid=?")) {
            ps.setInt(1, exempt ? 1 : 0);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    /** Whether this player held the exempt permission when last seen. */
    public boolean exempt(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement("SELECT exempt FROM " + players + " WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) != 0;
            }
        }
    }

    /** Names starting with a prefix, for tab completion of players who are not online. */
    public List<String> names(String prefix, int limit) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT name FROM " + players + " WHERE name_lower LIKE ? ORDER BY last_seen DESC LIMIT ?")) {
            ps.setString(1, prefix.toLowerCase(Locale.ROOT).replace("%", "") + "%");
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static PlayerRecord playerRow(ResultSet rs) throws SQLException {
        return new PlayerRecord(UUID.fromString(rs.getString("uuid")), rs.getString("name"), rs.getString("ip"),
                rs.getLong("first_seen"), rs.getLong("last_seen"));
    }

    // ---------------------------------------------------------------- punishments

    public long insert(Punishment p) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement("INSERT INTO " + punishments
                        + " (type, target, target_name, ip, reason, actor, actor_name, created, expires, active, revoked_by, revoked_at, silent, seen)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, p.type().key());
            ps.setString(2, p.target().toString());
            ps.setString(3, p.targetName());
            ps.setString(4, p.ip());
            ps.setString(5, p.reason());
            ps.setString(6, p.actor() == null ? null : p.actor().toString());
            ps.setString(7, p.actorName());
            ps.setLong(8, p.created());
            ps.setLong(9, p.expires());
            ps.setInt(10, p.active() ? 1 : 0);
            ps.setString(11, null);
            ps.setLong(12, 0);
            ps.setInt(13, p.silent() ? 1 : 0);
            ps.setInt(14, p.seen() ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        }
    }

    public Optional<Punishment> get(long id) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement("SELECT * FROM " + punishments + " WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(row(rs)) : Optional.empty();
            }
        }
    }

    /** The newest ban or mute in force on a player (or, for a ban, on their address). */
    public Optional<Punishment> inForce(Type type, UUID target, String ip, long now) throws SQLException {
        boolean byIp = type == Type.BAN && ip != null && !ip.isEmpty();
        String sql = "SELECT * FROM " + punishments + " WHERE type=? AND active=1 AND (expires=-1 OR expires>?) AND "
                + (byIp ? "(target=? OR ip=?)" : "target=?") + " ORDER BY created DESC LIMIT 1";
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            ps.setString(1, type.key());
            ps.setLong(2, now);
            ps.setString(3, target.toString());
            if (byIp) ps.setString(4, ip);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(row(rs)) : Optional.empty();
            }
        }
    }

    public List<Punishment> history(UUID target, int limit) throws SQLException {
        List<Punishment> out = new ArrayList<>();
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT * FROM " + punishments + " WHERE target=? ORDER BY created DESC LIMIT ?")) {
            ps.setString(1, target.toString());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(row(rs));
            }
        }
        return out;
    }

    /** Warnings that still count: not revoked, and newer than {@code since}. */
    public int warnings(UUID target, long since) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT COUNT(*) FROM " + punishments + " WHERE target=? AND type='warn' AND active=1 AND created>=?")) {
            ps.setString(1, target.toString());
            ps.setLong(2, since);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public int count(UUID target) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement("SELECT COUNT(*) FROM " + punishments + " WHERE target=?")) {
            ps.setString(1, target.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public List<Punishment> unseenWarnings(UUID target) throws SQLException {
        List<Punishment> out = new ArrayList<>();
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT * FROM " + punishments + " WHERE target=? AND type='warn' AND active=1 AND seen=0 ORDER BY created ASC")) {
            ps.setString(1, target.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(row(rs));
            }
        }
        return out;
    }

    public void markSeen(long id) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement("UPDATE " + punishments + " SET seen=1 WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public boolean revoke(long id, String by, long now) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "UPDATE " + punishments + " SET active=0, revoked_by=?, revoked_at=? WHERE id=? AND active=1")) {
            ps.setString(1, by);
            ps.setLong(2, now);
            ps.setLong(3, id);
            return ps.executeUpdate() > 0;
        }
    }

    /** Lifts every ban (or mute) in force on a player. How many there were. */
    public int revokeAll(Type type, UUID target, String by, long now) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement("UPDATE " + punishments
                + " SET active=0, revoked_by=?, revoked_at=? WHERE type=? AND target=? AND active=1 AND (expires=-1 OR expires>?)")) {
            ps.setString(1, by);
            ps.setLong(2, now);
            ps.setString(3, type.key());
            ps.setString(4, target.toString());
            ps.setLong(5, now);
            return ps.executeUpdate();
        }
    }

    /** The newest warning still counting, for /unwarn. */
    public Optional<Punishment> latestWarning(UUID target) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT * FROM " + punishments + " WHERE target=? AND type='warn' AND active=1 ORDER BY created DESC LIMIT 1")) {
            ps.setString(1, target.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(row(rs)) : Optional.empty();
            }
        }
    }

    private static Punishment row(ResultSet rs) throws SQLException {
        String actor = rs.getString("actor");
        return new Punishment(
                rs.getLong("id"),
                Type.valueOf(rs.getString("type").toUpperCase(Locale.ROOT)),
                UUID.fromString(rs.getString("target")),
                rs.getString("target_name"),
                rs.getString("ip"),
                rs.getString("reason"),
                actor == null ? null : UUID.fromString(actor),
                rs.getString("actor_name"),
                rs.getLong("created"),
                rs.getLong("expires"),
                rs.getInt("active") == 1,
                rs.getString("revoked_by"),
                rs.getLong("revoked_at"),
                rs.getInt("silent") == 1,
                rs.getInt("seen") == 1);
    }
}
