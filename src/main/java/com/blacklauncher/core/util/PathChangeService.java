package com.blacklauncher.core.util;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.config.ConfigManager;
import com.blacklauncher.core.db.Database;
import com.blacklauncher.core.exception.LauncherException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiConsumer;

/**
 * High-level launcher / game directory change with data migration.
 */
public final class PathChangeService {

    private static final Logger LOG = LoggerFactory.getLogger(PathChangeService.class);

    private PathChangeService() {
    }

    public static PathMigrationService.Result changeLauncherHome(ConfigManager configManager,
                                                                 Database database,
                                                                 Path newHome,
                                                                 boolean deleteOld,
                                                                 BiConsumer<Long, String> progress)
            throws LauncherException {
        Path oldHome = AppPaths.home().toAbsolutePath().normalize();
        Path oldGame = AppPaths.gameDir().toAbsolutePath().normalize();
        Path target = newHome.toAbsolutePath().normalize();
        if (oldHome.equals(target)) {
            throw new LauncherException("启动器目录未改变");
        }
        if (Files.exists(target)) {
            try {
                try (var stream = Files.list(target)) {
                    boolean empty = stream.findAny().isEmpty();
                    if (!empty && !Files.isSameFile(target, oldHome)) {
                        // Allow reuse if already contains our config (re-select).
                        if (!Files.isRegularFile(target.resolve("config.json"))) {
                            throw new LauncherException("目标目录非空且不是本启动器数据目录: " + target);
                        }
                    }
                }
            } catch (LauncherException e) {
                throw e;
            } catch (Exception e) {
                throw new LauncherException("无法检查目标目录: " + e.getMessage(), e);
            }
        }

        PathMigrationService.Result result = PathMigrationService.migrate(oldHome, target, false, progress);

        try {
            AppPaths.writeLauncherRedirect(target);
            AppPaths.setLauncherHome(target);

            AppConfig config = configManager.getConfig();
            config.setDataDirectory(target.toString());

            // If game dir was under old launcher home, remap into new home.
            if (oldGame.startsWith(oldHome)) {
                Path rel = oldHome.relativize(oldGame);
                Path mapped = target.resolve(rel.toString()).normalize();
                AppPaths.setGameDir(mapped);
                config.setGameDirectory(mapped.toString());
            }

            AppPaths.ensureDirectories();
            if (database != null) {
                database.reopen();
            }
            configManager.save();

            if (deleteOld) {
                if (progress != null) {
                    progress.accept(result.filesCopied(), "正在清理旧启动器目录…");
                }
                PathMigrationService.deleteTree(oldHome, true);
            }
            LOG.info("Launcher home changed {} → {}", oldHome, target);
            return result;
        } catch (LauncherException e) {
            // Best-effort rollback of in-memory paths.
            AppPaths.setLauncherHome(oldHome);
            AppPaths.setGameDir(oldGame);
            throw e;
        } catch (Exception e) {
            AppPaths.setLauncherHome(oldHome);
            AppPaths.setGameDir(oldGame);
            throw new LauncherException("切换启动器目录失败: " + e.getMessage(), e);
        }
    }

    public static PathMigrationService.Result changeGameDir(ConfigManager configManager,
                                                            Path newGameDir,
                                                            boolean deleteOld,
                                                            BiConsumer<Long, String> progress)
            throws LauncherException {
        Path oldGame = AppPaths.gameDir().toAbsolutePath().normalize();
        Path target = newGameDir.toAbsolutePath().normalize();
        if (oldGame.equals(target)) {
            throw new LauncherException("游戏目录未改变");
        }
        if (Files.exists(target)) {
            try {
                try (var stream = Files.list(target)) {
                    boolean empty = stream.findAny().isEmpty();
                    if (!empty) {
                        boolean looksLikeMc = Files.isDirectory(target.resolve("versions"))
                                || Files.isDirectory(target.resolve("libraries"))
                                || Files.isDirectory(target.resolve("assets"));
                        if (!looksLikeMc) {
                            throw new LauncherException("目标目录非空且不像 Minecraft 游戏目录: " + target);
                        }
                    }
                }
            } catch (LauncherException e) {
                throw e;
            } catch (Exception e) {
                throw new LauncherException("无法检查目标目录: " + e.getMessage(), e);
            }
        }

        PathMigrationService.Result result;
        if (Files.exists(oldGame) && hasAnyContent(oldGame)) {
            result = PathMigrationService.migrate(oldGame, target, false, progress);
        } else {
            try {
                Files.createDirectories(target);
            } catch (Exception e) {
                throw new LauncherException("创建游戏目录失败: " + e.getMessage(), e);
            }
            result = new PathMigrationService.Result(0, 0, oldGame, target);
        }

        AppPaths.setGameDir(target);
        AppConfig config = configManager.getConfig();
        config.setGameDirectory(target.toString());
        try {
            AppPaths.ensureDirectories();
            configManager.save();
            if (deleteOld && !oldGame.equals(AppPaths.home().toAbsolutePath().normalize())) {
                // Never delete launcher home when it equals old game (shouldn't happen).
                if (progress != null) {
                    progress.accept(result.filesCopied(), "正在清理旧游戏目录…");
                }
                PathMigrationService.deleteTree(oldGame, false);
            }
        } catch (Exception e) {
            AppPaths.setGameDir(oldGame);
            config.setGameDirectory(oldGame.equals(AppPaths.home().resolve("minecraft"))
                    ? "" : oldGame.toString());
            throw new LauncherException("切换游戏目录失败: " + e.getMessage(), e);
        }
        LOG.info("Game dir changed {} → {}", oldGame, target);
        return result;
    }

    private static boolean hasAnyContent(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream.findAny().isPresent();
        } catch (Exception e) {
            return false;
        }
    }
}
