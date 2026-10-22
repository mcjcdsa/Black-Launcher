package com.blacklauncher.ui.dialogs;

import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.modpack.ModpackExportOptions;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Export options panel (§6.3.1).
 */
public final class ModpackExportDialog {

    private ModpackExportDialog() {
    }

    public static Optional<ModpackExportOptions> show(Window owner, String versionId) {
        Dialog<ModpackExportOptions> dialog = new Dialog<>();
        dialog.setTitle("导出整合包");
        dialog.setHeaderText("导出版本：" + versionId);
        if (owner != null) {
            dialog.initOwner(owner);
        }

        String packName = Validation.sanitizeVersionId(versionId);
        TextField nameField = new TextField(packName);
        TextField pathField = new TextField(
                AppPaths.modpacksDir().resolve(packName + "-" + versionId + ".zip").toAbsolutePath().toString());
        HBox.setHgrow(pathField, Priority.ALWAYS);
        Button browse = new Button("浏览…");
        browse.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("保存整合包");
            chooser.setInitialFileName(nameField.getText() + "-" + versionId + ".zip");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP 整合包", "*.zip"));
            chooser.setInitialDirectory(AppPaths.modpacksDir().toFile());
            var file = chooser.showSaveDialog(owner);
            if (file != null) {
                pathField.setText(file.getAbsolutePath());
            }
        });

        ComboBox<String> formatBox = new ComboBox<>();
        formatBox.getItems().addAll("离线 ZIP（内置模组，推荐）", "Modrinth (.mrpack 结构)", "CurseForge (manifest)");
        formatBox.getSelectionModel().selectFirst();

        CheckBox mods = new CheckBox("导出模组（mods/）");
        mods.setSelected(true);
        CheckBox config = new CheckBox("导出配置文件（config/）");
        config.setSelected(true);
        CheckBox resourcepacks = new CheckBox("导出资源包（resourcepacks/）");
        CheckBox saves = new CheckBox("导出存档（saves/）");
        CheckBox servers = new CheckBox("导出服务器列表（servers.dat）");
        servers.setSelected(true);
        CheckBox jvm = new CheckBox("导出启动参数（JVM）");
        CheckBox strip = new CheckBox("移除作者皮肤 / 私有信息");
        strip.setSelected(true);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(8, 4, 4, 4));
        int r = 0;
        grid.add(new Label("整合包名称"), 0, r);
        grid.add(nameField, 1, r++);
        grid.add(new Label("导出路径"), 0, r);
        grid.add(new HBox(8, pathField, browse), 1, r++);
        grid.add(new Label("导出格式"), 0, r);
        grid.add(formatBox, 1, r++);
        grid.add(mods, 0, r, 2, 1);
        r++;
        grid.add(config, 0, r, 2, 1);
        r++;
        grid.add(resourcepacks, 0, r, 2, 1);
        r++;
        grid.add(saves, 0, r, 2, 1);
        r++;
        grid.add(servers, 0, r, 2, 1);
        r++;
        grid.add(jvm, 0, r, 2, 1);
        r++;
        grid.add(strip, 0, r, 2, 1);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().setPrefWidth(560);

        ButtonType ok = new ButtonType("开始导出", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(ok, cancel);

        dialog.setResultConverter(btn -> {
            if (btn != ok) {
                return null;
            }
            ModpackExportOptions opts = new ModpackExportOptions();
            opts.setPackName(nameField.getText() == null ? packName : nameField.getText().trim());
            opts.setOutputFile(Path.of(pathField.getText().trim()));
            int fi = formatBox.getSelectionModel().getSelectedIndex();
            opts.setFormat(fi == 1 ? ModpackExportOptions.Format.MODRINTH
                    : fi == 2 ? ModpackExportOptions.Format.CURSEFORGE
                    : ModpackExportOptions.Format.OFFLINE_ZIP);
            opts.setIncludeMods(mods.isSelected());
            opts.setIncludeConfig(config.isSelected());
            opts.setIncludeResourcepacks(resourcepacks.isSelected());
            opts.setIncludeSaves(saves.isSelected());
            opts.setIncludeServers(servers.isSelected());
            opts.setIncludeJvmArgs(jvm.isSelected());
            opts.setStripPrivate(strip.isSelected());
            return opts;
        });

        return dialog.showAndWait();
    }
}
