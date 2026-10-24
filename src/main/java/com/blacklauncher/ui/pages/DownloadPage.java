package com.blacklauncher.ui.pages;

import com.blacklauncher.core.config.ConfigManager;
import com.blacklauncher.core.db.Database;
import com.blacklauncher.core.exception.ExceptionHandler;
import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.net.DownloadCancelledException;
import com.blacklauncher.core.net.DownloadControl;
import com.blacklauncher.core.net.DownloadProgressSink;
import com.blacklauncher.core.net.DownloadSource;
import com.blacklauncher.core.net.MirrorSpeedTester;
import com.blacklauncher.core.net.UserPausedException;
import com.blacklauncher.core.util.McVersionUtil;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.download.InstancePaths;
import com.blacklauncher.download.LoaderInstallChoice;
import com.blacklauncher.download.ModLoader;
import com.blacklauncher.download.ModLoaderInstallService;
import com.blacklauncher.download.VersionManifestService;
import com.blacklauncher.download.VersionRepairService;
import com.blacklauncher.mod.ModInstallService;
import com.blacklauncher.mod.ModrinthClient;
import com.blacklauncher.model.VersionManifest;
import com.blacklauncher.skin.SkinService;
import com.blacklauncher.ui.UiAnimation;
import com.blacklauncher.ui.dialogs.InstallLocationDialog;
import com.blacklauncher.ui.dialogs.LoaderChoiceDialog;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Download center: game versions, mods, shaders, modpacks, skins.
 */
public class DownloadPage extends BorderPane {

