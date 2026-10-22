package com.blacklauncher.ui.dialogs;

import com.blacklauncher.download.ModLoader;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Optional;

/**
 * Asks which install flavour to use before downloading a game version.
 */
public final class LoaderChoiceDialog {

    private LoaderChoiceDialog() {
    }

    public static Optional<ModLoader> show(Window owner, String versionId) {
        Dialog<ModLoader> dialog = new Dialog<>();
        dialog.setTitle("选择下载类型");
        dialog.setHeaderText("即将下载 Minecraft " + versionId);
        if (owner != null) {
            dialog.initOwner(owner);
        }

        Label tip = new Label("请选择安装类型。原版可直接游玩；选择模组加载器将在原版基础上继续安装。");
        tip.setWrapText(true);

        ComboBox<ModLoader> combo = new ComboBox<>();
        combo.getItems().addAll(ModLoader.values());
        combo.getSelectionModel().select(ModLoader.VANILLA);
        combo.setMaxWidth(Double.MAX_VALUE);

        Label detail = new Label();
        detail.getStyleClass().add("muted");
        detail.setWrapText(true);
        updateDetail(detail, combo.getValue());
        combo.valueProperty().addListener((o, a, b) -> updateDetail(detail, b));

        VBox box = new VBox(12, tip, new Label("下载类型"), combo, detail);
        box.setPadding(new Insets(8, 4, 4, 4));
        dialog.getDialogPane().setContent(box);

        ButtonType ok = new ButtonType("开始下载", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, cancel);

        dialog.setResultConverter(button -> {
            if (button == ok) {
                return combo.getValue() == null ? ModLoader.VANILLA : combo.getValue();
            }
            return null;
        });

        return dialog.showAndWait();
    }

    private static void updateDetail(Label detail, ModLoader loader) {
        if (loader == null) {
            detail.setText("");
            return;
        }
        detail.setText(switch (loader) {
            case VANILLA -> "仅下载官方原版客户端、依赖库与资源文件。";
            case FABRIC -> "下载原版后自动安装最新 Fabric Loader，适合大多数新版模组。";
            case FORGE -> "下载原版后自动安装推荐版 Forge（若该版本提供）。";
            case QUILT -> "下载原版后自动安装最新 Quilt Loader。";
            case NEOFORGE -> "下载原版后自动安装最新 NeoForge（若该版本提供）。";
        });
    }
}
