package com.blacklauncher.download;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.db.Database;
import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.Validation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Uninstall / clone installed Minecraft version profiles and optional instance data.
 */
public class VersionLifecycleService {

    private static final Logger LOG = LoggerFactory.getLogger(VersionLifecycleService.class);

    private final AppConfig config;
    private final Database database;
    private final ModLoaderInstallService modLoaderInstallService;

    public VersionLifecycleService(AppConfig config, Database database) {
        this.config = config;
        this.database = database;
        this.modLoaderInstallService = new ModLoaderInstallService(config, database);
    }

    public void uninstall(String versionId, boolean deleteInstanceDir) throws Exception {
        Validation.requireSafeVersionId(versionId);
        Path versionDir = AppPaths.versionsDir().resolve(versionId);
        ModLoaderInstallService.deleteRecursive(versionDir);

        if (deleteInstanceDir) {
            Path instanceDir = AppPaths.gameDir().resolve("instances").resolve(versionId);
            ModLoaderInstallService.deleteRecursive(instanceDir);
            Path nativesDir = AppPaths.nativesDir().resolve(versionId);
            ModLoaderInstallService.deleteRecursive(nativesDir);
        }

        database.deleteInstalledVersion(versionId);
        if (config != null && versionId.equals(config.getSelectedVersion())) {
            config.setSelectedVersion("");
        }
        LOG.info("Uninstalled version {} (deleteInstanceDir={})", versionId, deleteInstanceDir);
    }

    public String copyInstance(String fromId, String newId, boolean copyMods, boolean copySaves)
            throws Exception {
        Validation.requireSafeVersionId(fromId);
        String toId = Validation.sanitizeVersionId(newId);
        Validation.requireSafeVersionId(toId);
        if (fromId.equals(toId)) {
            throw new LauncherException("目标版本 ID 与源相同");
        }
        Path dstVersion = AppPaths.versionsDir().resolve(toId);
        if (Files.exists(dstVersion)) {
            throw new LauncherException("目标版本已存在: " + toId);
        }

        modLoaderInstallService.relocateProfile(fromId, toId);

        if (config != null && config.isIsolateGameDirs()) {
            Path fromInstance = AppPaths.gameDir().resolve("instances").resolve(fromId);
            Path toInstance = AppPaths.gameDir().resolve("instances").resolve(toId);
            if (Files.isDirectory(fromInstance)) {
                if (Files.exists(toInstance)) {
                    ModLoaderInstallService.deleteRecursive(toInstance);
                }
                copyRecursive(fromInstance, toInstance);
                if (!copyMods) {
                    ModLoaderInstallService.deleteRecursive(toInstance.resolve("mods"));
                    Files.createDirectories(toInstance.resolve("mods"));
                }
                if (!copySaves) {
                    ModLoaderInstallService.deleteRecursive(toInstance.resolve("saves"));
                    Files.createDirectories(toInstance.resolve("saves"));
                }
            }
        }

        String type = "custom";
        try {
            for (Database.InstalledVersionRecord r : database.listInstalledVersions()) {
                if (fromId.equals(r.id())) {
                    type = r.type();
                    break;
                }
            }
        } catch (Exception ignored) {
            // keep default
        }
        database.upsertInstalledVersion(toId, type);
        LOG.info("Copied version {} -> {} (mods={}, saves={})", fromId, toId, copyMods, copySaves);
        return toId;
    }

    private static void copyRecursive(Path src, Path dst) throws Exception {
        Files.walk(src).forEach(p -> {
            try {
                Path rel = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(rel);
                } else {
                    Files.createDirectories(rel.getParent());
                    Files.copy(p, rel, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
