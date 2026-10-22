package com.blacklauncher.skin;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.config.ConfigManager;
import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.net.HttpClientFactory;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.JsonUtil;
import com.blacklauncher.core.util.Validation;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Local offline skins + Mojang skin API (requires MSA access token).
 */
public class SkinService {

    private static final MediaType PNG = MediaType.get("image/png");

    private final ConfigManager configManager;
    private final OkHttpClient client;

    public SkinService(ConfigManager configManager) {
        this.configManager = configManager;
        this.client = HttpClientFactory.create(configManager.getConfig());
    }

    public List<Path> listSkins() throws LauncherException {
        Path dir = AppPaths.skinsDir();
        List<Path> skins = new ArrayList<>();
        try {
            if (!Files.isDirectory(dir)) {
                return skins;
            }
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> {
                            String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                            return name.endsWith(".png");
                        })
                        .sorted()
                        .forEach(skins::add);
            }
            return skins;
        } catch (Exception e) {
            throw new LauncherException("读取皮肤列表失败", e);
        }
    }

    public Path importSkin(Path source) throws LauncherException {
        if (source == null || !Files.isRegularFile(source)) {
            throw new LauncherException("皮肤文件不存在");
        }
        String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".png")) {
            throw new LauncherException("仅支持 PNG 皮肤");
        }
        try {
            Path target = AppPaths.skinsDir().resolve(source.getFileName().toString().replaceAll("[\\\\/:*?\"<>|]", "_"));
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (Exception e) {
            throw new LauncherException("导入皮肤失败", e);
        }
    }

    public void selectSkin(Path skin) throws LauncherException {
        if (skin == null || !Files.isRegularFile(skin)) {
            throw new LauncherException("皮肤不存在");
        }
        AppConfig config = configManager.getConfig();
        config.setSelectedSkinPath(skin.toAbsolutePath().toString());
        configManager.save();
    }

    public String selectedSkinPath() {
        String path = configManager.getConfig().getSelectedSkinPath();
        return Validation.isNonBlank(path) ? path : "未选择（离线默认）";
    }

    public record OnlineSkin(String id, String state, String url, String variant) {
    }

    /** GET minecraft/profile skins (requires MSA token). */
    public List<OnlineSkin> fetchOnlineSkins() throws LauncherException {
        String token = requireToken();
        Request request = new Request.Builder()
                .url("https://api.minecraftservices.com/minecraft/profile")
                .header("Authorization", "Bearer " + token)
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new LauncherException("获取皮肤档案失败 HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new LauncherException("皮肤档案响应为空");
            }
            JsonObject json = JsonUtil.parse(body.string()).getAsJsonObject();
            List<OnlineSkin> list = new ArrayList<>();
            if (json.has("skins") && json.get("skins").isJsonArray()) {
                JsonArray skins = json.getAsJsonArray("skins");
                for (var el : skins) {
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    JsonObject s = el.getAsJsonObject();
                    list.add(new OnlineSkin(
                            s.has("id") ? s.get("id").getAsString() : "",
                            s.has("state") ? s.get("state").getAsString() : "",
                            s.has("url") ? s.get("url").getAsString() : "",
                            s.has("variant") ? s.get("variant").getAsString() : "CLASSIC"
                    ));
                }
            }
            return list;
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            throw new LauncherException("获取皮肤失败: " + e.getMessage(), e);
        }
    }

    /** PUT multipart upload to Mojang. variant: CLASSIC or SLIM. */
    public void uploadSkin(Path png, String variant) throws LauncherException {
        String token = requireToken();
        if (png == null || !Files.isRegularFile(png)) {
            throw new LauncherException("皮肤文件不存在");
        }
        String v = Validation.isNonBlank(variant) ? variant.toUpperCase(Locale.ROOT) : "CLASSIC";
        try {
            byte[] bytes = Files.readAllBytes(png);
            RequestBody fileBody = RequestBody.create(bytes, PNG);
            MultipartBody multipart = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("variant", v)
                    .addFormDataPart("file", png.getFileName().toString(), fileBody)
                    .build();
            Request request = new Request.Builder()
                    .url("https://api.minecraftservices.com/minecraft/profile/skins")
                    .header("Authorization", "Bearer " + token)
                    .post(multipart)
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String err = response.body() == null ? "" : response.body().string();
                    throw new LauncherException("上传皮肤失败 HTTP " + response.code()
                            + (err.isBlank() ? "" : ": " + err));
                }
            }
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            throw new LauncherException("上传皮肤失败: " + e.getMessage(), e);
        }
    }

    /** DELETE active skin (reset to default). */
    public void resetSkin() throws LauncherException {
        String token = requireToken();
        Request request = new Request.Builder()
                .url("https://api.minecraftservices.com/minecraft/profile/skins/active")
                .header("Authorization", "Bearer " + token)
                .delete()
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() && response.code() != 204) {
                throw new LauncherException("重置皮肤失败 HTTP " + response.code());
            }
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            throw new LauncherException("重置皮肤失败: " + e.getMessage(), e);
        }
    }

    private String requireToken() throws LauncherException {
        AppConfig c = configManager.getConfig();
        if (!"msa".equalsIgnoreCase(c.getAccountType())
                || !Validation.isNonBlank(c.getMinecraftAccessToken())) {
            throw new LauncherException("请先在设置中使用微软账号登录后再管理正版皮肤");
        }
        return c.getMinecraftAccessToken();
    }
}
