package com.blacklauncher.mod;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.net.HttpClientFactory;
import com.blacklauncher.core.net.ResumableDownloader;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.minecraft.DirectoryKind;
import com.blacklauncher.minecraft.GameProcessGuard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiConsumer;

/**
 * Downloads Modrinth mods / shaders into the selected instance directories.
 */
public class ModInstallService {

    private final ModrinthClient modrinthClient;
    private final ResumableDownloader downloader;

    public ModInstallService(AppConfig config) {
        this.modrinthClient = new ModrinthClient(config);
        this.downloader = new ResumableDownloader(HttpClientFactory.create(config));
    }

    public ModrinthClient client() {
        return modrinthClient;
    }

    public Path installMod(String projectId, String mcVersion, String loader, Path targetDir,
                           BiConsumer<Long, Long> progress) throws LauncherException {
        ModrinthClient.ModFile file = modrinthClient.latestFile(projectId, blankToNull(mcVersion), blankToNull(loader));
        return downloadTo(targetDir, file, progress);
    }

    public Path installShader(String projectId, String mcVersion, Path targetDir,
                              BiConsumer<Long, Long> progress) throws LauncherException {
        ModrinthClient.ModFile file = modrinthClient.latestFile(projectId, blankToNull(mcVersion), null);
        return downloadTo(targetDir, file, progress);
    }

    public Path installResourcePack(String projectId, String mcVersion, Path targetDir,
                                    BiConsumer<Long, Long> progress) throws LauncherException {
        ModrinthClient.ModFile file = modrinthClient.latestFile(projectId, blankToNull(mcVersion), null);
        return downloadTo(targetDir, file, progress);
    }

    public Path installModpack(String projectId, String mcVersion, BiConsumer<Long, Long> progress)
            throws LauncherException {
        ModrinthClient.ModFile file = modrinthClient.latestFile(projectId, blankToNull(mcVersion), null);
        String folder = Validation.isSafeVersionId(mcVersion) ? mcVersion : "shared";
        Path packDir = AppPaths.downloadsTempDir().resolve("modpacks").resolve(folder);
        return downloadTo(packDir, file, progress);
    }

    public Path installFile(ModrinthClient.ModFile file, Path dir, BiConsumer<Long, Long> progress)
            throws LauncherException {
        return downloadTo(dir, file, progress);
    }

    private Path downloadTo(Path dir, ModrinthClient.ModFile file, BiConsumer<Long, Long> progress)
            throws LauncherException {
        // Installing into mods/shaderpacks while the game is running risks file corruption.
        String dirName = dir == null ? "" : dir.getFileName().toString().toLowerCase();
        if ("mods".equals(dirName)) {
            GameProcessGuard.get().requireWritable(DirectoryKind.MODS);
        } else if ("shaderpacks".equals(dirName)) {
            GameProcessGuard.get().requireWritable(DirectoryKind.SHADERPACKS);
        } else if ("resourcepacks".equals(dirName)) {
            GameProcessGuard.get().requireWritable(DirectoryKind.RESOURCEPACKS);
        }
        try {
            Files.createDirectories(dir);
            String safeName = file.fileName().replaceAll("[\\\\/:*?\"<>|]", "_");
            Path target = dir.resolve(safeName);
            downloader.download(file.url(), target, file.sha1(), progress);
            return target;
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            throw new LauncherException("安装失败: " + e.getMessage(), e);
        }
    }

    private static String blankToNull(String value) {
        return Validation.isNonBlank(value) ? value.trim() : null;
    }
}
