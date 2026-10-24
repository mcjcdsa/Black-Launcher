package com.blacklauncher.moddev;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.net.HttpClientFactory;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.JsonUtil;
import com.blacklauncher.core.util.Validation;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fabric meta.fabricmc.net client with BMCL fallback and 24h disk cache.
 */
public class FabricMetaClient {

    public static final String LOOM_VERSION = "1.9-SNAPSHOT";

    /** Same bases as {@code ModLoaderInstallService.FABRIC_META_BASES}. */
    public static final String[] FABRIC_META_BASES = {
            "https://meta.fabricmc.net/v2",
            "https://bmclapi2.bangbang93.com/fabric-meta/v2"
    };

    private static final Logger LOG = LoggerFactory.getLogger(FabricMetaClient.class);
    private static final long CACHE_TTL_MS = Duration.ofHours(24).toMillis();
    private static final String FABRIC_API_METADATA =
            "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml";
    private static final Pattern VERSION_TAG = Pattern.compile("<version>([^<]+)</version>");

    private final OkHttpClient client;

    public FabricMetaClient(AppConfig config) {
        this.client = HttpClientFactory.create(config);
    }

    public FabricMetaClient(OkHttpClient client) {
        this.client = client != null ? client : HttpClientFactory.create(null);
    }

    public record GameVersion(String version, boolean stable) {
    }

