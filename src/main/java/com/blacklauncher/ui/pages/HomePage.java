package com.blacklauncher.ui.pages;

import com.blacklauncher.auth.MicrosoftAuthService;
import com.blacklauncher.core.config.ConfigManager;
import com.blacklauncher.core.db.Database;
import com.blacklauncher.core.exception.ExceptionHandler;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.JsonUtil;
import com.blacklauncher.core.util.UuidUtil;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.core.net.DownloadProgressSink;
import com.blacklauncher.download.GameFolderImportService;
import com.blacklauncher.download.InstalledVersionService;
import com.blacklauncher.download.VersionLifecycleService;
import com.blacklauncher.launch.GameExitInfo;
import com.blacklauncher.launch.GameLauncher;
import com.blacklauncher.launch.JavaFinder;
import com.blacklauncher.launch.JavaVersionProbe;
import com.blacklauncher.memory.MemoryOptimizeFacade;
import com.blacklauncher.memory.MemoryProfile;
import com.blacklauncher.memory.MemoryProfileStore;
import com.blacklauncher.model.VersionJson;
import com.blacklauncher.support.FeedbackPackExporter;
import com.blacklauncher.modpack.ModpackConflictPolicy;
import com.blacklauncher.modpack.ModpackDescriptor;
import com.blacklauncher.modpack.ModpackExportOptions;
import com.blacklauncher.modpack.ModpackExportService;
import com.blacklauncher.modpack.ModpackImportService;
import com.blacklauncher.modpack.ModpackInstallReport;
import com.blacklauncher.modpack.ModpackRepairService;
import com.blacklauncher.ui.dialogs.GameCrashDialog;
import com.blacklauncher.ui.dialogs.ImportGameFolderDialog;
import com.blacklauncher.ui.dialogs.ModpackConflictDialog;
import com.blacklauncher.ui.dialogs.ModpackExportDialog;
import com.blacklauncher.ui.dialogs.ModpackInstallReportDialog;
import com.blacklauncher.ui.dialogs.ModpackProgressDialog;
import com.blacklauncher.minecraft.WorldSaveService;
import com.blacklauncher.ui.dialogs.SaveManagerDialog;
import com.blacklauncher.ui.dialogs.ContentLibraryDialog;
import com.blacklauncher.ui.dialogs.ServerListDialog;
import com.blacklauncher.ui.dialogs.VersionSettingsDialog;
import javafx.scene.control.Tooltip;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Home: toolbar + TitledPane config + SplitPane log panel (@5).
 */
public class HomePage extends BorderPane {

    private static final Logger LOG = LoggerFactory.getLogger(HomePage.class);

