package com.blacklauncher.ui.dialogs;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.config.ConfigManager;
import com.blacklauncher.core.exception.ExceptionHandler;
import com.blacklauncher.core.util.DiskIoGuard;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.download.InstalledVersionService;
import com.blacklauncher.minecraft.DirectoryKind;
import com.blacklauncher.minecraft.GameProcessGuard;
import com.blacklauncher.minecraft.MinecraftLayout;
import com.blacklauncher.minecraft.WorldSaveService;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.awt.Desktop;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Lists instance worlds, shows format (legacy DIM / dimensions), backs up full folders.
 */
public final class SaveManagerDialog {

    private SaveManagerDialog() {
    }

    public static void show(Window owner, ConfigManager configManager, InstalledVersionService versions,
                            String versionId) {
        if (configManager == null) {
            ExceptionHandler.showInfo("提示", "配置不可用");
            return;
        }
        AppConfig config = configManager.getConfig();
        if (!Validation.isSafeVersionId(versionId)) {
            ExceptionHandler.showInfo("提示", "请先选择一个已安装版本");
            return;
        }

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("存档管理");
        dialog.setHeaderText("实例：" + versionId
                + (config.isIsolateGameDirs() ? "（版本隔离）" : "（共享目录）"));
        if (owner != null) {
            dialog.initOwner(owner);
        }

        MinecraftLayout layout = MinecraftLayout.forVersion(config, versionId);
        Label pathLabel = new Label("存档目录：" + layout.resolve(DirectoryKind.SAVES).toAbsolutePath());
        pathLabel.setWrapText(true);
        pathLabel.getStyleClass().add("muted");

        Label running = new Label();
        if (GameProcessGuard.get().isGameRunning()) {
            running.setText("游戏正在运行，备份/写入已锁定。请先关闭游戏。");
            running.getStyleClass().add("compat-warn");
        } else {
            running.setText("游戏未运行，可以安全备份完整世界文件夹。");
            running.getStyleClass().add("muted");
        }

        ListView<WorldSaveService.WorldInfo> list = new ListView<>();
        list.setPrefHeight(280);
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(WorldSaveService.WorldInfo item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                String fmt = switch (item.format()) {
                    case LEGACY_DIM -> "旧版 DIM";
                    case DIMENSIONS_TREE -> "新版 dimensions";
                    default -> "未知结构";
                };
                String ok = item.valid() ? "合法" : "异常";
                String dims = item.dimensions().isEmpty() ? "-" : String.join(",", item.dimensions());
                String dv = item.dataVersionHint() == null ? "-" : String.valueOf(item.dataVersionHint());
                setText(item.folderName()
                        + "  [" + fmt + "/" + ok + "]"
                        + "  维度:" + dims
                        + "  DataVersion:" + dv
                        + (item.hasDatapacks() ? "  ·数据包" : "")
                        + (item.issues().isEmpty() ? "" : "  ·" + item.issues().get(0)));
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);

        WorldSaveService service = new WorldSaveService(config);
        Runnable reload = () -> {
            try {
                List<WorldSaveService.WorldInfo> worlds = service.listWorlds(versionId);
                list.setItems(FXCollections.observableArrayList(worlds));
                if (worlds.isEmpty()) {
                    list.setPlaceholder(new Label("暂无单人存档"));
                }
            } catch (Exception e) {
                ExceptionHandler.handle("扫描存档失败", e);
            }
        };
        reload.run();

        CheckBox beforeLaunch = new CheckBox("启动前备份");
        beforeLaunch.setSelected(config.isBackupBeforeLaunch());
        CheckBox afterExit = new CheckBox("退出后备份");
        afterExit.setSelected(config.isBackupAfterExit());
        Spinner<Integer> keepSpinner = new Spinner<>();
        keepSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                1, 30, Math.max(1, Math.min(30, config.getBackupKeepCount())), 1));
        keepSpinner.setEditable(true);
        keepSpinner.setPrefWidth(80);

        Button savePolicy = new Button("保存备份策略");
        savePolicy.setOnAction(e -> {
            try {
                config.setBackupBeforeLaunch(beforeLaunch.isSelected());
                config.setBackupAfterExit(afterExit.isSelected());
                Integer keep = keepSpinner.getValue();
                config.setBackupKeepCount(keep == null ? 5 : Math.max(1, Math.min(30, keep)));
                configManager.save();
                ExceptionHandler.showInfo("已保存", "备份策略已写入配置");
            } catch (Exception ex) {
                ExceptionHandler.handle("保存备份策略失败", ex);
            }
        });

        HBox policyRow = new HBox(12, beforeLaunch, afterExit,
                new Label("保留份数"), keepSpinner, savePolicy);
        policyRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        Button backup = new Button("备份选中世界（完整文件夹）");
        backup.getStyleClass().add("primary-button");
        backup.setOnAction(e -> {
            WorldSaveService.WorldInfo selected = list.getSelectionModel().getSelectedItem();
            if (selected == null) {
                ExceptionHandler.showInfo("提示", "请选择一个世界");
                return;
            }
            try {
                WorldSaveService.BackupResult result = service.backupWorld(versionId, selected.folderName());
                service.rotateBackups(versionId, selected.folderName(), config.getBackupKeepCount());
                ExceptionHandler.showInfo("备份完成",
                        "已备份到：\n" + result.archiveOrFolder().toAbsolutePath()
                                + "\n大小约 " + DiskIoGuard.formatSize(result.bytesCopied())
                                + "\n（含 level.dat、维度、玩家、进度等全部子目录）");
            } catch (Exception ex) {
                ExceptionHandler.handle("备份失败", ex);
            }
        });

        Button migrate = new Button("迁移到其他版本…");
        migrate.setOnAction(e -> migrateSelected(owner, config, versions, service, list, versionId, reload));

        Button openDir = new Button("打开存档目录");
        openDir.setOnAction(e -> {
            try {
                java.nio.file.Path saves = layout.resolve(DirectoryKind.SAVES);
                java.nio.file.Files.createDirectories(saves);
                Desktop.getDesktop().open(saves.toFile());
            } catch (Exception ex) {
                ExceptionHandler.handle("打开目录失败", ex);
            }
        });

        Button refresh = new Button("刷新");
        refresh.setOnAction(e -> reload.run());

        HBox actions = new HBox(10, backup, migrate, openDir, refresh);
        VBox box = new VBox(10, pathLabel, running, policyRow, list, actions);
        box.setPadding(new Insets(8, 4, 4, 4));
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().setPrefWidth(760);
        dialog.getDialogPane().getButtonTypes().add(new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE));
        dialog.showAndWait();
    }

    private static void migrateSelected(Window owner,
                                        AppConfig config,
                                        InstalledVersionService versions,
                                        WorldSaveService service,
                                        ListView<WorldSaveService.WorldInfo> list,
                                        String fromVersionId,
                                        Runnable reload) {
        WorldSaveService.WorldInfo selected = list.getSelectionModel().getSelectedItem();
        if (selected == null) {
            ExceptionHandler.showInfo("提示", "请选择一个世界");
            return;
        }
        if (versions == null) {
            ExceptionHandler.showInfo("提示", "无法枚举已安装版本");
            return;
        }

        List<String> targets;
        try {
            targets = versions.list().stream()
                    .map(InstalledVersionService.InstalledVersion::id)
                    .filter(id -> !fromVersionId.equals(id))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Exception ex) {
            ExceptionHandler.handle("读取已安装版本失败", ex);
            return;
        }
        if (targets.isEmpty()) {
            ExceptionHandler.showInfo("提示", "没有其他可迁移的目标版本");
            return;
        }

        Dialog<ButtonType> migrateDialog = new Dialog<>();
        migrateDialog.setTitle("迁移存档");
        migrateDialog.setHeaderText("将「" + selected.folderName() + "」从 " + fromVersionId + " 复制到：");
        if (owner != null) {
            migrateDialog.initOwner(owner);
        }
        ComboBox<String> targetBox = new ComboBox<>(FXCollections.observableArrayList(targets));
        targetBox.getSelectionModel().selectFirst();
        CheckBox overwrite = new CheckBox("覆盖目标版本中的同名存档");
        VBox body = new VBox(10, targetBox, overwrite);
        body.setPadding(new Insets(8));
        migrateDialog.getDialogPane().setContent(body);
        migrateDialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        ButtonType result = migrateDialog.showAndWait().orElse(ButtonType.CANCEL);
        if (result != ButtonType.OK) {
            return;
        }
        String toId = targetBox.getSelectionModel().getSelectedItem();
        if (!Validation.isSafeVersionId(toId)) {
            ExceptionHandler.showInfo("提示", "请选择目标版本");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("确认迁移");
        confirm.setHeaderText("迁移存档到 " + toId + "？");
        confirm.setContentText("世界：" + selected.folderName()
                + (overwrite.isSelected() ? "\n同名存档将被覆盖。" : ""));
        if (owner != null) {
            confirm.initOwner(owner);
        }
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }

        try {
            service.migrateWorld(fromVersionId, toId, selected.folderName(), overwrite.isSelected());
            ExceptionHandler.showInfo("迁移完成",
                    "已将「" + selected.folderName() + "」复制到版本 " + toId);
            reload.run();
        } catch (Exception ex) {
            ExceptionHandler.handle("迁移失败", ex);
        }
    }
}
