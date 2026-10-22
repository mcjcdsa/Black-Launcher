package com.blacklauncher.modpack;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.JsonUtil;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.core.util.ZipSafe;
import com.blacklauncher.launch.LaunchScriptExporter;
import com.blacklauncher.minecraft.DirectoryKind;
import com.blacklauncher.minecraft.MinecraftLayout;
import com.blacklauncher.model.VersionJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Exports an installed version as a shareable modpack zip (§6.3.1).
 */
public final class ModpackExportService {

    private static final Logger LOG = LoggerFactory.getLogger(ModpackExportService.class);

    private final AppConfig config;

    public ModpackExportService(AppConfig config) {
        this.config = config;
    }

    public Path exportVersion(String versionId, ModpackExportOptions options) throws LauncherException {
        Validation.requireSafeVersionId(versionId);
        if (options == null || options.getOutputFile() == null) {
            throw new LauncherException("未指定导出路径");
        }
        Path versionDir = AppPaths.versionsDir().resolve(versionId);
        if (!Files.isDirectory(versionDir)) {
            throw new LauncherException("版本不存在: " + versionId);
        }

        Path staging = AppPaths.downloadsTempDir().resolve("modpack-export-" + System.currentTimeMillis());
        try {
            Files.createDirectories(staging);
            MinecraftLayout layout = MinecraftLayout.forVersion(config, versionId);

            ModpackDescriptor meta = new ModpackDescriptor();
            meta.setName(Validation.isNonBlank(options.getPackName()) ? options.getPackName() : versionId);
            meta.setVersion("1.0");
            meta.setMcVersion(guessMcVersion(versionId, versionDir));
            meta.setLoader(ModrinthIndexParser.guessLoaderFromId(versionId));
            meta.setLoaderVersion(ModrinthIndexParser.guessLoaderVersionFromId(versionId, meta.getLoader()));

            Path overrides = staging.resolve("overrides");
            Files.createDirectories(overrides);

            if (options.isIncludeMods()) {
                copyDir(layout.resolve(DirectoryKind.MODS), overrides.resolve("mods"));
            }
            if (options.isIncludeConfig()) {
                copyDir(layout.resolve(DirectoryKind.CONFIG), overrides.resolve("config"));
            }
            if (options.isIncludeResourcepacks()) {
                copyDir(layout.resolve(DirectoryKind.RESOURCEPACKS), overrides.resolve("resourcepacks"));
            }
            if (options.isIncludeSaves()) {
                copyDir(layout.resolve(DirectoryKind.SAVES), overrides.resolve("saves"));
            }
            if (options.isIncludeServers()) {
                Path servers = layout.resolve(DirectoryKind.SERVERS_DAT);
                if (Files.isRegularFile(servers)) {
                    Files.createDirectories(overrides);
                    Files.copy(servers, overrides.resolve("servers.dat"), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (options.isIncludeScreenshots()) {
                copyDir(layout.resolve(DirectoryKind.SCREENSHOTS), overrides.resolve("screenshots"));
            }
            if (options.isIncludeOptions()) {
                Path optionsTxt = layout.resolve(DirectoryKind.OPTIONS_TXT);
                if (Files.isRegularFile(optionsTxt) && !options.isStripPrivate()) {
                    Files.copy(optionsTxt, overrides.resolve("options.txt"), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            // Embed version core (jar/json) for full §7 export.
            Path versionsOut = staging.resolve("versions").resolve(versionId);
            copyDir(versionDir, versionsOut);

            if (options.isStripPrivate()) {
                stripPrivate(overrides);
            }
            if (options.isIncludeJvmArgs() && Validation.isNonBlank(config.getJvmArgs())) {
                Files.writeString(staging.resolve("blacklauncher.jvm.txt"),
                        config.getJvmArgs(), StandardCharsets.UTF_8);
            }
            if (options.isIncludeLaunchScript()) {
                try {
                    Path script = new LaunchScriptExporter(config).exportTo(
                            versionId, staging.resolve(versionId + "-独立启动脚本.bat"));
                    LOG.debug("Bundled launch script {}", script);
                } catch (Exception e) {
                    LOG.warn("Could not bundle launch script: {}", e.toString());
                }
            }

            // Also embed mods at top-level for offline CF-style consumers.
            if (options.isIncludeMods() && Files.isDirectory(overrides.resolve("mods"))) {
                copyDir(overrides.resolve("mods"), staging.resolve("mods"));
            }

            ModpackExportOptions.Format format = options.getFormat();
            if (format == ModpackExportOptions.Format.MODRINTH) {
                Files.writeString(staging.resolve("modrinth.index.json"),
                        ModrinthIndexParser.buildIndex(meta), StandardCharsets.UTF_8);
            } else if (format == ModpackExportOptions.Format.CURSEFORGE) {
                Files.writeString(staging.resolve("manifest.json"),
                        CurseForgeManifestParser.buildManifest(meta), StandardCharsets.UTF_8);
            } else {
                // Offline zip: both indexes + embedded content for max compatibility.
                Files.writeString(staging.resolve("modrinth.index.json"),
                        ModrinthIndexParser.buildIndex(meta), StandardCharsets.UTF_8);
                Files.writeString(staging.resolve("manifest.json"),
                        CurseForgeManifestParser.buildManifest(meta), StandardCharsets.UTF_8);
            }

            Path out = options.getOutputFile();
            Files.createDirectories(out.getParent());
            if (Files.exists(out)) {
                Files.delete(out);
            }
            ZipSafe.createZip(staging, out);
            LOG.info("Exported modpack {} -> {}", versionId, out);
            return out;
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            throw new LauncherException("导出整合包失败: " + e.getMessage(), e);
        } finally {
            try {
                if (Files.exists(staging)) {
                    try (Stream<Path> walk = Files.walk(staging)) {
                        walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (Exception ignored) {
                            }
                        });
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static void copyDir(Path src, Path dst) throws Exception {
        if (!Files.isDirectory(src)) {
            return;
        }
        Files.createDirectories(dst);
        try (var walk = Files.walk(src)) {
            walk.forEach(p -> {
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

    private static void stripPrivate(Path overrides) throws Exception {
        // Remove common personal files.
        String[] sensitive = {
                "options.txt", "usercache.json", "usernamecache.json",
                "launcher_profiles.json", "servers.dat_old"
        };
        for (String name : sensitive) {
            Path p = overrides.resolve(name);
            Files.deleteIfExists(p);
        }
        // Skin / auth leftovers under config
        Path config = overrides.resolve("config");
        if (Files.isDirectory(config)) {
            try (var walk = Files.walk(config)) {
                walk.filter(Files::isRegularFile).forEach(p -> {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (n.contains("skin") || n.contains("account") || n.contains("auth")
                            || n.contains("token") || n.contains("session")) {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    }
                });
            }
        }
    }

    private String guessMcVersion(String versionId, Path versionDir) {
        try {
            Path json = versionDir.resolve(versionId + ".json");
            if (Files.isRegularFile(json)) {
                VersionJson v = JsonUtil.fromJson(Files.readString(json, StandardCharsets.UTF_8), VersionJson.class);
                if (Validation.isNonBlank(v.getInheritsFrom())) {
                    return v.getInheritsFrom();
                }
                // fabric-loader-x-1.20.1
                String id = v.getId() == null ? versionId : v.getId();
                int last = id.lastIndexOf('-');
                if (last > 0 && id.substring(last + 1).matches("1\\.\\d+([.\\-]\\w+)*")) {
                    return id.substring(last + 1);
                }
                return id;
            }
        } catch (Exception e) {
            LOG.debug("guessMcVersion failed for {}", versionId, e);
        }
        return versionId;
    }
}
