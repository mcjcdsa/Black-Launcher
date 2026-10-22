package com.blacklauncher.ui.pages;

import com.blacklauncher.core.config.ConfigManager;
import com.blacklauncher.core.db.Database;
import com.blacklauncher.core.exception.ExceptionHandler;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.JsonUtil;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.download.InstancePaths;
import com.blacklauncher.mod.ModCompat;
import com.blacklauncher.mod.ModInstallService;
import com.blacklauncher.mod.ModrinthClient;
import com.blacklauncher.mod.ModrinthClient.ModDependency;
import com.blacklauncher.mod.ModrinthClient.ModVersion;
import com.blacklauncher.mod.ModrinthClient.ProjectDetail;
import com.blacklauncher.mod.SimpleMarkdown;
import com.blacklauncher.ui.dialogs.InstallLocationDialog;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.Window;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Modrinth mod detail page: info, markdown body, version filters, install, deps.
 */
public class ModDetailPane extends BorderPane {

    private final ConfigManager configManager;
    private final Database database;
    private final ExecutorService worker;
    private final Runnable onBack;
    private final Consumer<String> openProject;
    private final Consumer<String> statusSink;

    private final Label titleLabel = new Label();
    private final Label metaLabel = new Label();
    private final Label statsLabel = new Label();
    private final Label warnLabel = new Label();
    private final FlowPane tagPane = new FlowPane(6, 6);
    private final WebView bodyView = new WebView();
    private final ListView<ModVersion> versionList = new ListView<>();
    private final VBox depsBox = new VBox(6);
    private final Label emptyVersions = new Label("暂无发布版本");
    private final Label errorLabel = new Label();
    private final Button favoriteBtn = new Button("收藏");
    private final Button downloadBtn = new Button("下载并安装");
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Label progressLabel = new Label();
    private final ComboBox<String> gameFilter = new ComboBox<>();
    private final CheckBox loaderFabric = new CheckBox("Fabric");
    private final CheckBox loaderForge = new CheckBox("Forge");
    private final CheckBox loaderNeo = new CheckBox("NeoForge");
    private final CheckBox loaderQuilt = new CheckBox("Quilt");
    private final ToggleGroup typeGroup = new ToggleGroup();
    private final RadioButton typeRelease = new RadioButton("稳定版");
    private final RadioButton typeBeta = new RadioButton("测试版");
    private final RadioButton typeAlpha = new RadioButton("开发版");
    private final RadioButton typeAll = new RadioButton("全部类型");
    private final ComboBox<String> sortBox = new ComboBox<>();
    private final TitledPane changelogPane = new TitledPane();
    private final WebView changelogView = new WebView();

