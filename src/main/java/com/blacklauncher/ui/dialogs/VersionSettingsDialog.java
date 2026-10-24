package com.blacklauncher.ui.dialogs;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.config.ConfigManager;
import com.blacklauncher.core.db.Database;
import com.blacklauncher.core.exception.ExceptionHandler;
import com.blacklauncher.core.net.DownloadProgressSink;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.download.VersionFileCompleter;
import com.blacklauncher.download.VersionRenameService;
import com.blacklauncher.launch.LaunchScriptExporter;
import com.blacklauncher.minecraft.DirectoryKind;
import com.blacklauncher.minecraft.LocalModEntry;
import com.blacklauncher.minecraft.LocalModService;
import com.blacklauncher.minecraft.MinecraftLayout;
import com.blacklauncher.mod.ModHealthService;
import com.blacklauncher.mod.ModInstallService;
import com.blacklauncher.mod.ModrinthClient;
import com.blacklauncher.modpack.ModpackExportOptions;
import com.blacklauncher.modpack.ModpackExportService;
import com.blacklauncher.modpack.ModpackRepairService;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Per-version settings window (§7): folders, repair, launch script, mods, pack export.
 */
public final class VersionSettingsDialog {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final ConfigManager configManager;
    private final Database database;
    private String versionId;
    private final LocalModService modService;
    private final Runnable onVersionChanged;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "version-settings");
        t.setDaemon(true);
        return t;
    });

    private final TableView<LocalModEntry> modTable = new TableView<>();
    private final Label modEmpty = new Label("当前版本暂无 Mod 文件");
    private final Label statusLabel = new Label("就绪");
    private Label titleLabel;
    private Stage stage;

    private VersionSettingsDialog(ConfigManager configManager, Database database, String versionId,
                                  Runnable onVersionChanged) {
        this.configManager = configManager;
        this.database = database;
        this.versionId = versionId;
        this.onVersionChanged = onVersionChanged;
        this.modService = new LocalModService(configManager.getConfig());
    }

    public static void show(Window owner, ConfigManager configManager, Database database, String versionId) {
        show(owner, configManager, database, versionId, null);
    }

    public static void show(Window owner, ConfigManager configManager, Database database, String versionId,
                            Runnable onVersionChanged) {
        if (!Validation.isNonBlank(versionId)) {
            ExceptionHandler.showInfo("提示", "请先选择游戏版本");
            return;
        }
        VersionSettingsDialog dlg = new VersionSettingsDialog(
                configManager, database, versionId.trim(), onVersionChanged);
        dlg.open(owner);
    }

    private void open(Window owner) {
        stage = new Stage();
        stage.setTitle("版本设置 — " + versionId);
        stage.initModality(Modality.WINDOW_MODAL);
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.setWidth(720);
        stage.setHeight(640);
        stage.setMinWidth(640);
        stage.setMinHeight(520);

        BorderPane root = new BorderPane();
        root.getStyleClass().add("page");
        root.setPadding(new Insets(16));

        titleLabel = new Label("版本设置：" + versionId);
        titleLabel.getStyleClass().add("page-title");

        TitledPane folders = new TitledPane("游戏文件夹", buildFolderGrid());
        folders.setExpanded(true);
        folders.setCollapsible(false);

        TitledPane advanced = new TitledPane("高级设置", buildAdvancedPane());
        advanced.setExpanded(true);

        VBox body = new VBox(12, titleLabel, folders, advanced, statusLabel);
        VBox.setVgrow(advanced, Priority.ALWAYS);
        statusLabel.getStyleClass().add("muted");
        root.setCenter(body);

        Scene scene = new Scene(root);
        if (owner != null && owner.getScene() != null && owner.getScene().getStylesheets() != null) {
            scene.getStylesheets().setAll(owner.getScene().getStylesheets());
        }
        stage.setScene(scene);
        stage.setOnHidden(e -> worker.shutdownNow());
        stage.show();
        reloadMods();
    }

    private GridPane buildFolderGrid() {
        AppConfig config = configManager.getConfig();
        MinecraftLayout layout = MinecraftLayout.forVersion(config, versionId);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(8, 0, 4, 0));

        addFolderRow(grid, 0, "版本文件夹",
                "版本 jar / json 所在目录",
                AppPaths.versionsDir().resolve(versionId));
        addFolderRow(grid, 1, "存档文件夹",
                "单人存档目录",
                layout.resolve(DirectoryKind.SAVES));
        addFolderRow(grid, 2, "Mod 文件夹",
                "该版本独立 mods 目录",
                layout.resolve(DirectoryKind.MODS));
        addFolderRow(grid, 3, "截图文件夹",
                "游戏截图目录",
                layout.resolve(DirectoryKind.SCREENSHOTS));
        return grid;
    }

    private void addFolderRow(GridPane grid, int row, String title, String tip, Path path) {
        Label name = new Label(title);
        name.setMinWidth(100);
        Label pathLabel = new Label(path.toAbsolutePath().toString());
        pathLabel.getStyleClass().add("muted");
        pathLabel.setWrapText(true);
        pathLabel.setMaxWidth(420);
        Button open = new Button("打开");
        open.setTooltip(new Tooltip(tip));
        open.setOnAction(e -> openFolder(path, title));
        grid.add(name, 0, row);
        grid.add(pathLabel, 1, row);
        grid.add(open, 2, row);
        GridPane.setHgrow(pathLabel, Priority.ALWAYS);
    }

    private VBox buildAdvancedPane() {
        Button exportScript = new Button("导出启动脚本");
        exportScript.setOnAction(e -> doExportScript());
        Button completeFiles = new Button("补全文件");
        completeFiles.setOnAction(e -> doCompleteFiles());
        Button repairPack = new Button("修复整合包文件");
        repairPack.setTooltip(new Tooltip("根据实例内 modrinth.index.json / manifest.json 重下缺失模组"));
        repairPack.setOnAction(e -> doRepairModpack());
        Button exportPack = new Button("导出完整整合包");
        exportPack.getStyleClass().addAll("button", "primary");
        exportPack.setOnAction(e -> doExportFullPack());
        Button contentLibrary = new Button("内容库");
        contentLibrary.setTooltip(new Tooltip("管理资源包、光影与数据包"));
        contentLibrary.setOnAction(e -> ContentLibraryDialog.show(stage, configManager, versionId));
        Button serverList = new Button("服务器列表");
        serverList.setTooltip(new Tooltip("编辑本实例 servers.dat"));
        serverList.setOnAction(e -> ServerListDialog.show(stage, configManager, versionId));
        Button renameVersion = new Button("版本重命名");
        renameVersion.setOnAction(e -> renameVersion());
        Button restoreName = new Button("恢复默认名称");
        restoreName.setOnAction(e -> restoreDefaultVersionName());

        HBox ops = new HBox(10, exportScript, completeFiles, repairPack, exportPack,
                contentLibrary, serverList, renameVersion, restoreName);
        ops.setAlignment(Pos.CENTER_LEFT);

        Label modTitle = new Label("MOD 管理");
        modTitle.getStyleClass().add("section-title");
        configureModTable();
        modEmpty.getStyleClass().add("muted");
        modEmpty.setVisible(false);

        Button refreshMods = new Button("刷新列表");
        refreshMods.setOnAction(e -> reloadMods());
        Button openMods = new Button("打开目录");
        openMods.setOnAction(e -> openFolder(modService.modsDir(versionId), "Mod 文件夹"));
        Button enableMods = new Button("启用");
        enableMods.setOnAction(e -> setSelectedEnabled(true));
        Button disableMods = new Button("禁用");
        disableMods.setOnAction(e -> setSelectedEnabled(false));
        Button checkConflicts = new Button("检查冲突");
        checkConflicts.setOnAction(e -> checkModConflicts());
        Button checkUpdates = new Button("检查更新");
        checkUpdates.setOnAction(e -> checkModUpdates());
        Button rename = new Button("重命名");
        rename.setOnAction(e -> renameSelected());
        Button delete = new Button("删除");
        delete.setOnAction(e -> deleteSelected(false));
        Button deleteBatch = new Button("批量删除");
        deleteBatch.setOnAction(e -> deleteSelected(true));

        HBox modOps = new HBox(8, refreshMods, openMods, enableMods, disableMods,
                checkConflicts, checkUpdates, rename, delete, deleteBatch);
        modOps.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(10, ops, modTitle, modOps, modEmpty, modTable);
        VBox.setVgrow(modTable, Priority.ALWAYS);
        box.setPadding(new Insets(8, 0, 0, 0));
        return box;
    }

    private void configureModTable() {
        modTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        modTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        modTable.setPlaceholder(new Label("当前版本暂无 Mod 文件"));

        TableColumn<LocalModEntry, String> nameCol = new TableColumn<>("文件名");
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().fileName()));
        TableColumn<LocalModEntry, String> fmtCol = new TableColumn<>("格式");
        fmtCol.setMaxWidth(70);
        fmtCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().format()));
        TableColumn<LocalModEntry, String> sizeCol = new TableColumn<>("大小");
        sizeCol.setMaxWidth(90);
        sizeCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().sizeText()));
        TableColumn<LocalModEntry, String> timeCol = new TableColumn<>("修改时间");
        timeCol.setMaxWidth(140);
        timeCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().modified() == null ? "" : TIME_FMT.format(c.getValue().modified())));
        TableColumn<LocalModEntry, String> statusCol = new TableColumn<>("状态");
        statusCol.setMaxWidth(90);
        statusCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().status().label()));

        modTable.getColumns().setAll(nameCol, fmtCol, sizeCol, timeCol, statusCol);
        modTable.setRowFactory(tv -> {
            var row = new javafx.scene.control.TableRow<LocalModEntry>();
            row.setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2 && row.getItem() != null) {
                    ExceptionHandler.showInfo(row.getItem().fileName(),
                            "状态：" + row.getItem().status().label()
                                    + (row.getItem().statusDetail().isBlank()
                                    ? "" : "\n" + row.getItem().statusDetail())
                                    + "\n路径：\n" + row.getItem().path());
                }
            });
            return row;
        });
    }

    private void reloadMods() {
        statusLabel.setText("正在扫描 Mod…");
        worker.submit(() -> {
            try {
                List<LocalModEntry> list = modService.list(versionId);
                Platform.runLater(() -> {
                    modTable.setItems(FXCollections.observableArrayList(list));
                    boolean empty = list.isEmpty();
                    modEmpty.setVisible(empty);
                    modEmpty.setManaged(empty);
                    statusLabel.setText(empty ? "就绪（无 Mod）" : "共 " + list.size() + " 个 Mod 文件");
                });
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("扫描失败"));
                ExceptionHandler.handle("扫描 Mod 失败", e);
            }
        });
    }

    private void renameVersion() {
        TextInputDialog input = new TextInputDialog(versionId);
        input.setTitle("版本重命名");
        input.setHeaderText("自定义版本名称（同步修改版本文件夹，不改动游戏核心文件）");
        input.setContentText("新名称：");
        if (stage != null) {
            input.initOwner(stage);
        }
        input.showAndWait().ifPresent(raw -> worker.submit(() -> {
            try {
                String old = versionId;
                String newId = new VersionRenameService(configManager.getConfig(), database)
                        .rename(old, raw);
                configManager.save();
                Platform.runLater(() -> {
                    versionId = newId;
                    if (titleLabel != null) {
                        titleLabel.setText("版本设置：" + versionId);
                    }
                    if (stage != null) {
                        stage.setTitle("版本设置 — " + versionId);
                    }
                    statusLabel.setText("已重命名：" + old + " → " + newId);
                    if (onVersionChanged != null) {
                        onVersionChanged.run();
                    }
                    ExceptionHandler.showInfo("完成", "版本已重命名为：" + newId);
                    // Rebuild folder paths by reopening is heavy; just refresh mods.
                    reloadMods();
                });
            } catch (Exception e) {
                ExceptionHandler.handle("版本重命名失败", e);
            }
        }));
    }

    private void restoreDefaultVersionName() {
        worker.submit(() -> {
            try {
                String old = versionId;
                String restored = new VersionRenameService(configManager.getConfig(), database)
                        .restoreDefaultName(old);
                configManager.save();
                Platform.runLater(() -> {
                    versionId = restored;
                    if (titleLabel != null) {
                        titleLabel.setText("版本设置：" + versionId);
                    }
                    if (stage != null) {
                        stage.setTitle("版本设置 — " + versionId);
                    }
                    statusLabel.setText("已恢复默认名称：" + restored);
                    if (onVersionChanged != null) {
                        onVersionChanged.run();
                    }
                    ExceptionHandler.showInfo("完成", "已恢复为：" + restored);
                    reloadMods();
                });
            } catch (Exception e) {
                ExceptionHandler.handle("恢复默认名称失败", e);
            }
        });
    }

    private void renameSelected() {
        LocalModEntry sel = modTable.getSelectionModel().getSelectedItem();
        if (sel == null) {
            ExceptionHandler.showInfo("提示", "请先选择一个 Mod 文件");
            return;
        }
        TextInputDialog input = new TextInputDialog(sel.fileName());
        input.setTitle("重命名 Mod");
        input.setHeaderText("输入新文件名");
        input.setContentText("文件名：");
        if (stage != null) {
            input.initOwner(stage);
        }
        input.showAndWait().ifPresent(name -> worker.submit(() -> {
            try {
                modService.rename(sel.path(), name);
                Platform.runLater(this::reloadMods);
            } catch (Exception e) {
                ExceptionHandler.handle("重命名失败", e);
            }
        }));
    }

    private void setSelectedEnabled(boolean enabled) {
        List<LocalModEntry> selected = new ArrayList<>(modTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            ExceptionHandler.showInfo("提示", enabled ? "请先选择要启用的 Mod" : "请先选择要禁用的 Mod");
            return;
        }
        String action = enabled ? "启用" : "禁用";
        statusLabel.setText("正在" + action + "…");
        worker.submit(() -> {
            List<String> errors = new ArrayList<>();
            int ok = 0;
            for (LocalModEntry entry : selected) {
                try {
                    modService.setEnabled(entry.path(), enabled);
                    ok++;
                } catch (Exception ex) {
                    errors.add(entry.fileName() + ": " + ex.getMessage());
                }
            }
            int done = ok;
            Platform.runLater(() -> {
                reloadMods();
                if (errors.isEmpty()) {
                    statusLabel.setText("已" + action + " " + done + " 个");
                } else {
                    statusLabel.setText(action + "部分失败");
                    ExceptionHandler.showInfo(action + "结果",
                            "成功 " + done + " 个\n失败：\n" + String.join("\n", errors));
                }
            });
        });
    }

    private void checkModConflicts() {
        List<LocalModEntry> items = modTable.getItems() == null ? List.of() : List.copyOf(modTable.getItems());
        statusLabel.setText("正在检查冲突…");
        worker.submit(() -> {
            List<String> conflicts = modService.detectConflicts(items);
            Platform.runLater(() -> {
                statusLabel.setText(conflicts.isEmpty() ? "未发现冲突" : "发现 " + conflicts.size() + " 条冲突提示");
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("检查冲突");
                alert.setHeaderText(conflicts.isEmpty() ? "未发现明显冲突" : "发现 " + conflicts.size() + " 条提示");
                TextArea area = new TextArea(conflicts.isEmpty()
                        ? "已启用的 Mod 中未检测到同名多版本或重复 Fabric API。"
                        : String.join("\n\n", conflicts));
                area.setEditable(false);
                area.setWrapText(true);
                area.setPrefRowCount(12);
                area.setPrefColumnCount(48);
                alert.getDialogPane().setContent(area);
                if (stage != null) {
                    alert.initOwner(stage);
                }
                alert.showAndWait();
            });
        });
    }

    private void checkModUpdates() {
        List<LocalModEntry> items = modTable.getItems() == null ? List.of() : List.copyOf(modTable.getItems());
        if (items.isEmpty()) {
            ExceptionHandler.showInfo("提示", "当前没有可检查的 Mod");
            return;
        }
        statusLabel.setText("正在检查更新（Modrinth）…");
        AppConfig config = configManager.getConfig();
        String vid = versionId;
        worker.submit(() -> {
            try {
                ModHealthService health = new ModHealthService(config);
                List<ModHealthService.UpdateHint> updates = health.checkUpdates(config, vid, items);
                List<String> missing = health.missingDependencyHints(config, vid, items);
                Platform.runLater(() -> showUpdateResults(updates, missing));
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("检查更新失败"));
                ExceptionHandler.handle("检查 Mod 更新失败", e);
            }
        });
    }

    private void showUpdateResults(List<ModHealthService.UpdateHint> updates, List<String> missing) {
        StringBuilder body = new StringBuilder();
        if (updates == null || updates.isEmpty()) {
            body.append("未发现可更新的 Mod（仅支持已在 Modrinth 登记且可按哈希识别的 jar）。\n");
        } else {
            body.append("发现 ").append(updates.size()).append(" 个可更新：\n\n");
            for (ModHealthService.UpdateHint h : updates) {
                body.append("• ").append(h.projectTitle())
                        .append("\n  ").append(h.fileName())
                        .append("\n  ").append(h.currentVersion()).append(" → ").append(h.latestVersion())
                        .append("\n\n");
            }
        }
        if (missing != null && !missing.isEmpty()) {
            body.append("依赖提示：\n");
            for (String tip : missing) {
                body.append("• ").append(tip).append("\n");
            }
        }

        statusLabel.setText((updates == null || updates.isEmpty())
                ? "无可用更新"
                : "发现 " + updates.size() + " 个更新");

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("检查更新");
        alert.setHeaderText((updates == null || updates.isEmpty())
                ? "无可用更新"
                : "发现 " + updates.size() + " 个可更新");
        TextArea area = new TextArea(body.toString().trim());
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefRowCount(14);
        area.setPrefColumnCount(52);
        alert.getDialogPane().setContent(area);

        ButtonType updateAll = new ButtonType("全部更新", ButtonBar.ButtonData.OK_DONE);
        ButtonType close = new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE);
        if (updates != null && !updates.isEmpty()) {
            alert.getButtonTypes().setAll(updateAll, close);
        } else {
            alert.getButtonTypes().setAll(close);
        }
        if (stage != null) {
            alert.initOwner(stage);
        }
        var result = alert.showAndWait();
        if (result.isPresent() && result.get() == updateAll && updates != null && !updates.isEmpty()) {
            applyAllUpdates(updates);
        }
    }

    private void applyAllUpdates(List<ModHealthService.UpdateHint> updates) {
        statusLabel.setText("正在更新 Mod…");
        AppConfig config = configManager.getConfig();
        Path modsDir = modService.modsDir(versionId);
        List<ModHealthService.UpdateHint> copy = List.copyOf(updates);
        worker.submit(() -> {
            ModInstallService installer = new ModInstallService(config);
            List<String> errors = new ArrayList<>();
            int ok = 0;
            for (ModHealthService.UpdateHint hint : copy) {
                try {
                    ModrinthClient.ModFile file = new ModrinthClient.ModFile(
                            hint.newFileName(), hint.downloadUrl(), "", 0L);
                    Path downloaded = installer.installFile(file, modsDir, (d, t) -> {});
                    Path old = hint.file();
                    if (old != null && Files.exists(old)
                            && (downloaded == null || !old.toAbsolutePath().equals(downloaded.toAbsolutePath()))) {
                        try {
                            Files.deleteIfExists(old);
                        } catch (Exception delEx) {
                            errors.add("新文件已下载，但旧文件删除失败 " + hint.fileName() + ": " + delEx.getMessage());
                        }
                    }
                    ok++;
                } catch (Exception ex) {
                    errors.add(hint.fileName() + ": " + ex.getMessage());
                }
            }
            int done = ok;
            Platform.runLater(() -> {
                reloadMods();
                if (errors.isEmpty()) {
                    statusLabel.setText("已更新 " + done + " 个 Mod");
                    ExceptionHandler.showInfo("全部更新", "已成功更新 " + done + " 个 Mod");
                } else {
                    statusLabel.setText("更新部分失败");
                    ExceptionHandler.showInfo("全部更新",
                            "成功 " + done + " 个\n失败：\n" + String.join("\n", errors));
                }
            });
        });
    }

    private void deleteSelected(boolean batch) {
        List<LocalModEntry> selected = modTable.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) {
            ExceptionHandler.showInfo("提示", batch ? "请先勾选要删除的 Mod" : "请先选择要删除的 Mod");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("删除确认");
        confirm.setHeaderText("确定删除选中的 " + selected.size() + " 个文件？");
        confirm.setContentText(selected.stream().map(LocalModEntry::fileName)
                .collect(Collectors.joining("\n")));
        if (stage != null) {
            confirm.initOwner(stage);
        }
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        List<Path> paths = selected.stream().map(LocalModEntry::path).toList();
        worker.submit(() -> {
            try {
                modService.deleteAll(paths);
                Platform.runLater(() -> {
                    ExceptionHandler.showInfo("完成", "已删除 " + paths.size() + " 个文件");
                    reloadMods();
                });
            } catch (Exception e) {
                ExceptionHandler.handle("删除失败", e);
                Platform.runLater(this::reloadMods);
            }
        });
    }

    private void doExportScript() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("保存启动脚本");
        chooser.setInitialFileName(versionId + "-独立启动脚本.bat");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("批处理脚本", "*.bat"));
        try {
            Files.createDirectories(AppPaths.launchScriptsDir());
            chooser.setInitialDirectory(AppPaths.launchScriptsDir().toFile());
        } catch (Exception ignored) {
        }
        var file = chooser.showSaveDialog(stage);
        if (file == null) {
            return;
        }
        Path finalTarget = file.toPath();
        statusLabel.setText("正在导出启动脚本…");
        worker.submit(() -> {
            try {
                Path out = new LaunchScriptExporter(configManager.getConfig()).exportTo(versionId, finalTarget);
                Platform.runLater(() -> {
                    statusLabel.setText("启动脚本已导出");
                    ExceptionHandler.showInfo("启动脚本导出成功", out.toAbsolutePath().toString());
                });
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("导出脚本失败"));
                ExceptionHandler.handle("导出启动脚本失败", e);
            }
        });
    }

    private void doCompleteFiles() {
        statusLabel.setText("正在补全文件…");
        ModpackProgressDialog progress = new ModpackProgressDialog(stage, "补全文件");
        progress.show();
        DownloadProgressSink sink = new DownloadProgressSink(snap ->
                progress.update(snap.message(), snap.progress(),
                        snap.percentText() + "  " + snap.speedText()));
        worker.submit(() -> {
            try {
                VersionFileCompleter.Result result = new VersionFileCompleter(
                        configManager.getConfig(), database).complete(versionId, sink);
                Platform.runLater(() -> {
                    progress.close();
                    statusLabel.setText(result.alreadyHealthy() ? "文件完整" : "补全完成");
                    String msg;
                    if (result.alreadyHealthy() && result.fixedCount() == 0) {
                        msg = "文件完整无需修复";
                    } else {
                        msg = "已补全 " + result.fixedCount() + " 项\n\n"
                                + String.join("\n", result.details());
                    }
                    ExceptionHandler.showInfo("补全结果", msg);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    progress.close();
                    statusLabel.setText("补全失败");
                });
                ExceptionHandler.handle("补全文件失败", e);
            }
        });
    }

    private void doRepairModpack() {
        statusLabel.setText("正在修复整合包文件…");
        ModpackProgressDialog progress = new ModpackProgressDialog(stage, "修复整合包文件");
        progress.show();
        DownloadProgressSink sink = new DownloadProgressSink(snap ->
                progress.update(snap.message(), snap.progress(),
                        snap.percentText() + "  " + snap.speedText()));
        String vid = versionId;
        AppConfig cfg = configManager.getConfig();
        worker.submit(() -> {
            try {
                ModpackRepairService.RepairReport report =
                        new ModpackRepairService(cfg).repairFromIndex(cfg, vid, sink);
                Platform.runLater(() -> {
                    progress.close();
                    reloadMods();
                    statusLabel.setText("修复完成（重下 " + report.redownloaded() + "）");
                    StringBuilder msg = new StringBuilder();
                    msg.append("检查 ").append(report.checked()).append(" 个文件\n");
                    msg.append("重新下载 ").append(report.redownloaded()).append(" 个\n");
                    msg.append("仍缺失 ").append(report.missing().size()).append(" 个\n");
                    if (!report.fixed().isEmpty()) {
                        msg.append("\n—— 已修复 ——\n");
                        for (String f : report.fixed()) {
                            msg.append("· ").append(f).append('\n');
                        }
                    }
                    if (!report.missing().isEmpty()) {
                        msg.append("\n—— 仍缺失 ——\n");
                        for (String m : report.missing()) {
                            msg.append("· ").append(m).append('\n');
                        }
                    }
                    ExceptionHandler.showInfo("修复结果", msg.toString());
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    progress.close();
                    statusLabel.setText("修复失败");
                });
                ExceptionHandler.handle("修复整合包文件失败", e);
            }
        });
    }

    private void doExportFullPack() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出完整整合包");
        chooser.setInitialFileName(versionId + "-完整整合包.zip");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP 整合包", "*.zip"));
        try {
            Files.createDirectories(AppPaths.modpacksDir());
            chooser.setInitialDirectory(AppPaths.modpacksDir().toFile());
        } catch (Exception ignored) {
        }
        var file = chooser.showSaveDialog(stage);
        if (file == null) {
            return;
        }
        ModpackExportOptions opts = ModpackExportOptions.fullPack(versionId, file.toPath());
        ModpackProgressDialog progress = new ModpackProgressDialog(stage, "导出整合包");
        progress.show();
        progress.update("正在打包 " + versionId + "…", 0.15, "");
        statusLabel.setText("正在导出整合包…");
        worker.submit(() -> {
            try {
                Path out = new ModpackExportService(configManager.getConfig()).exportVersion(versionId, opts);
                Platform.runLater(() -> {
                    progress.close();
                    statusLabel.setText("整合包已导出");
                    Alert done = new Alert(Alert.AlertType.INFORMATION);
                    done.setTitle("导出完成");
                    done.setHeaderText("整合包导出成功");
                    done.setContentText(out.toAbsolutePath().toString());
                    ButtonType openDir = new ButtonType("打开所在文件夹");
                    done.getButtonTypes().setAll(openDir, ButtonType.CLOSE);
                    if (stage != null) {
                        done.initOwner(stage);
                    }
                    done.showAndWait().ifPresent(btn -> {
                        if (btn == openDir) {
                            openFolder(out.getParent(), "导出目录");
                        }
                    });
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    progress.close();
                    statusLabel.setText("导出失败");
                });
                ExceptionHandler.handle("导出整合包失败", e);
            }
        });
    }

    private void openFolder(Path path, String label) {
        worker.submit(() -> {
            try {
                Path target = path;
                if (target == null) {
                    Platform.runLater(() -> ExceptionHandler.showInfo("提示", label + " 路径无效"));
                    return;
                }
                if (!Files.exists(target)) {
                    Files.createDirectories(target);
                }
                Path open = Files.isDirectory(target) ? target : target.getParent();
                if (open == null) {
                    Platform.runLater(() -> ExceptionHandler.showInfo("提示", "无法打开：" + target));
                    return;
                }
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(open.toFile());
                } else {
                    Platform.runLater(() -> ExceptionHandler.showInfo(label, open.toAbsolutePath().toString()));
                }
            } catch (Exception e) {
                ExceptionHandler.handle("打开" + label + "失败（请检查权限或目录是否被占用）", e);
            }
        });
    }
}
