package com.blacklauncher.ui.pages;

import com.blacklauncher.BlackLauncherApp;
import com.blacklauncher.auth.MicrosoftAuthService;
import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.config.ConfigManager;
import com.blacklauncher.core.db.Database;
import com.blacklauncher.core.exception.ExceptionHandler;
import com.blacklauncher.core.net.DownloadSource;
import com.blacklauncher.core.net.MirrorSpeedTester;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.PathChangeService;
import com.blacklauncher.core.util.UuidUtil;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.download.InstalledVersionService;
import com.blacklauncher.launch.JavaFinder;
import com.blacklauncher.ui.UiAnimation;
import com.blacklauncher.ui.dialogs.FirstRunWizardDialog;
import com.blacklauncher.ui.dialogs.MicrosoftLoginDialog;
import com.blacklauncher.ui.dialogs.ModpackProgressDialog;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Settings as TabPane groups (@5).
 */
public class SettingsPage extends BorderPane {

    private final ConfigManager configManager;
    private final Database database;
    private final Supplier<Scene> sceneSupplier;
    private final Runnable onAnimationChanged;
    private final ExecutorService pathWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "path-migrate");
        t.setDaemon(true);
        return t;
    });

    private final ComboBox<String> language = new ComboBox<>();
    private final ComboBox<String> theme = new ComboBox<>();
    private final CheckBox enableAnimation = new CheckBox("启用界面动画（侧栏折叠 / 页面淡入）");
    private final ComboBox<String> logLevel = new ComboBox<>();
    private final TextField dataDir = new TextField();
    private final TextField gameDir = new TextField();
    private final TextField javaPath = new TextField();
    private final Spinner<Integer> minMemory = new Spinner<>(256, 32768, 512, 256);
    private final Spinner<Integer> maxMemory = new Spinner<>(512, 65536, 2048, 256);
    private final TextField jvmArgs = new TextField();
    private final Spinner<Integer> width = new Spinner<>(640, 7680, 854, 10);
    private final Spinner<Integer> height = new Spinner<>(360, 4320, 480, 10);
    private final CheckBox fullscreen = new CheckBox("全屏");
    private final TextField proxyHost = new TextField();
    private final Spinner<Integer> proxyPort = new Spinner<>(0, 65535, 0, 1);
    private final CheckBox isolateDirs = new CheckBox("每个版本独立游戏目录");
    private final TextField playerName = new TextField();
    private final CheckBox onlineEnabled = new CheckBox("启用联机功能入口");
    private final Spinner<Integer> lanUdpPort = new Spinner<>(1024, 65535, 25566, 1);
    private final Spinner<Integer> lanServerPort = new Spinner<>(1, 65535, 25565, 1);
    private final CheckBox lanAutoHelper = new CheckBox("增强模式自动加载 LANHelper 模组");
    private final TextField lanCloudUrl = new TextField();
    private final ComboBox<DownloadSource> downloadSource = new ComboBox<>();
    private final Spinner<Integer> downloadThreads = new Spinner<>(4, 128, 32, 4);
    private final CheckBox autoGameLang = new CheckBox("下载游戏时自动配置语言文件（options.txt）");
    private final ComboBox<String> gameLanguage = new ComboBox<>();
    private final TextField microsoftClientId = new TextField();
    private final Label accountStatus = new Label();
    private final MemoryOptimizePane memoryOptimizePane;
    private final TextField settingsSearch = new TextField();
    private final Label settingsSearchHint = new Label();
    private final ListView<JavaFinder.JavaInstall> javaInstallList = new ListView<>();
    private TabPane tabs;
    private final ExecutorService settingsWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "settings-worker");
        t.setDaemon(true);
        return t;
    });

    public SettingsPage(ConfigManager configManager,
                        Database database,
                        Supplier<Scene> sceneSupplier,
                        Runnable onAnimationChanged) {
        this.configManager = configManager;
        this.database = database;
        this.sceneSupplier = sceneSupplier;
        this.onAnimationChanged = onAnimationChanged;
        this.memoryOptimizePane = new MemoryOptimizePane(
                configManager, new InstalledVersionService(database, configManager.getConfig()));

        getStyleClass().add("page");
        setPadding(new Insets(0));

        Label title = new Label("设置");
        title.getStyleClass().add("page-title");

        language.setItems(FXCollections.observableArrayList("zh_CN", "en_US"));
        theme.setItems(FXCollections.observableArrayList("dark", "light"));
        logLevel.setItems(FXCollections.observableArrayList("DEBUG", "INFO", "WARN", "ERROR"));
        minMemory.setEditable(true);
        maxMemory.setEditable(true);
        width.setEditable(true);
        height.setEditable(true);
        proxyPort.setEditable(true);
        downloadThreads.setEditable(true);
        downloadSource.setItems(FXCollections.observableArrayList(DownloadSource.values()));
        gameLanguage.setItems(FXCollections.observableArrayList(
                "zh_CN", "zh_TW", "en_US", "ja_JP", "ko_KR", "ru_RU", "fr_FR", "de_DE"));
        lanUdpPort.setEditable(true);
        lanServerPort.setEditable(true);
        lanCloudUrl.setPromptText("可选云端辅助 Base URL（空=仅 Mojang/本地）");

        Button save = new Button("保存设置");
        save.getStyleClass().addAll("button", "primary");
        save.setOnAction(e -> saveSettings());

        Tab memTab = tab("内存与系统优化", scrollable(memoryOptimizePane));
        memTab.setOnSelectionChanged(e -> {
            if (memTab.isSelected()) {
                memoryOptimizePane.onActivated();
            }
        });

        tabs = new TabPane(
                tab("启动器", launcherPane()),
                tab("下载加速", downloadPane()),
                tab("游戏基础", gameBasicPane()),
                tab("Java", javaPane()),
                memTab,
                tab("联机", onlinePane()),
                tab("账号", accountPane())
        );
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        settingsSearch.setPromptText("搜索设置…");
        HBox.setHgrow(settingsSearch, Priority.ALWAYS);
        settingsSearchHint.getStyleClass().add("muted");
        settingsSearch.textProperty().addListener((o, a, b) -> onSettingsSearch(b));
        HBox searchRow = new HBox(10, settingsSearch, settingsSearchHint);
        searchRow.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, title, searchRow, tabs, save);
        setCenter(root);
        loadFromConfig();
        refreshAccountStatus();
        refreshJavaInstallList(false);
    }

    private static javafx.scene.control.ScrollPane scrollable(javafx.scene.Node content) {
        javafx.scene.control.ScrollPane sp = new javafx.scene.control.ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        return sp;
    }

    private static Tab tab(String name, javafx.scene.Node content) {
        Tab t = new Tab(name, content);
        t.setClosable(false);
        return t;
    }

    private GridPane grid() {
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(10);
        g.setPadding(new Insets(12));
        return g;
    }

    private VBox launcherPane() {
        GridPane g = grid();
        int r = 0;
        g.addRow(r++, new Label("语言"), language);
        g.addRow(r++, new Label("主题"), theme);
        g.addRow(r++, new Label("日志级别"), logLevel);
        g.add(enableAnimation, 0, r++, 2, 1);
        Label hint = new Label("关闭后侧栏折叠与页面切换无过渡，瞬间完成。");
        hint.getStyleClass().add("muted");
        g.add(hint, 0, r++, 2, 1);

        dataDir.setEditable(false);
        HBox.setHgrow(dataDir, Priority.ALWAYS);
        Button openLauncher = new Button("打开");
        openLauncher.setOnAction(e -> openDir(AppPaths.home()));
        Button changeLauncher = new Button("更换并迁移…");
        changeLauncher.setOnAction(e -> changeLauncherDir());
        HBox launcherBtns = new HBox(8, openLauncher, changeLauncher);
        g.addRow(r++, new Label("启动器文件夹"), dataDir);
        g.add(launcherBtns, 2, r - 1);

        gameDir.setEditable(false);
        HBox.setHgrow(gameDir, Priority.ALWAYS);
        Button openGame = new Button("打开");
        openGame.setOnAction(e -> openDir(AppPaths.gameDir()));
        Button changeGame = new Button("更换并迁移…");
        changeGame.setOnAction(e -> changeGameDirectory());
        HBox gameBtns = new HBox(8, openGame, changeGame);
        g.addRow(r++, new Label("游戏文件夹"), gameDir);
        g.add(gameBtns, 2, r - 1);

        Label pathHint = new Label("安装版布局：根目录下 BlackLauncher（程序）与 MinecraftGame（游戏）相互独立。"
                + "更换路径会迁移数据；启动器目录也可通过 ~/.black-launcher/launcher-home.txt 记住。"
                + "在线更新只应更新程序目录，勿触碰 MinecraftGame。");
        pathHint.getStyleClass().add("muted");
        pathHint.setWrapText(true);
        g.add(pathHint, 0, r++, 3, 1);

        Button rerunWizard = new Button("重新运行首次向导");
        rerunWizard.setOnAction(e -> rerunFirstRunWizard());
        g.add(rerunWizard, 0, r, 3, 1);
        return new VBox(g);
    }

    private VBox downloadPane() {
        GridPane g = grid();
        int r = 0;
        g.addRow(r++, new Label("下载源"), downloadSource);
        g.addRow(r++, new Label("并行线程数"), downloadThreads);

        Button testMirrors = new Button("镜像测速");
        testMirrors.setOnAction(e -> runMirrorSpeedTest(false));
        Button applyFastest = new Button("应用最快源");
        applyFastest.setOnAction(e -> applyFastestMirror());
        g.add(new HBox(8, testMirrors, applyFastest), 0, r++, 2, 1);

        Label dlHint = new Label("国内推荐 BMCLAPI 镜像；官方源延迟通常更高，适合镜像故障时切换。"
                + " 游戏文件保存在「启动器」页的游戏文件夹中。");
        dlHint.getStyleClass().add("muted");
        dlHint.setWrapText(true);
        g.add(dlHint, 0, r, 2, 1);
        return new VBox(g);
    }

    private void openDir(Path dir) {
        try {
            java.nio.file.Files.createDirectories(dir);
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(dir.toFile());
            }
        } catch (Exception ex) {
            ExceptionHandler.handle("打开目录失败", ex);
        }
    }

    private void changeLauncherDir() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择新的启动器文件夹");
        chooser.setInitialDirectory(AppPaths.home().toFile());
        File dir = chooser.showDialog(window());
        if (dir == null) {
            return;
        }
        Path target = dir.toPath();
        CheckBox deleteOld = new CheckBox("迁移成功后删除旧启动器目录（推荐释放磁盘）");
        deleteOld.setSelected(true);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("更换启动器文件夹");
        confirm.setHeaderText("将数据迁移到：\n" + target.toAbsolutePath());
        confirm.setContentText("包含配置、数据库、日志、皮肤、整合包导出等。\n"
                + "若游戏目录位于启动器内，会一并映射到新位置。");
        confirm.getDialogPane().setExpandableContent(deleteOld);
        confirm.getDialogPane().setExpanded(true);
        if (window() != null) {
            confirm.initOwner(window());
        }
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        runMigration("迁移启动器数据", (progress) -> PathChangeService.changeLauncherHome(
                configManager, database, target, deleteOld.isSelected(), progress));
    }

    private void changeGameDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择新的游戏文件夹");
        chooser.setInitialDirectory(AppPaths.gameDir().toFile());
        File dir = chooser.showDialog(window());
        if (dir == null) {
            return;
        }
        Path target = dir.toPath();
        CheckBox deleteOld = new CheckBox("迁移成功后删除旧游戏目录");
        deleteOld.setSelected(true);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("更换游戏文件夹");
        confirm.setHeaderText("将游戏数据迁移到：\n" + target.toAbsolutePath());
        confirm.setContentText("包含 versions / libraries / assets / instances（存档、Mod 等）。");
        confirm.getDialogPane().setExpandableContent(deleteOld);
        confirm.getDialogPane().setExpanded(true);
        if (window() != null) {
            confirm.initOwner(window());
        }
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        runMigration("迁移游戏数据", (progress) -> PathChangeService.changeGameDir(
                configManager, target, deleteOld.isSelected(), progress));
    }

    @FunctionalInterface
    private interface MigrationAction {
        void run(java.util.function.BiConsumer<Long, String> progress) throws Exception;
    }

    private void runMigration(String title, MigrationAction action) {
        ModpackProgressDialog progress = new ModpackProgressDialog(window(), title);
        progress.show();
        progress.update("准备迁移…", 0.05, "");
        pathWorker.submit(() -> {
            try {
                action.run((files, msg) -> progress.update(msg, Math.min(0.95, 0.1 + files * 0.001),
                        "已处理 " + files + " 个文件"));
                Platform.runLater(() -> {
                    progress.close();
                    refreshPathFields();
                    ExceptionHandler.showInfo("迁移完成",
                            "启动器文件夹：\n" + AppPaths.home().toAbsolutePath()
                                    + "\n\n游戏文件夹：\n" + AppPaths.gameDir().toAbsolutePath());
                });
            } catch (Exception e) {
                Platform.runLater(progress::close);
                ExceptionHandler.handle(title + "失败", e);
            }
        });
    }

    private void refreshPathFields() {
        dataDir.setText(AppPaths.home().toAbsolutePath().toString());
        gameDir.setText(AppPaths.gameDir().toAbsolutePath().toString());
    }

    private Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }

    private VBox gameBasicPane() {
        GridPane g = grid();
        int r = 0;
        g.add(autoGameLang, 0, r++, 2, 1);
        g.addRow(r++, new Label("默认游戏语言"), gameLanguage);
        g.addRow(r++, new Label("宽度"), width);
        g.addRow(r++, new Label("高度"), height);
        g.add(fullscreen, 0, r++, 2, 1);
        g.add(isolateDirs, 0, r++, 2, 1);
        g.addRow(r++, new Label("代理主机"), proxyHost);
        g.addRow(r, new Label("代理端口"), proxyPort);
        return new VBox(g);
    }

    private VBox javaPane() {
        GridPane g = grid();
        int r = 0;
        Button browseJava = new Button("浏览");
        browseJava.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("选择 java 可执行文件");
            File file = chooser.showOpenDialog(window());
            if (file != null) {
                javaPath.setText(file.getAbsolutePath());
            }
        });
        Button detectJava = new Button("自动检测");
        detectJava.setOnAction(e -> {
            String resolved = JavaFinder.resolveJavaExecutable("");
            javaPath.setText(resolved);
            String ver = JavaFinder.probeVersion(resolved);
            ExceptionHandler.showInfo("Java", resolved + (ver.isBlank() ? "" : "\n" + ver));
        });
        g.addRow(r++, new Label("Java 路径"), javaPath, browseJava);
        g.add(detectJava, 2, r - 1);

        javaInstallList.setPrefHeight(140);
        javaInstallList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(JavaFinder.JavaInstall item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : formatJavaInstall(item));
            }
        });
        g.add(new Label("本机 Java"), 0, r);
        g.add(javaInstallList, 1, r++, 2, 1);

        Button scanJava = new Button("扫描本机 Java");
        scanJava.setOnAction(e -> refreshJavaInstallList(true));
        Button useSelected = new Button("使用所选");
        useSelected.setOnAction(e -> {
            JavaFinder.JavaInstall selected = javaInstallList.getSelectionModel().getSelectedItem();
            if (selected == null) {
                ExceptionHandler.showInfo("提示", "请先在列表中选择一项 Java");
                return;
            }
            javaPath.setText(selected.executable());
        });
        g.add(new HBox(8, scanJava, useSelected), 1, r++, 2, 1);

        Label multiHint = new Label("多版本 MC 常需 Java 8/17/21，不匹配会启动失败。");
        multiHint.getStyleClass().add("muted");
        multiHint.setWrapText(true);
        g.add(multiHint, 0, r++, 3, 1);

        Label hint = new Label("堆内存、GC 方案与系统内存整理请到「内存与系统优化」按版本配置。"
                + " 下方数值仅作新版本默认种子。");
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);
        g.add(hint, 0, r++, 3, 1);
        g.addRow(r++, new Label("默认最小内存 MB"), minMemory);
        g.addRow(r++, new Label("默认最大内存 MB"), maxMemory);
        g.addRow(r, new Label("默认 JVM 参数"), jvmArgs);
        return new VBox(g);
    }

    private VBox onlinePane() {
        GridPane g = grid();
        int r = 0;
        g.add(onlineEnabled, 0, r++, 2, 1);
        g.addRow(r++, new Label("UDP 广播端口"), lanUdpPort);
        g.addRow(r++, new Label("默认服务端端口"), lanServerPort);
        g.add(lanAutoHelper, 0, r++, 2, 1);
        g.addRow(r++, new Label("云端辅助 URL"), lanCloudUrl);
        Label onlineHint = new Label(
                "联机模块需要联网拉取版本/server.jar/LANHelper；游戏连接本身仍走局域网。"
                        + " 不提供内网穿透或公网中转。UDP 广播失效时请用手动 IP 加入。");
        onlineHint.getStyleClass().add("muted");
        onlineHint.setWrapText(true);
        g.add(onlineHint, 0, r, 2, 1);
        return new VBox(g);
    }

    private VBox accountPane() {
        GridPane g = grid();
        int r = 0;
        g.addRow(r++, new Label("游戏昵称"), playerName);
        playerName.setPromptText("3–16 位字母/数字/下划线");
        microsoftClientId.setPromptText("Azure client_id（需 Mojang 审批）");
        g.addRow(r++, new Label("Client ID"), microsoftClientId);
        accountStatus.getStyleClass().add("muted");
        accountStatus.setWrapText(true);
        g.add(accountStatus, 0, r++, 3, 1);
        Button msLogin = new Button("微软登录");
        msLogin.getStyleClass().addAll("button", "primary");
        msLogin.setOnAction(e -> MicrosoftLoginDialog.show(window(), configManager, this::refreshAccountStatus));
        Button msRefresh = new Button("刷新令牌");
        msRefresh.setOnAction(e -> refreshMsToken());
        Button msLogout = new Button("退出正版账号");
        msLogout.setOnAction(e -> {
            new MicrosoftAuthService(configManager.getConfig()).logout();
            try {
                configManager.save();
            } catch (Exception ex) {
                ExceptionHandler.handle("保存失败", ex);
            }
            refreshAccountStatus();
        });
        g.add(new HBox(8, msLogin, msRefresh, msLogout), 0, r++, 3, 1);
        Label msHint = new Label("支持设备代码与浏览器 PKCE。浏览器方式需在 Azure 添加重定向 URI：http://127.0.0.1");
        msHint.getStyleClass().add("muted");
        msHint.setWrapText(true);
        g.add(msHint, 0, r, 3, 1);
        return new VBox(g);
    }

    private void refreshAccountStatus() {
        AppConfig c = configManager.getConfig();
        if ("msa".equalsIgnoreCase(c.getAccountType())
                && Validation.isNonBlank(c.getMinecraftAccessToken())) {
            accountStatus.setText("已登录微软账号：" + c.getPlayerName()
                    + "  UUID：" + c.getPlayerUuid());
        } else {
            accountStatus.setText("当前为离线模式（accessToken=0 / userType=legacy）");
        }
    }

    private void refreshMsToken() {
        try {
            MicrosoftAuthService auth = new MicrosoftAuthService(configManager.getConfig());
            MicrosoftAuthService.Session session = auth.refreshSession();
            auth.applyToConfig(session, true);
            configManager.save();
            refreshAccountStatus();
            ExceptionHandler.showInfo("已刷新", "Minecraft 访问令牌已更新");
        } catch (Exception e) {
            ExceptionHandler.handle("刷新令牌失败", e);
        }
    }

    private void onSettingsSearch(String raw) {
        if (tabs == null) {
            return;
        }
        String q = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            settingsSearchHint.setText("");
            return;
        }
        Map<String, List<String>> keywords = settingsTabKeywords();
        for (Tab tab : tabs.getTabs()) {
            String title = tab.getText() == null ? "" : tab.getText();
            List<String> keys = keywords.getOrDefault(title, List.of(title));
            boolean hit = title.toLowerCase(Locale.ROOT).contains(q);
            if (!hit) {
                for (String key : keys) {
                    if (key.toLowerCase(Locale.ROOT).contains(q) || q.contains(key.toLowerCase(Locale.ROOT))) {
                        hit = true;
                        break;
                    }
                }
            }
            if (hit) {
                tabs.getSelectionModel().select(tab);
                settingsSearchHint.setText("匹配：" + title);
                return;
            }
        }
        settingsSearchHint.setText("无匹配");
    }

    private static Map<String, List<String>> settingsTabKeywords() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("启动器", List.of("启动器", "语言", "主题", "路径", "文件夹", "向导", "动画", "迁移"));
        map.put("下载加速", List.of("下载", "加速", "镜像", "源", "线程", "bmcl", "官方", "测速"));
        map.put("游戏基础", List.of("游戏", "基础", "语言", "分辨率", "全屏", "代理", "隔离"));
        map.put("Java", List.of("java", "jdk", "jre", "jvm"));
        map.put("内存与系统优化", List.of("内存", "优化", "gc", "xmx", "xms", "整理"));
        map.put("联机", List.of("联机", "局域网", "lan", "广播", "云端"));
        map.put("账号", List.of("账号", "微软", "登录", "昵称", "令牌", "正版"));
        return map;
    }

    private void rerunFirstRunWizard() {
        boolean done = FirstRunWizardDialog.show(window(), configManager);
        loadFromConfig();
        refreshAccountStatus();
        refreshJavaInstallList(false);
        Scene scene = sceneSupplier == null ? null : sceneSupplier.get();
        if (scene != null) {
            BlackLauncherApp.applyTheme(scene, configManager.getConfig().getTheme());
        }
        if (done) {
            ExceptionHandler.showInfo("完成", "首次向导设置已应用");
        }
    }

    private void refreshJavaInstallList(boolean announce) {
        settingsWorker.submit(() -> {
            List<JavaFinder.JavaInstall> installs = JavaFinder.discoverInstalls();
            Platform.runLater(() -> {
                javaInstallList.setItems(FXCollections.observableArrayList(installs));
                String current = javaPath.getText() == null ? "" : javaPath.getText().trim();
                if (Validation.isNonBlank(current)) {
                    for (JavaFinder.JavaInstall j : installs) {
                        if (j.executable().equalsIgnoreCase(current)) {
                            javaInstallList.getSelectionModel().select(j);
                            break;
                        }
                    }
                }
                if (announce) {
                    ExceptionHandler.showInfo("扫描完成",
                            installs.isEmpty() ? "未发现可用 Java" : "共发现 " + installs.size() + " 个 Java");
                }
            });
        });
    }

    private static String formatJavaInstall(JavaFinder.JavaInstall j) {
        return "Java " + j.major() + " (" + (j.sixtyFourBit() ? "64位" : "32位") + ") — " + j.executable();
    }

    private void runMirrorSpeedTest(boolean applyFastest) {
        settingsWorker.submit(() -> {
            try {
                MirrorSpeedTester tester = new MirrorSpeedTester();
                List<MirrorSpeedTester.Result> results = tester.testAll();
                DownloadSource fastest = tester.recommendFastest();
                StringBuilder sb = new StringBuilder();
                for (MirrorSpeedTester.Result r : results) {
                    sb.append(r.source().displayName())
                            .append("：")
                            .append(r.ok() ? r.latencyMs() + " ms" : "失败")
                            .append("（")
                            .append(r.detail() == null ? "" : r.detail())
                            .append("）\n");
                }
                sb.append("\n推荐：").append(fastest.displayName());
                Platform.runLater(() -> {
                    if (applyFastest) {
                        downloadSource.getSelectionModel().select(fastest);
                        AppConfig c = configManager.getConfig();
                        c.setDownloadSource(fastest.id());
                        try {
                            configManager.save();
                            ExceptionHandler.showInfo("已应用最快源", sb.toString());
                        } catch (Exception e) {
                            ExceptionHandler.handle("保存下载源失败", e);
                        }
                    } else {
                        ExceptionHandler.showInfo("镜像测速", sb.toString());
                    }
                });
            } catch (Exception e) {
                ExceptionHandler.handle("镜像测速失败", e);
            }
        });
    }

    private void applyFastestMirror() {
        runMirrorSpeedTest(true);
    }

    private void loadFromConfig() {
        AppConfig c = configManager.getConfig();
        language.getSelectionModel().select(c.getLanguage());
        theme.getSelectionModel().select(c.getTheme());
        enableAnimation.setSelected(c.isEnableAnimation());
        logLevel.getSelectionModel().select(c.getLogLevel());
        refreshPathFields();
        javaPath.setText(c.getJavaPath());
        minMemory.getValueFactory().setValue(c.getMinMemoryMb());
        maxMemory.getValueFactory().setValue(c.getMaxMemoryMb());
        jvmArgs.setText(c.getJvmArgs() == null ? "" : c.getJvmArgs());
        width.getValueFactory().setValue(c.getGameWidth());
        height.getValueFactory().setValue(c.getGameHeight());
        fullscreen.setSelected(c.isFullscreen());
        proxyHost.setText(c.getProxyHost() == null ? "" : c.getProxyHost());
        proxyPort.getValueFactory().setValue(c.getProxyPort());
        isolateDirs.setSelected(c.isIsolateGameDirs());
        playerName.setText(c.getPlayerName());
        onlineEnabled.setSelected(c.isOnlineModeEnabled());
        var lan = c.getLan();
        lanUdpPort.getValueFactory().setValue(lan.getBroadcastUdpPort() <= 0 ? 25566 : lan.getBroadcastUdpPort());
        lanServerPort.getValueFactory().setValue(lan.getDefaultServerPort() <= 0 ? 25565 : lan.getDefaultServerPort());
        lanAutoHelper.setSelected(lan.isAutoLoadLanHelper());
        lanCloudUrl.setText(lan.getCloudBaseUrl() == null ? "" : lan.getCloudBaseUrl());
        downloadSource.getSelectionModel().select(DownloadSource.fromId(c.getDownloadSource()));
        downloadThreads.getValueFactory().setValue(c.getDownloadThreads());
        autoGameLang.setSelected(c.isAutoConfigureGameLanguage());
        gameLanguage.getSelectionModel().select(c.getGameLanguage());
        microsoftClientId.setText(c.getMicrosoftClientId() == null ? "" : c.getMicrosoftClientId());
    }

    private void saveSettings() {
        try {
            AppConfig c = configManager.getConfig();
            c.setLanguage(language.getValue());
            c.setTheme(theme.getValue());
            c.setEnableAnimation(enableAnimation.isSelected());
            UiAnimation.setEnabled(c.isEnableAnimation());
            if (onAnimationChanged != null) {
                onAnimationChanged.run();
            }
            c.setLogLevel(logLevel.getValue());
            c.setJavaPath(javaPath.getText() == null ? "" : javaPath.getText().trim());
            c.setMinMemoryMb(minMemory.getValue());
            c.setMaxMemoryMb(maxMemory.getValue());
            c.setJvmArgs(jvmArgs.getText() == null ? "" : jvmArgs.getText().trim());
            c.setGameWidth(width.getValue());
            c.setGameHeight(height.getValue());
            c.setFullscreen(fullscreen.isSelected());
            c.setProxyHost(proxyHost.getText() == null ? "" : proxyHost.getText().trim());
            c.setProxyPort(proxyPort.getValue());
            c.setIsolateGameDirs(isolateDirs.isSelected());
            c.setMicrosoftClientId(microsoftClientId.getText() == null
                    ? "" : microsoftClientId.getText().trim());
            String nickViolation = Validation.playerNameViolation(playerName.getText());
            if (nickViolation != null) {
                ExceptionHandler.showInfo("昵称不合规", nickViolation);
                return;
            }
            String name = playerName.getText().trim();
            c.setPlayerName(name);
            playerName.setText(name);
            boolean msa = "msa".equalsIgnoreCase(c.getAccountType())
                    && Validation.isNonBlank(c.getMinecraftAccessToken());
            if (!msa) {
                c.setPlayerUuid(UuidUtil.offlineUuid(name));
                c.setAccountType("offline");
            }
            c.setOnlineModeEnabled(onlineEnabled.isSelected());
            var lan = c.getLan();
            if (lan == null) {
                lan = new com.blacklauncher.online.LanConfig();
                c.setLan(lan);
            }
            lan.setBroadcastUdpPort(lanUdpPort.getValue());
            lan.setDefaultServerPort(lanServerPort.getValue());
            lan.setAutoLoadLanHelper(lanAutoHelper.isSelected());
            lan.setCloudBaseUrl(lanCloudUrl.getText() == null ? "" : lanCloudUrl.getText().trim());
            DownloadSource source = downloadSource.getValue() == null
                    ? DownloadSource.BMCLAPI : downloadSource.getValue();
            c.setDownloadSource(source.id());
            c.setDownloadThreads(downloadThreads.getValue());
            c.setAutoConfigureGameLanguage(autoGameLang.isSelected());
            c.setGameLanguage(gameLanguage.getValue() == null ? "zh_CN" : gameLanguage.getValue());
            configManager.save();

            Scene scene = sceneSupplier.get();
            if (scene != null) {
                BlackLauncherApp.applyTheme(scene, c.getTheme());
            }
            refreshAccountStatus();
            ExceptionHandler.showInfo("已保存", "设置已写入配置文件");
        } catch (Exception e) {
            ExceptionHandler.handle("保存设置失败", e);
        }
    }
}
