package com.blacklauncher.modpack;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.net.HttpClientFactory;
import com.blacklauncher.core.net.ResumableDownloader;
import com.blacklauncher.core.util.Validation;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Downloads CurseForge mod files via public CDN / BMCLAPI mirrors (no API key required).
 */
public final class CurseForgeClient {

    private final OkHttpClient client;
    private final ResumableDownloader downloader;

    public CurseForgeClient(AppConfig config) {
        this.client = HttpClientFactory.create(config);
        this.downloader = new ResumableDownloader(client);
    }

    public void setControl(com.blacklauncher.core.net.DownloadControl control) {
        downloader.setControl(control);
    }

    /**
     * Download a CurseForge file into {@code targetFile}. Returns the actual path written.
     */
    public Path downloadFile(int projectId, int fileId, Path targetFile, BiConsumer<Long, Long> progress)
            throws LauncherException {
        if (projectId <= 0 || fileId <= 0) {
            throw new LauncherException("无效的 CurseForge 文件 ID");
        }
        List<String> urls = buildCandidateUrls(projectId, fileId, targetFile.getFileName().toString());
        LauncherException last = null;
        for (String url : urls) {
            try {
                // Probe with HEAD/GET — ResumableDownloader will follow redirects.
                if (!looksReachable(url) && urls.indexOf(url) < urls.size() - 1) {
                    // Still try download; some mirrors reject HEAD.
                }
                downloader.download(url, targetFile, null, -1L, progress);
                return targetFile;
            } catch (LauncherException e) {
                last = e;
            }
        }
        throw last != null ? last : new LauncherException("CurseForge 文件下载失败: " + projectId + "/" + fileId);
    }

    private List<String> buildCandidateUrls(int projectId, int fileId, String fileName) {
        List<String> urls = new ArrayList<>();
        // BMCLAPI CurseForge proxy
        urls.add("https://bmclapi2.bangbang93.com/curseforge/mod/" + projectId + "/" + fileId);
        urls.add("https://bmclapi2.bangbang93.com/forge/download?fileid=" + fileId);

        // Official CDN path: files/{fileId/1000}/{fileId%1000}/{name}
        int a = fileId / 1000;
        int b = fileId % 1000;
        String name = Validation.isNonBlank(fileName) ? fileName : ("file-" + fileId + ".jar");
        urls.add(String.format(java.util.Locale.ROOT,
                "https://mediafilez.forgecdn.net/files/%d/%d/%s", a, b, name));
        urls.add(String.format(java.util.Locale.ROOT,
                "https://edge.forgecdn.net/files/%d/%d/%s", a, b, name));
        return urls;
    }

    private boolean looksReachable(String url) {
        Request request = new Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "BlackLauncher/1.0.0")
                .build();
        try (Response response = client.newCall(request).execute()) {
            int code = response.code();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return true; // optimistic — try GET anyway
        }
    }
}
