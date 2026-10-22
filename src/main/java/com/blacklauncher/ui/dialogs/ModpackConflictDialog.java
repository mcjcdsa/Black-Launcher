package com.blacklauncher.ui.dialogs;

import com.blacklauncher.modpack.ModpackConflictPolicy;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.stage.Window;

import java.util.Optional;

/**
 * Same-name version conflict: overwrite / copy suffix / cancel (§6.3.4).
 */
public final class ModpackConflictDialog {

    private ModpackConflictDialog() {
    }

    public static Optional<ModpackConflictPolicy> show(Window owner, String versionName) {
        Dialog<ModpackConflictPolicy> dialog = new Dialog<>();
        dialog.setTitle("版本名冲突");
        dialog.setHeaderText("已存在同名版本：「" + versionName + "」");
        if (owner != null) {
            dialog.initOwner(owner);
        }

        Label tip = new Label("请选择处理方式：\n"
                + "· 覆盖安装 — 删除旧版本后重新安装\n"
                + "· 作为副本导入 — 自动添加后缀（如 _2）\n"
                + "· 取消 — 不做任何写操作");
        tip.setWrapText(true);
        dialog.getDialogPane().setContent(tip);
        dialog.getDialogPane().setPrefWidth(420);

        ButtonType overwrite = new ButtonType("覆盖安装", ButtonBar.ButtonData.LEFT);
        ButtonType copy = new ButtonType("作为副本导入", ButtonBar.ButtonData.OTHER);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(overwrite, copy, cancel);

        dialog.setResultConverter(btn -> {
            if (btn == overwrite) {
                return ModpackConflictPolicy.OVERWRITE;
            }
            if (btn == copy) {
                return ModpackConflictPolicy.COPY_SUFFIX;
            }
            return ModpackConflictPolicy.CANCEL;
        });

        return dialog.showAndWait();
    }
}
