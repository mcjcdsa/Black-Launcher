package com.blacklauncher.mod;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.net.HttpClientFactory;
import com.blacklauncher.core.util.JsonUtil;
import com.blacklauncher.core.util.Validation;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Modrinth API client for browsing and downloading mods / shaders / modpacks.
 */
public class ModrinthClient {

    private static final String API = "https://api.modrinth.com/v2";
    private static final String USER_AGENT = "BlackLauncher/1.0.0 (contact: black-launcher)";
    private static final String SITE = "https://modrinth.com/mod/";

    private final OkHttpClient client;

    public ModrinthClient(AppConfig config) {
        this.client = HttpClientFactory.create(config);
    }

    /**
     * Search or browse popular projects. Empty query returns downloads-ranked catalog.
     */
    public List<ModProject> search(String query, String gameVersion, String loader, String projectType, int limit)
            throws LauncherException {
        boolean browsing = !Validation.isNonBlank(query);
        HttpUrl.Builder url = HttpUrl.parse(API + "/search").newBuilder()
                .addQueryParameter("limit", String.valueOf(Math.max(1, Math.min(limit, 50))))
                .addQueryParameter("index", browsing ? "downloads" : "relevance");
        if (!browsing) {
            url.addQueryParameter("query", query.trim());
        }

        List<String> facets = new ArrayList<>();
        facets.add("[\"project_type:" + (Validation.isNonBlank(projectType) ? projectType : "mod") + "\"]");
        if (Validation.isNonBlank(gameVersion)) {
            facets.add("[\"versions:" + gameVersion.trim() + "\"]");
        }
        if (Validation.isNonBlank(loader) && !"全部".equals(loader) && !"all".equalsIgnoreCase(loader)) {
            facets.add("[\"categories:" + loader.trim().toLowerCase(Locale.ROOT) + "\"]");
        }
        url.addQueryParameter("facets", "[" + String.join(",", facets) + "]");

        JsonObject root = getJson(url.build());
        JsonArray hits = root.getAsJsonArray("hits");
        List<ModProject> projects = new ArrayList<>();
        if (hits != null) {
            for (JsonElement element : hits) {
                projects.add(parseSearchHit(element.getAsJsonObject()));
            }
        }
        return projects;
    }

    public ProjectDetail getProject(String idOrSlug) throws LauncherException {
        if (!Validation.isNonBlank(idOrSlug)) {
            throw new LauncherException("模组 ID 为空");
        }
        JsonObject obj = getJson(HttpUrl.parse(API + "/project/" + idOrSlug.trim()).newBuilder().build());
        return parseProjectDetail(obj);
    }

    public ModFile latestFile(String projectId, String gameVersion, String loader) throws LauncherException {
        List<ModVersion> versions = listVersions(projectId, gameVersion, loader);
        if (versions.isEmpty()) {
            versions = listVersions(projectId, gameVersion, null);
        }
        if (versions.isEmpty()) {
            versions = listVersions(projectId, null, null);
        }
        if (versions.isEmpty()) {
            throw new LauncherException("未找到可下载的文件");
        }
        return versions.get(0).primaryFile();
    }

    public List<ModVersion> listVersions(String projectId, String gameVersion, String loader)
            throws LauncherException {
        HttpUrl.Builder url = HttpUrl.parse(API + "/project/" + projectId + "/version").newBuilder();
        if (Validation.isNonBlank(gameVersion)) {
            url.addQueryParameter("game_versions", "[\"" + gameVersion.trim() + "\"]");
        }
        if (Validation.isNonBlank(loader) && !"全部".equals(loader) && !"all".equalsIgnoreCase(loader)) {
            url.addQueryParameter("loaders", "[\"" + loader.trim().toLowerCase(Locale.ROOT) + "\"]");
        }

        JsonArray versions = getJsonArray(url.build());
        List<ModVersion> result = new ArrayList<>();
        if (versions == null) {
            return result;
        }
        for (JsonElement element : versions) {
            ModVersion parsed = parseVersion(element.getAsJsonObject());
            if (parsed != null) {
                result.add(parsed);
            }
        }
        return result;
    }

    /** All versions for a project (client-side filter/sort on detail page). */
    public List<ModVersion> listAllVersions(String projectId) throws LauncherException {
        return listVersions(projectId, null, null);
    }

    public static String projectPageUrl(String slugOrId) {
        return SITE + (slugOrId == null ? "" : slugOrId);
    }

