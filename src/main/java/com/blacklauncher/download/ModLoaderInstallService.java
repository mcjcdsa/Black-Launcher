package com.blacklauncher.download;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.db.Database;
import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.net.DownloadControl;
import com.blacklauncher.core.net.DownloadProgressSink;
import com.blacklauncher.core.net.DownloadSource;
import com.blacklauncher.core.net.HttpClientFactory;
import com.blacklauncher.core.net.ResumableDownloader;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.JsonUtil;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.launch.GameOptionsConfigurator;
import com.blacklauncher.launch.JavaFinder;
import com.blacklauncher.model.VersionJson;
import com.blacklauncher.model.VersionManifest;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Downloads vanilla game files and optionally installs a mod loader profile.
 */
public class ModLoaderInstallService {

    private static final Logger LOG = LoggerFactory.getLogger(ModLoaderInstallService.class);

    private static final String[] FABRIC_META_BASES = {
            "https://meta.fabricmc.net/v2",
            "https://bmclapi2.bangbang93.com/fabric-meta/v2"
    };
    private static final String[] QUILT_META_BASES = {
            "https://meta.quiltmc.org/v3",
            "https://bmclapi2.bangbang93.com/quilt-meta/v3"
    };
    private static final String FABRIC_MAVEN = "https://maven.fabricmc.net/";
    private static final String QUILT_MAVEN = "https://maven.quiltmc.org/repository/release/";
    private static final String FORGE_PROMOS =
            "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";
    private static final String FORGE_MAVEN = "https://maven.minecraftforge.net/net/minecraftforge/forge/";
    private static final String NEOFORGE_META =
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";
    private static final String NEOFORGE_MAVEN =
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/";

    private final AppConfig config;
    private final Database database;
    private final OkHttpClient client;
    private final ResumableDownloader downloader;
    private final GameDownloadService gameDownloadService;

    public ModLoaderInstallService(AppConfig config, Database database) {
        this.config = config;
        this.database = database;
        this.client = HttpClientFactory.create(config);
        DownloadSource source = DownloadSource.fromId(config == null ? null : config.getDownloadSource());
        this.downloader = new ResumableDownloader(client, source);
        this.gameDownloadService = new GameDownloadService(config, database);
        // Share control so Fabric/Forge installer downloads honor pause / network wait.
        this.downloader.setControl(gameDownloadService.downloadControl());
    }

    public GameDownloadService gameDownloadService() {
        return gameDownloadService;
    }

    public DownloadControl downloadControl() {
        return gameDownloadService.downloadControl();
    }

    public void cancel() {
        gameDownloadService.cancel();
    }

    public void pauseByUser() {
        gameDownloadService.pauseByUser();
    }

    public void resumeByUser() {
        gameDownloadService.resumeByUser();
    }

    /**
     * @return installed version id (may differ from vanilla id when a loader is selected)
     */
    public String downloadWithLoader(VersionManifest.VersionEntry entry,
                                     ModLoader loader,
                                     DownloadProgressSink sink) throws LauncherException {
        return downloadWithLoader(entry, new LoaderInstallChoice(loader, null, false), sink);
    }

    public String downloadWithLoader(VersionManifest.VersionEntry entry,
                                     LoaderInstallChoice choice,
                                     DownloadProgressSink sink) throws LauncherException {
        LoaderInstallChoice c = choice == null ? LoaderInstallChoice.vanilla() : choice;
        return downloadWithLoader(entry, c.loaderOrVanilla(), c.loaderVersion(), null,
                c.installCompanionApi(), sink);
    }

    /**
     * Install MC + loader with optional pinned loader version and preferred profile id (modpacks).
     *
     * @param pinnedLoaderVersion Forge/Fabric/… version string from modpack; null = auto
     * @param preferredProfileId  rename/copy result profile to this id when safe; null = keep default
     */
    public String downloadWithLoader(VersionManifest.VersionEntry entry,
                                     ModLoader loader,
                                     String pinnedLoaderVersion,
                                     String preferredProfileId,
                                     DownloadProgressSink sink) throws LauncherException {
        return downloadWithLoader(entry, loader, pinnedLoaderVersion, preferredProfileId, false, sink);
    }

