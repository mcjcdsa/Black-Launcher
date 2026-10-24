package com.blacklauncher.ui.pages;

import com.blacklauncher.core.config.ConfigManager;
import com.blacklauncher.core.exception.ExceptionHandler;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.mod.SimpleMarkdown;
import com.blacklauncher.moddev.FabricDocsService;
import com.blacklauncher.moddev.FabricMetaClient;
import com.blacklauncher.moddev.ModDevConfig;
import com.blacklauncher.moddev.TemplateGenerator;
import com.blacklauncher.moddev.TemplateRequest;
import com.blacklauncher.moddev.TemplateValidation;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;

import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fabric mod template generator + official docs viewer.
 */
public class ModDevPage extends BorderPane {

    private final ConfigManager configManager;
    private final FabricMetaClient metaClient;
    private final FabricDocsService docsService;
    private final TemplateGenerator generator;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "moddev-worker");
        t.setDaemon(true);
        return t;
    });

    private final TextField modNameField = new TextField("Example Mod");
    private final CheckBox customIdCheck = new CheckBox("使用自定义 ID");
    private final TextField modIdField = new TextField(TemplateValidation.DEFAULT_MOD_ID);
    private final TextField packageField = new TextField();
    private final ComboBox<String> mcVersionBox = new ComboBox<>();
    private final CheckBox kotlinCheck = new CheckBox("Kotlin 编程语言");
    private final CheckBox dataGenCheck = new CheckBox("数据生成 (Data Generation)");
    private final CheckBox splitCheck = new CheckBox("分离客户端与公共源码");
    private final CheckBox kotlinDslCheck = new CheckBox("Kotlin 构建脚本 (Gradle Kotlin DSL)");
    private final Label generateStatus = new Label();
    private Path lastGeneratedPath;

    private final ListView<FabricDocsService.DocEntry> docsList = new ListView<>();
    private final TextField searchField = new TextField();
    private final Label syncLabel = new Label();
    private final WebView docsView = new WebView();
    private final Label docsFooter = new Label(FabricDocsService.COPYRIGHT_NOTE);
    private boolean docsTabOpened;

    public ModDevPage(ConfigManager configManager) {
        this.configManager = configManager;
        this.metaClient = new FabricMetaClient(configManager.getConfig());
        this.docsService = new FabricDocsService(configManager.getConfig());
        this.generator = new TemplateGenerator(metaClient);

        getStyleClass().add("page");
        setPadding(new Insets(0));

        ModDevConfig prefs = configManager.getConfig().getModDev();
        packageField.setText(Validation.isNonBlank(prefs.getPackagePrefix())
                ? prefs.getPackagePrefix() + ".mod"
                : "com.example.mod");
        kotlinCheck.setSelected(prefs.isUseKotlin());
        dataGenCheck.setSelected(prefs.isDataGeneration());
        splitCheck.setSelected(prefs.isSplitSources());
        kotlinDslCheck.setSelected(prefs.isKotlinDsl());

        TabPane tabs = new TabPane();
        tabs.getTabs().addAll(buildTemplateTab(), buildDocsTab());
        tabs.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null && "Fabric 文档".equals(b.getText()) && !docsTabOpened) {
                docsTabOpened = true;
                onDocsTabFirstOpen();
            }
        });
        setCenter(tabs);

        Label tip = new Label("提示：已安装的 Fabric 游戏版本可从「下载」页安装 Fabric Loader。");
        tip.getStyleClass().add("muted");
        tip.setWrapText(true);
        tip.setPadding(new Insets(0, 0, 6, 0));
        setTop(tip);

        loadMinecraftVersions();
    }

    private Tab buildTemplateTab() {
        Label title = new Label("模组模板生成器");
        title.getStyleClass().add("page-title");
        Label desc = new Label("按参数生成本地 Fabric Loom 工程（类似 fabricmc.net/develop/template）。模板采用 CC0 许可。");
        desc.getStyleClass().add("muted");
        desc.setWrapText(true);

        modIdField.setDisable(true);
        customIdCheck.selectedProperty().addListener((o, a, b) -> modIdField.setDisable(!b));

        kotlinCheck.setTooltip(new Tooltip("使用 Kotlin 编写模组源码，并依赖 fabric-language-kotlin 语言适配器。"));
        kotlinDslCheck.setTooltip(new Tooltip(
                "将 build.gradle / settings.gradle 改为 Kotlin DSL（.kts）。与「Kotlin 编程语言」无关。"));
        dataGenCheck.setTooltip(new Tooltip("启用 Fabric 数据生成入口与 Loom datagen run 配置。"));
        splitCheck.setTooltip(new Tooltip("使用 Loom splitEnvironmentSourceSets，分离 client 与 common 源码。"));

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(8);
        form.add(new Label("模组名称"), 0, 0);
        form.add(modNameField, 1, 0);
        form.add(customIdCheck, 0, 1);
        form.add(modIdField, 1, 1);
        form.add(new Label("包名"), 0, 2);
        form.add(packageField, 1, 2);
        form.add(new Label("Minecraft 版本"), 0, 3);
        form.add(mcVersionBox, 1, 3);
        mcVersionBox.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(modNameField, Priority.ALWAYS);
        GridPane.setHgrow(modIdField, Priority.ALWAYS);
        GridPane.setHgrow(packageField, Priority.ALWAYS);
        GridPane.setHgrow(mcVersionBox, Priority.ALWAYS);

        VBox advanced = new VBox(6,
                new Label("高级选项"),
                kotlinCheck, dataGenCheck, splitCheck, kotlinDslCheck);
        advanced.getChildren().get(0).getStyleClass().add("muted");

        Button genDir = new Button("生成到工作目录");
        genDir.setDefaultButton(true);
        genDir.setOnAction(e -> generate(false));
        Button genZip = new Button("生成 ZIP…");
        genZip.setOnAction(e -> generate(true));
        Button openFolder = new Button("打开文件夹");
        openFolder.setOnAction(e -> openLastFolder());
        HBox actions = new HBox(8, genDir, genZip, openFolder);
        actions.setAlignment(Pos.CENTER_LEFT);

        generateStatus.setWrapText(true);
        generateStatus.getStyleClass().add("muted");

        VBox root = new VBox(12, title, desc, form, advanced, actions, generateStatus);
        root.setPadding(new Insets(8, 4, 8, 4));
        Tab tab = new Tab("模板生成器", root);
        tab.setClosable(false);
        return tab;
    }

    private Tab buildDocsTab() {
        Label title = new Label("Fabric 官方文档");
        title.getStyleClass().add("page-title");

        searchField.setPromptText("搜索文档…");
        Button searchBtn = new Button("搜索");
        searchBtn.setOnAction(e -> runSearch());
        searchField.setOnAction(e -> runSearch());
        Button syncBtn = new Button("同步文档");
        syncBtn.setOnAction(e -> syncDocs(true));
        HBox tools = new HBox(8, searchField, searchBtn, syncBtn, syncLabel);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        tools.setAlignment(Pos.CENTER_LEFT);
        syncLabel.getStyleClass().add("muted");

        docsList.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(FabricDocsService.DocEntry item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.title() + "  (" + item.path() + ")");
            }
        });
        docsList.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null) {
                showDoc(b.path());
            }
        });
        docsList.setPrefWidth(280);

        docsFooter.getStyleClass().add("muted");
        docsFooter.setWrapText(true);

        BorderPane right = new BorderPane();
        right.setCenter(docsView);
        right.setBottom(docsFooter);
        BorderPane.setMargin(docsFooter, new Insets(6, 0, 0, 0));

        HBox body = new HBox(10, docsList, right);
        HBox.setHgrow(right, Priority.ALWAYS);
        VBox.setVgrow(body, Priority.ALWAYS);

        VBox root = new VBox(10, title, tools, body);
        root.setPadding(new Insets(8, 4, 8, 4));
        VBox.setVgrow(body, Priority.ALWAYS);
        Tab tab = new Tab("Fabric 文档", root);
        tab.setClosable(false);
        updateSyncLabel();
        return tab;
    }

    private void loadMinecraftVersions() {
        mcVersionBox.setPromptText("加载中…");
        worker.submit(() -> {
            try {
                List<FabricMetaClient.GameVersion> versions = metaClient.listGameVersions();
                List<String> ids = versions.stream().map(FabricMetaClient.GameVersion::version).toList();
                Platform.runLater(() -> {
                    mcVersionBox.setItems(FXCollections.observableArrayList(ids));
                    String last = configManager.getConfig().getModDev().getLastMinecraftVersion();
                    if (Validation.isNonBlank(last) && ids.contains(last)) {
                        mcVersionBox.getSelectionModel().select(last);
                    } else if (!ids.isEmpty()) {
                        versions.stream().filter(FabricMetaClient.GameVersion::stable).findFirst()
                                .ifPresentOrElse(
                                        gv -> mcVersionBox.getSelectionModel().select(gv.version()),
                                        () -> mcVersionBox.getSelectionModel().selectFirst());
                    }
                    mcVersionBox.setPromptText("选择版本");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    mcVersionBox.setPromptText("版本列表加载失败（可离线使用缓存）");
                    generateStatus.setText("无法加载 Minecraft 版本列表: " + e.getMessage());
                });
                ExceptionHandler.handle("加载 Fabric 游戏版本失败", e);
            }
        });
    }

    private void generate(boolean asZip) {
        String modName = modNameField.getText();
        String modId = customIdCheck.isSelected() ? modIdField.getText() : "";
        String pkg = packageField.getText();
        String mc = mcVersionBox.getSelectionModel().getSelectedItem();

        TemplateRequest req = new TemplateRequest(
                modName, modId, pkg, mc,
                kotlinCheck.isSelected(), dataGenCheck.isSelected(),
                splitCheck.isSelected(), kotlinDslCheck.isSelected());
        String err = TemplateValidation.validate(req);
        if (err != null) {
            generateStatus.setText(err);
            return;
        }
        String resolvedId = TemplateValidation.normalizeModId(modId);
        Path zipTarget = null;
        if (asZip) {
            zipTarget = chooseZipPath(resolvedId);
            if (zipTarget == null) {
                generateStatus.setText("已取消");
                return;
            }
        }
        generateStatus.setText(asZip ? "正在生成 ZIP…" : "正在生成工程…");
        persistPrefs(pkg, mc);

        Path zipPath = zipTarget;
        worker.submit(() -> {
            try {
                Path work = resolveWorkDir();
                Files.createDirectories(work);
                Path result;
                if (zipPath != null) {
                    result = generator.generateToZip(req, zipPath);
                } else {
                    Path project = uniqueProjectDir(work, resolvedId);
                    result = generator.generate(req, project);
                }
                Path finalResult = result;
                Platform.runLater(() -> {
                    lastGeneratedPath = finalResult;
                    generateStatus.setText("已生成: " + finalResult);
                });
            } catch (Exception e) {
                Platform.runLater(() -> generateStatus.setText("生成失败: " + e.getMessage()));
                ExceptionHandler.handle("生成模组模板失败", e);
            }
        });
    }

    private Path chooseZipPath(String modId) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("保存模组模板 ZIP");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP", "*.zip"));
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT)
                .format(Instant.now().atZone(ZoneId.systemDefault()));
        chooser.setInitialFileName(modId + "-" + stamp + ".zip");
        try {
            Path work = resolveWorkDir();
            Files.createDirectories(work);
            chooser.setInitialDirectory(work.toFile());
        } catch (Exception ignored) {
        }
        var file = chooser.showSaveDialog(getScene() != null ? getScene().getWindow() : null);
        return file == null ? null : file.toPath();
    }

    private Path resolveWorkDir() {
        ModDevConfig prefs = configManager.getConfig().getModDev();
        if (Validation.isNonBlank(prefs.getWorkDirectory())) {
            return Path.of(prefs.getWorkDirectory().trim());
        }
        return AppPaths.modDevDir();
    }

    private static Path uniqueProjectDir(Path work, String modId) throws Exception {
        Path base = work.resolve(modId);
        if (!Files.exists(base)) {
            return base;
        }
        for (int i = 2; i < 1000; i++) {
            Path candidate = work.resolve(modId + "-" + i);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        return work.resolve(modId + "-" + System.currentTimeMillis());
    }

    private void persistPrefs(String pkg, String mc) {
        ModDevConfig prefs = configManager.getConfig().getModDev();
        if (Validation.isNonBlank(pkg) && pkg.contains(".")) {
            prefs.setPackagePrefix(pkg.substring(0, pkg.lastIndexOf('.')));
        } else if (Validation.isNonBlank(pkg)) {
            prefs.setPackagePrefix(pkg.trim());
        }
        if (Validation.isNonBlank(mc)) {
            prefs.setLastMinecraftVersion(mc.trim());
        }
        prefs.setUseKotlin(kotlinCheck.isSelected());
        prefs.setDataGeneration(dataGenCheck.isSelected());
        prefs.setSplitSources(splitCheck.isSelected());
        prefs.setKotlinDsl(kotlinDslCheck.isSelected());
        try {
            configManager.save();
        } catch (Exception ignored) {
            // non-fatal
        }
    }

    private void openLastFolder() {
        Path p = lastGeneratedPath;
        if (p == null) {
            generateStatus.setText("尚无生成结果");
            return;
        }
        try {
            Path open = Files.isDirectory(p) ? p : (p.getParent() != null ? p.getParent() : p);
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(open.toFile());
            } else {
                generateStatus.setText("当前环境不支持打开文件夹: " + open);
            }
        } catch (Exception e) {
            generateStatus.setText("打开失败: " + e.getMessage());
        }
    }

    private void onDocsTabFirstOpen() {
        if (docsService.isCached()) {
            reloadDocsList();
        } else {
            syncDocs(false);
        }
    }

    private void syncDocs(boolean manual) {
        syncLabel.setText(manual ? "正在同步…" : "首次同步文档…");
        worker.submit(() -> {
            try {
                docsService.sync(msg -> Platform.runLater(() -> syncLabel.setText(msg)));
                configManager.getConfig().getModDev().setDocsLastSyncEpochMs(docsService.lastSyncEpochMs());
                try {
                    configManager.save();
                } catch (Exception ignored) {
                }
                Platform.runLater(() -> {
                    updateSyncLabel();
                    reloadDocsList();
                });
            } catch (Exception e) {
                Platform.runLater(() -> syncLabel.setText("同步失败: " + e.getMessage()));
                ExceptionHandler.handle("同步 Fabric 文档失败", e);
            }
        });
    }

    private void reloadDocsList() {
        worker.submit(() -> {
            try {
                List<FabricDocsService.DocEntry> docs = docsService.listDocuments();
                Platform.runLater(() -> {
                    docsList.setItems(FXCollections.observableArrayList(docs));
                    if (!docs.isEmpty()) {
                        docsList.getSelectionModel().selectFirst();
                    }
                });
            } catch (Exception e) {
                ExceptionHandler.handle("加载文档目录失败", e);
            }
        });
    }

    private void runSearch() {
        String q = searchField.getText();
        if (!Validation.isNonBlank(q)) {
            reloadDocsList();
            return;
        }
        worker.submit(() -> {
            try {
                List<FabricDocsService.SearchHit> hits = docsService.search(q);
                List<FabricDocsService.DocEntry> entries = hits.stream()
                        .map(h -> new FabricDocsService.DocEntry(h.path(), h.title() + " — " + h.snippet()))
                        .toList();
                Platform.runLater(() -> docsList.setItems(FXCollections.observableArrayList(entries)));
            } catch (Exception e) {
                ExceptionHandler.handle("搜索文档失败", e);
            }
        });
    }

    private void showDoc(String path) {
        worker.submit(() -> {
            try {
                String md = docsService.readMarkdown(path);
                boolean dark = !"light".equalsIgnoreCase(configManager.getConfig().getTheme());
                String html = SimpleMarkdown.toHtmlDocument(md, dark);
                Platform.runLater(() -> docsView.getEngine().loadContent(html));
            } catch (Exception e) {
                Platform.runLater(() -> docsView.getEngine().loadContent(
                        "<html><body><p>无法打开文档: " + path + "</p></body></html>"));
            }
        });
    }

    private void updateSyncLabel() {
        long ms = docsService.lastSyncEpochMs();
        if (ms <= 0) {
            ms = configManager.getConfig().getModDev().getDocsLastSyncEpochMs();
        }
        if (ms <= 0) {
            syncLabel.setText("尚未同步");
            return;
        }
        String when = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        syncLabel.setText("上次同步: " + when);
    }
}