    private ModVersion parseVersion(JsonObject version) {
        JsonArray files = version.getAsJsonArray("files");
        if (files == null || files.isEmpty()) {
            return null;
        }
        JsonObject primary = files.get(0).getAsJsonObject();
        for (JsonElement fileEl : files) {
            JsonObject f = fileEl.getAsJsonObject();
            if (f.has("primary") && f.get("primary").getAsBoolean()) {
                primary = f;
                break;
            }
        }
        String sha1 = "";
        if (primary.has("hashes") && primary.getAsJsonObject("hashes").has("sha1")) {
            sha1 = primary.getAsJsonObject("hashes").get("sha1").getAsString();
        }
        long size = primary.has("size") ? primary.get("size").getAsLong() : 0L;
        List<String> loaders = readStringArray(version.getAsJsonArray("loaders"));
        List<String> gameVersions = readStringArray(version.getAsJsonArray("game_versions"));
        ModFile file = new ModFile(
                primary.get("filename").getAsString(),
                primary.get("url").getAsString(),
                sha1,
                size
        );
        String versionType = version.has("version_type") ? version.get("version_type").getAsString() : "release";
        String changelog = version.has("changelog") && !version.get("changelog").isJsonNull()
                ? version.get("changelog").getAsString() : "";
        long published = parseInstant(version.has("date_published") ? version.get("date_published").getAsString() : null);
        List<ModDependency> deps = parseDependencies(version.getAsJsonArray("dependencies"));
        return new ModVersion(
                version.get("id").getAsString(),
                version.has("version_number") ? version.get("version_number").getAsString() : "",
                version.has("name") ? version.get("name").getAsString() : "",
                loaders,
                gameVersions,
                file,
                versionType,
                changelog,
                published,
                deps
        );
    }

