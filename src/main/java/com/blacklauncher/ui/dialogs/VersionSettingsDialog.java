package com.blacklauncher.ui.dialogs;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.config.ConfigManager;
import com.blacklauncher.core.db.Database;
import com.blacklauncher.core.exception.ExceptionHandler;
import com.blacklauncher.core.net.DownloadProgressSink;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.download.VersionFileCompleter;
import com.blacklauncher.launch.LaunchScriptExporter;
import com.blacklauncher.minecraft.DirectoryKind;
import com.blacklauncher.minecraft.LocalModEntry;
import com.blacklauncher.minecraft.LocalModService;
import com.blacklauncher.minecraft.MinecraftLayout;
import com.blacklauncher.modpack.ModpackExportOptions;
import com.blacklauncher.modpack.ModpackExportService;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
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
    private final String versionId;
    private final LocalModService modService;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "version-settings");
        t.setDaemon(true);
        return t;
    });

    private final TableView<LocalModEntry> modTable = new TableView<>();
    private final Label modEmpty = new Label("当前版本暂无 Mod 文件");
    private final Label statusLabel = new Label("就绪");
    private Stage stage;

    private VersionSettingsDialog(ConfigManager configManager, Database database, String versionId) {
        this.configManager = configManager;
        this.database = database;
        this.versionId = versionId;
        this.modService = new LocalModService(configManager.getConfig());
    }

    public static void show(Window owner, ConfigManager configManager, Database database, String versionId) {
        if (!Validation.isNonBlank(versionId)) {
            ExceptionHandler.showInfo("提示", "请先选择游戏版本");
            return;
        }
        VersionSettingsDialog dlg = new VersionSettingsDialog(configManager, database, versionId.trim());
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

        Label title = new Label("版本设置：" + versionId);
        title.getStyleClass().add("page-title");

        TitledPane folders = new TitledPane("游戏文件夹", buildFolderGrid());
        folders.setExpanded(true);
        folders.setCollapsible(false);

        TitledPane advanced = new TitledPane("高级设置", buildAdvancedPane());
        advanced.setExpanded(true);

        VBox body = new VBox(12, title, folders, advanced, statusLabel);
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
        Button exportPack = new Button("导出完整整合包");
        exportPack.getStyleClass().addAll("button", "primary");
        exportPack.setOnAction(e -> doExportFullPack());

        HBox ops = new HBox(10, exportScript, completeFiles, exportPack);
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
        Button rename = new Button("重命名");
        rename.setOnAction(e -> renameSelected());
        Button delete = new Button("删除");
        delete.setOnAction(e -> deleteSelected(false));
        Button deleteBatch = new Button("批量删除");
        deleteBatch.setOnAction(e -> deleteSelected(true));

        HBox modOps = new HBox(8, refreshMods, openMods, rename, delete, deleteBatch);
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
