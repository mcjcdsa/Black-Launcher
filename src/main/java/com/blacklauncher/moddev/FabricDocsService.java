package com.blacklauncher.moddev;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.net.HttpClientFactory;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.JsonUtil;
import com.blacklauncher.core.util.Validation;
import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Syncs and searches FabricMC/fabric-docs Markdown for offline viewing.
 */
public class FabricDocsService {

    public static final String SOURCE_URL =
            "https://codeload.github.com/FabricMC/fabric-docs/zip/refs/heads/main";
    public static final String DOCS_SITE = "https://docs.fabricmc.net";
    public static final String GITHUB_REPO = "https://github.com/FabricMC/fabric-docs";
    public static final String COPYRIGHT_NOTE =
            "文档内容来自 FabricMC（docs.fabricmc.net / github.com/FabricMC/fabric-docs），仅供本地查阅。";

    private static final Logger LOG = LoggerFactory.getLogger(FabricDocsService.class);

    private final OkHttpClient client;
    private final Path cacheDir;

    public FabricDocsService(AppConfig config) {
        this(HttpClientFactory.create(config), AppPaths.fabricDocsCacheDir());
    }

    public FabricDocsService(OkHttpClient client, Path cacheDir) {
        this.client = client != null ? client : HttpClientFactory.create(null);
        this.cacheDir = cacheDir != null ? cacheDir : AppPaths.fabricDocsCacheDir();
    }

    public record DocEntry(String path, String title) {
    }

    public record SearchHit(String path, String title, String snippet) {
    }

    public boolean isCached() {
        Path develop = cacheDir.resolve("develop");
        try {
            return Files.isDirectory(develop)
                    && Files.walk(develop).anyMatch(p -> p.toString().endsWith(".md"));
        } catch (IOException e) {
            return false;
        }
    }

    public void sync(Consumer<String> progress) throws Exception {
        Consumer<String> p = progress != null ? progress : s -> {
        };
        Files.createDirectories(cacheDir);
        Path tmpZip = cacheDir.resolve("_fabric-docs-main.zip");
        Path extractRoot = cacheDir.resolve("_extract");
        p.accept("正在下载 Fabric 文档…");
        downloadZip(tmpZip);
        p.accept("正在解压文档…");
        deleteRecursive(extractRoot);
        Files.createDirectories(extractRoot);
        unzip(tmpZip, extractRoot);
        Path repoRoot = findRepoRoot(extractRoot);
        if (repoRoot == null) {
            throw new IllegalStateException("ZIP 中未找到 fabric-docs 内容");
        }
        Path developSrc = repoRoot.resolve("develop");
        Path indexSrc = repoRoot.resolve("index.md");
        Path developDest = cacheDir.resolve("develop");
        deleteRecursive(developDest);
        if (Files.isDirectory(developSrc)) {
            copyTree(developSrc, developDest);
        }
        if (Files.isRegularFile(indexSrc)) {
            Files.copy(indexSrc, cacheDir.resolve("index.md"), StandardCopyOption.REPLACE_EXISTING);
        }
        writeMeta();
        Files.deleteIfExists(tmpZip);
        deleteRecursive(extractRoot);
        p.accept("文档同步完成");
    }

    public List<DocEntry> listDocuments() throws IOException {
        List<DocEntry> out = new ArrayList<>();
        Path index = cacheDir.resolve("index.md");
        if (Files.isRegularFile(index)) {
            out.add(new DocEntry("index.md", titleOf("index.md", Files.readString(index, StandardCharsets.UTF_8))));
        }
        Path develop = cacheDir.resolve("develop");
        if (Files.isDirectory(develop)) {
            Files.walk(develop)
                    .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .forEach(p -> {
                        try {
                            String rel = cacheDir.relativize(p).toString().replace('\\', '/');
                            String md = Files.readString(p, StandardCharsets.UTF_8);
                            out.add(new DocEntry(rel, titleOf(rel, md)));
                        } catch (IOException e) {
                            LOG.warn("Skip doc {}", p, e);
                        }
                    });
        }
        out.sort(Comparator.comparing(DocEntry::path));
        return out;
    }