    private List<ModDependency> parseDependencies(JsonArray array) {
        List<ModDependency> list = new ArrayList<>();
        if (array == null) {
            return list;
        }
        for (JsonElement el : array) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String projectId = o.has("project_id") && !o.get("project_id").isJsonNull()
                    ? o.get("project_id").getAsString() : null;
            String versionId = o.has("version_id") && !o.get("version_id").isJsonNull()
                    ? o.get("version_id").getAsString() : null;
            String type = o.has("dependency_type") ? o.get("dependency_type").getAsString() : "required";
            if (Validation.isNonBlank(projectId) || Validation.isNonBlank(versionId)) {
                list.add(new ModDependency(projectId, versionId, type));
            }
        }
        return list;
    }

    private ProjectDetail parseProjectDetail(JsonObject obj) {
        String id = obj.has("id") ? obj.get("id").getAsString() : "";
        String slug = obj.has("slug") ? obj.get("slug").getAsString() : id;
        List<String> categories = readStringArray(obj.getAsJsonArray("categories"));
        List<String> loaders = readStringArray(obj.getAsJsonArray("loaders"));
        if (loaders.isEmpty()) {
            loaders = extractLoaders(categories);
        }
        List<String> gameVersions = readStringArray(obj.getAsJsonArray("game_versions"));
        String icon = textOrEmpty(obj, "icon_url");
        String body = textOrEmpty(obj, "body");
        String description = textOrEmpty(obj, "description");
        String license = "";
        if (obj.has("license") && obj.get("license").isJsonObject()) {
            JsonObject lic = obj.getAsJsonObject("license");
            license = textOrEmpty(lic, "name");
            if (!Validation.isNonBlank(license)) {
                license = textOrEmpty(lic, "id");
            }
        }
        String source = textOrEmpty(obj, "source_url");
        String issues = textOrEmpty(obj, "issues_url");
        String discord = textOrEmpty(obj, "discord_url");
        String wiki = textOrEmpty(obj, "wiki_url");
        String published = textOrEmpty(obj, "published");
        String updated = textOrEmpty(obj, "updated");
        long downloads = obj.has("downloads") ? obj.get("downloads").getAsLong() : 0L;
        long followers = obj.has("followers") ? obj.get("followers").getAsLong() : 0L;
        String title = textOrEmpty(obj, "title");
        String author = "";
        // team members require extra call; slug used as fallback display
        List<String> gallery = new ArrayList<>();
        if (obj.has("gallery") && obj.get("gallery").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("gallery")) {
                if (el.isJsonObject() && el.getAsJsonObject().has("url")) {
                    gallery.add(el.getAsJsonObject().get("url").getAsString());
                }
            }
        }
        return new ProjectDetail(
                id, slug, title, description, body, downloads, followers, author,
                categories, loaders, gameVersions, icon, license, source, issues, discord, wiki,
                parseInstant(published), parseInstant(updated), gallery
        );
    }

    private ModProject parseSearchHit(JsonObject obj) {
        List<String> categories = readStringArray(obj.getAsJsonArray("categories"));
        String icon = textOrEmpty(obj, "icon_url");
        String author = textOrEmpty(obj, "author");
        String projectId = obj.has("project_id") ? obj.get("project_id").getAsString()
                : (obj.has("id") ? obj.get("id").getAsString() : "");
        return new ModProject(
                projectId,
                textOrEmpty(obj, "slug"),
                textOrEmpty(obj, "title"),
                textOrEmpty(obj, "description"),
                obj.has("downloads") ? obj.get("downloads").getAsLong() : 0L,
                author,
                categories,
                icon
        );
    }

    private static List<String> extractLoaders(List<String> categories) {
        List<String> loaders = new ArrayList<>();
        if (categories == null) {
            return loaders;
        }
        for (String c : categories) {
            String lower = c.toLowerCase(Locale.ROOT);
            if (lower.equals("fabric") || lower.equals("forge") || lower.equals("quilt")
                    || lower.equals("neoforge") || lower.equals("liteloader") || lower.equals("rift")) {
                loaders.add(lower);
            }
        }
        return loaders;
    }

    private static String textOrEmpty(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return "";
        }
        return obj.get(key).getAsString();
    }

    private static long parseInstant(String iso) {
        if (!Validation.isNonBlank(iso)) {
            return 0L;
        }
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
    }

    private List<String> readStringArray(JsonArray array) {
        List<String> list = new ArrayList<>();
        if (array == null) {
            return list;
        }
        for (JsonElement el : array) {
            if (!el.isJsonNull()) {
                list.add(el.getAsString());
            }
        }
        return list;
    }

    private JsonObject getJson(HttpUrl url) throws LauncherException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new LauncherException("Modrinth 请求失败: HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new LauncherException("Modrinth 响应为空");
            }
            return JsonUtil.parse(body.string()).getAsJsonObject();
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            throw new LauncherException("Modrinth 请求失败: " + e.getMessage(), e);
        }
    }

    private JsonArray getJsonArray(HttpUrl url) throws LauncherException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new LauncherException("Modrinth 请求失败: HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new LauncherException("Modrinth 响应为空");
            }
            return JsonUtil.parse(body.string()).getAsJsonArray();
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            throw new LauncherException("Modrinth 请求失败: " + e.getMessage(), e);
        }
    }

    public record ModProject(
            String id,
            String slug,
            String title,
            String description,
            long downloads,
            String author,
            List<String> categories,
            String iconUrl
    ) {
        public String loadersLabel() {
            List<String> loaders = extractLoaders(categories);
            return String.join(", ", loaders);
        }
    }

    public record ModFile(String fileName, String url, String sha1, long size) {
        public ModFile(String fileName, String url, String sha1) {
            this(fileName, url, sha1, 0L);
        }
    }

    public record ModDependency(String projectId, String versionId, String dependencyType) {
        public boolean required() {
            return "required".equalsIgnoreCase(dependencyType);
        }

        public boolean optional() {
            return "optional".equalsIgnoreCase(dependencyType);
        }

        public boolean incompatible() {
            return "incompatible".equalsIgnoreCase(dependencyType);
        }
    }

    public record ModVersion(
            String id,
            String versionNumber,
            String name,
            List<String> loaders,
            List<String> gameVersions,
            ModFile primaryFile,
            String versionType,
            String changelog,
            long datePublished,
            List<ModDependency> dependencies
    ) {
        public boolean hasRequiredDeps() {
            return dependencies != null && dependencies.stream().anyMatch(ModDependency::required);
        }

        public boolean hasConflicts() {
            return dependencies != null && dependencies.stream().anyMatch(ModDependency::incompatible);
        }

        public String versionTypeLabel() {
            if (versionType == null) {
                return "稳定";
            }
            return switch (versionType.toLowerCase(Locale.ROOT)) {
                case "beta" -> "测试";
                case "alpha" -> "开发";
                default -> "稳定";
            };
        }
    }

    public record ProjectDetail(
            String id,
            String slug,
            String title,
            String description,
            String body,
            long downloads,
            long followers,
            String author,
            List<String> categories,
            List<String> loaders,
            List<String> gameVersions,
            String iconUrl,
            String license,
            String sourceUrl,
            String issuesUrl,
            String discordUrl,
            String wikiUrl,
            long publishedAt,
            long updatedAt,
            List<String> gallery
    ) {
        public ModProject asProject() {
            return new ModProject(id, slug, title, description, downloads, author, categories, iconUrl);
        }
    }
}