    private final ConfigManager configManager;
    private final Database database;
    private final InstalledVersionService installedVersionService;
    private final GameLauncher gameLauncher;
    private final Consumer<String> statusSink;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "home-worker");
        t.setDaemon(true);
        return t;
    });

    private final ComboBox<String> versionBox = new ComboBox<>();
    private final TextField versionSearch = new TextField();
    private final Label versionMeta = new Label();
    private final Label playerLabel = new Label();
    private final Label onlineStatus = new Label("联机状态：检测中…");
    private final Label runStatus = new Label("运行状态：未运行");
    private final TextArea launchPreview = new TextArea();
    private final TextArea liveLog = new TextArea();
    private final Button launchButton = new Button("一键启动");
    private final TextField playerNameField = new TextField();
    private final TextField javaPathField = new TextField();
    private final Slider maxMemorySlider = new Slider(512, 16384, 2048);
    private final Label maxMemoryLabel = new Label();
    private final ComboBox<String> resolutionBox = new ComboBox<>();
    private Path lastLaunchLog;
    private List<String> allVersionIds = List.of();

    public HomePage(ConfigManager configManager,
                    Database database,
                    InstalledVersionService installedVersionService,
                    GameLauncher gameLauncher,
                    Consumer<String> statusSink) {
        this.configManager = configManager;
        this.database = database;
        this.installedVersionService = installedVersionService;
        this.gameLauncher = gameLauncher;
        this.statusSink = statusSink == null ? s -> {} : statusSink;

        getStyleClass().add("page");
        setPadding(new Insets(0));

        versionBox.setPrefWidth(220);
        versionBox.setOnAction(e -> onVersionChanged());
        versionSearch.setPromptText("搜索版本…");
        versionSearch.setPrefWidth(120);
        versionSearch.textProperty().addListener((o, a, b) -> applyVersionFilter());

        playerNameField.setPromptText("游戏昵称");
        playerNameField.setText(configManager.getConfig().getPlayerName());
        playerNameField.setPrefWidth(140);
        playerNameField.focusedProperty().addListener((o, was, now) -> {
            if (was && !now) {
                persistPlayerNameFromField(false);
            }
        });

        launchButton.getStyleClass().addAll("button", "primary");
        launchButton.setOnAction(e -> launch());

        Button refresh = new Button("刷新");
        refresh.setOnAction(e -> refresh());
        Button importFolder = new Button("导入游戏文件夹");
        importFolder.setOnAction(e -> importGameFolder());
        Button importPack = new Button("导入整合包");
        importPack.setOnAction(e -> chooseAndImportModpacks());
        Button exportPack = new Button("导出整合包");
        exportPack.setOnAction(e -> exportSelectedModpack());
        Button versionSettings = new Button("版本设置");
        versionSettings.setTooltip(new Tooltip("请先选择游戏版本"));
        versionSettings.setOnAction(e -> {
            String id = versionBox.getSelectionModel().getSelectedItem();
            VersionSettingsDialog.show(window(), configManager, database, id, this::refresh);
        });
        versionBox.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            boolean has = Validation.isNonBlank(b);
            versionSettings.setDisable(!has);
            versionSettings.setTooltip(new Tooltip(has ? "打开当前版本专属设置" : "请先选择游戏版本"));
        });
        versionSettings.setDisable(!Validation.isNonBlank(versionBox.getSelectionModel().getSelectedItem()));

        Button saves = new Button("存档管理");
        saves.setOnAction(e -> {
            String id = versionBox.getSelectionModel().getSelectedItem();
            SaveManagerDialog.show(window(), configManager, installedVersionService, id);
        });

        MenuItem exportMenu = new MenuItem("导出整合包");
        exportMenu.setOnAction(e -> exportSelectedModpack());
        MenuItem repairPackMenu = new MenuItem("修复整合包文件");
        repairPackMenu.setOnAction(e -> repairSelectedModpack());
        MenuItem settingsMenu = new MenuItem("版本设置");
        settingsMenu.setOnAction(e -> {
            String id = versionBox.getSelectionModel().getSelectedItem();
            VersionSettingsDialog.show(window(), configManager, database, id, this::refresh);
        });
        MenuItem renameMenu = new MenuItem("版本重命名");
        renameMenu.setOnAction(e -> renameSelectedVersion());
        MenuItem restoreNameMenu = new MenuItem("恢复默认名称");
        restoreNameMenu.setOnAction(e -> restoreSelectedVersionName());
        MenuItem copyMenu = new MenuItem("复制实例");
        copyMenu.setOnAction(e -> copySelectedInstance());
        MenuItem uninstallMenu = new MenuItem("卸载版本");
        uninstallMenu.setOnAction(e -> uninstallSelectedVersion());
        MenuItem feedbackMenu = new MenuItem("导出反馈包");
        feedbackMenu.setOnAction(e -> exportSelectedFeedbackPack());
        MenuItem contentLibMenu = new MenuItem("内容库");
        contentLibMenu.setOnAction(e -> {
            String id = versionBox.getSelectionModel().getSelectedItem();
            ContentLibraryDialog.show(window(), configManager, id);
        });
        MenuItem serverListMenu = new MenuItem("服务器列表");
        serverListMenu.setOnAction(e -> {
            String id = versionBox.getSelectionModel().getSelectedItem();
            ServerListDialog.show(window(), configManager, id);
        });
        versionBox.setContextMenu(new ContextMenu(
                renameMenu, restoreNameMenu, settingsMenu, exportMenu, repairPackMenu,
                contentLibMenu, serverListMenu, copyMenu, uninstallMenu, feedbackMenu));

        HBox toolbar = new HBox(10,
                new Label("版本"), versionSearch, versionBox, versionSettings, refresh,
                new Label("游戏昵称"), playerNameField,
                launchButton, importPack, exportPack, importFolder, saves);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(0, 0, 8, 0));

        versionMeta.getStyleClass().add("muted");
        playerLabel.getStyleClass().add("muted");
        onlineStatus.getStyleClass().add("status-pill");
        runStatus.getStyleClass().add("status-pill");

        var cfg = configManager.getConfig();
        javaPathField.setText(cfg.getJavaPath() == null ? "" : cfg.getJavaPath());
        javaPathField.setPromptText("Java 路径（设置中也可改）");
        maxMemorySlider.setMajorTickUnit(1024);
        maxMemorySlider.setBlockIncrement(256);
        maxMemorySlider.setShowTickMarks(false);
        maxMemorySlider.setValue(cfg.getMaxMemoryMb());
        maxMemoryLabel.setText(cfg.getMaxMemoryMb() + " MB");
        maxMemorySlider.valueProperty().addListener((o, a, b) -> {
            int mb = (int) Math.round(b.doubleValue() / 256.0) * 256;
            maxMemoryLabel.setText(mb + " MB");
        });
        maxMemorySlider.setOnMouseReleased(e -> applyMemoryFromSlider());
        resolutionBox.setItems(FXCollections.observableArrayList(
                "854x480", "1280x720", "1366x768", "1600x900", "1920x1080"));
        resolutionBox.getSelectionModel().select(cfg.getGameWidth() + "x" + cfg.getGameHeight());
        resolutionBox.setOnAction(e -> applyResolution());

        Button browseJava = new Button("浏览");
        browseJava.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("选择 java");
            var file = chooser.showOpenDialog(window());
            if (file != null) {
                javaPathField.setText(file.getAbsolutePath());
                cfg.setJavaPath(file.getAbsolutePath());
                try {
                    configManager.save();
                } catch (Exception ex) {
                    ExceptionHandler.handle("保存 Java 路径失败", ex);
                }
                onVersionChanged();
            }
        });

        launchPreview.setEditable(false);
        launchPreview.setWrapText(true);
        launchPreview.setPrefRowCount(5);
        launchPreview.getStyleClass().add("code-area");

        VBox launchConfig = new VBox(8,
                versionMeta, playerLabel,
                new HBox(8, new Label("最大内存"), maxMemorySlider, maxMemoryLabel),
                new HBox(8, new Label("分辨率"), resolutionBox),
                new HBox(8, new Label("Java"), javaPathField, browseJava),
                new Label("JVM / 启动参数预览"),
                launchPreview);
        HBox.setHgrow(javaPathField, Priority.ALWAYS);
        TitledPane launchPane = new TitledPane("游戏启动配置", launchConfig);
        launchPane.setExpanded(true);

        Button openSaves = new Button("打开存档管理");
        openSaves.setOnAction(e -> {
            String id = versionBox.getSelectionModel().getSelectedItem();
            SaveManagerDialog.show(window(), configManager, installedVersionService, id);
        });
        Label savesHint = new Label("备份、删除、导入本地存档请使用存档管理弹窗。");
        savesHint.getStyleClass().add("muted");
        savesHint.setWrapText(true);
        TitledPane savesPane = new TitledPane("存档管理", new VBox(8, savesHint, openSaves));
        savesPane.setExpanded(false);

        VBox left = new VBox(10, launchPane, savesPane, onlineStatus, runStatus);
        VBox.setVgrow(launchPane, Priority.ALWAYS);

        Button openLog = new Button("查看日志");
        openLog.setOnAction(e -> openLatestLog());
        Button exportLog = new Button("导出日志");
        exportLog.setOnAction(e -> exportLatestLog());
        liveLog.setEditable(false);
        liveLog.setWrapText(true);
        liveLog.getStyleClass().add("code-area");
        VBox.setVgrow(liveLog, Priority.ALWAYS);
        VBox right = new VBox(8,
                new Label("启动日志"),
                liveLog,
                new HBox(8, openLog, exportLog));
        VBox.setVgrow(right, Priority.ALWAYS);

        SplitPane split = new SplitPane(left, right);
        split.setDividerPositions(0.48);
        VBox.setVgrow(split, Priority.ALWAYS);

        VBox body = new VBox(8, toolbar, split);
        setCenter(body);
        refresh();
    }

    private void applyMemoryFromSlider() {
        int mb = (int) Math.round(maxMemorySlider.getValue() / 256.0) * 256;
        mb = Math.max(512, Math.min(16384, mb));
        maxMemorySlider.setValue(mb);
        maxMemoryLabel.setText(mb + " MB");
        var cfg = configManager.getConfig();
        cfg.setMaxMemoryMb(mb);
        if (cfg.getMinMemoryMb() > mb) {
            cfg.setMinMemoryMb(Math.max(512, mb / 2));
        }
        try {
            configManager.save();
            String versionId = versionBox.getSelectionModel().getSelectedItem();
            if (Validation.isNonBlank(versionId)) {
                MemoryProfile profile = MemoryProfileStore.load(cfg, versionId);
                profile.setXmxMb(mb);
                profile.setXmsMb(Math.min(profile.getXmsMb(), mb));
                if (profile.getXmsMb() < 512) {
                    profile.setXmsMb(512);
                }
                MemoryProfileStore.save(cfg, versionId, profile);
            }
        } catch (Exception e) {
            ExceptionHandler.handle("保存内存失败", e);
        }
        onVersionChanged();
    }

    private void applyResolution() {
        String v = resolutionBox.getValue();
        if (!Validation.isNonBlank(v) || !v.contains("x")) {
            return;
        }
        String[] parts = v.split("x");
        try {
            configManager.getConfig().setGameWidth(Integer.parseInt(parts[0].trim()));
            configManager.getConfig().setGameHeight(Integer.parseInt(parts[1].trim()));
            configManager.save();
            onVersionChanged();
        } catch (Exception e) {
            ExceptionHandler.handle("保存分辨率失败", e);
        }
    }

    private void pushStatus(String text) {
        statusSink.accept(text);
    }

    public void refresh() {
        worker.submit(() -> {
            try {
                List<InstalledVersionService.InstalledVersion> versions = installedVersionService.list();
                Platform.runLater(() -> {
                    allVersionIds = versions.stream()
                            .map(InstalledVersionService.InstalledVersion::id)
                            .collect(Collectors.toList());
                    applyVersionFilter();
                    updatePlayerLabel();
                    boolean netOk = com.blacklauncher.core.net.NetworkMonitor.get().isOnline();
                    onlineStatus.setText(netOk
                            ? "联机状态：联网正常 · 局域网联机可用"
                            : "联机状态：离线 · 仅本地局域网模式");
                    String rs = gameLauncher.isRunning() ? "运行状态：游戏运行中" : "运行状态：未运行";
                    runStatus.setText(rs);
                    pushStatus(rs);
                });
            } catch (Exception e) {
                ExceptionHandler.handle("刷新主页失败", e);
            }
        });
    }

    private void importGameFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择游戏文件夹（.minecraft / versions / 单个版本目录）");
        Path hint = Path.of(System.getProperty("user.home"), "AppData", "Roaming", ".minecraft");
        if (Files.isDirectory(hint)) {
            chooser.setInitialDirectory(hint.toFile());
        }
        var dir = chooser.showDialog(window());
        if (dir == null) {
            return;
        }
        Path selected = dir.toPath();
        runStatus.setText("运行状态：正在识别游戏文件夹…");
        worker.submit(() -> {
            try {
                GameFolderImportService importService = new GameFolderImportService(database);
                GameFolderImportService.ScanResult scan = importService.scan(selected);
                if (scan.versions().isEmpty()) {
                    Platform.runLater(() -> {
                        runStatus.setText("运行状态：未运行");
                        ExceptionHandler.showInfo("未识别到版本",
                                "在所选目录中没有找到可用的 Minecraft 版本。\n"
                                        + "请选择：\n"
                                        + "· .minecraft 根目录\n"
                                        + "· versions 文件夹\n"
                                        + "· 或单个版本目录（含 version.json）");
                    });
                    return;
                }
                Platform.runLater(() -> {
                    var choice = ImportGameFolderDialog.show(window(), scan);
                    if (choice.isEmpty() || choice.get().versions().isEmpty()) {
                        runStatus.setText("运行状态：未运行");
                        return;
                    }
                    runStatus.setText("运行状态：正在导入…");
                    worker.submit(() -> {
                        try {
                            GameFolderImportService.ImportResult result = importService.importSelected(
                                    scan, choice.get().versions(), choice.get().options());
                            Platform.runLater(() -> {
                                runStatus.setText("运行状态：未运行");
                                StringBuilder msg = new StringBuilder();
                                msg.append("成功导入 ").append(result.imported()).append(" 个版本");
                                if (result.skipped() > 0) {
                                    msg.append("，跳过 ").append(result.skipped());
                                }
                                if (result.failed() > 0) {
                                    msg.append("，失败 ").append(result.failed());
                                }
                                if (!result.importedIds().isEmpty()) {
                                    msg.append("\n\n已导入：\n").append(String.join("\n", result.importedIds()));
                                    configManager.getConfig().setSelectedVersion(result.importedIds().get(0));
                                    try {
                                        configManager.save();
                                    } catch (Exception ignored) {
                                        // refresh will still show list
                                    }
                                }
                                if (!result.messages().isEmpty()) {
                                    msg.append("\n\n详情：\n");
                                    result.messages().stream().limit(12).forEach(m -> msg.append("· ").append(m).append('\n'));
                                }
                                ExceptionHandler.showInfo("导入完成", msg.toString().trim());
                                refresh();
                            });
                        } catch (Exception e) {
                            Platform.runLater(() -> runStatus.setText("运行状态：未运行"));
                            ExceptionHandler.handle("导入失败", e);
                        }
                    });
                });
            } catch (Exception e) {
                Platform.runLater(() -> runStatus.setText("运行状态：未运行"));
                ExceptionHandler.handle("识别游戏文件夹失败", e);
            }
        });
    }

    /** File-chooser entry for 「导入整合包」 (multi-select). */
    public void chooseAndImportModpacks() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择整合包（可多选）");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("整合包", "*.zip", "*.mrpack"),
                new FileChooser.ExtensionFilter("所有文件", "*.*"));
        Path initial = AppPaths.modpacksDir();
        if (Files.isDirectory(initial)) {
            chooser.setInitialDirectory(initial.toFile());
        }
        var files = chooser.showOpenMultipleDialog(window());
        if (files == null || files.isEmpty()) {
            return;
        }
        List<Path> paths = files.stream().map(java.io.File::toPath).toList();
        importModpackFiles(paths);
    }

    /** Drag-drop / programmatic entry — imports .zip / .mrpack files sequentially. */
    public void importModpackFiles(List<Path> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        List<Path> packs = new ArrayList<>();
        for (Path p : files) {
            if (p == null || !Files.isRegularFile(p)) {
                continue;
            }
            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (n.endsWith(".zip") || n.endsWith(".mrpack")) {
                packs.add(p);
            }
        }
        if (packs.isEmpty()) {
            ExceptionHandler.showInfo("提示", "请拖入 .zip 或 .mrpack 整合包文件");
            return;
        }
        importNextModpack(packs, 0);
    }

    private void importNextModpack(List<Path> packs, int index) {
        if (index >= packs.size()) {
            return;
        }
        Path pack = packs.get(index);
        worker.submit(() -> {
            try {
                ModpackImportService service = new ModpackImportService(configManager.getConfig(), database);
                ModpackDescriptor desc = service.inspect(pack);
                String name = Validation.sanitizeVersionId(desc.getName());
                Path existing = AppPaths.versionsDir().resolve(name);
                Platform.runLater(() -> {
                    ModpackConflictPolicy policy = ModpackConflictPolicy.COPY_SUFFIX;
                    if (Files.exists(existing)
                            || Files.exists(AppPaths.gameDir().resolve("instances").resolve(name))) {
                        var choice = ModpackConflictDialog.show(window(), name);
                        if (choice.isEmpty() || choice.get() == ModpackConflictPolicy.CANCEL) {
                            pushStatus("已取消导入");
                            importNextModpack(packs, index + 1);
                            return;
                        }
                        policy = choice.get();
                    }
                    ModpackConflictPolicy finalPolicy = policy;
                    ModpackProgressDialog progress = new ModpackProgressDialog(window(), "导入整合包");
                    progress.show();
                    progress.dialog().setOnCloseRequest(ev -> service.downloadControl().cancel());
                    DownloadProgressSink sink = new DownloadProgressSink(snap ->
                            progress.update(snap.message(), snap.progress(),
                                    snap.percentText() + "  " + snap.speedText()));
                    worker.submit(() -> {
                        try {
                            ModpackInstallReport report = service.importPack(desc, finalPolicy, sink);
                            Platform.runLater(() -> {
                                progress.close();
                                if (!report.isSuccess()) {
                                    ExceptionHandler.showInfo("导入结束", report.getSummary());
                                    importNextModpack(packs, index + 1);
                                    return;
                                }
                                configManager.getConfig().setSelectedVersion(report.getVersionId());
                                try {
                                    configManager.save();
                                } catch (Exception ignored) {
                                }
                                refresh();
                                var action = ModpackInstallReportDialog.show(
                                        window(), report, configManager.getConfig());
                                try {
                                    configManager.save();
                                } catch (Exception ignored) {
                                }
                                onVersionChanged();
                                if (action.isPresent() && action.get() == ModpackInstallReportDialog.Action.LAUNCH) {
                                    versionBox.getSelectionModel().select(report.getVersionId());
                                    launch();
                                } else {
                                    importNextModpack(packs, index + 1);
                                }
                            });
                        } catch (Exception e) {
                            Platform.runLater(() -> {
                                progress.close();
                                ExceptionHandler.handle("导入整合包失败", e);
                                importNextModpack(packs, index + 1);
                            });
                        }
                    });
                });
            } catch (Exception e) {
                ExceptionHandler.handle("识别整合包失败: " + pack.getFileName(), e);
                Platform.runLater(() -> importNextModpack(packs, index + 1));
            }
        });
    }

    private void renameSelectedVersion() {
        String id = versionBox.getSelectionModel().getSelectedItem();
        if (!Validation.isNonBlank(id)) {
            ExceptionHandler.showInfo("提示", "请先选择游戏版本");
            return;
        }
        javafx.scene.control.TextInputDialog input = new javafx.scene.control.TextInputDialog(id);
        input.setTitle("版本重命名");
        input.setHeaderText("自定义版本名称（将同步修改版本文件夹标识）");
        input.setContentText("新名称：");
        if (window() != null) {
            input.initOwner(window());
        }
        input.showAndWait().ifPresent(raw -> worker.submit(() -> {
            try {
                String newId = new com.blacklauncher.download.VersionRenameService(
                        configManager.getConfig(), database).rename(id, raw);
                configManager.save();
                Platform.runLater(() -> {
                    refresh();
                    versionBox.getSelectionModel().select(newId);
                    ExceptionHandler.showInfo("完成", "版本已重命名为：" + newId);
                });
            } catch (Exception e) {
                ExceptionHandler.handle("版本重命名失败", e);
            }
        }));
    }

    private void restoreSelectedVersionName() {
        String id = versionBox.getSelectionModel().getSelectedItem();
        if (!Validation.isNonBlank(id)) {
            ExceptionHandler.showInfo("提示", "请先选择游戏版本");
            return;
        }
        worker.submit(() -> {
            try {
                String restored = new com.blacklauncher.download.VersionRenameService(
                        configManager.getConfig(), database).restoreDefaultName(id);
                configManager.save();
                Platform.runLater(() -> {
                    refresh();
                    versionBox.getSelectionModel().select(restored);
                    ExceptionHandler.showInfo("完成", "已恢复为：" + restored);
                });
            } catch (Exception e) {
                ExceptionHandler.handle("恢复默认名称失败", e);
            }
        });
    }

    private void exportSelectedModpack() {
        String id = versionBox.getSelectionModel().getSelectedItem();
        if (!Validation.isNonBlank(id)) {
            ExceptionHandler.showInfo("提示", "请先选择要导出的游戏版本");
            return;
        }
        var opts = ModpackExportDialog.show(window(), id);
        if (opts.isEmpty()) {
            return;
        }
        ModpackExportOptions options = opts.get();
        ModpackProgressDialog progress = new ModpackProgressDialog(window(), "导出整合包");
        progress.show();
        progress.update("正在打包 " + id + "…", 0.2, "");
        worker.submit(() -> {
            try {
                Path out = new ModpackExportService(configManager.getConfig()).exportVersion(id, options);
                Platform.runLater(() -> {
                    progress.close();
                    ExceptionHandler.showInfo("导出完成", "整合包已保存到：\n" + out.toAbsolutePath());
                    pushStatus("整合包已导出");
                });
            } catch (Exception e) {
                Platform.runLater(progress::close);
                ExceptionHandler.handle("导出整合包失败", e);
            }
        });
    }

    private void repairSelectedModpack() {
        String id = versionBox.getSelectionModel().getSelectedItem();
        if (!Validation.isNonBlank(id)) {
            ExceptionHandler.showInfo("提示", "请先选择游戏版本");
            return;
        }
        ModpackProgressDialog progress = new ModpackProgressDialog(window(), "修复整合包文件");
        progress.show();
        DownloadProgressSink sink = new DownloadProgressSink(snap ->
                progress.update(snap.message(), snap.progress(),
                        snap.percentText() + "  " + snap.speedText()));
        var cfg = configManager.getConfig();
        worker.submit(() -> {
            try {
                ModpackRepairService.RepairReport report =
                        new ModpackRepairService(cfg).repairFromIndex(cfg, id, sink);
                Platform.runLater(() -> {
                    progress.close();
                    StringBuilder msg = new StringBuilder();
                    msg.append("检查 ").append(report.checked()).append(" 个文件\n");
                    msg.append("重新下载 ").append(report.redownloaded()).append(" 个\n");
                    msg.append("仍缺失 ").append(report.missing().size()).append(" 个");
                    ExceptionHandler.showInfo("修复结果", msg.toString());
                    pushStatus("整合包修复完成");
                });
            } catch (Exception e) {
                Platform.runLater(progress::close);
                ExceptionHandler.handle("修复整合包文件失败", e);
            }
        });
    }

    private Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }

    private void applyVersionFilter() {
        String q = versionSearch.getText() == null ? "" : versionSearch.getText().trim().toLowerCase(Locale.ROOT);
        String previous = versionBox.getSelectionModel().getSelectedItem();
        List<String> filtered = allVersionIds.stream()
                .filter(id -> q.isEmpty() || id.toLowerCase(Locale.ROOT).contains(q))
                .collect(Collectors.toList());
        versionBox.setItems(FXCollections.observableArrayList(filtered));
        if (Validation.isNonBlank(previous) && filtered.contains(previous)) {
            versionBox.getSelectionModel().select(previous);
        } else {
            String selected = configManager.getConfig().getSelectedVersion();
            if (Validation.isNonBlank(selected) && filtered.contains(selected)) {
                versionBox.getSelectionModel().select(selected);
            } else if (!filtered.isEmpty()) {
                versionBox.getSelectionModel().selectFirst();
            } else {
                versionBox.getSelectionModel().clearSelection();
            }
        }
        onVersionChanged();
    }

    private void onVersionChanged() {
        String id = versionBox.getSelectionModel().getSelectedItem();
        if (!Validation.isNonBlank(id)) {
            versionMeta.setText(allVersionIds.isEmpty()
                    ? "尚未安装任何版本，请前往「下载」。"
                    : "无匹配版本，请调整搜索关键词。");
            launchPreview.clear();
            return;
        }
        configManager.getConfig().setSelectedVersion(id);
        try {
            configManager.save();
        } catch (Exception e) {
            ExceptionHandler.handle("保存选中版本失败", e);
        }

        worker.submit(() -> {
            try {
                List<InstalledVersionService.InstalledVersion> versions = installedVersionService.list();
                InstalledVersionService.InstalledVersion meta = versions.stream()
                        .filter(v -> v.id().equals(id))
                        .findFirst()
                        .orElse(new InstalledVersionService.InstalledVersion(id, "unknown", 0, 0, 0));
                var cfg = configManager.getConfig();
                MemoryProfile mem = MemoryProfileStore.load(cfg, id);
                String java = JavaFinder.resolveJavaExecutable(cfg.getJavaPath());
                int actualMajor = JavaVersionProbe.probeMajor(java);
                int requiredMajor = 0;
                Path jsonPath = AppPaths.versionsDir().resolve(id).resolve(id + ".json");
                if (Files.isRegularFile(jsonPath)) {
                    VersionJson vj = JsonUtil.fromJson(
                            Files.readString(jsonPath, StandardCharsets.UTF_8), VersionJson.class);
                    requiredMajor = JavaVersionProbe.requiredMajor(vj);
                }
                String javaTip;
                if (requiredMajor > 0 && actualMajor > 0 && actualMajor < requiredMajor) {
                    javaTip = String.format("Java %d（需要 %d+，不匹配）", actualMajor, requiredMajor);
                } else if (actualMajor > 0) {
                    javaTip = requiredMajor > 0
                            ? String.format("Java %d（需要 %d+）", actualMajor, requiredMajor)
                            : "Java " + actualMajor;
                } else {
                    javaTip = java;
                }
                String preview = gameLauncher.previewCommand(id);
                String tipFinal = javaTip;
                boolean mismatch = requiredMajor > 0 && actualMajor > 0 && actualMajor < requiredMajor;
                Platform.runLater(() -> {
                    maxMemorySlider.setValue(mem.getXmxMb());
                    maxMemoryLabel.setText(mem.getXmxMb() + " MB");
                    versionMeta.setText(String.format(
                            "版本 %s · 类型 %s · Mod %d · 光影 %d · 存档 %d · %s · 内存 %d-%dMB · 分辨率 %dx%d",
                            meta.id(), meta.type(), meta.modCount(), meta.shaderCount(), meta.saveCount(),
                            tipFinal,
                            mem.getXmsMb(),
                            mem.getXmxMb(),
                            cfg.getGameWidth(),
                            cfg.getGameHeight()
                    ));
                    versionMeta.setStyle(mismatch ? "-fx-text-fill: #c0392b;" : "");
                    launchPreview.setText(preview);
                });
            } catch (Exception e) {
                ExceptionHandler.handle("读取版本信息失败", e);
            }
        });
    }

    private void copySelectedInstance() {
        String id = versionBox.getSelectionModel().getSelectedItem();
        if (!Validation.isNonBlank(id)) {
            ExceptionHandler.showInfo("提示", "请先选择要复制的版本");
            return;
        }
        TextInputDialog nameDlg = new TextInputDialog(id + "-copy");
        nameDlg.setTitle("复制实例");
        nameDlg.setHeaderText("为副本指定新版本 ID");
        nameDlg.setContentText("新 ID：");
        nameDlg.initOwner(window());
        Optional<String> nameOpt = nameDlg.showAndWait();
        if (nameOpt.isEmpty() || !Validation.isNonBlank(nameOpt.get())) {
            return;
        }
        CheckBox copyMods = new CheckBox("复制 mods");
        copyMods.setSelected(true);
        CheckBox copySaves = new CheckBox("复制存档");
        copySaves.setSelected(false);
        Alert opts = new Alert(Alert.AlertType.CONFIRMATION);
        opts.setTitle("复制选项");
        opts.setHeaderText("复制 " + id + " → " + nameOpt.get().trim());
        opts.getDialogPane().setContent(new VBox(8, copyMods, copySaves,
                new Label("未勾选时目标实例对应目录将为空。")));
        opts.initOwner(window());
        if (opts.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        String newId = nameOpt.get().trim();
        boolean mods = copyMods.isSelected();
        boolean saves = copySaves.isSelected();
        worker.submit(() -> {
            try {
                String created = new VersionLifecycleService(configManager.getConfig(), database)
                        .copyInstance(id, newId, mods, saves);
                Platform.runLater(() -> {
                    ExceptionHandler.showInfo("完成", "已复制为：" + created);
                    refresh();
                });
            } catch (Exception e) {
                ExceptionHandler.handle("复制实例失败", e);
            }
        });
    }

    private void uninstallSelectedVersion() {
        String id = versionBox.getSelectionModel().getSelectedItem();
        if (!Validation.isNonBlank(id)) {
            ExceptionHandler.showInfo("提示", "请先选择要卸载的版本");
            return;
        }
        CheckBox deleteInstance = new CheckBox("同时删除独立实例目录（mods / 存档等）");
        deleteInstance.setSelected(true);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("卸载版本");
        confirm.setHeaderText("确定卸载 " + id + "？");
        confirm.getDialogPane().setContent(new VBox(8, deleteInstance,
                new Label("此操作不可撤销。")));
        confirm.initOwner(window());
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        boolean wipeInstance = deleteInstance.isSelected();
        worker.submit(() -> {
            try {
                new VersionLifecycleService(configManager.getConfig(), database)
                        .uninstall(id, wipeInstance);
                try {
                    configManager.save();
                } catch (Exception ignored) {
                    // selectedVersion already cleared in service
                }
                Platform.runLater(() -> {
                    ExceptionHandler.showInfo("完成", "已卸载：" + id);
                    refresh();
                });
            } catch (Exception e) {
                ExceptionHandler.handle("卸载版本失败", e);
            }
        });
    }

    private void exportSelectedFeedbackPack() {
        String id = versionBox.getSelectionModel().getSelectedItem();
        if (!Validation.isNonBlank(id)) {
            ExceptionHandler.showInfo("提示", "请先选择版本");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出反馈包");
        chooser.setInitialFileName("blacklauncher-feedback-" + id + ".zip");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP 反馈包", "*.zip"));
        var file = chooser.showSaveDialog(window());
        if (file == null) {
            return;
        }
        Path log = lastLaunchLog;
        worker.submit(() -> {
            try {
                Path zip = FeedbackPackExporter.export(
                        configManager.getConfig(), id, log, file.toPath());
                Platform.runLater(() ->
                        ExceptionHandler.showInfo("完成", "反馈包已导出到：\n" + zip.toAbsolutePath()));
            } catch (Exception e) {
                ExceptionHandler.handle("导出反馈包失败", e);
            }
        });
    }

    private void updatePlayerLabel() {
        var c = configManager.getConfig();
        boolean msa = isMsaLoggedIn();
        playerLabel.setText((msa ? "正版：" : "离线：") + c.getPlayerName()
                + "  UUID：" + UuidUtil.withDashes(c.getPlayerUuid())
                + (msa ? "  (msa)" : "  (legacy)"));
        playerNameField.setDisable(false);
        playerNameField.setPromptText(msa ? "游戏昵称（正版可改显示名）" : "游戏昵称");
        if (!playerNameField.isFocused()) {
            playerNameField.setText(c.getPlayerName());
        }
    }

    private boolean isMsaLoggedIn() {
        var c = configManager.getConfig();
        return "msa".equalsIgnoreCase(c.getAccountType())
                && Validation.isNonBlank(c.getMinecraftAccessToken());
    }

    /** @return false if validation failed and caller should abort */
    private boolean persistPlayerNameFromField(boolean showError) {
        String raw = playerNameField.getText();
        String violation = Validation.playerNameViolation(raw);
        if (violation != null) {
            if (showError) {
                ExceptionHandler.showInfo("昵称不合规", violation);
            }
            return false;
        }
        String name = raw.trim();
        playerNameField.setText(name);
        var c = configManager.getConfig();
        c.setPlayerName(name);
        if (!isMsaLoggedIn()) {
            c.setPlayerUuid(UuidUtil.offlineUuid(name));
            c.setAccountType("offline");
        }
        try {
            configManager.save();
            updatePlayerLabel();
            return true;
        } catch (Exception e) {
            ExceptionHandler.handle("保存游戏昵称失败", e);
            return false;
        }
    }

    private void launch() {
        String id = versionBox.getSelectionModel().getSelectedItem();
        if (!Validation.isNonBlank(id)) {
            ExceptionHandler.showInfo("提示", "请先安装并选择一个游戏版本");
            return;
        }
        if (!persistPlayerNameFromField(true)) {
            return;
        }
        updatePlayerLabel();

        launchButton.setDisable(true);
        runStatus.setText("运行状态：正在启动…");
        pushStatus("正在启动 " + id);
        liveLog.clear();
        liveLog.appendText("正在启动 " + id + "...\n");
        worker.submit(() -> {
            Consumer<String> logSink = line -> Platform.runLater(() -> {
                liveLog.appendText(line + "\n");
                liveLog.setScrollTop(Double.MAX_VALUE);
            });
            try {
                maybeRefreshMsaToken();
                maybeBackupWorlds(id, true);
                try {
                    gameLauncher.launch(id, logSink, this::onGameExit, false);
                } catch (Exception first) {
                    if (!MemoryOptimizeFacade.isLowMemoryConfirm(first)) {
                        throw first;
                    }
                    boolean proceed = confirmLowMemory(MemoryOptimizeFacade.lowMemoryConfirmMessage(first));
                    if (!proceed) {
                        Platform.runLater(() -> {
                            launchButton.setDisable(false);
                            runStatus.setText("运行状态：已取消启动");
                            pushStatus("已取消启动");
                        });
                        return;
                    }
                    gameLauncher.launch(id, logSink, this::onGameExit, true);
                }
                Platform.runLater(() -> {
                    liveLog.appendText("游戏进程已启动，启动器正在监测运行状态。\n");
                    runStatus.setText("运行状态：游戏运行中（监测中）");
                    pushStatus("游戏运行中");
                });
            } catch (Exception e) {
                ExceptionHandler.handle("启动失败", e);
                Platform.runLater(() -> {
                    launchButton.setDisable(false);
                    runStatus.setText("运行状态：启动失败");
                    pushStatus("启动失败");
                });
            }
        });
    }

    /** Blocks the worker thread until the FX confirmation dialog closes. */
    private boolean confirmLowMemory(String message) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("内存不足");
            alert.setHeaderText("系统空闲内存较低");
            alert.setContentText(Validation.isNonBlank(message)
                    ? message
                    : "系统空闲内存不足512MB，确认仍要启动？");
            Optional<ButtonType> result = alert.showAndWait();
            future.complete(result.isPresent() && result.get() == ButtonType.OK);
        });
        try {
            return future.get(2, TimeUnit.MINUTES);
        } catch (Exception e) {
            return false;
        }
    }

    private void maybeRefreshMsaToken() throws Exception {
        var c = configManager.getConfig();
        if (!"msa".equalsIgnoreCase(c.getAccountType())) {
            return;
        }
        if (!Validation.isNonBlank(c.getMicrosoftRefreshToken())) {
            if (!Validation.isNonBlank(c.getMinecraftAccessToken())) {
                throw new Exception("正版令牌缺失，请在设置中重新微软登录");
            }
            return;
        }
        long expires = c.getTokenExpiresAt();
        boolean nearExpiry = expires <= 0
                || expires - System.currentTimeMillis() <= 30L * 60L * 1000L;
        boolean missingAccess = !Validation.isNonBlank(c.getMinecraftAccessToken());
        if (!nearExpiry && !missingAccess) {
            return;
        }
        try {
            MicrosoftAuthService auth = new MicrosoftAuthService(c);
            MicrosoftAuthService.Session session = auth.refreshSession();
            auth.applyToConfig(session, true);
            configManager.save();
            Platform.runLater(this::updatePlayerLabel);
        } catch (Exception e) {
            long left = expires - System.currentTimeMillis();
            if (expires > 0 && left > 60_000L && Validation.isNonBlank(c.getMinecraftAccessToken())) {
                // Still have >1 min on the old token — continue, but surface a warning in log.
                Platform.runLater(() -> liveLog.appendText(
                        "[警告] 刷新正版令牌失败，将使用现有令牌继续启动: " + e.getMessage() + "\n"));
                return;
            }
            throw new Exception("正版令牌已过期或刷新失败，请在设置中重新微软登录: " + e.getMessage(), e);
        }
    }

    private void onGameExit(GameExitInfo info) {
        Platform.runLater(() -> {
            launchButton.setDisable(false);
            lastLaunchLog = info.logFile();
            if (info.crashed()) {
                runStatus.setText("运行状态：异常退出（退出码 " + info.exitCode() + "）");
                pushStatus("异常退出 " + info.exitCode());
                liveLog.appendText("\n[监测] " + info.summary() + "\n");
                GameCrashDialog.show(window(), info, configManager.getConfig());
            } else {
                runStatus.setText("运行状态：已正常结束");
                pushStatus("已正常结束");
                liveLog.appendText("\n[监测] " + info.summary() + "\n");
            }
        });
        if (!info.crashed()) {
            worker.submit(() -> maybeBackupWorlds(info.versionId(), false));
        }
    }

    /**
     * Best-effort world backup + rotation. Never throws; launch/exit continues on failure.
     *
     * @param beforeLaunch true = honor backupBeforeLaunch; false = honor backupAfterExit
     */
    private void maybeBackupWorlds(String versionId, boolean beforeLaunch) {
        var cfg = configManager.getConfig();
        if (beforeLaunch) {
            if (!cfg.isBackupBeforeLaunch()) {
                return;
            }
        } else if (!cfg.isBackupAfterExit()) {
            return;
        }
        if (!Validation.isSafeVersionId(versionId)) {
            return;
        }
        String label = beforeLaunch ? "启动前备份" : "退出后备份";
        try {
            WorldSaveService service = new WorldSaveService(cfg);
            List<WorldSaveService.WorldInfo> worlds = service.listWorlds(versionId);
            List<WorldSaveService.WorldInfo> valid = worlds.stream()
                    .filter(WorldSaveService.WorldInfo::valid)
                    .toList();
            if (valid.isEmpty()) {
                return;
            }
            Platform.runLater(() -> {
                runStatus.setText("运行状态：" + label + "中…");
                pushStatus(label + "中");
                liveLog.appendText(label + "：共 " + valid.size() + " 个世界\n");
            });
            int keep = cfg.getBackupKeepCount();
            for (WorldSaveService.WorldInfo world : valid) {
                try {
                    service.backupWorld(versionId, world.folderName());
                    service.rotateBackups(versionId, world.folderName(), keep);
                    Platform.runLater(() -> liveLog.appendText(
                            label + "完成：" + world.folderName() + "\n"));
                } catch (Exception e) {
                    LOG.warn("{} failed for world {}: {}", label, world.folderName(), e.toString());
                    Platform.runLater(() -> liveLog.appendText(
                            "[警告] " + label + "失败 " + world.folderName() + ": " + e.getMessage() + "\n"));
                }
            }
        } catch (Exception e) {
            LOG.warn("{} aborted for {}: {}", label, versionId, e.toString());
            Platform.runLater(() -> liveLog.appendText(
                    "[警告] " + label + "跳过: " + e.getMessage() + "\n"));
        }
    }

    private void openLatestLog() {
        worker.submit(() -> {
            try {
                Path path = resolveLatestLogPath();
                Platform.runLater(() -> openPath(path));
            } catch (Exception e) {
                ExceptionHandler.handle("读取启动日志失败", e);
            }
        });
    }

    private void exportLatestLog() {
        worker.submit(() -> {
            try {
                Path path = resolveLatestLogPath();
                if (path == null || !Files.isRegularFile(path)) {
                    Platform.runLater(() -> ExceptionHandler.showInfo("提示", "暂无启动日志可导出"));
                    return;
                }
                Platform.runLater(() -> {
                    FileChooser chooser = new FileChooser();
                    chooser.setTitle("导出启动日志");
                    chooser.setInitialFileName(path.getFileName().toString());
                    chooser.getExtensionFilters().add(
                            new FileChooser.ExtensionFilter("日志文件", "*.log", "*.txt"));
                    var file = chooser.showSaveDialog(window());
                    if (file == null) {
                        return;
                    }
                    try {
                        Files.copy(path, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        ExceptionHandler.showInfo("完成", "日志已导出到：\n" + file.getAbsolutePath());
                    } catch (Exception e) {
                        ExceptionHandler.handle("导出日志失败", e);
                    }
                });
            } catch (Exception e) {
                ExceptionHandler.handle("导出日志失败", e);
            }
        });
    }

    private Path resolveLatestLogPath() throws Exception {
        if (lastLaunchLog != null && Files.isRegularFile(lastLaunchLog)) {
            return lastLaunchLog;
        }
        Database.LaunchLogRecord latest = database.latestLaunchLog();
        if (latest == null || !Validation.isNonBlank(latest.logPath())) {
            return null;
        }
        Path path = Path.of(latest.logPath());
        return Files.isRegularFile(path) ? path : null;
    }

    private void openPath(Path path) {
        try {
            Path openPath = path;
            if (openPath == null || !Files.exists(openPath)) {
                openPath = AppPaths.logsDir();
            }
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(openPath.toFile());
            } else {
                ExceptionHandler.showInfo("日志路径", openPath.toAbsolutePath().toString());
            }
        } catch (Exception e) {
            ExceptionHandler.handle("打开日志失败", e);
        }
    }
}
