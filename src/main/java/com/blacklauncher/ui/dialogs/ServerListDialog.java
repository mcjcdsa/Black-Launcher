package com.blacklauncher.ui.dialogs;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.config.ConfigManager;
import com.blacklauncher.core.exception.ExceptionHandler;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.minecraft.DirectoryKind;
import com.blacklauncher.minecraft.GameProcessGuard;
import com.blacklauncher.minecraft.MinecraftLayout;
import com.blacklauncher.minecraft.ServerListService;
import com.blacklauncher.minecraft.ServerListService.ServerEntry;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Edits Minecraft {@code servers.dat} for a version instance.
 */
public final class ServerListDialog {

    private ServerListDialog() {
    }

    public static void show(Window owner, ConfigManager configManager, String versionId) {
        if (configManager == null) {
            ExceptionHandler.showInfo("提示", "配置不可用");
            return;
        }
        if (!Validation.isSafeVersionId(versionId)) {
            ExceptionHandler.showInfo("提示", "请先选择一个已安装版本");
            return;
        }
        String vid = versionId.trim();

        AppConfig config = configManager.getConfig();
        ServerListService service = new ServerListService(config);

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("服务器列表");
        dialog.setHeaderText("实例：" + vid
                + (config.isIsolateGameDirs() ? "（版本隔离）" : "（共享目录）"));
        if (owner != null) {
            dialog.initOwner(owner);
        }

        Label pathLabel = new Label("文件："
                + MinecraftLayout.forVersion(config, vid).resolve(DirectoryKind.SERVERS_DAT).toAbsolutePath());
        pathLabel.setWrapText(true);
        pathLabel.getStyleClass().add("muted");

        Label running = new Label();
        if (GameProcessGuard.get().isGameRunning()) {
            running.setText("游戏正在运行，写入已锁定。可查看列表，保存请先关闭游戏。");
            running.getStyleClass().add("compat-warn");
        } else {
            running.setText("可编辑并保存到 servers.dat。");
            running.getStyleClass().add("muted");
        }

        ObservableList<ServerEntry> items = FXCollections.observableArrayList();
        TableView<ServerEntry> table = new TableView<>(items);
        table.setPlaceholder(new Label("暂无服务器条目"));
        table.setPrefHeight(280);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<ServerEntry, String> nameCol = new TableColumn<>("名称");
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue() == null ? "" : c.getValue().name()));
        TableColumn<ServerEntry, String> ipCol = new TableColumn<>("地址");
        ipCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue() == null ? "" : c.getValue().ip()));
        TableColumn<ServerEntry, String> pinCol = new TableColumn<>("置顶");
        pinCol.setMaxWidth(72);
        pinCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue() != null && c.getValue().pinned() ? "是" : ""));
        @SuppressWarnings("unchecked")
        TableColumn<ServerEntry, ?>[] cols = new TableColumn[]{nameCol, ipCol, pinCol};
        table.getColumns().setAll(cols);
        VBox.setVgrow(table, Priority.ALWAYS);

        Runnable reload = () -> {
            try {
                List<ServerEntry> loaded = service.load(config, vid);
                items.setAll(loaded);
            } catch (Exception e) {
                ExceptionHandler.handle("读取服务器列表失败", e);
            }
        };
        reload.run();

        Button add = new Button("添加");
        add.setOnAction(e -> editEntry(owner, null).ifPresent(entry -> {
            items.add(entry);
            table.getSelectionModel().select(entry);
        }));

        Button edit = new Button("编辑");
        edit.setOnAction(e -> {
            ServerEntry sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) {
                ExceptionHandler.showInfo("提示", "请先选择一条服务器");
                return;
            }
            editEntry(owner, sel).ifPresent(updated -> {
                int idx = items.indexOf(sel);
                if (idx >= 0) {
                    items.set(idx, updated.withPinned(sel.pinned()));
                }
            });
        });

        Button remove = new Button("删除");
        remove.setOnAction(e -> {
            ServerEntry sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) {
                ExceptionHandler.showInfo("提示", "请先选择一条服务器");
                return;
            }
            items.remove(sel);
        });

        Button pin = new Button("置顶");
        pin.setOnAction(e -> {
            ServerEntry sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) {
                ExceptionHandler.showInfo("提示", "请先选择一条服务器");
                return;
            }
            List<ServerEntry> reordered = new ArrayList<>(items.size());
            reordered.add(sel.withPinned(true));
            for (ServerEntry entry : items) {
                if (entry == sel) {
                    continue;
                }
                reordered.add(entry.withPinned(false));
            }
            items.setAll(reordered);
            table.getSelectionModel().select(0);
        });

        Button refresh = new Button("刷新");
        refresh.setOnAction(e -> reload.run());

        Button save = new Button("保存");
        save.getStyleClass().add("primary-button");
        save.setOnAction(e -> {
            try {
                service.save(config, vid, new ArrayList<>(items));
                reload.run();
                ExceptionHandler.showInfo("已保存", "服务器列表已写入 servers.dat");
            } catch (Exception ex) {
                ExceptionHandler.handle("保存服务器列表失败", ex);
            }
        });

        HBox actions = new HBox(10, add, edit, remove, pin, refresh, save);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(10, pathLabel, running, table, actions);
        box.setPadding(new Insets(8, 4, 4, 4));
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().setPrefWidth(640);
        dialog.getDialogPane().getButtonTypes().add(new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE));
        dialog.showAndWait();
    }

    private static Optional<ServerEntry> editEntry(Window owner, ServerEntry existing) {
        Dialog<ServerEntry> form = new Dialog<>();
        form.setTitle(existing == null ? "添加服务器" : "编辑服务器");
        form.setHeaderText("填写名称与地址（host 或 host:port）");
        if (owner != null) {
            form.initOwner(owner);
        }

        TextField nameField = new TextField(existing == null ? "" : existing.name());
        nameField.setPromptText("名称");
        TextField ipField = new TextField(existing == null ? "" : existing.ip());
        ipField.setPromptText("例如 192.168.1.10:25565");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(8, 4, 4, 4));
        grid.add(new Label("名称"), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(new Label("地址"), 0, 1);
        grid.add(ipField, 1, 1);
        GridPane.setHgrow(nameField, Priority.ALWAYS);
        GridPane.setHgrow(ipField, Priority.ALWAYS);

        form.getDialogPane().setContent(grid);
        form.getDialogPane().setPrefWidth(420);
        ButtonType ok = new ButtonType("确定", ButtonBar.ButtonData.OK_DONE);
        form.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
        form.setResultConverter(btn -> {
            if (btn != ok) {
                return null;
            }
            String name = nameField.getText() == null ? "" : nameField.getText().trim();
            String ip = ipField.getText() == null ? "" : ipField.getText().trim();
            if (!Validation.isNonBlank(ip)) {
                ExceptionHandler.showInfo("提示", "地址不能为空");
                return null;
            }
            if (!Validation.isNonBlank(name)) {
                name = ip;
            }
            boolean pinned = existing != null && existing.pinned();
            return new ServerEntry(name, ip, pinned);
        });

        // Prefer TextInputDialog-style focus
        nameField.requestFocus();
        return form.showAndWait();
    }
}