    public List<GameVersion> listGameVersions() throws Exception {
        String json = fetchCached("/versions/game", "versions-game.json");
        JsonArray arr = JsonUtil.parse(json).getAsJsonArray();
        List<GameVersion> out = new ArrayList<>();
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            String version = o.has("version") ? o.get("version").getAsString() : null;
            if (!Validation.isNonBlank(version)) {
                continue;
            }
            boolean stable = o.has("stable") && o.get("stable").getAsBoolean();
            out.add(new GameVersion(version, stable));
        }
        return out;
    }

    public List<String> listYarnVersions(String gameVersion) throws Exception {
        if (!Validation.isNonBlank(gameVersion)) {
            return List.of();
        }
        String path = "/versions/yarn/" + gameVersion.trim();
        String json = fetchCached(path, "yarn-" + sanitizeKey(gameVersion) + ".json");
        JsonArray arr = JsonUtil.parse(json).getAsJsonArray();
        List<YarnEntry> entries = new ArrayList<>();
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            if (!o.has("version")) {
                continue;
            }
            String version = o.get("version").getAsString();
            boolean stable = o.has("stable") && o.get("stable").getAsBoolean();
            entries.add(new YarnEntry(version, stable));
        }
        entries.sort(Comparator
                .comparing((YarnEntry e) -> !e.stable)
                .thenComparing(e -> e.version, Comparator.reverseOrder()));
        List<String> out = new ArrayList<>(entries.size());
        for (YarnEntry e : entries) {
            out.add(e.version);
        }
        return out;
    }

    public Optional<String> latestStableYarn(String gameVersion) {
        try {
            List<String> versions = listYarnVersions(gameVersion);
            return versions.isEmpty() ? Optional.empty() : Optional.of(versions.get(0));
        } catch (Exception e) {
            LOG.warn("Failed to resolve yarn for {}", gameVersion, e);
            return Optional.empty();
        }
    }

    public Optional<String> latestStableLoader(String gameVersion) {
        if (!Validation.isNonBlank(gameVersion)) {
            return Optional.empty();
        }
        try {
            String path = "/versions/loader/" + gameVersion.trim();
            String json = fetchCached(path, "loader-" + sanitizeKey(gameVersion) + ".json");
            JsonArray arr = JsonUtil.parse(json).getAsJsonArray();
            String first = null;
            for (JsonElement el : arr) {
                JsonObject root = el.getAsJsonObject();
                JsonObject loader = root.has("loader") ? root.getAsJsonObject("loader") : root;
                if (!loader.has("version")) {
                    continue;
                }
                String version = loader.get("version").getAsString();
                boolean stable = loader.has("stable") && loader.get("stable").getAsBoolean();
                if (first == null) {
                    first = version;
                }
                if (stable) {
                    return Optional.of(version);
                }
            }
            return Optional.ofNullable(first);
        } catch (Exception e) {
            LOG.warn("Failed to resolve loader for {}", gameVersion, e);
            return Optional.empty();
        }
    }

    /**
     * Picks the newest fabric-api Maven version whose version string contains {@code gameVersion}.
     */
    public Optional<String> resolveFabricApi(String gameVersion) {
        if (!Validation.isNonBlank(gameVersion)) {
            return Optional.empty();
        }
        String gv = gameVersion.trim();
        try {
            String xml = fetchUrlCached(FABRIC_API_METADATA, "fabric-api-maven-metadata.xml");
            List<String> matches = new ArrayList<>();
            Matcher m = VERSION_TAG.matcher(xml);
            while (m.find()) {
                String ver = m.group(1).trim();
                if (ver.contains(gv)) {
                    matches.add(ver);
                }
            }
            if (matches.isEmpty()) {
                return Optional.empty();
            }
            matches.sort(FabricMetaClient::compareMavenVersions);
            return Optional.of(matches.get(matches.size() - 1));
        } catch (Exception e) {
            LOG.warn("Failed to resolve fabric-api for {}", gameVersion, e);
            return Optional.empty();
        }
    }

    private String fetchCached(String metaPath, String cacheFileName) throws Exception {
        Path cacheFile = cacheDir().resolve(cacheFileName);
        if (isCacheFresh(cacheFileName)) {
            return Files.readString(cacheFile, StandardCharsets.UTF_8);
        }
        Exception networkError = null;
        for (String base : FABRIC_META_BASES) {
            String url = base + metaPath;
            try {
                String body = httpGet(url);
                writeCache(cacheFile, body);
                return body;
            } catch (Exception e) {
                networkError = e;
                LOG.warn("Fabric meta failed: {}", url, e);
            }
        }
        if (Files.isRegularFile(cacheFile) && Files.size(cacheFile) > 0) {
            LOG.info("Using cached fabric meta: {}", cacheFile);
            return Files.readString(cacheFile, StandardCharsets.UTF_8);
        }
        throw networkError != null ? networkError : new IllegalStateException("无法获取 Fabric 元数据: " + metaPath);
    }

    private String fetchUrlCached(String url, String cacheFileName) throws Exception {
        Path cacheFile = cacheDir().resolve(cacheFileName);
        if (isCacheFresh(cacheFileName)) {
            return Files.readString(cacheFile, StandardCharsets.UTF_8);
        }
        try {
            String body = httpGet(url);
            writeCache(cacheFile, body);
            return body;
        } catch (Exception e) {
            if (Files.isRegularFile(cacheFile) && Files.size(cacheFile) > 0) {
                LOG.info("Using cached URL body: {}", cacheFile);
                return Files.readString(cacheFile, StandardCharsets.UTF_8);
            }
            throw e;
        }
    }

    private void writeCache(Path cacheFile, String body) {
        try {
            Files.createDirectories(cacheFile.getParent());
            Files.writeString(cacheFile, body, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warn("Failed to write fabric-meta cache {}", cacheFile, e);
        }
    }

    private Path cacheDir() {
        return AppPaths.home().resolve("cache").resolve("fabric-meta");
    }

    private String httpGet(String url) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "BlackLauncher")
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("HTTP " + response.code() + " for " + url);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IllegalStateException("Empty body for " + url);
            }
            return body.string();
        }
    }

    /** Returns true if a cache file exists and is younger than TTL. */
    public boolean isCacheFresh(String cacheFileName) {
        try {
            Path f = cacheDir().resolve(cacheFileName);
            if (!Files.isRegularFile(f)) {
                return false;
            }
            long age = System.currentTimeMillis() - Files.getLastModifiedTime(f).toMillis();
            return age >= 0 && age < CACHE_TTL_MS;
        } catch (Exception e) {
            return false;
        }
    }

    private static String sanitizeKey(String s) {
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }

    private static int compareMavenVersions(String a, String b) {
        String[] pa = a.split("[.+\\-]");
        String[] pb = b.split("[.+\\-]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            String sa = i < pa.length ? pa[i] : "0";
            String sb = i < pb.length ? pb[i] : "0";
            boolean na = sa.chars().allMatch(Character::isDigit);
            boolean nb = sb.chars().allMatch(Character::isDigit);
            int cmp;
            if (na && nb) {
                cmp = Integer.compare(Integer.parseInt(sa), Integer.parseInt(sb));
            } else {
                cmp = sa.compareTo(sb);
            }
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private record YarnEntry(String version, boolean stable) {
    }
}