    private final ConfigManager configManager;
    private final Database database;
    private final Runnable onInstalledChanged;
    private final java.util.function.Consumer<java.util.List<Path>> modpackImportHandler;
    private final ExecutorService worker = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "download-worker");
        t.setDaemon(true);
        return t;
    });

    private final TableView<VersionManifest.VersionEntry> versionList = new TableView<>();
    private final ComboBox<String> typeFilter = new ComboBox<>();
    private final TextField versionSearch = new TextField();
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Label progressLabel = new Label("\u5c31\u7eea");
    private final Label progressPercent = new Label("0%");
    private final Label progressSpeed = new Label("0 B/s");
    private final Label mainProgressLabel = new Label("\u4e3b\u6587\u4ef6 0%");
    private final Label depProgressLabel = new Label("\u4f9d\u8d56 0%");
    private final Label depFileLabel = new Label("");
    private final DownloadProgressSink progressSink = new DownloadProgressSink(this::applyProgressSnapshot);
    private List<VersionManifest.VersionEntry> allVersions = List.of();
    private ModLoaderInstallService activeDownload;
    private Button pauseButton;
    private Button resumeButton;
    private volatile String lastProgressMessage = "\u5c31\u7eea";
    private Timeline progressTimeline;

    private final TableView<ModrinthClient.ModProject> modList = new TableView<>();
    private final TableView<ModrinthClient.ModProject> shaderList = new TableView<>();
    private final TableView<ModrinthClient.ModProject> resourcePackList = new TableView<>();
    private final TableView<ModrinthClient.ModProject> packList = new TableView<>();
    private final ListView<String> skinList = new ListView<>();
    private final ImageView skinPreview = new ImageView();

    private final Label modStatus = statusLabel("\u6b63\u5728\u52a0\u8f7d\u70ed\u95e8 Mod...");
    private final Label shaderStatus = statusLabel("\u6b63\u5728\u52a0\u8f7d\u70ed\u95e8\u5149\u5f71...");
    private final Label resourcePackStatus = statusLabel("\u6b63\u5728\u52a0\u8f7d\u70ed\u95e8\u8d44\u6e90\u5305...");
    private final Label packStatus = statusLabel("\u6b63\u5728\u52a0\u8f7d\u70ed\u95e8\u6574\u5408\u5305...");
    private final Label skinStatus = statusLabel("\u6b63\u5728\u52a0\u8f7d\u76ae\u80a4\u5217\u8868...");

    private final TextField modQuery = new TextField();
    private final TextField modGameVersion = new TextField();
    private final ComboBox<String> modLoader = new ComboBox<>();

    private final TextField shaderQuery = new TextField();
    private final TextField shaderGameVersion = new TextField();

    private final TextField resourcePackQuery = new TextField();
    private final TextField resourcePackGameVersion = new TextField();

    private final TextField packQuery = new TextField();
    private final TextField packGameVersion = new TextField();

    private final AtomicBoolean modsLoaded = new AtomicBoolean(false);
    private final AtomicBoolean shadersLoaded = new AtomicBoolean(false);
    private final AtomicBoolean resourcePacksLoaded = new AtomicBoolean(false);
    private final AtomicBoolean packsLoaded = new AtomicBoolean(false);

    private final StackPane pageStack = new StackPane();
    private VBox listRoot;
    private ModDetailPane modDetailPane;

    public DownloadPage(ConfigManager configManager,
                        Database database,
                        Runnable onInstalledChanged) {
        this(configManager, database, onInstalledChanged, null);
    }

    public DownloadPage(ConfigManager configManager,
                        Database database,
                        Runnable onInstalledChanged,
                        java.util.function.Consumer<java.util.List<Path>> modpackImportHandler) {
        this.configManager = configManager;
        this.database = database;
        this.onInstalledChanged = onInstalledChanged;
        this.modpackImportHandler = modpackImportHandler;

        getStyleClass().add("page");
        setPadding(new Insets(4));

        Label title = new Label("\u4e0b\u8f7d");
        title.getStyleClass().add("page-title");

        TabPane tabs = new TabPane(
                gameTab(),
                modTab(),
                shaderTab(),
                resourcePackTab(),
                packTab(),
                skinTab()
        );
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(14);
        HBox.setHgrow(progressBar, Priority.ALWAYS);
        progressLabel.getStyleClass().add("muted");
        progressPercent.getStyleClass().add("progress-meta");
        progressSpeed.getStyleClass().add("progress-meta");
        mainProgressLabel.getStyleClass().add("progress-meta");
        depProgressLabel.getStyleClass().add("progress-meta");
        depFileLabel.getStyleClass().add("muted");
        progressPercent.setMinWidth(56);
        progressSpeed.setMinWidth(90);
        mainProgressLabel.setMinWidth(88);
        depProgressLabel.setMinWidth(72);
        depFileLabel.setMaxWidth(360);

        HBox progressMeta = new HBox(16, progressPercent, progressSpeed, mainProgressLabel, depProgressLabel);
        progressMeta.setAlignment(Pos.CENTER_LEFT);
        HBox progressRow = new HBox(12, progressBar, progressMeta);
        progressRow.setAlignment(Pos.CENTER_LEFT);

        listRoot = new VBox(8, title, tabs, progressLabel, progressRow, depFileLabel);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        modDetailPane = new ModDetailPane(
                configManager,
                database,
                worker,
                this::showModList,
                this::openModDetail,
                msg -> setProgress(msg, progressBar.getProgress())
        );
        modDetailPane.setVisible(false);
        modDetailPane.setManaged(false);

        pageStack.getChildren().addAll(listRoot, modDetailPane);
        setCenter(pageStack);

        preloadDefaults();
        reloadManifest();
        // Eagerly load catalogs so tabs are never blank on first open.
        loadPopularProjects("mod", null, preferredLoader(), modList, modStatus, modsLoaded);
        loadPopularProjects("shader", null, null, shaderList, shaderStatus, shadersLoaded);
        loadPopularProjects("resourcepack", null, null, resourcePackList, resourcePackStatus, resourcePacksLoaded);
        loadPopularProjects("modpack", null, null, packList, packStatus, packsLoaded);
    }

    private void showModList() {
        modDetailPane.setVisible(false);
        modDetailPane.setManaged(false);
        listRoot.setVisible(true);
        listRoot.setManaged(true);
    }

    private void openModDetail(String projectIdOrSlug) {
        if (!Validation.isNonBlank(projectIdOrSlug)) {
            return;
        }
        listRoot.setVisible(false);
        listRoot.setManaged(false);
        modDetailPane.setVisible(true);
        modDetailPane.setManaged(true);
        modDetailPane.load(projectIdOrSlug);
    }

    private void preloadDefaults() {
        String selected = configManager.getConfig().getSelectedVersion();
        String base = McVersionUtil.extractBaseVersion(selected);
        if (!Validation.isNonBlank(base)) {
            base = "1.20.1";
        }
        modGameVersion.setText(base);
        shaderGameVersion.setText(base);
        packGameVersion.setText(base);

        String loaderHint = preferredLoader();
        modLoader.setItems(FXCollections.observableArrayList("\u5168\u90e8", "fabric", "forge", "quilt", "neoforge"));
        if (Validation.isNonBlank(loaderHint) && modLoader.getItems().contains(loaderHint)) {
            modLoader.getSelectionModel().select(loaderHint);
        } else {
            modLoader.getSelectionModel().select("\u5168\u90e8");
        }
    }

    private String preferredLoader() {
        String selected = configManager.getConfig().getSelectedVersion();
        if (!Validation.isNonBlank(selected)) {
            return "fabric";
        }
        String lower = selected.toLowerCase(Locale.ROOT);
        if (lower.contains("neoforge")) {
            return "neoforge";
        }
        if (lower.contains("forge")) {
            return "forge";
        }
        if (lower.contains("quilt")) {
            return "quilt";
        }
        if (lower.contains("fabric")) {
            return "fabric";
        }
        return "\u5168\u90e8";
    }

    private Tab gameTab() {
        typeFilter.setItems(FXCollections.observableArrayList("\u5168\u90e8", "\u6b63\u5f0f\u7248", "\u5feb\u7167\u7248", "\u5386\u53f2\u7248"));
        typeFilter.getSelectionModel().selectFirst();
        typeFilter.setOnAction(e -> applyVersionFilter());

        versionSearch.setPromptText("\u641c\u7d22\u7248\u672c\u53f7");
        versionSearch.textProperty().addListener((o, a, b) -> applyVersionFilter());

        Button reload = new Button("\u5237\u65b0\u6e05\u5355");
        reload.setOnAction(e -> reloadManifest());

        Button download = new Button("\u4e0b\u8f7d\u9009\u4e2d\u7248\u672c");
        download.getStyleClass().addAll("button", "primary");
        download.setOnAction(e -> downloadSelectedVersion());

        pauseButton = new Button("\u6682\u505c");
        pauseButton.setDisable(true);
        pauseButton.setOnAction(e -> {
            if (activeDownload != null) {
                activeDownload.pauseByUser();
            }
        });

        resumeButton = new Button("\u7ee7\u7eed");
        resumeButton.setDisable(true);
        resumeButton.setOnAction(e -> {
            if (activeDownload != null) {
                activeDownload.resumeByUser();
            }
        });

        Button cancel = new Button("\u53d6\u6d88\u4e0b\u8f7d");
        cancel.setOnAction(e -> {
            if (activeDownload != null) {
                activeDownload.cancel();
            }
        });

        Button inspectOnly = new Button("\u4ec5\u6821\u9a8c");
        inspectOnly.setOnAction(e -> inspectSelectedVersion());

        Button prepareRepair = new Button("\u8865\u5168\u7f3a\u5931");
        prepareRepair.setOnAction(e -> prepareRepairSelectedVersion());

        Button mirrorSpeed = new Button("\u955c\u50cf\u6d4b\u901f");
        mirrorSpeed.setOnAction(e -> runMirrorSpeedTest());

        configureVersionTable(versionList);
        versionList.setOnMouseClicked(ev -> {
            if (ev.getButton() == MouseButton.PRIMARY && ev.getClickCount() == 2) {
                if (versionList.getSelectionModel().getSelectedItem() != null) {
                    downloadSelectedVersion();
                }
            }
        });
        VBox.setVgrow(versionList, Priority.ALWAYS);

        Label tip = new Label("\u4e0b\u8f7d\u524d\u4f1a\u8be2\u95ee\u5b89\u88c5\u7c7b\u578b\uff1a\u539f\u7248 / Fabric / Forge / Quilt / NeoForge\uff1b\u65ad\u7f51\u4f1a\u81ea\u52a8\u7b49\u5f85\u7eed\u4f20\uff0c\u624b\u52a8\u6682\u505c\u4e0d\u4f1a\u81ea\u52a8\u6062\u590d");
        tip.getStyleClass().add("muted");

        HBox tools = new HBox(10, new Label("\u7c7b\u578b"), typeFilter, versionSearch, reload, download,
                pauseButton, resumeButton, cancel, inspectOnly, prepareRepair, mirrorSpeed);
        tools.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(10, tip, tools, versionList);
        VBox.setVgrow(box, Priority.ALWAYS);
        return new Tab("\u6e38\u620f\u7248\u672c", box);
    }

    private Tab modTab() {
        modQuery.setPromptText("\u641c\u7d22 Mod\uff08\u7559\u7a7a\u663e\u793a\u70ed\u95e8\uff09");
        modGameVersion.setPromptText("\u6e38\u620f\u7248\u672c\uff0c\u5982 1.20.1");
        HBox.setHgrow(modQuery, Priority.ALWAYS);

        Button search = new Button("\u641c\u7d22");
        search.setOnAction(e -> {
            modsLoaded.set(false);
            searchProjects(modQuery.getText(), modGameVersion.getText(), loaderFilter(modLoader.getValue()),
                    "mod", modList, modStatus, modsLoaded);
        });

        Button refresh = new Button("\u5237\u65b0\u70ed\u95e8");
        refresh.setOnAction(e -> {
            modQuery.clear();
            modsLoaded.set(false);
            loadPopularProjects("mod", modGameVersion.getText(), loaderFilter(modLoader.getValue()),
                    modList, modStatus, modsLoaded);
        });

        Button detail = new Button("\u67e5\u770b\u8be6\u60c5");
        detail.getStyleClass().addAll("button", "primary");
        detail.setOnAction(e -> {
            ModrinthClient.ModProject selected = modList.getSelectionModel().getSelectedItem();
            if (selected == null) {
                ExceptionHandler.showInfo("\u63d0\u793a", "\u8bf7\u5148\u9009\u62e9 Mod");
                return;
            }
            openModDetail(selected.id());
        });

        Button install = new Button("\u5feb\u901f\u5b89\u88c5\u6700\u65b0\u7248");
        install.setOnAction(e -> installSelectedMod());

        Button favorites = new Button("\u6211\u7684\u6536\u85cf");
        favorites.setOnAction(e -> showFavorites());

        configureProjectTable(modList);
        modList.setOnMouseClicked(ev -> {
            if (ev.getButton() == MouseButton.PRIMARY && ev.getClickCount() == 2) {
                ModrinthClient.ModProject selected = modList.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    openModDetail(selected.id());
                }
            }
        });
        HBox tools = new HBox(8, modQuery, modGameVersion, modLoader, search, refresh, detail, install, favorites);
        tools.setAlignment(Pos.CENTER_LEFT);
        Label tip = new Label("\u53cc\u51fb\u6216\u70b9\u300c\u67e5\u770b\u8be6\u60c5\u300d\u8fdb\u5165\u6a21\u7ec4\u8be6\u60c5\u9875\uff08\u7248\u672c\u7b5b\u9009 / \u4f9d\u8d56 / \u5b89\u88c5\uff09");
        tip.getStyleClass().add("muted");
        VBox box = new VBox(10, tip, tools, modStatus, modList);
        VBox.setVgrow(modList, Priority.ALWAYS);
        return new Tab("Mod", box);
    }

    private void showFavorites() {
        worker.submit(() -> {
            try {
                var favs = database.listModFavorites();
                Platform.runLater(() -> {
                    if (favs.isEmpty()) {
                        ExceptionHandler.showInfo("\u6536\u85cf", "\u8fd8\u6ca1\u6709\u6536\u85cf\u7684\u6a21\u7ec4\u3002\u5728\u8be6\u60c5\u9875\u70b9\u51fb\u300c\u6536\u85cf\u300d\u5373\u53ef\u6dfb\u52a0\u3002");
                        return;
                    }
                    List<ModrinthClient.ModProject> projects = favs.stream()
                            .map(f -> new ModrinthClient.ModProject(
                                    f.projectId(), f.slug(), f.title(), "\u5df2\u6536\u85cf",
                                    0L, "", List.of(), f.iconUrl() == null ? "" : f.iconUrl()))
                            .toList();
                    modList.setItems(FXCollections.observableArrayList(projects));
                    modStatus.setText("\u6536\u85cf " + projects.size() + " \u9879\uff08\u53cc\u51fb\u6253\u5f00\u8be6\u60c5\uff09");
                });
            } catch (Exception e) {
                ExceptionHandler.handle("\u8bfb\u53d6\u6536\u85cf\u5931\u8d25", e);
            }
        });
    }

    private Tab shaderTab() {
        shaderQuery.setPromptText("\u641c\u7d22\u5149\u5f71\uff08\u7559\u7a7a\u663e\u793a\u70ed\u95e8\uff09");
        shaderGameVersion.setPromptText("\u6e38\u620f\u7248\u672c");
        HBox.setHgrow(shaderQuery, Priority.ALWAYS);

        Button search = new Button("\u641c\u7d22");
        search.setOnAction(e -> {
            shadersLoaded.set(false);
            searchProjects(shaderQuery.getText(), shaderGameVersion.getText(), null,
                    "shader", shaderList, shaderStatus, shadersLoaded);
        });

        Button refresh = new Button("\u5237\u65b0\u70ed\u95e8");
        refresh.setOnAction(e -> {
            shaderQuery.clear();
            shadersLoaded.set(false);
            loadPopularProjects("shader", shaderGameVersion.getText(), null,
                    shaderList, shaderStatus, shadersLoaded);
        });

        Button install = new Button("\u4e0b\u8f7d\u5e76\u5b89\u88c5");
        install.getStyleClass().addAll("button", "primary");
        install.setOnAction(e -> installSelectedShader());

        configureProjectTable(shaderList);
        HBox tools = new HBox(8, shaderQuery, shaderGameVersion, search, refresh, install);
        VBox box = new VBox(10, tools, shaderStatus, shaderList);
        VBox.setVgrow(shaderList, Priority.ALWAYS);
        return new Tab("\u5149\u5f71", box);
    }

    private Tab resourcePackTab() {
        resourcePackQuery.setPromptText("搜索资源包（留空显示热门）");
        resourcePackGameVersion.setPromptText("游戏版本");
        HBox.setHgrow(resourcePackQuery, Priority.ALWAYS);

        Button search = new Button("搜索");
        search.setOnAction(e -> {
            resourcePacksLoaded.set(false);
            searchProjects(resourcePackQuery.getText(), resourcePackGameVersion.getText(), null,
                    "resourcepack", resourcePackList, resourcePackStatus, resourcePacksLoaded);
        });

        Button refresh = new Button("刷新热门");
        refresh.setOnAction(e -> {
            resourcePackQuery.clear();
            resourcePacksLoaded.set(false);
            loadPopularProjects("resourcepack", resourcePackGameVersion.getText(), null,
                    resourcePackList, resourcePackStatus, resourcePacksLoaded);
        });

        Button install = new Button("下载并安装");
        install.getStyleClass().addAll("button", "primary");
        install.setOnAction(e -> installSelectedResourcePack());

        configureProjectTable(resourcePackList);
        HBox tools = new HBox(8, resourcePackQuery, resourcePackGameVersion, search, refresh, install);
        VBox box = new VBox(10, tools, resourcePackStatus, resourcePackList);
        VBox.setVgrow(resourcePackList, Priority.ALWAYS);
        return new Tab("资源包", box);
    }

    private Tab packTab() {
        packQuery.setPromptText("\u641c\u7d22\u6574\u5408\u5305\uff08\u7559\u7a7a\u663e\u793a\u70ed\u95e8\uff09");
        packGameVersion.setPromptText("\u6e38\u620f\u7248\u672c");
        HBox.setHgrow(packQuery, Priority.ALWAYS);

        Button search = new Button("\u641c\u7d22");
        search.setOnAction(e -> {
            packsLoaded.set(false);
            searchProjects(packQuery.getText(), packGameVersion.getText(), null,
                    "modpack", packList, packStatus, packsLoaded);
        });

        Button refresh = new Button("\u5237\u65b0\u70ed\u95e8");
        refresh.setOnAction(e -> {
            packQuery.clear();
            packsLoaded.set(false);
            loadPopularProjects("modpack", packGameVersion.getText(), null,
                    packList, packStatus, packsLoaded);
        });

        Button download = new Button("\u4e0b\u8f7d\u6574\u5408\u5305");
        download.getStyleClass().addAll("button", "primary");
        download.setOnAction(e -> downloadSelectedPack());

        configureProjectTable(packList);
        HBox tools = new HBox(8, packQuery, packGameVersion, search, refresh, download);
        VBox box = new VBox(10, tools, packStatus, packList);
        VBox.setVgrow(packList, Priority.ALWAYS);
        return new Tab("\u6574\u5408\u5305", box);
    }

    private Tab skinTab() {
        SkinService skinService = new SkinService(configManager);
        Button refresh = new Button("\u5237\u65b0\u672c\u5730");
        refresh.setOnAction(e -> reloadSkins(skinService));

        Button importBtn = new Button("\u5bfc\u5165\u672c\u5730\u76ae\u80a4");
        importBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("\u9009\u62e9 PNG \u76ae\u80a4");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG", "*.png"));
            File file = chooser.showOpenDialog(window());
            if (file == null) {
                return;
            }
            worker.submit(() -> {
                try {
                    Path imported = skinService.importSkin(file.toPath());
                    Platform.runLater(() -> {
                        reloadSkins(skinService);
                        ExceptionHandler.showInfo("\u5b8c\u6210", "\u5df2\u5bfc\u5165: " + imported.getFileName());
                    });
                } catch (Exception ex) {
                    ExceptionHandler.handle("\u5bfc\u5165\u76ae\u80a4\u5931\u8d25", ex);
                }
            });
        });

        Button select = new Button("\u8bbe\u4e3a\u5f53\u524d\u76ae\u80a4");
        select.getStyleClass().addAll("button", "primary");
        select.setOnAction(e -> {
            String selected = skinList.getSelectionModel().getSelectedItem();
            if (!Validation.isNonBlank(selected)) {
                ExceptionHandler.showInfo("\u63d0\u793a", "\u8bf7\u9009\u62e9\u76ae\u80a4");
                return;
            }
            try {
                skinService.selectSkin(Path.of(selected));
                ExceptionHandler.showInfo("\u5b8c\u6210", "\u5df2\u9009\u62e9\u76ae\u80a4\uff08\u79bb\u7ebf\uff09");
            } catch (Exception ex) {
                ExceptionHandler.handle("\u9009\u62e9\u76ae\u80a4\u5931\u8d25", ex);
            }
        });

        Button fetchOnline = new Button("\u62c9\u53d6\u6b63\u7248\u76ae\u80a4");
        fetchOnline.setOnAction(e -> worker.submit(() -> {
            try {
                List<SkinService.OnlineSkin> skins = skinService.fetchOnlineSkins();
                String summary = skins.isEmpty() ? "\u6863\u6848\u4e2d\u65e0\u76ae\u80a4\u6761\u76ee"
                        : skins.stream()
                        .map(s -> s.state() + " / " + s.variant() + " \u2192 " + s.url())
                        .reduce((a, b) -> a + "\n" + b).orElse("");
                Platform.runLater(() -> {
                    skinStatus.setText("\u6b63\u7248\u76ae\u80a4 " + skins.size() + " \u6761");
                    ExceptionHandler.showInfo("\u6b63\u7248\u76ae\u80a4", summary);
                });
            } catch (Exception ex) {
                ExceptionHandler.handle("\u62c9\u53d6\u6b63\u7248\u76ae\u80a4\u5931\u8d25", ex);
            }
        }));

        Button uploadOnline = new Button("\u4e0a\u4f20\u5230\u6b63\u7248");
        uploadOnline.setOnAction(e -> {
            String selected = skinList.getSelectionModel().getSelectedItem();
            if (!Validation.isNonBlank(selected)) {
                ExceptionHandler.showInfo("\u63d0\u793a", "\u8bf7\u5148\u9009\u62e9\u672c\u5730 PNG");
                return;
            }
            worker.submit(() -> {
                try {
                    skinService.uploadSkin(Path.of(selected), "CLASSIC");
                    Platform.runLater(() -> ExceptionHandler.showInfo("\u5b8c\u6210", "\u76ae\u80a4\u5df2\u4e0a\u4f20\uff08CLASSIC\uff09"));
                } catch (Exception ex) {
                    ExceptionHandler.handle("\u4e0a\u4f20\u76ae\u80a4\u5931\u8d25", ex);
                }
            });
        });

        Button resetOnline = new Button("\u91cd\u7f6e\u6b63\u7248\u76ae\u80a4");
        resetOnline.setOnAction(e -> worker.submit(() -> {
            try {
                skinService.resetSkin();
                Platform.runLater(() -> ExceptionHandler.showInfo("\u5b8c\u6210", "\u5df2\u91cd\u7f6e\u4e3a\u6b63\u7248\u9ed8\u8ba4\u76ae\u80a4"));
            } catch (Exception ex) {
                ExceptionHandler.handle("\u91cd\u7f6e\u76ae\u80a4\u5931\u8d25", ex);
            }
        }));

        Label current = new Label("\u5f53\u524d: " + skinService.selectedSkinPath());
        current.getStyleClass().add("muted");
        Label onlineHint = new Label("\u6b63\u7248\u76ae\u80a4\u9700\u5728\u300c\u8bbe\u7f6e\u300d\u4e2d\u5b8c\u6210\u5fae\u8f6f\u767b\u5f55\u540e\u518d\u64cd\u4f5c\u3002");
        onlineHint.getStyleClass().add("muted");
        onlineHint.setWrapText(true);

        HBox tools = new HBox(8, refresh, importBtn, select);
        HBox onlineTools = new HBox(8, fetchOnline, uploadOnline, resetOnline);

        skinPreview.setFitWidth(128);
        skinPreview.setFitHeight(128);
        skinPreview.setPreserveRatio(true);
        skinPreview.setSmooth(true);

        StackPane previewPane = new StackPane(skinPreview);
        previewPane.setStyle("-fx-background-color: #2a2e36;");
        previewPane.setMinWidth(160);
        previewPane.setPrefWidth(200);
        StackPane.setAlignment(skinPreview, Pos.CENTER);

        skinList.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> showSkinPreview(b));
        VBox.setVgrow(skinList, Priority.ALWAYS);

        VBox left = new VBox(10, tools, onlineTools, current, onlineHint, skinStatus, skinList);
        VBox.setVgrow(skinList, Priority.ALWAYS);
        SplitPane split = new SplitPane(left, previewPane);
        split.setDividerPositions(0.72);
        VBox.setVgrow(split, Priority.ALWAYS);

        VBox box = new VBox(split);
        VBox.setVgrow(box, Priority.ALWAYS);
        reloadSkins(skinService);
        return new Tab("\u76ae\u80a4", box);
    }

    private void configureVersionTable(TableView<VersionManifest.VersionEntry> table) {
        TableColumn<VersionManifest.VersionEntry, String> idCol = new TableColumn<>("\u7248\u672c\u53f7");
        idCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue() == null ? "" : blankToEmpty(c.getValue().getId())));

        TableColumn<VersionManifest.VersionEntry, String> typeCol = new TableColumn<>("\u7c7b\u578b");
        typeCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue() == null ? "" : localizeType(c.getValue().getType())));

        TableColumn<VersionManifest.VersionEntry, String> timeCol = new TableColumn<>("\u53d1\u5e03\u65f6\u95f4");
        timeCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue() == null ? "" : blankToEmpty(c.getValue().getReleaseTime())));

        TableColumn<VersionManifest.VersionEntry, String> actionCol = new TableColumn<>("\u64cd\u4f5c");
        actionCol.setCellValueFactory(c -> new SimpleStringProperty("\u5b89\u88c5 / \u4fee\u590d"));
        actionCol.setMinWidth(90);

        table.getColumns().setAll(idCol, typeCol, timeCol, actionCol);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("\u6682\u65e0\u7248\u672c\u6570\u636e"));
    }

    private void configureProjectTable(TableView<ModrinthClient.ModProject> table) {
        TableColumn<ModrinthClient.ModProject, String> nameCol = new TableColumn<>("\u540d\u79f0");
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue() == null ? "" : blankToEmpty(c.getValue().title())));

        TableColumn<ModrinthClient.ModProject, String> authorCol = new TableColumn<>("\u4f5c\u8005");
        authorCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue() == null ? "" : blankToEmpty(c.getValue().author())));

        TableColumn<ModrinthClient.ModProject, String> downloadsCol = new TableColumn<>("\u4e0b\u8f7d\u91cf");
        downloadsCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue() == null ? "" : Long.toString(c.getValue().downloads())));

        TableColumn<ModrinthClient.ModProject, String> loaderCol = new TableColumn<>("\u52a0\u8f7d\u5668");
        loaderCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue() == null ? "" : blankToEmpty(c.getValue().loadersLabel())));

        TableColumn<ModrinthClient.ModProject, String> descCol = new TableColumn<>("\u7b80\u4ecb");
        descCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue() == null ? "" : blankToEmpty(c.getValue().description())));

        table.getColumns().setAll(nameCol, authorCol, downloadsCol, loaderCol, descCol);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("\u6682\u65e0\u7ed3\u679c"));
    }

    private void showSkinPreview(String path) {
        if (!Validation.isNonBlank(path)) {
            skinPreview.setImage(null);
            return;
        }
        try {
            skinPreview.setImage(new Image(Path.of(path).toUri().toString(), 128, 128, true, true));
        } catch (Exception e) {
            skinPreview.setImage(null);
        }
    }

    private static String localizeType(String type) {
        if (type == null) {
            return "";
        }
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "release" -> "\u6b63\u5f0f\u7248";
            case "snapshot" -> "\u5feb\u7167\u7248";
            default -> type;
        };
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void reloadManifest() {
        setProgress("\u6b63\u5728\u83b7\u53d6\u7248\u672c\u6e05\u5355...", 0.05);
        worker.submit(() -> {
            try {
                VersionManifest manifest = new VersionManifestService(configManager.getConfig()).fetch();
                allVersions = manifest.getVersions();
                Platform.runLater(this::applyVersionFilter);
                setProgress("\u7248\u672c\u6e05\u5355\u5df2\u66f4\u65b0\uff0c\u5171 " + allVersions.size() + " \u4e2a\u7248\u672c", 1.0);
            } catch (Exception e) {
                ExceptionHandler.handle("\u83b7\u53d6\u7248\u672c\u6e05\u5355\u5931\u8d25", e);
                setProgress("\u83b7\u53d6\u6e05\u5355\u5931\u8d25\uff08\u53ef\u79bb\u7ebf\u4f7f\u7528\u5df2\u5b89\u88c5\u7248\u672c\uff09", 0);
            }
        });
    }

    private void applyVersionFilter() {
        String type = typeFilter.getSelectionModel().getSelectedItem();
        String q = versionSearch.getText() == null ? "" : versionSearch.getText().trim().toLowerCase(Locale.ROOT);
        List<VersionManifest.VersionEntry> filtered = allVersions.stream()
                .filter(v -> matchType(type, v.getType()))
                .filter(v -> q.isEmpty() || v.getId().toLowerCase(Locale.ROOT).contains(q))
                .collect(Collectors.toList());
        versionList.setItems(FXCollections.observableArrayList(filtered));
    }

    private boolean matchType(String filter, String type) {
        if (filter == null || "\u5168\u90e8".equals(filter) || "all".equalsIgnoreCase(filter)) {
            return true;
        }
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return switch (filter) {
            case "\u6b63\u5f0f\u7248", "release" -> "release".equals(t);
            case "\u5feb\u7167\u7248", "snapshot" -> "snapshot".equals(t);
            case "\u5386\u53f2\u7248", "old" -> "old_alpha".equals(t) || "old_beta".equals(t);
            default -> true;
        };
    }

    private void downloadSelectedVersion() {
        VersionManifest.VersionEntry selected = versionList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            ExceptionHandler.showInfo("\u63d0\u793a", "\u8bf7\u9009\u62e9\u8981\u4e0b\u8f7d\u7684\u7248\u672c");
            return;
        }

        Optional<LoaderInstallChoice> choice = LoaderChoiceDialog.show(window(), selected.getId());
        if (choice.isEmpty()) {
            setProgress("\u5df2\u53d6\u6d88\u4e0b\u8f7d", 0);
            return;
        }
        LoaderInstallChoice installChoice = choice.get();
        ModLoader loader = installChoice.loaderOrVanilla();

        ModLoaderInstallService service = new ModLoaderInstallService(configManager.getConfig(), database);
        activeDownload = service;
        service.downloadControl().setStateListener(this::onDownloadControlState);
        setControlButtons(true, false);
        progressSink.reset("\u51c6\u5907\u4e0b\u8f7d " + selected.getId() + "\uff08" + loader.displayName() + "\uff09...");
        worker.submit(() -> {
            try {
                String installedId = service.downloadWithLoader(selected, installChoice, progressSink);
                configManager.getConfig().setSelectedVersion(installedId);
                configManager.save();
                String base = McVersionUtil.extractBaseVersion(installedId);
                Platform.runLater(() -> {
                    if (Validation.isNonBlank(base)) {
                        modGameVersion.setText(base);
                        shaderGameVersion.setText(base);
                        packGameVersion.setText(base);
                    }
                    if (loader != ModLoader.VANILLA) {
                        modLoader.getSelectionModel().select(loader.id());
                    }
                    if (onInstalledChanged != null) {
                        onInstalledChanged.run();
                    }
                    ExceptionHandler.showInfo("\u5b8c\u6210",
                            "\u5df2\u5b89\u88c5\uff1a" + installedId + "\n\u7c7b\u578b\uff1a" + loader.displayName());
                });
            } catch (DownloadCancelledException e) {
                setProgress("\u5df2\u53d6\u6d88\u4e0b\u8f7d", 0);
            } catch (UserPausedException e) {
                setProgress("\u5df2\u6682\u505c\uff08\u9700\u624b\u52a8\u70b9\u51fb\u7ee7\u7eed\uff09", 0);
            } catch (LauncherException e) {
                ExceptionHandler.handle("\u4e0b\u8f7d\u5931\u8d25", e);
            } catch (Exception e) {
                ExceptionHandler.handle("\u4e0b\u8f7d\u5931\u8d25", e);
            } finally {
                service.downloadControl().setStateListener(null);
                activeDownload = null;
                Platform.runLater(() -> setControlButtons(false, false));
            }
        });
    }

    private String resolveSelectedVersionId() {
        VersionManifest.VersionEntry selected = versionList.getSelectionModel().getSelectedItem();
        if (selected != null && Validation.isNonBlank(selected.getId())) {
            return selected.getId();
        }
        return configManager.getConfig().getSelectedVersion();
    }

    /** 仅校验：inspect only，不 prepareRepair。 */
    private void inspectSelectedVersion() {
        String versionId = resolveSelectedVersionId();
        if (!Validation.isNonBlank(versionId)) {
            ExceptionHandler.showInfo("提示", "请先选择要校验的版本");
            return;
        }
        setProgress("正在校验版本完整性: " + versionId, 0.1);
        worker.submit(() -> {
            try {
                VersionRepairService.RepairReport report = new VersionRepairService().inspect(versionId);
                if (report.healthy()) {
                    setProgress("版本完好: " + versionId, 1.0);
                    Platform.runLater(() ->
                            ExceptionHandler.showInfo("校验结果", versionId + "\n完整性正常"));
                    return;
                }
                String issues = String.join("\n• ", report.issues());
                setProgress("发现完整性问题: " + versionId, 1.0);
                Platform.runLater(() ->
                        ExceptionHandler.showInfo("校验结果",
                                versionId + "\n• " + issues + "\n\n可使用「补全缺失」清理损坏文件后重新下载。"));
            } catch (Exception e) {
                ExceptionHandler.handle("版本校验失败", e);
            }
        });
    }

    /** 补全缺失：inspect；不健康则确认后 prepareRepair。 */
    private void prepareRepairSelectedVersion() {
        String versionId = resolveSelectedVersionId();
        if (!Validation.isNonBlank(versionId)) {
            ExceptionHandler.showInfo("提示", "请先选择要补全的版本");
            return;
        }
        setProgress("正在检测版本完整性: " + versionId, 0.1);
        worker.submit(() -> {
            try {
                VersionRepairService repairService = new VersionRepairService();
                VersionRepairService.RepairReport report = repairService.inspect(versionId);
                if (report.healthy()) {
                    setProgress("版本完好: " + versionId, 1.0);
                    Platform.runLater(() ->
                            ExceptionHandler.showInfo("补全缺失", versionId + "\n完整性正常，无需补全"));
                    return;
                }
                String issues = String.join("\n• ", report.issues());
                Platform.runLater(() -> {
                    Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                    confirm.setTitle("补全缺失");
                    confirm.setHeaderText(versionId + " 发现以下问题");
                    confirm.setContentText("• " + issues
                            + "\n\n将清理损坏文件以便重新下载（保留断点 .part）。是否继续？");
                    if (window() != null) {
                        confirm.initOwner(window());
                    }
                    var result = confirm.showAndWait();
                    if (result.isEmpty() || result.get() != ButtonType.OK) {
                        setProgress("已取消补全: " + versionId, 0);
                        return;
                    }
                    worker.submit(() -> {
                        try {
                            repairService.prepareRepair(versionId);
                            setProgress("已准备修复，请重新下载: " + versionId, 1.0);
                            Platform.runLater(() ->
                                    ExceptionHandler.showInfo("已准备补全",
                                            versionId + "\n损坏文件已清理，请重新下载该版本以补全缺失内容。"));
                        } catch (Exception e) {
                            ExceptionHandler.handle("准备补全失败", e);
                        }
                    });
                });
            } catch (Exception e) {
                ExceptionHandler.handle("版本补全失败", e);
            }
        });
    }

    private void runMirrorSpeedTest() {
        setProgress("正在镜像测速…", 0.05);
        worker.submit(() -> {
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
                sb.append("\n推荐最快：").append(fastest.displayName());
                String body = sb.toString();
                Platform.runLater(() -> {
                    setProgress("镜像测速完成", 1.0);
                    Alert ask = new Alert(Alert.AlertType.CONFIRMATION);
                    ask.setTitle("镜像测速");
                    ask.setHeaderText("测速结果");
                    ask.setContentText(body + "\n\n是否将下载源切换为「" + fastest.displayName() + "」？");
                    if (window() != null) {
                        ask.initOwner(window());
                    }
                    var result = ask.showAndWait();
                    if (result.isPresent() && result.get() == ButtonType.OK) {
                        try {
                            configManager.getConfig().setDownloadSource(fastest.id());
                            configManager.save();
                            ExceptionHandler.showInfo("已切换下载源", fastest.displayName());
                        } catch (Exception e) {
                            ExceptionHandler.handle("保存下载源失败", e);
                        }
                    }
                });
            } catch (Exception e) {
                ExceptionHandler.handle("镜像测速失败", e);
            }
        });
    }

    private void onDownloadControlState(DownloadControl.State state) {
        Platform.runLater(() -> {
            switch (state) {
                case WAITING_NETWORK -> {
                    setControlButtons(true, false);
                    progressLabel.setText("\u7b49\u5f85\u7f51\u7edc\u6062\u590d\u540e\u81ea\u52a8\u7eed\u4f20\u2026\uff08" + lastProgressMessage + "\uff09");
                }
                case PAUSED_BY_USER -> {
                    setControlButtons(false, true);
                    progressLabel.setText("\u5df2\u6682\u505c\uff08\u4e0d\u4f1a\u81ea\u52a8\u6062\u590d\uff0c\u8bf7\u70b9\u51fb\u7ee7\u7eed\uff09\uff08" + lastProgressMessage + "\uff09");
                }
                case RUNNING -> {
                    setControlButtons(true, false);
                    progressLabel.setText(lastProgressMessage);
                }
                case CANCELLED -> {
                    setControlButtons(false, false);
                    progressLabel.setText("\u5df2\u53d6\u6d88\u4e0b\u8f7d");
                }
            }
        });
    }

    private void setControlButtons(boolean canPause, boolean canResume) {
        if (pauseButton != null) {
            pauseButton.setDisable(!canPause || activeDownload == null);
        }
        if (resumeButton != null) {
            resumeButton.setDisable(!canResume || activeDownload == null);
        }
    }

    private void loadPopularProjects(String type, String gameVersion, String loader,
                                     TableView<ModrinthClient.ModProject> target,
                                     Label status,
                                     AtomicBoolean loadedFlag) {
        searchProjects("", gameVersion, loader, type, target, status, loadedFlag);
    }

    private void searchProjects(String query, String gameVersion, String loader, String type,
                                TableView<ModrinthClient.ModProject> target,
                                Label status,
                                AtomicBoolean loadedFlag) {
        Platform.runLater(() -> status.setText(
                Validation.isNonBlank(query) ? "\u6b63\u5728\u641c\u7d22..." : "\u6b63\u5728\u52a0\u8f7d\u70ed\u95e8\u5217\u8868..."));
        worker.submit(() -> {
            try {
                String version = blankToNull(gameVersion);
                List<ModrinthClient.ModProject> projects = new ModrinthClient(configManager.getConfig())
                        .search(query, version, loader, type, 40);
                Platform.runLater(() -> {
                    target.setItems(FXCollections.observableArrayList(projects));
                    if (projects.isEmpty()) {
                        status.setText("\u6ca1\u6709\u627e\u5230\u7ed3\u679c\uff0c\u53ef\u8c03\u6574\u7248\u672c/\u52a0\u8f7d\u5668\u540e\u91cd\u8bd5");
                    } else if (Validation.isNonBlank(query)) {
                        status.setText("\u641c\u7d22\u5230 " + projects.size() + " \u4e2a\u7ed3\u679c");
                    } else {
                        status.setText("\u5df2\u52a0\u8f7d\u70ed\u95e8 " + typeLabel(type) + " " + projects.size() + " \u4e2a\uff08\u53ef\u641c\u7d22\u7b5b\u9009\uff09");
                    }
                    loadedFlag.set(true);
                });
                setProgress("Modrinth\uff1a" + typeLabel(type) + " " + projects.size() + " \u9879", 1.0);
            } catch (Exception e) {
                Platform.runLater(() -> status.setText("\u52a0\u8f7d\u5931\u8d25\uff1a" + e.getMessage()));
                ExceptionHandler.handle("\u52a0\u8f7d " + typeLabel(type) + " \u5931\u8d25", e);
            }
        });
    }

    private void installSelectedMod() {
        ModrinthClient.ModProject selected = modList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            ExceptionHandler.showInfo("\u63d0\u793a", "\u8bf7\u5148\u9009\u62e9 Mod");
            return;
        }
        String mcVersion = resolveMcVersion(modGameVersion.getText());
        String loader = loaderFilter(modLoader.getValue());
        Optional<InstallLocationDialog.Result> choice = InstallLocationDialog.show(
                window(),
                configManager.getConfig(),
                selected.title(),
                InstancePaths.ContentFolder.MODS,
                mcVersion,
                loader
        );
        if (choice.isEmpty()) {
            setProgress("\u5df2\u53d6\u6d88 Mod \u5b89\u88c5", 0);
            return;
        }
        InstallLocationDialog.Result location = choice.get();
        configManager.getConfig().setSelectedVersion(location.versionId());
        try {
            configManager.save();
        } catch (Exception e) {
            ExceptionHandler.handle("\u4fdd\u5b58\u9009\u4e2d\u7248\u672c\u5931\u8d25", e);
        }

        worker.submit(() -> {
            try {
                progressSink.reset("\u6b63\u5728\u4e0b\u8f7d Mod: " + selected.title());
                AtomicLong last = new AtomicLong(0);
                Path path = new ModInstallService(configManager.getConfig())
                        .installMod(selected.id(), mcVersion, loader, location.targetDir(),
                                (done, total) -> {
                                    long prev = last.getAndSet(done);
                                    long delta = done - prev;
                                    if (delta > 0) {
                                        progressSink.addBytes(delta);
                                    }
                                    double fp = total > 0 ? done / (double) total : 0.5;
                                    progressSink.mainFile("\u4e0b\u8f7d Mod: " + selected.title(), fp);
                                    progressSink.status("\u4e0b\u8f7d Mod...", 0.05 + 0.9 * fp);
                                });
                progressSink.complete("Mod \u5df2\u5b89\u88c5: " + path.getFileName());
                Platform.runLater(() -> ExceptionHandler.showInfo("\u5b8c\u6210",
                        "\u5df2\u5b89\u88c5\u5230\uff1a\n" + path.toAbsolutePath()));
            } catch (Exception e) {
                ExceptionHandler.handle("\u5b89\u88c5 Mod \u5931\u8d25", e);
            }
        });
    }

    private void installSelectedShader() {
        ModrinthClient.ModProject selected = shaderList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            ExceptionHandler.showInfo("\u63d0\u793a", "\u8bf7\u5148\u9009\u62e9\u5149\u5f71");
            return;
        }
        String mcVersion = resolveMcVersion(shaderGameVersion.getText());
        String loader = loaderFilter(modLoader.getValue());
        Optional<InstallLocationDialog.Result> choice = InstallLocationDialog.show(
                window(),
                configManager.getConfig(),
                selected.title(),
                InstancePaths.ContentFolder.SHADERPACKS,
                mcVersion,
                loader
        );
        if (choice.isEmpty()) {
            setProgress("\u5df2\u53d6\u6d88\u5149\u5f71\u5b89\u88c5", 0);
            return;
        }
        InstallLocationDialog.Result location = choice.get();
        configManager.getConfig().setSelectedVersion(location.versionId());
        try {
            configManager.save();
        } catch (Exception e) {
            ExceptionHandler.handle("\u4fdd\u5b58\u9009\u4e2d\u7248\u672c\u5931\u8d25", e);
        }

        worker.submit(() -> {
            try {
                progressSink.reset("\u6b63\u5728\u4e0b\u8f7d\u5149\u5f71: " + selected.title());
                AtomicLong last = new AtomicLong(0);
                Path path = new ModInstallService(configManager.getConfig())
                        .installShader(selected.id(), mcVersion, location.targetDir(),
                                (done, total) -> {
                                    long prev = last.getAndSet(done);
                                    long delta = done - prev;
                                    if (delta > 0) {
                                        progressSink.addBytes(delta);
                                    }
                                    double fp = total > 0 ? done / (double) total : 0.5;
                                    progressSink.mainFile("\u4e0b\u8f7d\u5149\u5f71: " + selected.title(), fp);
                                    progressSink.status("\u4e0b\u8f7d\u5149\u5f71...", 0.05 + 0.9 * fp);
                                });
                progressSink.complete("\u5149\u5f71\u5df2\u5b89\u88c5: " + path.getFileName());
                Platform.runLater(() -> ExceptionHandler.showInfo("\u5b8c\u6210",
                        "\u5df2\u5b89\u88c5\u5230\uff1a\n" + path.toAbsolutePath()));
            } catch (Exception e) {
                ExceptionHandler.handle("\u5b89\u88c5\u5149\u5f71\u5931\u8d25", e);
            }
        });
    }

    private void installSelectedResourcePack() {
        ModrinthClient.ModProject selected = resourcePackList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            ExceptionHandler.showInfo("提示", "请先选择资源包");
            return;
        }
        String mcVersion = resolveMcVersion(resourcePackGameVersion.getText());
        Optional<InstallLocationDialog.Result> choice = InstallLocationDialog.show(
                window(),
                configManager.getConfig(),
                selected.title(),
                InstancePaths.ContentFolder.RESOURCEPACKS,
                mcVersion,
                null
        );
        if (choice.isEmpty()) {
            setProgress("已取消资源包安装", 0);
            return;
        }
        InstallLocationDialog.Result location = choice.get();
        configManager.getConfig().setSelectedVersion(location.versionId());
        try {
            configManager.save();
        } catch (Exception e) {
            ExceptionHandler.handle("保存选中版本失败", e);
        }

        worker.submit(() -> {
            try {
                progressSink.reset("正在下载资源包: " + selected.title());
                AtomicLong last = new AtomicLong(0);
                Path path = new ModInstallService(configManager.getConfig())
                        .installResourcePack(selected.id(), mcVersion, location.targetDir(),
                                (done, total) -> {
                                    long prev = last.getAndSet(done);
                                    long delta = done - prev;
                                    if (delta > 0) {
                                        progressSink.addBytes(delta);
                                    }
                                    double fp = total > 0 ? done / (double) total : 0.5;
                                    progressSink.mainFile("下载资源包: " + selected.title(), fp);
                                    progressSink.status("下载资源包...", 0.05 + 0.9 * fp);
                                });
                progressSink.complete("资源包已安装: " + path.getFileName());
                Platform.runLater(() -> ExceptionHandler.showInfo("完成",
                        "已安装到：\n" + path.toAbsolutePath()));
            } catch (Exception e) {
                ExceptionHandler.handle("安装资源包失败", e);
            }
        });
    }

    private void downloadSelectedPack() {
        ModrinthClient.ModProject selected = packList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            ExceptionHandler.showInfo("\u63d0\u793a", "\u8bf7\u5148\u9009\u62e9\u6574\u5408\u5305");
            return;
        }
        String mcVersion = resolveMcVersion(packGameVersion.getText());
        worker.submit(() -> {
            try {
                progressSink.reset("\u6b63\u5728\u4e0b\u8f7d\u6574\u5408\u5305: " + selected.title());
                AtomicLong last = new AtomicLong(0);
                Path path = new ModInstallService(configManager.getConfig())
                        .installModpack(selected.id(), mcVersion,
                                (done, total) -> {
                                    long prev = last.getAndSet(done);
                                    long delta = done - prev;
                                    if (delta > 0) {
                                        progressSink.addBytes(delta);
                                    }
                                    double fp = total > 0 ? done / (double) total : 0.5;
                                    progressSink.mainFile("\u4e0b\u8f7d\u6574\u5408\u5305: " + selected.title(), fp);
                                    progressSink.status("\u4e0b\u8f7d\u6574\u5408\u5305...", 0.05 + 0.9 * fp);
                                });
                progressSink.complete("\u6574\u5408\u5305\u5df2\u4e0b\u8f7d: " + path.getFileName());
                Platform.runLater(() -> {
                    if (modpackImportHandler == null) {
                        ExceptionHandler.showInfo("\u5b8c\u6210",
                                "\u6574\u5408\u5305\u6587\u4ef6\u5df2\u4fdd\u5b58\uff1a\n" + path.toAbsolutePath());
                        return;
                    }
                    javafx.scene.control.Alert ask = new javafx.scene.control.Alert(
                            javafx.scene.control.Alert.AlertType.CONFIRMATION);
                    ask.setTitle("\u5bfc\u5165\u6574\u5408\u5305");
                    ask.setHeaderText("\u6574\u5408\u5305\u5df2\u4e0b\u8f7d");
                    ask.setContentText("\u6587\u4ef6\uff1a" + path.toAbsolutePath()
                            + "\n\n\u662f\u5426\u7acb\u5373\u5bfc\u5165\u4e3a\u53ef\u542f\u52a8\u7248\u672c\uff1f");
                    ask.initOwner(window());
                    var result = ask.showAndWait();
                    if (result.isPresent() && result.get() == javafx.scene.control.ButtonType.OK) {
                        modpackImportHandler.accept(java.util.List.of(path));
                    }
                });
            } catch (Exception e) {
                ExceptionHandler.handle("\u4e0b\u8f7d\u6574\u5408\u5305\u5931\u8d25", e);
            }
        });
    }

    private String resolveMcVersion(String typed) {
        if (Validation.isNonBlank(typed)) {
            String extracted = McVersionUtil.extractBaseVersion(typed.trim());
            if (Validation.isNonBlank(extracted)) {
                return extracted;
            }
        }
        String fromSelected = McVersionUtil.extractBaseVersion(configManager.getConfig().getSelectedVersion());
        if (Validation.isNonBlank(fromSelected)) {
            return fromSelected;
        }
        return null;
    }

    private String loaderFilter(String value) {
        if (!Validation.isNonBlank(value) || "\u5168\u90e8".equals(value)) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private void reloadSkins(SkinService skinService) {
        Platform.runLater(() -> skinStatus.setText("\u6b63\u5728\u52a0\u8f7d\u76ae\u80a4\u5217\u8868..."));
        worker.submit(() -> {
            try {
                List<String> paths = skinService.listSkins().stream()
                        .map(Path::toAbsolutePath)
                        .map(Path::toString)
                        .toList();
                Platform.runLater(() -> {
                    skinList.setItems(FXCollections.observableArrayList(paths));
                    skinStatus.setText(paths.isEmpty()
                            ? "\u6682\u65e0\u76ae\u80a4\uff0c\u53ef\u70b9\u51fb\u300c\u5bfc\u5165\u672c\u5730\u76ae\u80a4\u300d"
                            : "\u5df2\u52a0\u8f7d " + paths.size() + " \u4e2a\u76ae\u80a4");
                });
            } catch (Exception e) {
                Platform.runLater(() -> skinStatus.setText("\u52a0\u8f7d\u76ae\u80a4\u5931\u8d25"));
                ExceptionHandler.handle("\u5237\u65b0\u76ae\u80a4\u5931\u8d25", e);
            }
        });
    }

    private Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }

    private static Label statusLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("muted");
        return label;
    }

    private static String typeLabel(String type) {
        return switch (type) {
            case "mod" -> "Mod";
            case "shader" -> "\u5149\u5f71";
            case "modpack" -> "\u6574\u5408\u5305";
            default -> type;
        };
    }

    private static String blankToNull(String value) {
        return Validation.isNonBlank(value) ? value.trim() : null;
    }

    private void setProgress(String message, double value) {
        progressSink.status(message, value);
    }

    private void applyProgressSnapshot(DownloadProgressSink.Snapshot snap) {
        Platform.runLater(() -> {
            lastProgressMessage = snap.message() == null ? "" : snap.message();
            DownloadControl.State state = activeDownload == null
                    ? DownloadControl.State.RUNNING
                    : activeDownload.downloadControl().state();
            if (state == DownloadControl.State.WAITING_NETWORK) {
                progressLabel.setText("\u7b49\u5f85\u7f51\u7edc\u6062\u590d\u540e\u81ea\u52a8\u7eed\u4f20\u2026\uff08" + lastProgressMessage + "\uff09");
            } else if (state == DownloadControl.State.PAUSED_BY_USER) {
                progressLabel.setText("\u5df2\u6682\u505c\uff08\u4e0d\u4f1a\u81ea\u52a8\u6062\u590d\uff0c\u8bf7\u70b9\u51fb\u7ee7\u7eed\uff09\uff08" + lastProgressMessage + "\uff09");
            } else {
                progressLabel.setText(lastProgressMessage);
            }
            double target = snap.progress();
            if (UiAnimation.isEnabled()) {
                if (progressTimeline != null) {
                    progressTimeline.stop();
                }
                progressTimeline = new Timeline(
                        new KeyFrame(Duration.ZERO,
                                new KeyValue(progressBar.progressProperty(), progressBar.getProgress())),
                        new KeyFrame(Duration.millis(180),
                                new KeyValue(progressBar.progressProperty(), target, Interpolator.EASE_OUT))
                );
                progressTimeline.play();
            } else {
                progressBar.setProgress(target);
            }
            progressPercent.setText("\u6574\u4f53 " + snap.percentText());
            progressSpeed.setText(snap.speedText());
            mainProgressLabel.setText("\u4e3b\u6587\u4ef6 " + snap.mainPercentText());
            depProgressLabel.setText("\u4f9d\u8d56 " + snap.depPercentText());
            String depName = snap.depFileName() == null ? "" : snap.depFileName().trim();
            depFileLabel.setText(depName.isEmpty() ? "" : "\u5f53\u524d\u4f9d\u8d56\uff1a" + depName);
        });
    }
}
