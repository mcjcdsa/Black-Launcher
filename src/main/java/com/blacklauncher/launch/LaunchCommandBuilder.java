package com.blacklauncher.launch;

import com.blacklauncher.AppInfo;
import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.net.DownloadSource;
import com.blacklauncher.core.net.HttpClientFactory;
import com.blacklauncher.core.net.ResumableDownloader;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.JsonUtil;
import com.blacklauncher.core.util.ProcessCharsets;
import com.blacklauncher.core.util.UuidUtil;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.download.MavenLibraryResolver;
import com.blacklauncher.download.OsRules;
import com.blacklauncher.download.VersionJsonMerger;
import com.blacklauncher.model.VersionJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the Minecraft client process command line (legacy + modern arguments).
 */
public class LaunchCommandBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(LaunchCommandBuilder.class);

    public List<String> build(AppConfig config, String versionId, Path gameDir) throws LauncherException {
        Validation.requireSafeVersionId(versionId);
        Path versionJsonPath = AppPaths.versionsDir().resolve(versionId).resolve(versionId + ".json");
        if (!Files.isRegularFile(versionJsonPath)) {
            throw new LauncherException("版本未完整安装: " + versionId);
        }

        VersionJson version = loadMergedVersion(versionId);
        JavaVersionProbe.ensureCompatible(config.getJavaPath(), version);
        ensureLibrariesPresent(config, version);
        Path clientJar = resolveClientJar(versionId, version);
        if (!Files.isRegularFile(clientJar)) {
            throw new LauncherException("缺少客户端 jar: " + versionId);
        }
        if (!Validation.isNonBlank(version.getMainClass())) {
            throw new LauncherException("版本主类缺失");
        }

        Path natives = NativesExtractor.extract(version, versionId);
        Path log4jFile = Log4jConfigInjector.ensureConfig(gameDir);
        String java = JavaFinder.resolveJavaExecutable(config.getJavaPath());
        List<String> classpath = buildClasspath(version, clientJar);
        String cpJoined = String.join(System.getProperty("path.separator"), classpath);

        String uuid = UuidUtil.withDashes(config.getPlayerUuid());
        String accessToken = resolveAccessToken(config);
        String userType = resolveUserType(config);
        String assetsId = version.getAssetIndex() != null ? version.getAssetIndex().getId() : version.getAssets();
        if (!Validation.isNonBlank(assetsId)) {
            assetsId = "legacy";
        }

        Map<String, String> placeholders = buildPlaceholders(
                config, versionId, gameDir, natives, cpJoined, uuid, accessToken, userType, assetsId, version);
        Map<String, Boolean> features = new HashMap<>();
        features.put("is_demo_user", false);
        // Mojang: has_custom_resolution means width/height args should be applied (not fullscreen).
        features.put("has_custom_resolution", !config.isFullscreen());
        features.put("has_quick_plays_support", false);
        features.put("is_quick_play_singleplayer", false);
        features.put("is_quick_play_multiplayer", false);
        features.put("is_quick_play_realms", false);

        List<String> command = new ArrayList<>();
        command.add(java);
        // HMCL-style hardening defaults (addDefault: only if not already present).
        addDefault(command, "-Xms" + config.getMinMemoryMb() + "M", a -> a.startsWith("-Xms"));
        addDefault(command, "-Xmx" + config.getMaxMemoryMb() + "M", a -> a.startsWith("-Xmx"));
        addDefault(command, "-Dlog4j2.formatMsgNoLookups=true",
                a -> a.startsWith("-Dlog4j2.formatMsgNoLookups="));
        addDefault(command, "-Djava.rmi.server.useCodebaseOnly=true",
                a -> a.startsWith("-Djava.rmi.server.useCodebaseOnly="));
        addDefault(command, "-Dcom.sun.jndi.rmi.object.trustURLCodebase=false",
                a -> a.startsWith("-Dcom.sun.jndi.rmi.object.trustURLCodebase="));
        addDefault(command, "-Dfml.ignoreInvalidMinecraftCertificates=true",
                a -> a.startsWith("-Dfml.ignoreInvalidMinecraftCertificates="));
        addDefault(command, "-Dfml.ignorePatchDiscrepancies=true",
                a -> a.startsWith("-Dfml.ignorePatchDiscrepancies="));

        // Match HMCL: force game stdout/stderr to native charset so log capture is not mojibake.
        String enc = ProcessCharsets.encodingName();
        addDefault(command, "-Dfile.encoding=" + enc, a -> a.startsWith("-Dfile.encoding="));
        int javaMajor = JavaVersionProbe.probeMajor(java);
        if (javaMajor >= 19) {
            addDefault(command, "-Dstdout.encoding=" + enc, a -> a.startsWith("-Dstdout.encoding="));
            addDefault(command, "-Dstderr.encoding=" + enc, a -> a.startsWith("-Dstderr.encoding="));
        } else {
            // Java ≤18, or probe failed (most Minecraft JREs are 8–17).
            addDefault(command, "-Dsun.stdout.encoding=" + enc, a -> a.startsWith("-Dsun.stdout.encoding="));
            addDefault(command, "-Dsun.stderr.encoding=" + enc, a -> a.startsWith("-Dsun.stderr.encoding="));
            if (javaMajor <= 0) {
                addDefault(command, "-Dstdout.encoding=" + enc, a -> a.startsWith("-Dstdout.encoding="));
                addDefault(command, "-Dstderr.encoding=" + enc, a -> a.startsWith("-Dstderr.encoding="));
            }
        }

        boolean usedModernJvm = false;
        if (version.getArguments() != null) {
            List<String> jvmArgs = ArgumentResolver.resolve(version.getArguments(), "jvm", placeholders, features);
            if (!jvmArgs.isEmpty()) {
                usedModernJvm = true;
                command.addAll(jvmArgs);
            }
        }
        if (!usedModernJvm) {
            addDefault(command, "-Djava.library.path=" + natives.toAbsolutePath(),
                    a -> a.startsWith("-Djava.library.path="));
            addDefault(command, "-Dminecraft.launcher.brand=" + AppInfo.NAME_EN.replace(' ', '-'),
                    a -> a.startsWith("-Dminecraft.launcher.brand="));
            addDefault(command, "-Dminecraft.launcher.version=" + AppInfo.VERSION,
                    a -> a.startsWith("-Dminecraft.launcher.version="));
            command.add("-cp");
            command.add(cpJoined);
        } else {
            if (command.stream().noneMatch(a -> a.startsWith("-Djava.library.path="))) {
                command.add("-Djava.library.path=" + natives.toAbsolutePath());
            }
            if (command.stream().noneMatch(a -> "-cp".equals(a) || "-classpath".equals(a))) {
                command.add("-cp");
                command.add(cpJoined);
            }
        }

        if (Validation.isNonBlank(config.getJvmArgs())) {
            for (String arg : config.getJvmArgs().trim().split("\\s+")) {
                if (!arg.isBlank()) {
                    command.add(arg);
                }
            }
        }

        BootstrapIgnoreList.rewriteInPlace(command, version.getMainClass(), clientJar, classpath);
        Log4jConfigInjector.applyJvmArg(command, log4jFile);

        command.add(version.getMainClass());

        boolean usedModernGame = false;
        if (version.getArguments() != null) {
            List<String> gameArgs = ArgumentResolver.resolve(version.getArguments(), "game", placeholders, features);
            if (!gameArgs.isEmpty()) {
                usedModernGame = true;
                command.addAll(gameArgs);
            }
        }
        if (!usedModernGame) {
            if (Validation.isNonBlank(version.getMinecraftArguments())) {
                String args = ArgumentResolver.substitute(version.getMinecraftArguments(), placeholders);
                for (String part : args.split("\\s+")) {
                    if (!part.isBlank()) {
                        command.add(part.replace("\"", ""));
                    }
                }
            } else {
                command.add("--username");
                command.add(config.getPlayerName());
                command.add("--version");
                command.add(versionId);
                command.add("--gameDir");
                command.add(gameDir.toAbsolutePath().toString());
                command.add("--assetsDir");
                command.add(AppPaths.assetsDir().toAbsolutePath().toString());
                command.add("--assetIndex");
                command.add(assetsId);
                command.add("--uuid");
                command.add(uuid);
                command.add("--accessToken");
                command.add(accessToken);
                command.add("--userType");
                command.add(userType);
                command.add("--versionType");
                command.add(version.getType() == null ? "release" : version.getType());
            }
        }

        // Resolution / fullscreen from launcher settings (if not already present).
        if (config.isFullscreen()) {
            if (command.stream().noneMatch(a -> a.equals("--fullscreen"))) {
                command.add("--fullscreen");
            }
        } else {
            if (command.stream().noneMatch(a -> a.equals("--width"))) {
                command.add("--width");
                command.add(String.valueOf(config.getGameWidth()));
                command.add("--height");
                command.add(String.valueOf(config.getGameHeight()));
            }
        }
        return command;
    }

    public String preview(AppConfig config, String versionId) {
        try {
            Path gameDir = com.blacklauncher.minecraft.MinecraftLayout
                    .forVersion(config, versionId).instanceRoot();
            List<String> cmd = build(config, versionId, gameDir);
            String token = resolveAccessToken(config);
            return cmd.stream()
                    .map(a -> Validation.isNonBlank(token) && !"0".equals(token) && a.equals(token)
                            ? "<accessToken>" : a)
                    .collect(Collectors.joining(" "));
        } catch (Exception e) {
            return "无法预览启动参数: " + e.getMessage();
        }
    }

    /** Merged version JSON (follows inheritsFrom) for repair / scripting. */
    public VersionJson loadMerged(String versionId) throws LauncherException {
        return loadMergedVersion(versionId);
    }

    /** HMCL CommandBuilder.addDefault: only add if no matching flag already present. */
    private static void addDefault(List<String> command, String arg, java.util.function.Predicate<String> already) {
        if (command.stream().noneMatch(already)) {
            command.add(arg);
        }
    }

    private Map<String, String> buildPlaceholders(AppConfig config,
                                                  String versionId,
                                                  Path gameDir,
                                                  Path natives,
                                                  String classpath,
                                                  String uuid,
                                                  String accessToken,
                                                  String userType,
                                                  String assetsId,
                                                  VersionJson version) {
        Map<String, String> map = new HashMap<>();
        map.put("auth_player_name", config.getPlayerName());
        map.put("version_name", versionId);
        map.put("game_directory", gameDir.toAbsolutePath().toString());
        map.put("assets_root", AppPaths.assetsDir().toAbsolutePath().toString());
        map.put("assets_index_name", assetsId);
        map.put("auth_uuid", uuid);
        map.put("auth_access_token", accessToken);
        map.put("auth_session", accessToken);
        map.put("user_type", userType);
        map.put("version_type", version.getType() == null ? "release" : version.getType());
        map.put("natives_directory", natives.toAbsolutePath().toString());
        map.put("launcher_name", AppInfo.NAME_EN);
        map.put("launcher_version", AppInfo.VERSION);
        map.put("classpath", classpath);
        map.put("library_directory", AppPaths.librariesDir().toAbsolutePath().toString());
        map.put("classpath_separator", System.getProperty("path.separator"));
        Path primary = resolveClientJar(versionId, version);
        map.put("primary_jar", primary.toAbsolutePath().toString());
        map.put("primary_jar_name", primary.getFileName().toString());
        map.put("file_separator", System.getProperty("file.separator"));
        map.put("clientid", Validation.isNonBlank(config.getMicrosoftClientId())
                ? config.getMicrosoftClientId() : "00000000402b5328");
        map.put("auth_xuid", Validation.isNonBlank(config.getXboxUserHash()) ? config.getXboxUserHash() : "0");
        map.put("user_properties", "{}");
        map.put("resolution_width", String.valueOf(config.getGameWidth()));
        map.put("resolution_height", String.valueOf(config.getGameHeight()));
        return map;
    }

    private static String resolveAccessToken(AppConfig config) {
        if (config != null && "msa".equalsIgnoreCase(config.getAccountType())
                && Validation.isNonBlank(config.getMinecraftAccessToken())) {
            return config.getMinecraftAccessToken();
        }
        return "0";
    }

    private static String resolveUserType(AppConfig config) {
        if (config != null && Validation.isNonBlank(config.getAccountType())) {
            String t = config.getAccountType().toLowerCase(Locale.ROOT);
            if ("msa".equals(t) || "microsoft".equals(t)) {
                return "msa";
            }
            if ("mojang".equals(t)) {
                return "mojang";
            }
        }
        return "legacy";
    }

    private void ensureLibrariesPresent(AppConfig config, VersionJson version) throws LauncherException {
        List<Path> missing = MavenLibraryResolver.listMissingLibraries(version);
        if (missing.isEmpty()) {
            return;
        }
        LOG.warn("Missing {} libraries before launch, attempting download…", missing.size());
        DownloadSource source = DownloadSource.fromId(config == null ? null : config.getDownloadSource());
        ResumableDownloader downloader = new ResumableDownloader(HttpClientFactory.create(config), source);
        try {
            MavenLibraryResolver.ensureLibraries(version, downloader);
        } catch (LauncherException e) {
            LOG.error("Ensure libraries incomplete", e);
        }
        List<Path> stillMissing = MavenLibraryResolver.listMissingLibraries(version);
        if (!stillMissing.isEmpty()) {
            String sample = stillMissing.stream()
                    .limit(6)
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.joining(", "));
            throw new LauncherException("启动依赖不完整，缺少 "
                    + stillMissing.size() + " 个库（如 " + sample
                    + "）。请重新安装该版本或检查网络后重试。");
        }
    }

    private List<String> buildClasspath(VersionJson version, Path clientJar) throws LauncherException {
        List<String> classpath = new ArrayList<>();
        List<String> missingNames = new ArrayList<>();
        String classifierKey = OsRules.nativesClassifierKey();
        if (version.getLibraries() != null) {
            for (VersionJson.Library library : version.getLibraries()) {
                if (!OsRules.allows(library.getRules())) {
                    continue;
                }
                // Classic natives-only jars are extracted, not on -cp.
                // Modern :natives-* artifacts stay on classpath (LWJGL3 / HMCL model).
                if (NativesExtractor.isClassicNativesOnly(library)) {
                    continue;
                }
                if (Validation.isNonBlank(library.getName())
                        && library.getName().toLowerCase(Locale.ROOT).contains(":natives-")
                        && !NativesExtractor.isNativeLibraryName(library.getName(), classifierKey)) {
                    // Other platform's natives jar — skip.
                    continue;
                }
                Path lib = null;
                if (library.getDownloads() != null && library.getDownloads().getArtifact() != null
                        && library.getDownloads().getArtifact().getPath() != null) {
                    lib = AppPaths.librariesDir()
                            .resolve(library.getDownloads().getArtifact().getPath().replace('\\', '/'));
                } else if (Validation.isNonBlank(library.getName())) {
                    try {
                        lib = AppPaths.librariesDir().resolve(MavenLibraryResolver.mavenPath(library.getName()));
                    } catch (Exception ignored) {
                        lib = null;
                    }
                }
                if (lib != null && Files.isRegularFile(lib)) {
                    classpath.add(lib.toAbsolutePath().toString());
                } else if (lib != null && Validation.isNonBlank(library.getName())) {
                    missingNames.add(library.getName());
                }
            }
        }
        if (!missingNames.isEmpty()) {
            throw new LauncherException("Classpath 缺少依赖: " + String.join(", ",
                    missingNames.subList(0, Math.min(6, missingNames.size()))));
        }
        String main = version.getMainClass() == null ? "" : version.getMainClass().toLowerCase(Locale.ROOT);
        if (main.contains("knotclient")) {
            boolean hasLoader = classpath.stream().anyMatch(p -> {
                String n = p.toLowerCase(Locale.ROOT);
                return n.contains("fabric-loader") || n.contains("quilt-loader");
            });
            if (!hasLoader) {
                throw new LauncherException(
                        "未找到 Fabric/Quilt Loader 库（KnotClient）。请重新安装该加载器版本。");
            }
        }
        classpath.add(clientJar.toAbsolutePath().toString());
        return classpath.stream().distinct().collect(Collectors.toList());
    }

    private VersionJson loadMergedVersion(String versionId) throws LauncherException {
        Set<String> seen = new HashSet<>();
        VersionJson current = readVersionJson(versionId);
        seen.add(versionId);
        while (Validation.isNonBlank(current.getInheritsFrom())) {
            String parentId = current.getInheritsFrom();
            if (!seen.add(parentId)) {
                throw new LauncherException("版本继承循环: " + versionId);
            }
            VersionJson parent = readVersionJson(parentId);
            current = VersionJsonMerger.merge(parent, current);
        }
        return current;
    }

    private VersionJson readVersionJson(String versionId) throws LauncherException {
        Validation.requireSafeVersionId(versionId);
        Path path = AppPaths.versionsDir().resolve(versionId).resolve(versionId + ".json");
        try {
            VersionJson json = JsonUtil.fromJson(Files.readString(path, StandardCharsets.UTF_8), VersionJson.class);
            if (json == null) {
                throw new LauncherException("版本 JSON 无效: " + versionId);
            }
            return json;
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            throw new LauncherException("读取版本 JSON 失败: " + versionId, e);
        }
    }

    private Path resolveClientJar(String versionId, VersionJson version) {
        Path own = AppPaths.versionsDir().resolve(versionId).resolve(versionId + ".jar");
        if (Files.isRegularFile(own)) {
            return own;
        }
        if (Validation.isNonBlank(version.getInheritsFrom())) {
            Path parent = AppPaths.versionsDir()
                    .resolve(version.getInheritsFrom())
                    .resolve(version.getInheritsFrom() + ".jar");
            if (Files.isRegularFile(parent)) {
                return parent;
            }
        }
        String base = com.blacklauncher.core.util.McVersionUtil.extractBaseVersion(versionId);
        if (Validation.isSafeVersionId(base)) {
            Path vanilla = AppPaths.versionsDir().resolve(base).resolve(base + ".jar");
            if (Files.isRegularFile(vanilla)) {
                return vanilla;
            }
        }
        return own;
    }
}