    public String downloadWithLoader(VersionManifest.VersionEntry entry,
                                     ModLoader loader,
                                     String pinnedLoaderVersion,
                                     String preferredProfileId,
                                     boolean installCompanionApi,
                                     DownloadProgressSink sink) throws LauncherException {
        Validation.requireSafeVersionId(entry.getId());
        ModLoader selected = loader == null ? ModLoader.VANILLA : loader;

        report(sink, "下载原版 " + entry.getId() + "...", 0.01);
        gameDownloadService.downloadVersion(entry, sink);

        if (selected == ModLoader.VANILLA) {
            String id = entry.getId();
            if (Validation.isSafeVersionId(preferredProfileId) && !preferredProfileId.equals(id)) {
                id = relocateProfile(id, preferredProfileId);
            }
            GameOptionsConfigurator.applyIfEnabled(config, id);
            if (sink != null) {
                sink.complete("原版安装完成");
            }
            return id;
        }

        report(sink, "安装 " + selected.displayName() + "...", 0.75);
        String profileId = switch (selected) {
            case FABRIC -> installFabric(entry.getId(), pinnedLoaderVersion, sink);
            case QUILT -> installQuilt(entry.getId(), pinnedLoaderVersion, sink);
            case FORGE -> installForge(entry.getId(), pinnedLoaderVersion, sink);
            case NEOFORGE -> installNeoForge(entry.getId(), pinnedLoaderVersion, sink);
            default -> entry.getId();
        };

        if (Validation.isSafeVersionId(preferredProfileId) && !preferredProfileId.equals(profileId)) {
            profileId = relocateProfile(profileId, preferredProfileId);
        }

        try {
            database.upsertInstalledVersion(profileId, selected.id());
        } catch (Exception e) {
            throw new LauncherException("记录加载器版本失败", e);
        }

        if (installCompanionApi) {
            try {
                LoaderApiInstaller.installIfNeeded(config, selected, entry.getId(), profileId, sink);
            } catch (LauncherException apiEx) {
                // Do not roll back loader — surface warning via status then continue.
                LOG.warn("Companion API install failed: {}", apiEx.getMessage());
                report(sink, apiEx.getMessage(), 0.97);
            }
        } else if (LoaderApiInstaller.supportsCompanionApi(selected)) {
            report(sink, "已跳过配套 API 安装", 0.97);
        }

        GameOptionsConfigurator.applyIfEnabled(config, profileId);
        if (sink != null) {
            sink.complete(selected.displayName() + " 安装完成: " + profileId);
        }
        return profileId;
    }

    /** Copy a version profile folder to a new id (for named modpack installs). */
    public String relocateProfile(String fromId, String toId) throws LauncherException {
        Validation.requireSafeVersionId(fromId);
        Validation.requireSafeVersionId(toId);
        if (fromId.equals(toId)) {
            return toId;
        }
        Path src = AppPaths.versionsDir().resolve(fromId);
        Path dst = AppPaths.versionsDir().resolve(toId);
        if (!Files.isDirectory(src)) {
            throw new LauncherException("源版本不存在: " + fromId);
        }
        try {
            if (Files.exists(dst)) {
                deleteRecursive(dst);
            }
            copyRecursive(src, dst);
            Path json = dst.resolve(fromId + ".json");
            Path jsonNew = dst.resolve(toId + ".json");
            if (Files.isRegularFile(json)) {
                VersionJson v = JsonUtil.fromJson(Files.readString(json, StandardCharsets.UTF_8), VersionJson.class);
                v.setId(toId);
                Files.writeString(jsonNew, JsonUtil.toJsonPretty(v), StandardCharsets.UTF_8);
                if (!json.equals(jsonNew)) {
                    Files.deleteIfExists(json);
                }
            }
            Path jar = dst.resolve(fromId + ".jar");
            Path jarNew = dst.resolve(toId + ".jar");
            if (Files.isRegularFile(jar) && !Files.isRegularFile(jarNew)) {
                Files.move(jar, jarNew);
            }
            return toId;
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            throw new LauncherException("重命名版本失败: " + e.getMessage(), e);
        }
    }

    private static void copyRecursive(Path src, Path dst) throws Exception {
        Files.walk(src).forEach(p -> {
            try {
                Path rel = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(rel);
                } else {
                    Files.createDirectories(rel.getParent());
                    Files.copy(p, rel);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    public static void deleteRecursive(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // best-effort cleanup
                }
            });
        }
    }