    private ProjectDetail project;
    private List<ModVersion> allVersions = List.of();
    private ModVersion selectedVersion;
    private final AtomicBoolean favourited = new AtomicBoolean(false);
    private final Set<String> installedFileNames = new HashSet<>();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public ModDetailPane(ConfigManager configManager,
                         Database database,
                         ExecutorService worker,
                         Runnable onBack,
                         Consumer<String> openProject,
                         Consumer<String> statusSink) {
        this.configManager = configManager;
        this.database = database;
        this.worker = worker;
        this.onBack = onBack;
        this.openProject = openProject;
        this.statusSink = statusSink == null ? s -> {
        } : statusSink;

        getStyleClass().add("page");
        setPadding(new Insets(4));

        Button back = new Button("← 返回列表");
        back.setOnAction(e -> {
            if (onBack != null) {
                onBack.run();
            }
        });

        titleLabel.getStyleClass().add("page-title");
        metaLabel.getStyleClass().add("muted");
        metaLabel.setWrapText(true);
        statsLabel.getStyleClass().add("muted");
        warnLabel.getStyleClass().add("compat-warn");
        warnLabel.setWrapText(true);
        warnLabel.setVisible(false);
        warnLabel.setManaged(false);
        errorLabel.getStyleClass().add("compat-warn");
        errorLabel.setWrapText(true);
        emptyVersions.getStyleClass().add("muted");

        favoriteBtn.setOnAction(e -> toggleFavorite());
        Button share = new Button("分享链接");
        share.setOnAction(e -> copyShareLink());
        Button issues = new Button("反馈问题");
        issues.setOnAction(e -> openExternal(project == null ? null : project.issuesUrl()));
        Button site = new Button("Modrinth 主页");
        site.setOnAction(e -> {
            if (project != null) {
                openExternal(ModrinthClient.projectPageUrl(project.slug()));
            }
        });
        Button retry = new Button("重试加载");
        retry.setOnAction(e -> {
            if (project != null) {
                load(project.id());
            }
        });

        HBox actions = new HBox(8, back, favoriteBtn, share, issues, site, retry);
        actions.setAlignment(Pos.CENTER_LEFT);

        bodyView.setPrefHeight(220);
        bodyView.setContextMenuEnabled(false);
        changelogView.setPrefHeight(160);
        changelogPane.setText("更新日志（点击展开）");
        changelogPane.setExpanded(false);
        changelogPane.setContent(changelogView);

        setupFilters();
        setupVersionList();

        downloadBtn.getStyleClass().add("primary-button");
        downloadBtn.setDisable(true);
        downloadBtn.setOnAction(e -> downloadSelected());
        Button saveOnly = new Button("仅保存文件");
        saveOnly.setOnAction(e -> downloadSelected(true));
        Button cancelDl = new Button("取消下载");
        cancelDl.setOnAction(e -> cancelled.set(true));
        progressBar.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(progressBar, Priority.ALWAYS);
        progressBar.setProgress(0);

        TitledPane depsPane = new TitledPane("依赖与兼容性", depsBox);
        depsPane.setExpanded(true);

        Label comments = new Label("评论区：当前版本通过 Modrinth 网站查看与发表评论（需登录账号）。点击「Modrinth 主页」跳转。");
        comments.getStyleClass().add("muted");
        comments.setWrapText(true);

        VBox header = new VBox(8, actions, titleLabel, metaLabel, statsLabel, tagPane, warnLabel, errorLabel);
        VBox filters = new VBox(8,
                new Label("筛选版本"),
                new HBox(10, new Label("游戏版本"), gameFilter),
                new HBox(12, loaderFabric, loaderForge, loaderNeo, loaderQuilt),
                new HBox(12, typeRelease, typeBeta, typeAlpha, typeAll),
                new HBox(10, new Label("排序"), sortBox)
        );

        VBox versions = new VBox(8, filters, emptyVersions, versionList,
                new HBox(10, downloadBtn, saveOnly, cancelDl),
                new HBox(10, progressBar, progressLabel));
        VBox.setVgrow(versionList, Priority.ALWAYS);
        versionList.setPrefHeight(220);

        VBox body = new VBox(12, header, new Label("模组介绍"), bodyView, changelogPane, versions, depsPane, comments);
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("mod-detail-scroll");
        setCenter(scroll);
    }