    public String readMarkdown(String relativePath) throws IOException {
        if (!Validation.isNonBlank(relativePath)) {
            throw new IllegalArgumentException("路径为空");
        }
        String rel = relativePath.replace('\\', '/').replaceAll("^/+", "");
        if (rel.contains("..")) {
            throw new IllegalArgumentException("非法路径");
        }
        Path file = cacheDir.resolve(rel).normalize();
        if (!file.startsWith(cacheDir.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("非法路径");
        }
        if (!Files.isRegularFile(file)) {
            throw new IOException("文档不存在: " + rel);
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    public List<SearchHit> search(String query) throws IOException {
        if (!Validation.isNonBlank(query)) {
            return List.of();
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<SearchHit> hits = new ArrayList<>();
        for (DocEntry doc : listDocuments()) {
            String md = readMarkdown(doc.path());
            String lower = md.toLowerCase(Locale.ROOT);
            int idx = lower.indexOf(q);
            if (idx < 0) {
                continue;
            }
            int start = Math.max(0, idx - 40);
            int end = Math.min(md.length(), idx + q.length() + 60);
            String snippet = md.substring(start, end).replace('\n', ' ').trim();
            if (start > 0) {
                snippet = "…" + snippet;
            }
            if (end < md.length()) {
                snippet = snippet + "…";
            }
            hits.add(new SearchHit(doc.path(), doc.title(), snippet));
        }
        return hits;
    }

    public long lastSyncEpochMs() {
        Path meta = cacheDir.resolve("meta.json");
        if (!Files.isRegularFile(meta)) {
            return 0L;
        }
        try {
            JsonObject o = JsonUtil.parse(Files.readString(meta, StandardCharsets.UTF_8)).getAsJsonObject();
            return o.has("syncEpochMs") ? o.get("syncEpochMs").getAsLong() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private void writeMeta() throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("syncEpochMs", System.currentTimeMillis());
        o.addProperty("sourceUrl", SOURCE_URL);
        o.addProperty("docsSite", DOCS_SITE);
        o.addProperty("github", GITHUB_REPO);
        o.addProperty("copyright", COPYRIGHT_NOTE);
        Files.writeString(cacheDir.resolve("meta.json"), JsonUtil.toJsonPretty(o), StandardCharsets.UTF_8);
    }

    private void downloadZip(Path target) throws Exception {
        Request request = new Request.Builder()
                .url(SOURCE_URL)
                .header("User-Agent", "BlackLauncher")
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " downloading fabric-docs");
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Empty fabric-docs zip");
            }
            Files.createDirectories(target.getParent());
            try (InputStream in = body.byteStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void unzip(Path zip, Path dest) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path out = dest.resolve(entry.getName()).normalize();
                if (!out.startsWith(dest.toAbsolutePath().normalize())) {
                    throw new IOException("Zip slip: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    private static Path findRepoRoot(Path extractRoot) throws IOException {
        try (var stream = Files.list(extractRoot)) {
            return stream.filter(Files::isDirectory).findFirst().orElse(extractRoot);
        }
    }

    private static void copyTree(Path src, Path dest) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path target = dest.resolve(src.relativize(dir).toString());
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path target = dest.resolve(src.relativize(file).toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String titleOf(String path, String md) {
        for (String line : md.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("# ")) {
                return t.substring(2).trim();
            }
        }
        String name = Path.of(path).getFileName().toString();
        if (name.endsWith(".md")) {
            name = name.substring(0, name.length() - 3);
        }
        return name;
    }

    private static void deleteRecursive(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try {
            Files.walk(root)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException e) {
            LOG.warn("deleteRecursive failed: {}", root, e);
        }
    }
}
