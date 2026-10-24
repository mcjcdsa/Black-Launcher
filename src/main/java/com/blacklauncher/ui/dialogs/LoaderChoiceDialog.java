package com.blacklauncher.ui.dialogs;

import com.blacklauncher.core.exception.ExceptionHandler;
import com.blacklauncher.core.net.HttpClientFactory;
import com.blacklauncher.core.util.JsonUtil;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.download.LoaderApiInstaller;
import com.blacklauncher.download.LoaderInstallChoice;
import com.blacklauncher.download.ModLoader;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asks which install flavour / loader version to use, and whether to auto-install companion API (§10.1).
 */
public final class LoaderChoiceDialog {

    private static final String[] FABRIC_META = {
            "https://meta.fabricmc.net/v2",
            "https://bmclapi2.bangbang93.com/fabric-meta/v2"
    };
    private static final String[] QUILT_META = {
            "https://meta.quiltmc.org/v3",
            "https://bmclapi2.bangbang93.com/quilt-meta/v3"
    };
    private static final String FORGE_PROMOS =
            "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";
    private static final String NEOFORGE_META =
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";

    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "loader-choice");
        t.setDaemon(true);
        return t;
    });

    private LoaderChoiceDialog() {
    }

    public static Optional<LoaderInstallChoice> show(Window owner, String versionId) {
        Dialog<LoaderInstallChoice> dialog = new Dialog<>();
        dialog.setTitle("选择下载类型");
        dialog.setHeaderText("即将下载 Minecraft " + versionId);
        if (owner != null) {
            dialog.initOwner(owner);
        }

        Label tip = new Label("请选择安装类型。选择模组加载器时可指定版本，并自动安装配套 API（Fabric / Quilt）。");
        tip.setWrapText(true);

        ComboBox<ModLoader> combo = new ComboBox<>();
        combo.getItems().addAll(ModLoader.values());
        combo.getSelectionModel().select(ModLoader.VANILLA);
        combo.setMaxWidth(Double.MAX_VALUE);

        Label loaderVerLabel = new Label("加载器版本");
        ComboBox<String> loaderVersions = new ComboBox<>();
        loaderVersions.setMaxWidth(Double.MAX_VALUE);
        loaderVersions.setDisable(true);
        loaderVersions.setPromptText("自动选择推荐版本");

        CheckBox installApi = new CheckBox("自动安装配套 API（推荐）");
        installApi.setSelected(true);
        installApi.setDisable(true);

        Label detail = new Label();
        detail.getStyleClass().add("muted");
        detail.setWrapText(true);
        Label loadStatus = new Label();
        loadStatus.getStyleClass().add("muted");

        AtomicInteger requestId = new AtomicInteger();
        Runnable refresh = () -> {
            ModLoader loader = combo.getValue();
            updateDetail(detail, loader);
            boolean needsLoader = loader != null && loader != ModLoader.VANILLA;
            boolean apiOk = LoaderApiInstaller.supportsCompanionApi(loader);
            loaderVerLabel.setDisable(!needsLoader);
            loaderVersions.setDisable(!needsLoader);
            installApi.setDisable(!apiOk);
            if (!apiOk) {
                installApi.setSelected(false);
            } else {
                installApi.setSelected(true);
            }
            loaderVersions.getItems().clear();
            if (!needsLoader) {
                loadStatus.setText("");
                return;
            }
            int rid = requestId.incrementAndGet();
            loadStatus.setText("正在拉取 " + loader.displayName() + " 版本列表…");
            LOADER.submit(() -> {
                try {
                    List<String> versions = listLoaderVersions(versionId, loader);
                    Platform.runLater(() -> {
                        if (rid != requestId.get()) {
                            return;
                        }
                        loaderVersions.getItems().setAll(versions);
                        if (!versions.isEmpty()) {
                            loaderVersions.getSelectionModel().selectFirst();
                            loadStatus.setText("共 " + versions.size() + " 个可用版本");
                        } else {
                            loadStatus.setText("未找到可用加载器版本（将尝试自动匹配）");
                        }
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        if (rid != requestId.get()) {
                            return;
                        }
                        loadStatus.setText("版本列表加载失败，可仍继续安装（自动匹配）");
                        ExceptionHandler.handle("加载器版本列表失败", e);
                    });
                }
            });
        };
        combo.valueProperty().addListener((o, a, b) -> refresh.run());
        refresh.run();

        VBox box = new VBox(12, tip,
                new Label("下载类型"), combo,
                loaderVerLabel, loaderVersions, loadStatus,
                installApi, detail);
        box.setPadding(new Insets(8, 4, 4, 4));
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().setPrefWidth(440);

        ButtonType ok = new ButtonType("开始下载", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, cancel);

        dialog.setResultConverter(button -> {
            if (button != ok) {
                return null;
            }
            ModLoader loader = combo.getValue() == null ? ModLoader.VANILLA : combo.getValue();
            String ver = loaderVersions.getSelectionModel().getSelectedItem();
            if (!Validation.isNonBlank(ver) || "自动选择推荐版本".equals(ver)) {
                ver = null;
            }
            boolean api = installApi.isSelected() && LoaderApiInstaller.supportsCompanionApi(loader);
            return new LoaderInstallChoice(loader, ver, api);
        });

        return dialog.showAndWait();
    }

    private static void updateDetail(Label detail, ModLoader loader) {
        if (loader == null) {
            detail.setText("");
            return;
        }
        detail.setText(switch (loader) {
            case VANILLA -> "仅下载官方原版客户端、依赖库与资源文件。";
            case FABRIC -> "安装 Fabric Loader，并可自动下载配套 Fabric API 到 mods 目录。";
            case FORGE -> "安装推荐/所选 Forge（Forge 本体已含 API，无需额外模组）。";
            case QUILT -> "安装 Quilt Loader，并可自动下载配套 Quilted Fabric API。";
            case NEOFORGE -> "安装所选 NeoForge（NeoForge 本体已含 API，无需额外模组）。";
        });
    }

    private static List<String> listLoaderVersions(String gameVersion, ModLoader loader) throws Exception {
        OkHttpClient client = HttpClientFactory.create(null);
        return switch (loader) {
            case FABRIC -> listFabricLike(client, FABRIC_META, gameVersion);
            case QUILT -> listFabricLike(client, QUILT_META, gameVersion);
            case FORGE -> listForge(client, gameVersion);
            case NEOFORGE -> listNeoForge(client, gameVersion);
            default -> List.of();
        };
    }

    private static List<String> listFabricLike(OkHttpClient client, String[] bases, String gameVersion)
            throws Exception {
        JsonArray arr = JsonUtil.parse(httpGet(client, bases, "/versions/loader/" + gameVersion)).getAsJsonArray();
        List<String> stable = new ArrayList<>();
        List<String> other = new ArrayList<>();
        for (JsonElement el : arr) {
            JsonObject loader = el.getAsJsonObject().getAsJsonObject("loader");
            if (loader == null || !loader.has("version")) {
                continue;
            }
            String ver = loader.get("version").getAsString();
            if (loader.has("stable") && loader.get("stable").getAsBoolean()) {
                stable.add(ver);
            } else {
                other.add(ver);
            }
        }
        List<String> out = new ArrayList<>(stable.size() + other.size());
        out.addAll(stable);
        out.addAll(other);
        return out;
    }

    private static List<String> listForge(OkHttpClient client, String gameVersion) throws Exception {
        JsonObject root = JsonUtil.parse(httpGet(client, FORGE_PROMOS)).getAsJsonObject();
        JsonObject promos = root.getAsJsonObject("promos");
        List<String> out = new ArrayList<>();
        if (promos != null) {
            if (promos.has(gameVersion + "-recommended")) {
                out.add(promos.get(gameVersion + "-recommended").getAsString());
            }
            if (promos.has(gameVersion + "-latest")) {
                String latest = promos.get(gameVersion + "-latest").getAsString();
                if (!out.contains(latest)) {
                    out.add(latest);
                }
            }
        }
        return out;
    }

    private static List<String> listNeoForge(OkHttpClient client, String gameVersion) throws Exception {
        String xml = httpGet(client, NEOFORGE_META);
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
        versions.sort(Comparator.reverseOrder());
        if (versions.size() > 40) {
            return versions.subList(0, 40);
        }
        return versions;
    }

    private static String neoPrefix(String gameVersion) {
        String rest = gameVersion.startsWith("1.") ? gameVersion.substring(2) : gameVersion;
        if (!rest.contains(".")) {
            return rest + ".0.";
        }
        return rest + ".";
    }

    private static String httpGet(OkHttpClient client, String[] bases, String path) throws Exception {
        Exception last = null;
        for (String base : bases) {
            try {
                return httpGet(client, base + path);
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new Exception("无法访问加载器元数据");
    }

    private static String httpGet(OkHttpClient client, String url) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "BlackLauncher/1.0.0")
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("HTTP " + response.code() + ": " + url);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new Exception("空响应: " + url);
            }
            return body.string();
        }
    }
}