    public void load(String projectIdOrSlug) {
        errorLabel.setText("");
        statusSink.accept("正在加载模组详情…");
        progressBar.setProgress(0);
        progressLabel.setText("");
        worker.submit(() -> {
            try {
                ModInstallService service = new ModInstallService(configManager.getConfig());
                ProjectDetail detail;
                try {
                    detail = service.client().getProject(projectIdOrSlug);
                    database.upsertModProjectCache(detail.id(), detail.slug(), detail.title(),
                            JsonUtil.toJson(detail));
                } catch (Exception netEx) {
                    String cached = database.getModProjectCacheJson(projectIdOrSlug);
                    if (!Validation.isNonBlank(cached)) {
                        // try by id lookup later — show error
                        throw netEx;
                    }
                    detail = JsonUtil.fromJson(cached, ProjectDetail.class);
                    Platform.runLater(() -> errorLabel.setText("网络异常，已显示本地缓存（可能不是最新）。"
                            + netEx.getMessage()));
                }
                List<ModVersion> versions = List.of();
                try {
                    versions = service.client().listAllVersions(detail.id());
                } catch (Exception e) {
                    Platform.runLater(() -> errorLabel.setText(
                            (errorLabel.getText() + " 版本列表加载失败: " + e.getMessage()).trim()));
                }
                boolean fav = database.isModFavorite(detail.id());
                Set<String> installed = scanInstalledModFiles();
                ProjectDetail finalDetail = detail;
                List<ModVersion> finalVersions = versions;
                Platform.runLater(() -> {
                    applyProject(finalDetail, finalVersions, fav, installed);
                    statusSink.accept("模组详情已加载");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    errorLabel.setText("加载失败：" + e.getMessage() + "（可点击重试）");
                    statusSink.accept("模组详情加载失败");
                });
                ExceptionHandler.handle("加载模组详情失败", e);
            }
        });
    }

    private void applyProject(ProjectDetail detail, List<ModVersion> versions, boolean fav, Set<String> installed) {
        this.project = detail;
        this.allVersions = versions == null ? List.of() : versions;
        this.installedFileNames.clear();
        this.installedFileNames.addAll(installed);
        favourited.set(fav);
        favoriteBtn.setText(fav ? "已收藏" : "收藏");

        titleLabel.setText(detail.title());
        metaLabel.setText("ID: " + detail.id()
                + "  ·  slug: " + detail.slug()
                + (Validation.isNonBlank(detail.license()) ? "  ·  许可: " + detail.license() : "")
                + (Validation.isNonBlank(detail.sourceUrl()) ? "  ·  开源可查" : ""));
        statsLabel.setText("下载 " + detail.downloads()
                + "  ·  关注 " + detail.followers()
                + "  ·  更新 " + SimpleMarkdown.formatTime(detail.updatedAt()));

        tagPane.getChildren().clear();
        List<String> loadersTag = detail.loaders() == null ? List.of() : detail.loaders();
        List<String> gamesTag = detail.gameVersions() == null ? List.of() : detail.gameVersions();
        for (String loader : loadersTag) {
            tagPane.getChildren().add(tag(loader));
        }
        gamesTag.stream().limit(12).forEach(v -> tagPane.getChildren().add(tag(v)));

        ModCompat.InstanceHint hint = ModCompat.fromSelectedVersion(
                configManager.getConfig().getSelectedVersion());
        String warn = ModCompat.projectMismatchWarning(detail, hint);
        if (Validation.isNonBlank(warn)) {
            warnLabel.setText(warn);
            warnLabel.setVisible(true);
            warnLabel.setManaged(true);
        } else {
            warnLabel.setVisible(false);
            warnLabel.setManaged(false);
        }

        boolean dark = !"light".equalsIgnoreCase(configManager.getConfig().getTheme());
        bodyView.getEngine().loadContent(SimpleMarkdown.toHtmlDocument(detail.body(), dark));
        String changelogs = allVersions.stream()
                .filter(v -> Validation.isNonBlank(v.changelog()))
                .limit(8)
                .map(v -> "### " + v.versionNumber() + "\n" + v.changelog())
                .collect(Collectors.joining("\n\n"));
        changelogView.getEngine().loadContent(SimpleMarkdown.toHtmlDocument(
                Validation.isNonBlank(changelogs) ? changelogs : "暂无更新日志", dark));

        List<String> gameOpts = new ArrayList<>();
        gameOpts.add("全部版本");
        allVersions.stream()
                .flatMap(v -> v.gameVersions().stream())
                .distinct()
                .sorted(Comparator.reverseOrder())
                .forEach(gameOpts::add);
        gameFilter.setItems(FXCollections.observableArrayList(gameOpts));
        gameFilter.getSelectionModel().selectFirst();

        Set<String> loaders = allVersions.stream()
                .flatMap(v -> v.loaders().stream())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        loaderFabric.setSelected(loaders.contains("fabric") || loaders.isEmpty());
        loaderForge.setSelected(loaders.contains("forge") || loaders.isEmpty());
        loaderNeo.setSelected(loaders.contains("neoforge") || loaders.isEmpty());
        loaderQuilt.setSelected(loaders.contains("quilt") || loaders.isEmpty());
        typeRelease.setSelected(true);

        rebuildDeps(null);
        applyFilters();
    }

