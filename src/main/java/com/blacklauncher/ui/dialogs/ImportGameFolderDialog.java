package com.blacklauncher.ui.dialogs;

import com.blacklauncher.download.GameFolderImportService;
import com.blacklauncher.download.GameFolderImportService.DetectedVersion;
import com.blacklauncher.download.GameFolderImportService.ImportOptions;
import com.blacklauncher.download.GameFolderImportService.ScanResult;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Preview recognized versions from an external game folder before import.
 */
public final class ImportGameFolderDialog {

    public record Choice(List<DetectedVersion> versions, ImportOptions options) {
    }

    private ImportGameFolderDialog() {
    }

    public static Optional<Choice> show(Window owner, ScanResult scan) {
        Dialog<Choice> dialog = new Dialog<>();
        dialog.setTitle("导入游戏文件夹");
        dialog.setHeaderText(headerFor(scan));
        if (owner != null) {
            dialog.initOwner(owner);
        }

        Label tip = new Label("已识别下列版本，勾选后导入到启动器数据目录（可多选）。");
        tip.setWrapText(true);

        ListView<DetectedVersion> list = new ListView<>();
        list.setItems(FXCollections.observableArrayList(scan.versions()));
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        list.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(DetectedVersion item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.summaryLine());
            }
        });
        list.getSelectionModel().selectAll();
        list.setPrefHeight(260);
        VBox.setVgrow(list, Priority.ALWAYS);

        CheckBox overwrite = new CheckBox("覆盖已存在的同名版本");
        CheckBox copyLibs = new CheckBox("合并依赖库 libraries");
        copyLibs.setSelected(scan.hasLibraries());
        copyLibs.setDisable(!scan.hasLibraries());
        CheckBox copyAssets = new CheckBox("合并资源 assets（可能较大）");
        copyAssets.setSelected(scan.hasAssets());
        copyAssets.setDisable(!scan.hasAssets());
        CheckBox copyContent = new CheckBox("导入 Mod / 光影 / 存档等到独立实例目录");
        copyContent.setSelected(true);

        Label pathLabel = new Label("来源：" + scan.selectedPath());
        pathLabel.getStyleClass().add("muted");
        pathLabel.setWrapText(true);

        VBox box = new VBox(10, tip, pathLabel, list, overwrite, copyLibs, copyAssets, copyContent);
        box.setPadding(new Insets(8, 4, 4, 4));
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().setPrefWidth(640);

        ButtonType ok = new ButtonType("开始导入", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, cancel);

        dialog.setResultConverter(button -> {
            if (button != ok) {
                return null;
            }
            List<DetectedVersion> selected = new ArrayList<>(list.getSelectionModel().getSelectedItems());
            if (selected.isEmpty()) {
                selected = new ArrayList<>(scan.versions());
            }
            ImportOptions options = new ImportOptions(
                    overwrite.isSelected(),
                    copyLibs.isSelected(),
                    copyAssets.isSelected(),
                    copyContent.isSelected()
            );
            return new Choice(selected, options);
        });

        return dialog.showAndWait();
    }

    private static String headerFor(ScanResult scan) {
        String kind = switch (scan.kind()) {
            case MINECRAFT_ROOT -> "已识别为 Minecraft 游戏根目录";
            case VERSIONS_DIR -> "已识别为 versions 目录";
            case SINGLE_VERSION -> "已识别为单个版本文件夹";
            case UNKNOWN -> "未能确认目录类型";
        };
        return kind + " · 发现 " + scan.versions().size() + " 个版本";
    }
}
