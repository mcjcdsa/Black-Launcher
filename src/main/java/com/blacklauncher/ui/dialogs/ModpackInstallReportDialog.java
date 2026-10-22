package com.blacklauncher.ui.dialogs;

import com.blacklauncher.modpack.ModpackInstallReport;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Optional;

/**
 * Post-import report with optional one-click launch (§6).
 */
public final class ModpackInstallReportDialog {

    public enum Action {
        CLOSE,
        LAUNCH
    }

    private ModpackInstallReportDialog() {
    }

    public static Optional<Action> show(Window owner, ModpackInstallReport report) {
        Dialog<Action> dialog = new Dialog<>();
        dialog.setTitle("整合包导入完成");
        String header = report.isPartial() ? "导入完成（部分缺失）" : "导入成功";
        dialog.setHeaderText(header);
        if (owner != null) {
            dialog.initOwner(owner);
        }

        Label summary = new Label(report.getSummary());
        summary.setWrapText(true);

        StringBuilder body = new StringBuilder();
        body.append("版本 ID：").append(report.getVersionId()).append('\n');
        body.append("成功文件：").append(report.getInstalled().size()).append('\n');
        body.append("缺失文件：").append(report.getMissing().size()).append('\n');
        if (!report.getMissing().isEmpty()) {
            body.append("\n—— 缺失 / 失效 ——\n");
            for (String m : report.getMissing()) {
                body.append("· ").append(m).append('\n');
            }
        }
        if (!report.getWarnings().isEmpty()) {
            body.append("\n—— 警告 ——\n");
            for (String w : report.getWarnings()) {
                body.append("· ").append(w).append('\n');
            }
        }

        TextArea area = new TextArea(body.toString());
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefRowCount(12);
        VBox.setVgrow(area, Priority.ALWAYS);

        VBox box = new VBox(10, summary, area);
        box.setPadding(new Insets(8, 4, 4, 4));
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().setPrefWidth(520);

        ButtonType launch = new ButtonType("一键启动", ButtonBar.ButtonData.OK_DONE);
        ButtonType close = new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE);
        if (report.isSuccess()) {
            dialog.getDialogPane().getButtonTypes().setAll(launch, close);
        } else {
            dialog.getDialogPane().getButtonTypes().setAll(close);
        }

        dialog.setResultConverter(btn -> btn == launch ? Action.LAUNCH : Action.CLOSE);
        return dialog.showAndWait();
    }
}
