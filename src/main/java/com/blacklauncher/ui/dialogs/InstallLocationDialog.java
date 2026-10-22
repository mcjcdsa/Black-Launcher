package com.blacklauncher.ui.dialogs;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.download.InstancePaths;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Asks where to install a mod / shader. Defaults to the matched game instance folder.
 */
public final class InstallLocationDialog {

    private InstallLocationDialog() {
    }

    public record Result(String versionId, Path targetDir) {
    }

    public static Optional<Result> show(Window owner,
                                        AppConfig config,
                                        String projectTitle,
                                        InstancePaths.ContentFolder folder,
                                        String mcVersion,
                                        String loader) {
        String defaultVersion = InstancePaths.resolveDefaultInstanceId(config, mcVersion, loader);
        List<String> versions = InstancePaths.listInstalledVersionIds();
        if (!versions.contains(defaultVersion) && Validation.isSafeVersionId(defaultVersion)) {
            versions = new java.util.ArrayList<>(versions);
            versions.add(0, defaultVersion);
        }

        Dialog<Result> dialog = new Dialog<>();
        dialog.setTitle("选择安装位置");
        dialog.setHeaderText("安装「" + projectTitle + "」到哪个游戏版本？");
        if (owner != null) {
            dialog.initOwner(owner);
        }

        Label tip = new Label("默认会安装到所选版本实例目录下的 " + folder.dirName() + " 文件夹。");
        tip.setWrapText(true);
        tip.getStyleClass().add("muted");

        ComboBox<String> versionBox = new ComboBox<>();
        versionBox.getItems().addAll(versions);
        if (Validation.isSafeVersionId(defaultVersion)) {
            versionBox.getSelectionModel().select(defaultVersion);
        } else if (!versions.isEmpty()) {
            versionBox.getSelectionModel().selectFirst();
        }
        versionBox.setMaxWidth(Double.MAX_VALUE);

        TextField pathField = new TextField();
        pathField.setEditable(true);
        HBox.setHgrow(pathField, Priority.ALWAYS);

        Runnable syncPath = () -> {
            String id = versionBox.getValue();
            if (Validation.isSafeVersionId(id)) {
                pathField.setText(InstancePaths.contentDir(config, id, folder).toAbsolutePath().toString());
            }
        };
        versionBox.valueProperty().addListener((o, a, b) -> syncPath.run());
        syncPath.run();

        Button browse = new Button("浏览…");
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("选择 " + folder.label() + " 安装目录");
            String current = pathField.getText();
            if (Validation.isNonBlank(current)) {
                File cur = new File(current);
                if (cur.isDirectory()) {
                    chooser.setInitialDirectory(cur);
                } else if (cur.getParentFile() != null && cur.getParentFile().isDirectory()) {
                    chooser.setInitialDirectory(cur.getParentFile());
                }
            }
            File picked = chooser.showDialog(owner);
            if (picked != null) {
                pathField.setText(picked.getAbsolutePath());
            }
        });

        Button reset = new Button("恢复默认");
        reset.setOnAction(e -> syncPath.run());

        Label matchHint = new Label(buildMatchHint(defaultVersion, mcVersion, loader, folder));
        matchHint.setWrapText(true);
        matchHint.getStyleClass().add("muted");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(8, 4, 4, 4));
        grid.add(tip, 0, 0, 2, 1);
        grid.add(new Label("游戏版本"), 0, 1);
        grid.add(versionBox, 1, 1);
        grid.add(new Label("安装目录"), 0, 2);
        HBox pathRow = new HBox(8, pathField, browse, reset);
        grid.add(pathRow, 1, 2);
        grid.add(matchHint, 1, 3);
        GridPane.setHgrow(versionBox, Priority.ALWAYS);
        GridPane.setHgrow(pathRow, Priority.ALWAYS);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().setPrefWidth(640);

        ButtonType ok = new ButtonType("开始下载", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, cancel);

        dialog.setResultConverter(button -> {
            if (button != ok) {
                return null;
            }
            String pathText = pathField.getText() == null ? "" : pathField.getText().trim();
            if (!Validation.isNonBlank(pathText)) {
                return null;
            }
            String versionId = versionBox.getValue();
            if (!Validation.isSafeVersionId(versionId)) {
                versionId = defaultVersion;
            }
            return new Result(versionId, Path.of(pathText));
        });

        return dialog.showAndWait();
    }

    private static String buildMatchHint(String defaultVersion, String mcVersion, String loader,
                                        InstancePaths.ContentFolder folder) {
        StringBuilder sb = new StringBuilder("建议默认：");
        sb.append(defaultVersion).append(" / ").append(folder.dirName());
        if (Validation.isNonBlank(mcVersion) || Validation.isNonBlank(loader)) {
            sb.append("（匹配 ");
            if (Validation.isNonBlank(mcVersion)) {
                sb.append(mcVersion);
            }
            if (Validation.isNonBlank(loader) && !"全部".equals(loader)) {
                if (Validation.isNonBlank(mcVersion)) {
                    sb.append(" + ");
                }
                sb.append(loader);
            }
            sb.append("）");
        }
        return sb.toString();
    }
}
