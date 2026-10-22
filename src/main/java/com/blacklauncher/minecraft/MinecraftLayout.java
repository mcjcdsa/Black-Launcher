package com.blacklauncher.minecraft;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.Validation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * Resolves Minecraft directory paths with version-isolation awareness.
 * <p>
 * Shared system dirs ({@code assets}/{@code libraries}/{@code versions}) always live under
 * {@link AppPaths#gameDir()}. User content dirs follow isolation: either the shared game root
 * or {@code instances/{versionId}/}.
 */
public final class MinecraftLayout {

    private final AppConfig config;
    private final String versionId;
    private final boolean isolate;

    public MinecraftLayout(AppConfig config, String versionId) {
        this.config = config;
        this.versionId = Validation.isNonBlank(versionId) ? versionId.trim() : "";
        this.isolate = config != null && config.isIsolateGameDirs()
                && Validation.isSafeVersionId(this.versionId);
    }

    public static MinecraftLayout forVersion(AppConfig config, String versionId) {
        return new MinecraftLayout(config, versionId);
    }

    /** Shared .minecraft-equivalent root used by this launcher. */
    public Path gameRoot() {
        return AppPaths.gameDir();
    }

    /**
     * Working directory for the instance: isolated instance folder or shared game root.
     */
    public Path instanceRoot() {
        if (isolate) {
            return AppPaths.gameDir().resolve("instances").resolve(versionId);
        }
        return AppPaths.gameDir();
    }

    public boolean isolationEnabled() {
        return isolate;
    }

    public String versionId() {
        return versionId;
    }

    public Path resolve(DirectoryKind kind) {
        return switch (kind) {
            case ASSETS -> AppPaths.assetsDir();
            case LIBRARIES -> AppPaths.librariesDir();
            case VERSIONS -> AppPaths.versionsDir();
            case NATIVES -> Validation.isSafeVersionId(versionId)
                    ? AppPaths.nativesDir().resolve(versionId)
                    : AppPaths.nativesDir();
            case MODS -> instanceRoot().resolve("mods");
            case SAVES -> instanceRoot().resolve("saves");
            case RESOURCEPACKS -> instanceRoot().resolve("resourcepacks");
            case SHADERPACKS -> instanceRoot().resolve("shaderpacks");
            case CONFIG -> instanceRoot().resolve("config");
            case SCREENSHOTS -> instanceRoot().resolve("screenshots");
            case LOGS -> instanceRoot().resolve("logs");
            case CRASH_REPORTS -> instanceRoot().resolve("crash-reports");
            case OPTIONS_TXT -> instanceRoot().resolve("options.txt");
            case SERVERS_DAT -> instanceRoot().resolve("servers.dat");
            case FABRIC_EXT -> instanceRoot().resolve("fabric");
            case FORGE_EXT -> instanceRoot().resolve("config"); // forge configs often under config/
        };
    }

    public Map<DirectoryKind, Path> snapshot() {
        Map<DirectoryKind, Path> map = new EnumMap<>(DirectoryKind.class);
        for (DirectoryKind kind : DirectoryKind.values()) {
            map.put(kind, resolve(kind));
        }
        return map;
    }

    /** Ensures standard user-content directories exist for launch / tools. */
    public void ensureUserDirectories() throws Exception {
        Files.createDirectories(instanceRoot());
        Files.createDirectories(resolve(DirectoryKind.MODS));
        Files.createDirectories(resolve(DirectoryKind.SAVES));
        Files.createDirectories(resolve(DirectoryKind.RESOURCEPACKS));
        Files.createDirectories(resolve(DirectoryKind.SHADERPACKS));
        Files.createDirectories(resolve(DirectoryKind.CONFIG));
        Files.createDirectories(resolve(DirectoryKind.SCREENSHOTS));
        Files.createDirectories(resolve(DirectoryKind.LOGS));
        Files.createDirectories(resolve(DirectoryKind.CRASH_REPORTS));
    }

    /**
     * Path priority helper: when isolation is on, instance path wins for user content;
     * otherwise shared game root.
     */
    public Path preferredUserContent(String folderName) {
        Path isolated = AppPaths.gameDir().resolve("instances").resolve(versionId).resolve(folderName);
        Path shared = AppPaths.gameDir().resolve(folderName);
        if (isolate) {
            return isolated;
        }
        return shared;
    }
}
