package com.blacklauncher.ui.dialogs;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.exception.ExceptionHandler;
import com.blacklauncher.core.util.DiskIoGuard;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.minecraft.DirectoryKind;
import com.blacklauncher.minecraft.GameProcessGuard;
import com.blacklauncher.minecraft.MinecraftLayout;
import com.blacklauncher.minecraft.WorldSaveFormat;
import com.blacklauncher.minecraft.WorldSaveService;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.awt.Desktop;
import java.util.List;

/**
 * Lists instance worlds, shows format (legacy DIM / dimensions), backs up full folders.
 */
public final class SaveManagerDialog {

    private SaveManagerDialog() {
    }

    public static void show(Window owner, AppConfig config, String versionId) {
        if (!Validation.isSafeVersionId(versionId)) {
            ExceptionHandler.showInfo("提示", "请先选择一个已安装版本");
            return;
        }

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("存档管理");
        dialog.setHeaderText("实例：" + versionId
                + (config != null && config.isIsolateGameDirs() ? "（版本隔离）" : "（共享目录）"));
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
                ExceptionHandler.showInfo("备份完成",
                        "已备份到：\n" + result.archiveOrFolder().toAbsolutePath()
                                + "\n大小约 " + DiskIoGuard.formatSize(result.bytesCopied())
                                + "\n（含 level.dat、维度、玩家、进度等全部子目录）");
            } catch (Exception ex) {
                ExceptionHandler.handle("备份失败", ex);
            }
        });

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

        HBox actions = new HBox(10, backup, openDir, refresh);
        VBox box = new VBox(10, pathLabel, running, list, actions);
        box.setPadding(new Insets(8, 4, 4, 4));
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().setPrefWidth(720);
        dialog.getDialogPane().getButtonTypes().add(new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE));
        dialog.showAndWait();
    }
}
