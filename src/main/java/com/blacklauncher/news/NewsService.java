package com.blacklauncher.news;

import com.blacklauncher.AppInfo;
import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.db.Database;
import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.net.HttpClientFactory;
import com.blacklauncher.core.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.util.ArrayList;
import java.util.List;

/**
 * Loads news from remote JSON with offline SQLite cache fallback.
 */
public class NewsService {

    /**
     * Public Minecraft news endpoint used as remote source.
     */
    public static final String NEWS_URL =
            "https://launchercontent.mojang.com/news.json";

    private final OkHttpClient client;
    private final Database database;

    public NewsService(AppConfig config, Database database) {
        this.client = HttpClientFactory.create(config);
        this.database = database;
    }

    public List<Database.NewsRecord> load(boolean forceRefresh) throws LauncherException {
        try {
            if (!forceRefresh) {
                List<Database.NewsRecord> cached = database.listNews();
                if (!cached.isEmpty()) {
                    return cached;
                }
            }
            List<Database.NewsRecord> remote = fetchRemote();
            if (!remote.isEmpty()) {
                database.replaceNewsCache(remote);
                return remote;
            }
            List<Database.NewsRecord> cached = database.listNews();
            if (!cached.isEmpty()) {
                return cached;
            }
            return defaultNews();
        } catch (LauncherException e) {
            try {
                List<Database.NewsRecord> cached = database.listNews();
                if (!cached.isEmpty()) {
                    return cached;
                }
            } catch (Exception ignored) {
                // fall through
            }
            return defaultNews();
        } catch (Exception e) {
            throw new LauncherException("加载新闻失败: " + e.getMessage(), e);
        }
    }

    private List<Database.NewsRecord> fetchRemote() throws LauncherException {
        Request request = new Request.Builder().url(NEWS_URL).get().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new LauncherException("新闻接口 HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new LauncherException("新闻响应为空");
            }
            JsonObject root = JsonUtil.parse(body.string()).getAsJsonObject();
            JsonArray entries = root.getAsJsonArray("entries");
            List<Database.NewsRecord> list = new ArrayList<>();
            if (entries != null) {
                int i = 0;
                for (JsonElement el : entries) {
                    if (i++ >= 30) {
                        break;
                    }
                    JsonObject obj = el.getAsJsonObject();
                    String id = obj.has("id") ? obj.get("id").getAsString() : ("news-" + i);
                    String title = obj.has("title") ? obj.get("title").getAsString() : "无标题";
                    String text = "";
                    if (obj.has("text") && !obj.get("text").isJsonNull()) {
                        text = obj.get("text").getAsString();
                    } else if (obj.has("content") && obj.get("content").isJsonObject()
                            && obj.getAsJsonObject("content").has("text")) {
                        text = obj.getAsJsonObject("content").get("text").getAsString();
                    }
                    String category = obj.has("category") ? obj.get("category").getAsString() : "minecraft";
                    String summary = text.length() > 160 ? text.substring(0, 160) + "..." : text;
                    list.add(new Database.NewsRecord(id, title, summary, text, category, System.currentTimeMillis()));
                }
            }
            return list;
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            throw new LauncherException("拉取新闻失败: " + e.getMessage(), e);
        }
    }

    private List<Database.NewsRecord> defaultNews() {
        long now = System.currentTimeMillis();
        return List.of(
                new Database.NewsRecord(
                        "local-welcome",
                        "欢迎使用 " + AppInfo.NAME,
                        "布莱克启动器第一阶段已就绪：下载、启动、设置、新闻均可离线使用缓存。",
                        "布莱克（Black Launcher）第一阶段提供版本下载、启动、Mod/光影检索安装、皮肤管理与新闻缓存。"
                                + "联机与云存档将在后续阶段交付。",
                        "launcher",
                        now
                ),
                new Database.NewsRecord(
                        "local-online",
                        "联机服务预告",
                        "局域网联机、内网穿透与服务器托管将在第二阶段开放。",
                        "第二阶段将提供房间发现、Token 鉴权、白名单与付费联机能力。",
                        "online",
                        now - 1000
                )
        );
    }
}