    private void setupFilters() {
        typeRelease.setToggleGroup(typeGroup);
        typeBeta.setToggleGroup(typeGroup);
        typeAlpha.setToggleGroup(typeGroup);
        typeAll.setToggleGroup(typeGroup);
        typeRelease.setSelected(true);

        sortBox.setItems(FXCollections.observableArrayList("最新发布", "最早发布"));
        sortBox.getSelectionModel().selectFirst();

        gameFilter.setOnAction(e -> applyFilters());
        sortBox.setOnAction(e -> applyFilters());
        loaderFabric.setOnAction(e -> applyFilters());
        loaderForge.setOnAction(e -> applyFilters());
        loaderNeo.setOnAction(e -> applyFilters());
        loaderQuilt.setOnAction(e -> applyFilters());
        typeGroup.selectedToggleProperty().addListener((o, a, b) -> applyFilters());
    }

    private void setupVersionList() {
        versionList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ModVersion item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                    setStyle("");
                    return;
                }
                String installed = item.primaryFile() != null
                        && installedFileNames.contains(item.primaryFile().fileName().toLowerCase(Locale.ROOT))
                        ? "  ·已安装" : "";
                String deps = item.hasRequiredDeps() ? "  ·前置" : "";
                String conflict = item.hasConflicts() ? "  ·冲突" : "";
                setText(item.versionNumber()
                        + "  [" + item.versionTypeLabel() + "]"
                        + "  MC " + String.join(",", item.gameVersions())
                        + "  " + String.join(",", item.loaders())
                        + "  " + SimpleMarkdown.formatBytes(item.primaryFile() == null ? 0 : item.primaryFile().size())
                        + "  " + SimpleMarkdown.formatTime(item.datePublished())
                        + deps + conflict + installed);
                String tip = Validation.isNonBlank(item.changelog())
                        ? item.changelog().substring(0, Math.min(200, item.changelog().length()))
                        : item.name();
                if (item.dependencies() != null && !item.dependencies().isEmpty()) {
                    tip += "\n依赖: " + item.dependencies().stream()
                            .map(d -> d.dependencyType() + ":" + d.projectId())
                            .collect(Collectors.joining(", "));
                }
                setTooltip(new Tooltip(tip));
            }
        });
        versionList.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            selectedVersion = b;
            downloadBtn.setDisable(b == null || b.primaryFile() == null);
            rebuildDeps(b);
        });
    }

    private void applyFilters() {
        if (allVersions == null) {
            return;
        }
        String game = gameFilter.getValue();
        Set<String> loaders = new HashSet<>();
        if (loaderFabric.isSelected()) {
            loaders.add("fabric");
        }
        if (loaderForge.isSelected()) {
            loaders.add("forge");
        }
        if (loaderNeo.isSelected()) {
            loaders.add("neoforge");
        }
        if (loaderQuilt.isSelected()) {
            loaders.add("quilt");
        }
        String type = typeAll.isSelected() ? "all"
                : typeBeta.isSelected() ? "beta"
                : typeAlpha.isSelected() ? "alpha" : "release";
        boolean newestFirst = sortBox.getSelectionModel().getSelectedIndex() <= 0;

        List<ModVersion> filtered = allVersions.stream()
                .filter(v -> {
                    if (Validation.isNonBlank(game) && !"全部版本".equals(game)) {
                        if (v.gameVersions() == null || v.gameVersions().stream()
                                .noneMatch(g -> g.equalsIgnoreCase(game))) {
                            return false;
                        }
                    }
                    if (!loaders.isEmpty() && v.loaders() != null && !v.loaders().isEmpty()) {
                        boolean hit = v.loaders().stream()
                                .map(s -> s.toLowerCase(Locale.ROOT))
                                .anyMatch(loaders::contains);
                        if (!hit) {
                            return false;
                        }
                    }
                    if (!"all".equals(type)) {
                        String vt = v.versionType() == null ? "release" : v.versionType().toLowerCase(Locale.ROOT);
                        if (!vt.equals(type)) {
                            return false;
                        }
                    }
                    return true;
                })
                .sorted((a, b) -> Long.compare(
                        newestFirst ? b.datePublished() : a.datePublished(),
                        newestFirst ? a.datePublished() : b.datePublished()))
                .collect(Collectors.toList());

        versionList.setItems(FXCollections.observableArrayList(filtered));
        emptyVersions.setVisible(filtered.isEmpty());
        emptyVersions.setManaged(filtered.isEmpty());
        if (!filtered.isEmpty()) {
            versionList.getSelectionModel().selectFirst();
        } else {
            selectedVersion = null;
            downloadBtn.setDisable(true);
        }
    }

    private void rebuildDeps(ModVersion version) {
        depsBox.getChildren().clear();
        if (version == null || version.dependencies() == null || version.dependencies().isEmpty()) {
            Label empty = new Label("当前选中版本无声明依赖。");
            empty.getStyleClass().add("muted");
            depsBox.getChildren().add(empty);
            return;
        }
        List<ModDependency> required = version.dependencies().stream().filter(ModDependency::required).toList();
        List<ModDependency> optional = version.dependencies().stream().filter(ModDependency::optional).toList();
        List<ModDependency> conflict = version.dependencies().stream().filter(ModDependency::incompatible).toList();
        depsBox.getChildren().add(section("必填依赖", required));
        depsBox.getChildren().add(section("可选依赖", optional));
        depsBox.getChildren().add(section("冲突模组", conflict));
    }

    private VBox section(String title, List<ModDependency> deps) {
        VBox box = new VBox(4);
        Label h = new Label(title + "（" + deps.size() + "）");
        h.getStyleClass().add("section-title");
        box.getChildren().add(h);
        if (deps.isEmpty()) {
            Label none = new Label("无");
            none.getStyleClass().add("muted");
            box.getChildren().add(none);
            return box;
        }
        for (ModDependency d : deps) {
            HBox row = new HBox(8);
            row.setAlignment(Pos.CENTER_LEFT);
            Label id = new Label((d.projectId() == null ? "?" : d.projectId()) + "  [" + d.dependencyType() + "]");
            Button go = new Button("查看");
            go.setOnAction(e -> {
                if (Validation.isNonBlank(d.projectId()) && openProject != null) {
                    openProject.accept(d.projectId());
                }
            });
            row.getChildren().addAll(id, go);
            box.getChildren().add(row);
        }
        return box;
    }

    private void downloadSelected() {
        downloadSelected(false);
    }

    private void downloadSelected(boolean saveOnly) {
        if (selectedVersion == null || selectedVersion.primaryFile() == null || project == null) {
            return;
        }
        ModCompat.InstanceHint hint = ModCompat.fromSelectedVersion(
                configManager.getConfig().getSelectedVersion());
        boolean match = ModCompat.versionMatchesInstance(selectedVersion, hint);
        if (!match && !saveOnly) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("兼容性提示");
            alert.setHeaderText("当前实例与选中模组包可能不匹配");
            alert.setContentText("实例：" + hint.versionId()
                    + "\n模组包 MC：" + String.join(",", selectedVersion.gameVersions())
                    + "\n模组包加载器：" + String.join(",", selectedVersion.loaders())
                    + "\n\n选择「继续安装」将仍尝试写入当前实例；「仅保存」只下载到缓存目录。");
            ButtonType installAnyway = new ButtonType("继续安装", ButtonBar.ButtonData.OK_DONE);
            ButtonType save = new ButtonType("仅保存", ButtonBar.ButtonData.OTHER);
            ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
            alert.getButtonTypes().setAll(installAnyway, save, cancel);
            Optional<ButtonType> r = alert.showAndWait();
            if (r.isEmpty() || r.get() == cancel) {
                return;
            }
            if (r.get() == save) {
                saveOnly = true;
            }
        }

        Path targetDir;
        if (saveOnly) {
            targetDir = AppPaths.downloadsTempDir().resolve("mods");
        } else {
            Optional<InstallLocationDialog.Result> choice = InstallLocationDialog.show(
                    window(),
                    configManager.getConfig(),
                    project.title(),
                    InstancePaths.ContentFolder.MODS,
                    Validation.isNonBlank(hint.mcVersion()) ? hint.mcVersion()
                            : (selectedVersion.gameVersions().isEmpty()
                            ? "1.20.1" : selectedVersion.gameVersions().get(0)),
                    selectedVersion.loaders().isEmpty() ? hint.loader() : selectedVersion.loaders().get(0)
            );
            if (choice.isEmpty()) {
                return;
            }
            targetDir = choice.get().targetDir();
            configManager.getConfig().setSelectedVersion(choice.get().versionId());
            try {
                configManager.save();
            } catch (Exception ignored) {
                // continue install
            }
        }

        Path dest = targetDir.resolve(selectedVersion.primaryFile().fileName()
                .replaceAll("[\\\\/:*?\"<>|]", "_"));
        if (Files.isRegularFile(dest)) {
            Alert exists = new Alert(Alert.AlertType.CONFIRMATION);
            exists.setTitle("文件已存在");
            exists.setHeaderText(dest.getFileName() + " 已存在");
            exists.setContentText("覆盖安装还是跳过？");
            ButtonType overwrite = new ButtonType("覆盖", ButtonBar.ButtonData.OK_DONE);
            ButtonType skip = new ButtonType("跳过", ButtonBar.ButtonData.CANCEL_CLOSE);
            exists.getButtonTypes().setAll(overwrite, skip);
            Optional<ButtonType> er = exists.showAndWait();
            if (er.isEmpty() || er.get() == skip) {
                return;
            }
        }

        cancelled.set(false);
        downloadBtn.setDisable(true);
        ModVersion ver = selectedVersion;
        Path finalDir = targetDir;
        boolean finalSaveOnly = saveOnly;
        worker.submit(() -> {
            try {
                ModInstallService service = new ModInstallService(configManager.getConfig());
                statusSink.accept("正在下载 " + ver.primaryFile().fileName());
                Path path = service.installFile(ver.primaryFile(), finalDir, (done, total) -> {
                    if (cancelled.get()) {
                        return;
                    }
                    Platform.runLater(() -> {
                        if (total > 0) {
                            progressBar.setProgress(done / (double) total);
                        }
                        progressLabel.setText(SimpleMarkdown.formatBytes(done)
                                + (total > 0 ? " / " + SimpleMarkdown.formatBytes(total) : ""));
                    });
                });
                if (cancelled.get()) {
                    Platform.runLater(() -> {
                        progressLabel.setText("已取消");
                        downloadBtn.setDisable(false);
                    });
                    return;
                }
                List<ModDependency> missingRequired = ver.dependencies() == null ? List.of()
                        : ver.dependencies().stream().filter(ModDependency::required).toList();
                Platform.runLater(() -> {
                    progressBar.setProgress(1);
                    progressLabel.setText("完成: " + path.getFileName());
                    downloadBtn.setDisable(false);
                    installedFileNames.add(ver.primaryFile().fileName().toLowerCase(Locale.ROOT));
                    versionList.refresh();
                    statusSink.accept(finalSaveOnly ? "已保存到缓存" : "已安装到实例 mods");
                    if (!missingRequired.isEmpty() && !finalSaveOnly) {
                        offerDepDownloads(missingRequired, finalDir);
                    } else {
                        ExceptionHandler.showInfo("完成",
                                (finalSaveOnly ? "已保存：\n" : "已安装：\n") + path.toAbsolutePath());
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> downloadBtn.setDisable(false));
                ExceptionHandler.handle("模组下载失败", e);
            }
        });
    }

    private void offerDepDownloads(List<ModDependency> required, Path modsDir) {
        String list = required.stream()
                .map(d -> "• " + d.projectId())
                .collect(Collectors.joining("\n"));
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("缺少前置依赖");
        alert.setHeaderText("检测到必填前置模组，是否一键下载安装？");
        alert.setContentText(list);
        Optional<ButtonType> r = alert.showAndWait();
        if (r.isEmpty() || r.get() != ButtonType.OK) {
            return;
        }
        worker.submit(() -> {
            ModInstallService service = new ModInstallService(configManager.getConfig());
            StringBuilder msg = new StringBuilder();
            for (ModDependency d : required) {
                if (!Validation.isNonBlank(d.projectId())) {
                    continue;
                }
                try {
                    Path p = service.installMod(d.projectId(),
                            ModCompat.fromSelectedVersion(configManager.getConfig().getSelectedVersion()).mcVersion(),
                            ModCompat.fromSelectedVersion(configManager.getConfig().getSelectedVersion()).loader(),
                            modsDir,
                            (done, total) -> {
                            });
                    msg.append("✓ ").append(p.getFileName()).append('\n');
                } catch (Exception e) {
                    msg.append("✗ ").append(d.projectId()).append(": ").append(e.getMessage()).append('\n');
                }
            }
            Platform.runLater(() -> ExceptionHandler.showInfo("前置依赖", msg.toString()));
        });
    }

    private void toggleFavorite() {
        if (project == null) {
            return;
        }
        worker.submit(() -> {
            try {
                if (favourited.get()) {
                    database.removeModFavorite(project.id());
                    favourited.set(false);
                } else {
                    database.upsertModFavorite(project.id(), project.slug(), project.title(), project.iconUrl());
                    favourited.set(true);
                }
                Platform.runLater(() -> favoriteBtn.setText(favourited.get() ? "已收藏" : "收藏"));
            } catch (Exception e) {
                ExceptionHandler.handle("收藏失败", e);
            }
        });
    }

    private void copyShareLink() {
        if (project == null) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(ModrinthClient.projectPageUrl(project.slug()));
        Clipboard.getSystemClipboard().setContent(content);
        ExceptionHandler.showInfo("已复制", "模组链接已复制到剪贴板");
    }

    private Set<String> scanInstalledModFiles() {
        Set<String> names = new HashSet<>();
        try {
            String selected = configManager.getConfig().getSelectedVersion();
            if (!Validation.isNonBlank(selected)) {
                return names;
            }
            Path mods = InstancePaths.contentDir(configManager.getConfig(), selected, InstancePaths.ContentFolder.MODS);
            if (!Files.isDirectory(mods)) {
                return names;
            }
            try (var stream = Files.list(mods)) {
                stream.filter(Files::isRegularFile)
                        .forEach(p -> names.add(p.getFileName().toString().toLowerCase(Locale.ROOT)));
            }
        } catch (Exception ignored) {
            // ignore
        }
        return names;
    }

    private static Label tag(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("mod-tag");
        return l;
    }

    private void openExternal(String url) {
        if (!Validation.isNonBlank(url)) {
            ExceptionHandler.showInfo("提示", "该模组未提供此链接");
            return;
        }
        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception e) {
            ExceptionHandler.handle("打开链接失败", e);
        }
    }

    private Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }
}
