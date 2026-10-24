package com.blacklauncher.core.db;

import com.blacklauncher.core.util.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite persistence for installed versions, launch history and news cache.
 */
public class Database implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Database.class);

    private Connection connection;

    public void open() throws Exception {
        Class.forName("org.sqlite.JDBC");
        String url = "jdbc:sqlite:" + AppPaths.databaseFile().toAbsolutePath();
        connection = DriverManager.getConnection(url);
        connection.setAutoCommit(true);
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
        }
    }

    /** Close and reopen against current {@link AppPaths#databaseFile()} (after path migration). */
    public synchronized void reopen() throws Exception {
        close();
        open();
        migrate();
        LOG.info("Database reopened at {}", AppPaths.databaseFile());
    }

    public void migrate() throws Exception {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS installed_versions (
                        id TEXT PRIMARY KEY,
                        type TEXT NOT NULL,
                        installed_at INTEGER NOT NULL,
                        last_launched_at INTEGER
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS launch_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        version_id TEXT NOT NULL,
                        started_at INTEGER NOT NULL,
                        exit_code INTEGER,
                        log_path TEXT
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS news_cache (
                        id TEXT PRIMARY KEY,
                        title TEXT NOT NULL,
                        summary TEXT,
                        body TEXT,
                        category TEXT,
                        published_at INTEGER,
                        fetched_at INTEGER NOT NULL
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS mod_favorites (
                        project_id TEXT PRIMARY KEY,
                        slug TEXT,
                        title TEXT NOT NULL,
                        icon_url TEXT,
                        favorited_at INTEGER NOT NULL
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS mod_project_cache (
                        project_id TEXT PRIMARY KEY,
                        slug TEXT,
                        title TEXT,
                        payload_json TEXT NOT NULL,
                        fetched_at INTEGER NOT NULL
                    )
                    """);
        }
    }

    public void upsertModFavorite(String projectId, String slug, String title, String iconUrl) throws Exception {
        String sql = """
                INSERT INTO mod_favorites(project_id, slug, title, icon_url, favorited_at)
                VALUES(?, ?, ?, ?, ?)
                ON CONFLICT(project_id) DO UPDATE SET
                    slug = excluded.slug,
                    title = excluded.title,
                    icon_url = excluded.icon_url,
                    favorited_at = excluded.favorited_at
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setString(2, slug);
            ps.setString(3, title == null ? projectId : title);
            ps.setString(4, iconUrl);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public void removeModFavorite(String projectId) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM mod_favorites WHERE project_id = ?")) {
            ps.setString(1, projectId);
            ps.executeUpdate();
        }
    }

    public boolean isModFavorite(String projectId) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM mod_favorites WHERE project_id = ? LIMIT 1")) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public List<ModFavoriteRecord> listModFavorites() throws Exception {
        List<ModFavoriteRecord> list = new ArrayList<>();
        String sql = "SELECT project_id, slug, title, icon_url, favorited_at FROM mod_favorites ORDER BY favorited_at DESC";
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new ModFavoriteRecord(
                        rs.getString("project_id"),
                        rs.getString("slug"),
                        rs.getString("title"),
                        rs.getString("icon_url"),
                        rs.getLong("favorited_at")
                ));
            }
        }
        return list;
    }

    public void upsertModProjectCache(String projectId, String slug, String title, String payloadJson)
            throws Exception {
        String sql = """
                INSERT INTO mod_project_cache(project_id, slug, title, payload_json, fetched_at)
                VALUES(?, ?, ?, ?, ?)
                ON CONFLICT(project_id) DO UPDATE SET
                    slug = excluded.slug,
                    title = excluded.title,
                    payload_json = excluded.payload_json,
                    fetched_at = excluded.fetched_at
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setString(2, slug);
            ps.setString(3, title);
            ps.setString(4, payloadJson);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public String getModProjectCacheJson(String projectId) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT payload_json FROM mod_project_cache WHERE project_id = ?")) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("payload_json") : null;
            }
        }
    }

    public void upsertInstalledVersion(String id, String type) throws Exception {
        String sql = """
                INSERT INTO installed_versions(id, type, installed_at)
                VALUES(?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET type = excluded.type
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, type);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public void deleteInstalledVersion(String id) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM installed_versions WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    public List<InstalledVersionRecord> listInstalledVersions() throws Exception {
        List<InstalledVersionRecord> list = new ArrayList<>();
        String sql = "SELECT id, type, installed_at, last_launched_at FROM installed_versions ORDER BY installed_at DESC";
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new InstalledVersionRecord(
                        rs.getString("id"),
                        rs.getString("type"),
                        rs.getLong("installed_at"),
                        rs.getObject("last_launched_at") == null ? null : rs.getLong("last_launched_at")
                ));
            }
        }
        return list;
    }

    public void markLaunched(String versionId) throws Exception {
        String sql = "UPDATE installed_versions SET last_launched_at = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, versionId);
            ps.executeUpdate();
        }
    }

    public long insertLaunchLog(String versionId, String logPath) throws Exception {
        String sql = "INSERT INTO launch_logs(version_id, started_at, log_path) VALUES(?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, versionId);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, logPath);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        return -1;
    }

    public void finishLaunchLog(long id, int exitCode) throws Exception {
        String sql = "UPDATE launch_logs SET exit_code = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, exitCode);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public LaunchLogRecord latestLaunchLog() throws Exception {
        String sql = """
                SELECT id, version_id, started_at, exit_code, log_path
                FROM launch_logs ORDER BY started_at DESC LIMIT 1
                """;
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {
                return new LaunchLogRecord(
                        rs.getLong("id"),
                        rs.getString("version_id"),
                        rs.getLong("started_at"),
                        rs.getObject("exit_code") == null ? null : rs.getInt("exit_code"),
                        rs.getString("log_path")
                );
            }
        }
        return null;
    }

    public void replaceNewsCache(List<NewsRecord> news) throws Exception {
        connection.setAutoCommit(false);
        try (Statement st = connection.createStatement()) {
            st.execute("DELETE FROM news_cache");
            String sql = """
                    INSERT INTO news_cache(id, title, summary, body, category, published_at, fetched_at)
                    VALUES(?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                long now = System.currentTimeMillis();
                for (NewsRecord item : news) {
                    ps.setString(1, item.id());
                    ps.setString(2, item.title());
                    ps.setString(3, item.summary());
                    ps.setString(4, item.body());
                    ps.setString(5, item.category());
                    ps.setObject(6, item.publishedAt());
                    ps.setLong(7, now);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            connection.commit();
        } catch (Exception e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public List<NewsRecord> listNews() throws Exception {
        List<NewsRecord> list = new ArrayList<>();
        String sql = "SELECT id, title, summary, body, category, published_at FROM news_cache ORDER BY published_at DESC";
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new NewsRecord(
                        rs.getString("id"),
                        rs.getString("title"),
                        rs.getString("summary"),
                        rs.getString("body"),
                        rs.getString("category"),
                        rs.getObject("published_at") == null ? null : rs.getLong("published_at")
                ));
            }
        }
        return list;
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                LOG.warn("Failed to close database", e);
            }
        }
    }

    public record InstalledVersionRecord(String id, String type, long installedAt, Long lastLaunchedAt) {
    }

    public record LaunchLogRecord(long id, String versionId, long startedAt, Integer exitCode, String logPath) {
    }

    public record NewsRecord(String id, String title, String summary, String body, String category, Long publishedAt) {
    }

    public record ModFavoriteRecord(String projectId, String slug, String title, String iconUrl, long favoritedAt) {
    }
}
