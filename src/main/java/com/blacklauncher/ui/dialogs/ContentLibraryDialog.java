package com.blacklauncher.ui.dialogs;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.config.ConfigManager;
import com.blacklauncher.core.exception.ExceptionHandler;
import com.blacklauncher.core.util.DiskIoGuard;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.minecraft.LocalContentService;
import com.blacklauncher.minecraft.LocalContentService.ContentEntry;
import com.blacklauncher.minecraft.LocalContentService.ContentKind;
import com.blacklauncher.minecraft.WorldSaveService;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Unified content library: resource packs, shader packs, and world datapacks.
 */
public final class ContentLibraryDialog {

    private ContentLibraryDialog() {
    }

    public static void show(Window owner, ConfigManager cm, String versionId) {
        if (cm == null) {
            ExceptionHandler.showInfo("提示", "配置不可用");
            return;
        }
        if (!Validation.isSafeVersionId(versionId)) {
            ExceptionHandler.showInfo("提示", "请先选择一个已安装版本");
            return;
        }

        AppConfig config = cm.getConfig();
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("内容库");
        dialog.setHeaderText("实例：" + versionId
                + (config.isIsolateGameDirs() ? "（版本隔离）" : "（共享目录）"));
        if (owner != null) {
            dialog.initOwner(owner);
        }

        TabPane tabs = new TabPane(
                packsTab(owner, config, versionId, ContentKind.RESOURCEPACK, "资源包"),
                packsTab(owner, config, versionId, ContentKind.SHADERPACK, "光影"),
                datapackTab(owner, config, versionId)
        );
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setPrefHeight(420);

        VBox root = new VBox(8, tabs);
        root.setPadding(new Insets(4));
        VBox.setVgrow(tabs, Priority.ALWAYS);

        dialog.getDialogPane().setContent(root);
        dialog.getDialogPane().setPrefWidth(780);
        dialog.getDialogPane().getButtonTypes().add(new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE));
        dialog.showAndWait();
    }

    private static Tab packsTab(Window owner, AppConfig config, String versionId,
                                ContentKind kind, String title) {
        TableView<ContentEntry> table = createTable();
        Label hint = new Label();
        hint.setWrapText(true);
        hint.getStyleClass().add("compat-warn");
        hint.setManaged(false);
        hint.setVisible(false);

        Runnable reload = () -> {
            try {
                List<ContentEntry> items = LocalContentService.listPacks(config, versionId, kind);
                table.setItems(FXCollections.observableArrayList(items));
                table.setPlaceholder(new Label(items.isEmpty() ? "暂无" + title : ""));
                if (kind == ContentKind.SHADERPACK) {
                    String msg = LocalContentService.shaderLoaderHint(config, versionId);
                    boolean show = Validation.isNonBlank(msg);
                    hint.setText(msg == null ? "" : msg);
                    hint.setManaged(show);
                    hint.setVisible(show);
                }
            } catch (Exception e) {
                ExceptionHandler.handle("扫描" + title + "失败", e);
            }
        };
        reload.run();

        Button refresh = new Button("刷新");
        refresh.setOnAction(e -> reload.run());
        Button enable = new Button("启用");
        enable.setOnAction(e -> setEnabled(table, true, reload));
        Button disable = new Button("禁用");
        disable.setOnAction(e -> setEnabled(table, false, reload));
        Button delete = new Button("删除");
        delete.setOnAction(e -> deleteSelected(table, reload));
        Button openDir = new Button("打开目录");
        openDir.setOnAction(e -> {
            try {
                Path dir = LocalContentService.packsDir(config, versionId, kind);
                Files.createDirectories(dir);
                Desktop.getDesktop().open(dir.toFile());
            } catch (Exception ex) {
                ExceptionHandler.handle("打开目录失败", ex);
            }
        });

        HBox actions = new HBox(8, refresh, enable, disable, delete, openDir);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(8, hint, table, actions);
        VBox.setVgrow(table, Priority.ALWAYS);
        return new Tab(title, box);
    }

    private static Tab datapackTab(Window owner, AppConfig config, String versionId) {
        WorldSaveService worlds = new WorldSaveService(config);
        ComboBox<WorldSaveService.WorldInfo> worldBox = new ComboBox<>();
        worldBox.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(worldBox, Priority.ALWAYS);
        worldBox.setCellFactory(lv -> worldCell());
        worldBox.setButtonCell(worldCell());

        TableView<ContentEntry> table = createTable();
        Label emptyWorld = new Label("请选择存档世界以查看数据包");
        emptyWorld.getStyleClass().add("muted");

        Runnable reloadWorlds = () -> {
            try {
                List<WorldSaveService.WorldInfo> list = worlds.listWorlds(versionId);
                WorldSaveService.WorldInfo prev = worldBox.getSelectionModel().getSelectedItem();
                worldBox.setItems(FXCollections.observableArrayList(list));
                if (list.isEmpty()) {
                    worldBox.getSelectionModel().clearSelection();
                    table.getItems().clear();
                    table.setPlaceholder(new Label("暂无单人存档"));
                } else {
                    String prevName = prev == null ? null : prev.folderName();
                    WorldSaveService.WorldInfo match = list.stream()
                            .filter(w -> w.folderName().equals(prevName))
                            .findFirst()
                            .orElse(list.get(0));
                    worldBox.getSelectionModel().select(match);
                }
            } catch (Exception e) {
                ExceptionHandler.handle("扫描存档失败", e);
            }
        };

        Runnable reloadPacks = () -> {
            WorldSaveService.WorldInfo w = worldBox.getSelectionModel().getSelectedItem();
            if (w == null) {
                table.getItems().clear();
                table.setPlaceholder(emptyWorld);
                return;
            }
            try {
                List<ContentEntry> items = LocalContentService.listDatapacks(config, versionId, w.folderName());
                table.setItems(FXCollections.observableArrayList(items));
                table.setPlaceholder(new Label(items.isEmpty() ? "该世界暂无数据包" : ""));
            } catch (Exception e) {
                ExceptionHandler.handle("扫描数据包失败", e);
            }
        };
        worldBox.setOnAction(e -> reloadPacks.run());
        reloadWorlds.run();
        reloadPacks.run();

        Button refresh = new Button("刷新");
        refresh.setOnAction(e -> {
            reloadWorlds.run();
            reloadPacks.run();
        });
        Button enable = new Button("启用");
        enable.setOnAction(e -> setEnabled(table, true, reloadPacks));
        Button disable = new Button("禁用");
        disable.setOnAction(e -> setEnabled(table, false, reloadPacks));
        Button delete = new Button("删除");
        delete.setOnAction(e -> deleteSelected(table, reloadPacks));
        Button openDir = new Button("打开目录");
        openDir.setOnAction(e -> {
            WorldSaveService.WorldInfo w = worldBox.getSelectionModel().getSelectedItem();
            if (w == null) {
                ExceptionHandler.showInfo("提示", "请先选择存档世界");
                return;
            }
            try {
                Path dir = LocalContentService.datapacksDir(config, versionId, w.folderName());
                Files.createDirectories(dir);
                Desktop.getDesktop().open(dir.toFile());
            } catch (Exception ex) {
                ExceptionHandler.handle("打开目录失败", ex);
            }
        });
        Button install = new Button("安装数据包…");
        install.getStyleClass().add("primary-button");
        install.setOnAction(e -> installDatapack(owner, config, versionId, worldBox, reloadPacks));

        HBox worldRow = new HBox(8, new Label("存档世界"), worldBox, install);
        worldRow.setAlignment(Pos.CENTER_LEFT);
        HBox actions = new HBox(8, refresh, enable, disable, delete, openDir);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(8, worldRow, table, actions);
        VBox.setVgrow(table, Priority.ALWAYS);
        return new Tab("数据包", box);
    }

    private static ListCell<WorldSaveService.WorldInfo> worldCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(WorldSaveService.WorldInfo item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.folderName() + (item.hasDatapacks() ? " ·有数据包" : ""));
                }
            }
        };
    }

    private static TableView<ContentEntry> createTable() {
        TableView<ContentEntry> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(280);

        TableColumn<ContentEntry, String> nameCol = new TableColumn<>("名称");
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        TableColumn<ContentEntry, String> sizeCol = new TableColumn<>("大小");
        sizeCol.setMaxWidth(100);
        sizeCol.setCellValueFactory(c -> new SimpleStringProperty(
                DiskIoGuard.formatSize(c.getValue().sizeBytes())));
        TableColumn<ContentEntry, String> statusCol = new TableColumn<>("状态");
        statusCol.setMaxWidth(80);
        statusCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().enabled() ? "启用" : "禁用"));
        TableColumn<ContentEntry, String> detailCol = new TableColumn<>("详情");
        detailCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().detail() == null ? "" : c.getValue().detail()));

        table.getColumns().setAll(nameCol, sizeCol, statusCol, detailCol);
        return table;
    }

    private static void setEnabled(TableView<ContentEntry> table, boolean enabled, Runnable reload) {
        ContentEntry selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            ExceptionHandler.showInfo("提示", "请先选择一项");
            return;
        }
        try {
            LocalContentService.setEnabled(selected.path(), enabled);
            reload.run();
        } catch (Exception e) {
            ExceptionHandler.handle((enabled ? "启用" : "禁用") + "失败", e);
        }
    }

    private static void deleteSelected(TableView<ContentEntry> table, Runnable reload) {
        ContentEntry selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            ExceptionHandler.showInfo("提示", "请先选择一项");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("确认删除");
        confirm.setHeaderText("删除「" + selected.name() + "」？");
        confirm.setContentText("此操作不可恢复。");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        try {
            LocalContentService.delete(selected.path());
            reload.run();
        } catch (Exception e) {
            ExceptionHandler.handle("删除失败", e);
        }
    }

    private static void installDatapack(Window owner, AppConfig config, String versionId,
                                        ComboBox<WorldSaveService.WorldInfo> worldBox,
                                        Runnable reload) {
        WorldSaveService.WorldInfo w = worldBox.getSelectionModel().getSelectedItem();
        if (w == null) {
            ExceptionHandler.showInfo("提示", "请先选择存档世界");
            return;
        }

        Alert pick = new Alert(Alert.AlertType.CONFIRMATION);
        pick.setTitle("安装数据包");
        pick.setHeaderText("选择安装来源");
        pick.setContentText("可安装 zip 文件或文件夹。");
        ButtonType zipType = new ButtonType("选择 ZIP…");
        ButtonType folderType = new ButtonType("选择文件夹…");
        pick.getButtonTypes().setAll(zipType, folderType, ButtonType.CANCEL);
        ButtonType choice = pick.showAndWait().orElse(ButtonType.CANCEL);
        if (choice == ButtonType.CANCEL) {
            return;
        }

        Path source = null;
        if (choice == zipType) {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("选择数据包 ZIP");
            chooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("ZIP 数据包", "*.zip"),
                    new FileChooser.ExtensionFilter("所有文件", "*.*"));
            File file = chooser.showOpenDialog(owner);
            if (file != null) {
                source = file.toPath();
            }
        } else if (choice == folderType) {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("选择数据包文件夹");
            File dir = chooser.showDialog(owner);
            if (dir != null) {
                source = dir.toPath();
            }
        }
        if (source == null) {
            return;
        }
        try {
            Path dest = LocalContentService.installDatapack(config, versionId, w.folderName(), source);
            ExceptionHandler.showInfo("完成", "已安装到：\n" + dest.toAbsolutePath());
            reload.run();
        } catch (Exception e) {
            ExceptionHandler.handle("安装数据包失败", e);
        }
    }
}