    private String installFabric(String gameVersion, String pinnedLoaderVersion, DownloadProgressSink sink)
            throws LauncherException {
        String loaderVersion = Validation.isNonBlank(pinnedLoaderVersion)
                ? pinnedLoaderVersion.trim()
                : pickStableOrFirstLoader(metaGetJsonArray(FABRIC_META_BASES, "/versions/loader/" + gameVersion),
                "Fabric", gameVersion);
        report(sink, "组装 Fabric " + loaderVersion + " 配置...", 0.78);
        JsonObject loaderInfo = metaGetJsonObject(FABRIC_META_BASES,
                "/versions/loader/" + gameVersion + "/" + loaderVersion);
        VersionJson profile = buildFabricLikeProfile(loaderInfo, gameVersion, "fabric-loader", FABRIC_MAVEN);
        return writeMergedProfile(profile, gameVersion, sink);
    }

    private String installQuilt(String gameVersion, String pinnedLoaderVersion, DownloadProgressSink sink)
            throws LauncherException {
        String loaderVersion = Validation.isNonBlank(pinnedLoaderVersion)
                ? pinnedLoaderVersion.trim()
                : pickStableOrFirstLoader(metaGetJsonArray(QUILT_META_BASES, "/versions/loader/" + gameVersion),
                "Quilt", gameVersion);
        report(sink, "组装 Quilt " + loaderVersion + " 配置...", 0.78);
        JsonObject loaderInfo = metaGetJsonObject(QUILT_META_BASES,
                "/versions/loader/" + gameVersion + "/" + loaderVersion);
        VersionJson profile = buildFabricLikeProfile(loaderInfo, gameVersion, "quilt-loader", QUILT_MAVEN);
        return writeMergedProfile(profile, gameVersion, sink);
    }

    /**
     * Builds a launcher profile from Fabric/Quilt loader+launcherMeta JSON
     * (avoids the fragile /installer/.../profile/json endpoint that often 404s).
     */
    private VersionJson buildFabricLikeProfile(JsonObject loaderInfo, String gameVersion,
                                               String idPrefix, String defaultMaven)
            throws LauncherException {
        if (loaderInfo == null || !loaderInfo.has("loader") || !loaderInfo.has("launcherMeta")) {
            throw new LauncherException("加载器元数据不完整");
        }
        JsonObject loader = loaderInfo.getAsJsonObject("loader");
        JsonObject meta = loaderInfo.getAsJsonObject("launcherMeta");

        String loaderVer = loader.get("version").getAsString();
        String profileId = idPrefix + "-" + loaderVer + "-" + gameVersion;
        Validation.requireSafeVersionId(profileId);

        VersionJson profile = new VersionJson();
        profile.setId(profileId);
        profile.setInheritsFrom(gameVersion);
        profile.setType("release");

        String mainClass = null;
        if (meta.has("mainClass")) {
            JsonElement mc = meta.get("mainClass");
            if (mc.isJsonPrimitive()) {
                mainClass = mc.getAsString();
            } else if (mc.isJsonObject() && mc.getAsJsonObject().has("client")) {
                mainClass = mc.getAsJsonObject().get("client").getAsString();
            }
        }
        if (!Validation.isNonBlank(mainClass)) {
            mainClass = idPrefix.startsWith("quilt")
                    ? "org.quiltmc.loader.impl.launch.knot.KnotClient"
                    : "net.fabricmc.loader.impl.launch.knot.KnotClient";
        }
        profile.setMainClass(mainClass);
        if (meta.has("arguments")) {
            profile.setArguments(meta.get("arguments"));
        }

        List<VersionJson.Library> libraries = new ArrayList<>();
        addMavenArtifact(libraries, loaderInfo.getAsJsonObject("intermediary"), defaultMaven);
        addMavenArtifact(libraries, loaderInfo.getAsJsonObject("hashed"), defaultMaven);
        if (loader.has("maven")) {
            libraries.add(mavenLibrary(loader.get("maven").getAsString(), defaultMaven));
        }
        if (meta.has("libraries")) {
            JsonObject libs = meta.getAsJsonObject("libraries");
            appendMetaLibraries(libraries, libs.getAsJsonArray("common"), defaultMaven);
            appendMetaLibraries(libraries, libs.getAsJsonArray("client"), defaultMaven);
        }
        profile.setLibraries(libraries);
        return profile;
    }

    private static void addMavenArtifact(List<VersionJson.Library> out, JsonObject artifact, String defaultMaven) {
        if (artifact != null && artifact.has("maven")) {
            out.add(mavenLibrary(artifact.get("maven").getAsString(), defaultMaven));
        }
    }

