package com.blacklauncher.download;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.net.DownloadProgressSink;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.minecraft.DirectoryKind;
import com.blacklauncher.minecraft.MinecraftLayout;
import com.blacklauncher.mod.ModInstallService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Auto-installs companion API mods for Fabric / Quilt after loader install (§10.1).
 * Forge / NeoForge ship their own APIs with the loader — no extra mod required.
 */
public final class LoaderApiInstaller {

    private static final Logger LOG = LoggerFactory.getLogger(LoaderApiInstaller.class);

    /** Modrinth project id: Fabric API */
    public static final String FABRIC_API = "P7dR8mSH";
    /** Modrinth project id: Quilted Fabric API */
    public static final String QUILTED_FABRIC_API = "AANobbMI";

    private LoaderApiInstaller() {
    }

    public static boolean supportsCompanionApi(ModLoader loader) {
        return loader == ModLoader.FABRIC || loader == ModLoader.QUILT;
    }

    public static String companionLabel(ModLoader loader) {
        return switch (loader) {
            case FABRIC -> "Fabric API";
            case QUILT -> "Quilted Fabric API";
            default -> "配套 API";
        };
    }

    /**
     * @return true if an API jar was installed; false if skipped / not needed / not found
     */
    public static boolean installIfNeeded(AppConfig config,
                                          ModLoader loader,
                                          String mcVersion,
                                          String profileId,
                                          DownloadProgressSink sink) throws LauncherException {
        if (!supportsCompanionApi(loader)) {
            if (sink != null) {
                sink.status(loader.displayName() + " 无需额外配套 API 模组", 0.96);
            }
            return false;
        }
        String projectId = loader == ModLoader.FABRIC ? FABRIC_API : QUILTED_FABRIC_API;
        String label = companionLabel(loader);
        String gameVer = Validation.isNonBlank(mcVersion) ? mcVersion.trim() : "";
        Path modsDir = MinecraftLayout.forVersion(config, profileId).resolve(DirectoryKind.MODS);

        if (sink != null) {
            sink.status("检索配套 " + label + "...", 0.93);
            sink.dependency(label, 0);
        }
        try {
            ModInstallService mods = new ModInstallService(config);
            AtomicLong last = new AtomicLong(0);
            Path installed = mods.installMod(projectId, gameVer, loader.id(), modsDir, (done, total) -> {
                long prev = last.getAndSet(done);
                long delta = done - prev;
                if (delta > 0 && sink != null) {
                    sink.addBytes(delta);
                }
                if (sink != null && total > 0) {
                    double p = done / (double) total;
                    sink.dependency(label, p);
                    sink.status("下载 " + label + "...", 0.93 + 0.05 * p);
                }
            });
            LOG.info("Installed companion API {} -> {}", label, installed);
            if (sink != null) {
                sink.dependency(installed.getFileName().toString(), 1.0);
                sink.status(label + " 已安装", 0.98);
            }
            return true;
        } catch (LauncherException e) {
            LOG.warn("Companion API not installed for {} / {}: {}", loader, gameVer, e.getMessage());
            throw new LauncherException(
                    "未能自动安装 " + label + "（" + e.getMessage() + "）。加载器本体已安装，可稍后手动安装 API。",
                    e);
        }
    }
}
