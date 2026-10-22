package com.blacklauncher.download;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.net.DownloadMirror;
import com.blacklauncher.core.net.DownloadSource;
import com.blacklauncher.core.net.HttpClientFactory;
import com.blacklauncher.core.util.JsonUtil;
import com.blacklauncher.model.VersionManifest;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches Mojang version manifest (with optional mirror).
 */
public class VersionManifestService {

    public static final String MANIFEST_URL =
            "https://launchermeta.mojang.com/mc/game/version_manifest.json";

    private final OkHttpClient client;
    private final DownloadSource source;

    public VersionManifestService(AppConfig config) {
        this.client = HttpClientFactory.create(config);
        this.source = DownloadSource.fromId(config == null ? null : config.getDownloadSource());
    }

    public VersionManifest fetch() throws LauncherException {
        String primary = DownloadMirror.manifestUrl(source);
        Exception last = null;
        for (String url : new String[]{primary, MANIFEST_URL}) {
            try {
                return fetchOnce(url);
            } catch (Exception e) {
                last = e;
            }
        }
        throw new LauncherException("获取版本清单失败: " + (last == null ? "" : last.getMessage()), last);
    }

    private VersionManifest fetchOnce(String url) throws LauncherException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "BlackLauncher/1.0.0")
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new LauncherException("获取版本清单失败: HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new LauncherException("版本清单响应为空");
            }
            VersionManifest manifest = JsonUtil.fromJson(body.string(), VersionManifest.class);
            if (manifest == null || manifest.getVersions() == null) {
                throw new LauncherException("版本清单解析失败");
            }
            return manifest;
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            throw new LauncherException("获取版本清单失败: " + e.getMessage(), e);
        }
    }
}