    private static VersionJson.Library mavenLibrary(String mavenCoord, String url) {
        VersionJson.Library lib = new VersionJson.Library();
        lib.setName(mavenCoord);
        lib.setUrl(url);
        return lib;
    }

    private static void appendMetaLibraries(List<VersionJson.Library> out, JsonArray array, String defaultMaven) {
        if (array == null) {
            return;
        }
        for (JsonElement el : array) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject obj = el.getAsJsonObject();
            if (!obj.has("name")) {
                continue;
            }
            String name = obj.get("name").getAsString();
            String url = obj.has("url") ? obj.get("url").getAsString() : defaultMaven;
            out.add(mavenLibrary(name, url));
        }
    }

    private String writeMergedProfile(VersionJson profile, String gameVersion, DownloadProgressSink sink)
            throws LauncherException {
        VersionJson vanilla = readInstalledVersionJson(gameVersion);
        VersionJson merged = VersionJsonMerger.merge(vanilla, profile);
        if (!Validation.isNonBlank(merged.getId())) {
            merged.setId(profile.getId());
        }
        Validation.requireSafeVersionId(merged.getId());

        Path versionDir = AppPaths.versionsDir().resolve(merged.getId());
        try {
            Files.createDirectories(versionDir);
            Files.writeString(versionDir.resolve(merged.getId() + ".json"),
                    JsonUtil.toJsonPretty(merged), StandardCharsets.UTF_8);

            Path vanillaJar = AppPaths.versionsDir().resolve(gameVersion).resolve(gameVersion + ".jar");
            Path profileJar = versionDir.resolve(merged.getId() + ".jar");
            if (Files.isRegularFile(vanillaJar) && !Files.isRegularFile(profileJar)) {
                Files.copy(vanillaJar, profileJar);
            }
        } catch (Exception e) {
            throw new LauncherException("写入加载器版本失败", e);
        }

        report(sink, "下载加载器依赖库...", 0.85);
        // Only fetch jars that are not already present (vanilla libs were downloaded earlier).
        MavenLibraryResolver.downloadLibraries(merged, downloader, true);
        List<Path> missing = MavenLibraryResolver.listMissingLibraries(merged);
        if (!missing.isEmpty()) {
            throw new LauncherException("加载器依赖仍缺失 " + missing.size()
                    + " 个（例如 " + missing.get(0).getFileName() + "）");
        }
        String main = merged.getMainClass() == null ? "" : merged.getMainClass().toLowerCase(Locale.ROOT);
        if (main.contains("knotclient")) {
            boolean loaderOk = merged.getLibraries() != null && merged.getLibraries().stream()
                    .map(VersionJson.Library::getName)
                    .filter(Validation::isNonBlank)
                    .anyMatch(n -> {
                        String lower = n.toLowerCase(Locale.ROOT);
                        if (!(lower.contains("fabric-loader") || lower.contains("quilt-loader"))) {
                            return false;
                        }
                        try {
                            return Files.isRegularFile(AppPaths.librariesDir()
                                    .resolve(MavenLibraryResolver.mavenPath(n)));
                        } catch (Exception e) {
                            return false;
                        }
                    });
            if (!loaderOk) {
                throw new LauncherException("Fabric/Quilt Loader 库未正确下载，无法启动 KnotClient");
            }
        }
        return merged.getId();
    }

    private String installForge(String gameVersion, String pinnedLoaderVersion, DownloadProgressSink sink)
            throws LauncherException {
        String forgeVer = Validation.isNonBlank(pinnedLoaderVersion)
                ? pinnedLoaderVersion.trim()
                : resolveForgeVersion(gameVersion);
        String full = gameVersion + "-" + forgeVer;
        String installerUrl = FORGE_MAVEN + full + "/forge-" + full + "-installer.jar";
        Path installer = AppPaths.downloadsTempDir().resolve("forge-" + full + "-installer.jar");
        report(sink, "下载 Forge 安装器 " + forgeVer + "...", 0.80);
        AtomicLong last = new AtomicLong(0);
        downloader.download(installerUrl, installer, null, -1L, (done, total) -> {
            long prev = last.getAndSet(done);
            long delta = done - prev;
            if (delta > 0 && sink != null) {
                sink.addBytes(delta);
            }
        });
        runInstaller(installer, sink, "Forge");
        String profileId = findNewestVersionIdContaining(gameVersion, "forge");
        if (!Validation.isNonBlank(profileId)) {
            profileId = gameVersion + "-forge-" + forgeVer;
        }
        ensureVersionJar(profileId, gameVersion);
        return profileId;
    }

    private String installNeoForge(String gameVersion, String pinnedLoaderVersion, DownloadProgressSink sink)
            throws LauncherException {
        String neoVer = Validation.isNonBlank(pinnedLoaderVersion)
                ? pinnedLoaderVersion.trim()
                : resolveNeoForgeVersion(gameVersion);
        String installerUrl = NEOFORGE_MAVEN + neoVer + "/neoforge-" + neoVer + "-installer.jar";
        Path installer = AppPaths.downloadsTempDir().resolve("neoforge-" + neoVer + "-installer.jar");
        report(sink, "下载 NeoForge 安装器 " + neoVer + "...", 0.80);
        AtomicLong last = new AtomicLong(0);
        downloader.download(installerUrl, installer, null, -1L, (done, total) -> {
            long prev = last.getAndSet(done);
            long delta = done - prev;
            if (delta > 0 && sink != null) {
                sink.addBytes(delta);
            }
        });
        runInstaller(installer, sink, "NeoForge");
        String profileId = findNewestVersionIdContaining(gameVersion, "neoforge");
        if (!Validation.isNonBlank(profileId)) {
            profileId = "neoforge-" + neoVer;
        }
        ensureVersionJar(profileId, gameVersion);
        return profileId;
    }

    private void runInstaller(Path installerJar, DownloadProgressSink sink, String label)
            throws LauncherException {
        report(sink, "运行 " + label + " 安装器...", 0.88);
        String java = JavaFinder.resolveJavaExecutable(config.getJavaPath());
        List<String> command = List.of(
                java,
                "-jar",
                installerJar.toAbsolutePath().toString(),
                "--installClient",
                AppPaths.gameDir().toAbsolutePath().toString()
        );
        try {
            Process process = new ProcessBuilder(command)
                    .directory(AppPaths.gameDir().toFile())
                    .redirectErrorStream(true)
                    .start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                    LOG.info("{} installer: {}", label, line);
                }
            }
            int code = process.waitFor();
            if (code != 0) {
                throw new LauncherException(label + " 安装器退出码 " + code + "\n"
                        + summarize(output.toString()));
            }
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            throw new LauncherException(label + " 安装失败: " + e.getMessage(), e);
        }
    }

    private void ensureVersionJar(String profileId, String gameVersion) throws LauncherException {
        try {
            Path profileDir = AppPaths.versionsDir().resolve(profileId);
            if (!Files.isDirectory(profileDir)) {
                return;
            }
            Path profileJar = profileDir.resolve(profileId + ".jar");
            Path vanillaJar = AppPaths.versionsDir().resolve(gameVersion).resolve(gameVersion + ".jar");
            if (!Files.isRegularFile(profileJar) && Files.isRegularFile(vanillaJar)) {
                Files.copy(vanillaJar, profileJar);
            }
        } catch (Exception e) {
            throw new LauncherException("准备版本 jar 失败", e);
        }
    }

    private String pickStableOrFirstLoader(JsonArray arr, String label, String gameVersion)
            throws LauncherException {
        if (arr == null || arr.isEmpty()) {
            throw new LauncherException("该版本暂无 " + label + " Loader: " + gameVersion);
        }
        for (JsonElement el : arr) {
            JsonObject item = el.getAsJsonObject();
            JsonObject loader = item.getAsJsonObject("loader");
            if (loader != null && loader.has("stable") && loader.get("stable").getAsBoolean()) {
                return loader.get("version").getAsString();
            }
        }
        return arr.get(0).getAsJsonObject().getAsJsonObject("loader").get("version").getAsString();
    }

    private JsonArray metaGetJsonArray(String[] bases, String path) throws LauncherException {
        return JsonUtil.parse(metaGet(bases, path)).getAsJsonArray();
    }

    private JsonObject metaGetJsonObject(String[] bases, String path) throws LauncherException {
        return JsonUtil.parse(metaGet(bases, path)).getAsJsonObject();
    }

    private String metaGet(String[] bases, String path) throws LauncherException {
        LauncherException last = null;
        for (String base : bases) {
            String url = base + path;
            try {
                return httpGet(url);
            } catch (LauncherException e) {
                last = e;
                LOG.warn("Meta request failed: {}", url, e);
            }
        }
        throw last != null ? last : new LauncherException("无法访问加载器元数据: " + path);
    }

    private String resolveForgeVersion(String gameVersion) throws LauncherException {
        JsonObject root = JsonUtil.parse(httpGet(FORGE_PROMOS)).getAsJsonObject();
        JsonObject promos = root.getAsJsonObject("promos");
        if (promos == null) {
            throw new LauncherException("无法解析 Forge 版本列表");
        }
        if (promos.has(gameVersion + "-recommended")) {
            return promos.get(gameVersion + "-recommended").getAsString();
        }
        if (promos.has(gameVersion + "-latest")) {
            return promos.get(gameVersion + "-latest").getAsString();
        }
        throw new LauncherException("该版本暂无 Forge: " + gameVersion);
    }

    private String resolveNeoForgeVersion(String gameVersion) throws LauncherException {
        String xml = httpGet(NEOFORGE_META);
        String prefix = neoPrefix(gameVersion);
        List<String> versions = new ArrayList<>();
        int idx = 0;
        while (true) {
            int start = xml.indexOf("<version>", idx);
            if (start < 0) {
                break;
            }
            int end = xml.indexOf("</version>", start);
            if (end < 0) {
                break;
            }
            String ver = xml.substring(start + "<version>".length(), end).trim();
            if (ver.startsWith(prefix)) {
                versions.add(ver);
            }
            idx = end + 1;
        }
        if (versions.isEmpty()) {
            throw new LauncherException("该版本暂无 NeoForge: " + gameVersion);
        }
        versions.sort(Comparator.naturalOrder());
        return versions.get(versions.size() - 1);
    }

    private static String neoPrefix(String gameVersion) {
        // 1.20.1 -> 20.1. ; 1.21 -> 21.0.
        String rest = gameVersion.startsWith("1.") ? gameVersion.substring(2) : gameVersion;
        if (!rest.contains(".")) {
            return rest + ".0.";
        }
        return rest + ".";
    }

    private VersionJson readInstalledVersionJson(String versionId) throws LauncherException {
        Path path = AppPaths.versionsDir().resolve(versionId).resolve(versionId + ".json");
        try {
            VersionJson json = JsonUtil.fromJson(Files.readString(path, StandardCharsets.UTF_8), VersionJson.class);
            if (json == null) {
                throw new LauncherException("原版 JSON 无效: " + versionId);
            }
            return json;
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            throw new LauncherException("读取原版 JSON 失败: " + versionId, e);
        }
    }

    private String findNewestVersionIdContaining(String gameVersion, String keyword) {
        Path versions = AppPaths.versionsDir();
        if (!Files.isDirectory(versions)) {
            return null;
        }
        try (var stream = Files.list(versions)) {
            return stream.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(id -> id.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT)))
                    .filter(id -> id.contains(gameVersion) || id.toLowerCase(Locale.ROOT).contains(
                            gameVersion.replace(".", "").toLowerCase(Locale.ROOT)))
                    .max(Comparator.naturalOrder())
                    .orElseGet(() -> {
                        try (var stream2 = Files.list(versions)) {
                            return stream2.filter(Files::isDirectory)
                                    .map(p -> p.getFileName().toString())
                                    .filter(id -> id.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT)))
                                    .max(Comparator.comparingLong(id -> {
                                        try {
                                            return Files.getLastModifiedTime(
                                                    versions.resolve(id).resolve(id + ".json")).toMillis();
                                        } catch (Exception e) {
                                            return 0L;
                                        }
                                    }))
                                    .orElse(null);
                        } catch (Exception e) {
                            return null;
                        }
                    });
        } catch (Exception e) {
            return null;
        }
    }

    private String httpGet(String url) throws LauncherException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "BlackLauncher/1.0.0")
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new LauncherException("请求失败 HTTP " + response.code() + ": " + url);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new LauncherException("空响应: " + url);
            }
            return body.string();
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            throw new LauncherException("网络请求失败: " + e.getMessage(), e);
        }
    }

    private static String summarize(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() > 500 ? trimmed.substring(trimmed.length() - 500) : trimmed;
    }

    private void report(DownloadProgressSink sink, String message, double progress) {
        if (sink != null) {
            sink.status(message, Math.max(0, Math.min(1, progress)));
        }
    }
}
